/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jemule.Main;
import org.jemule.config.ConfigApplyResult;
import org.jemule.config.ConfigParameter;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileSource;
import org.jemule.core.MetricsHistory;
import org.jemule.kad.GlobalSearchForwarder;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadSearchBridge;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loopback-only HTTP dashboard and JSON API for local server administration.
 */
final class AdminWebPanel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AdminWebPanel.class);
    private static final int MAX_BODY_BYTES = 4096;
    /** Classpath folder of the panel's static files, the Next.js export of admin-ui/ built by Gradle. */
    static final String UI_ROOT = "admin-ui/";
    /** Policy of the JSON answers and of the files that are not pages. */
    private static final String API_SECURITY_POLICY = "default-src 'self' 'unsafe-inline'; frame-ancestors 'none'";
    /** The files of the export already read, by resource name. */
    private final Map<String, UiFile> uiFiles = new java.util.concurrent.ConcurrentHashMap<>();

    private final Server server;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer http;
    private ExecutorService executor;
    /** Longest wait for the running requests when the panel closes. */
    static final int CLOSE_TIMEOUT_SECONDS = 5;

    AdminWebPanel(Server server) {
        this.server = server;
    }

    synchronized void start(int port) throws IOException {
        if (http != null) return;
        String bindConfig = (server != null && server.getConfig() != null)
                ? server.getConfig().adminBindAddress()
                : ServerConfig.DEFAULT_ADMIN_BIND_ADDRESS;
        InetAddress bindAddr;
        try {
            bindAddr = InetAddress.getByName(bindConfig);
        } catch (Exception e) {
            log.error("Invalid adminBindAddress {}: {}", bindConfig, e.getMessage());
            bindAddr = InetAddress.getByName("127.0.0.1");
        }
        http = HttpServer.create(new InetSocketAddress(bindAddr, port), 32);
        if (executor == null) executor = org.jemule.core.ServerThreads.adminExecutor("admin-web");
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
        log.info("Admin web panel listening at http://{}:{}/", bindAddr.getHostAddress(), http.getAddress().getPort());
    }

    int port() {
        return http == null ? 0 : http.getAddress().getPort();
    }

    boolean isLoopbackBound() {
        return http != null && http.getAddress().getAddress().isLoopbackAddress();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage());
            } catch (IOException e) {
                log.warn("Admin web request failed: {}", e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                log.error("Admin web request failed", e);
                if (exchange.getResponseCode() == -1) sendError(exchange, 500, "Internal server error");
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
        // The relay servers (relayClients + relayToken) may post their files there, and nothing else
        if (exchange.getRequestURI().getPath().equals(RelayFeed.PATH)) {
            relayFiles(exchange, remoteAddress);
            return;
        }
        if (exchange.getRequestURI().getPath().equals(RelayIndexView.PATH)) {
            relayIndex(exchange, remoteAddress);
            return;
        }
        if (server.getAdminAccessFilter() != null && !server.getAdminAccessFilter().isAllowed(remoteAddress)) {
            sendError(exchange, 403, "Access denied by admin access filter");
            return;
        }
        if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            sendError(exchange, 403, "Valid Host header required");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        // HEAD too: the client-side router of the panel checks its routes with it
        if ((method.equals("GET") || method.equals("HEAD")) && !path.startsWith("/api/")) {
            serveUi(exchange, path);
            return;
        }
        if (method.equals("GET")) {
            switch (path) {
                case "/api/status" -> json(exchange, 200, status());
                case "/api/overview" -> json(exchange, 200, overview());
                case "/api/history" -> json(exchange, 200, history(exchange));
                case "/api/clients" -> clients(exchange);
                case "/api/peers" -> json(exchange, 200, peers());
                case "/api/servers" -> json(exchange, 200, servers());
                case "/api/ingestion" -> json(exchange, 200, ingestion());
                case "/api/searches" -> json(exchange, 200, searches());
                case "/api/filters" -> json(exchange, 200, filters());
                case "/api/bots" -> json(exchange, 200, bots());
                case "/api/blocks" -> json(exchange, 200, blocks());
                case "/api/blocks/export" -> exportBannedNames(exchange);
                case "/api/settings" -> json(exchange, 200, settings());
                default -> sendError(exchange, 404, "Not found");
            }
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", path.startsWith("/api/") ? "GET, POST" : "GET, HEAD, POST");
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"), port())) {
            sendError(exchange, 403, "A valid Origin header is required");
            return;
        }
        Map<String, String> form = readForm(exchange);
        switch (path) {
            case "/api/blocks" -> {
                String name = form.getOrDefault("name", "");
                if (FakeFileDetector.normalize(name) == null)
                    throw new IllegalArgumentException("Name must hold 1 to " + FakeFileDetector.MAX_NAME_LENGTH + " characters");
                String reason = form.getOrDefault("reason", "").trim();
                if (reason.length() > 240) throw new IllegalArgumentException("Reason is too long");
                String banned = server.getFakeFileDetector().ban(name, "Admin web panel" + (reason.isEmpty() ? "" : ": " + reason));
                int removed = server.getFileIndex().removeFilesNamed(banned);
                json(exchange, 200, Map.of("banned", banned, "filesRemoved", removed));
            }
            case "/api/unblock" -> {
                String name = form.getOrDefault("name", "");
                if (FakeFileDetector.normalize(name) == null)
                    throw new IllegalArgumentException("Name must hold 1 to " + FakeFileDetector.MAX_NAME_LENGTH + " characters");
                boolean wasBanned = server.getFakeFileDetector().unban(name);
                json(exchange, 200, Map.of("unbanned", FakeFileDetector.normalize(name), "wasBanned", wasBanned));
            }
            case "/api/settings" -> json(exchange, 200, configResult(server.updateConfig(form)));
            case "/api/settings/reload" -> json(exchange, 200, configResult(server.reloadConfig()));
            case "/api/filters/reload" -> {
                String filterPath = server.getConfig().ipFilterPath();
                boolean reloaded = filterPath != null && !filterPath.isBlank();
                if (reloaded) server.reloadIpFilter(filterPath);
                server.reloadChildAbuseKeywords(server.getConfig().childAbuseKeywordsPath());
                json(exchange, 200, Map.of("reloaded", reloaded, "path", filterPath == null ? "" : filterPath));
            }
            case "/api/kick" -> {
                String ip = form.getOrDefault("ip", "").trim();
                if (!ip.isEmpty()) {
                    json(exchange, 200, Map.of("kicked", server.kickIp(ip), "ip", ip));
                    return;
                }
                String identifier = form.getOrDefault("clientId",
                        form.getOrDefault("identifier", "")).trim();
                if (identifier.isEmpty()) throw new IllegalArgumentException("clientId is required");
                json(exchange, 200, Map.of("kicked", server.kickClient(identifier) ? 1 : 0, "clientId", identifier));
            }
            default -> sendError(exchange, 404, "Not found");
        }
    }

    /**
     * GET of the panel's static files (the Next.js export of admin-ui/, packaged under {@value #UI_ROOT} in the JAR):
     * a route ending with a slash gives its index.html, a route without the slash is redirected to it, anything else
     * that is not a file of the export gets the 404 page.
     */
    private void serveUi(HttpExchange exchange, String path) throws IOException {
        String resource = uiResource(path);
        UiFile file = resource == null ? null : uiFile(resource);
        if (file == null && resource != null && !path.endsWith("/") && uiFile(resource + "/index.html") != null) {
            String query = exchange.getRequestURI().getRawQuery();
            exchange.getResponseHeaders().set("Location", path + "/" + (query == null ? "" : "?" + query));
            send(exchange, 308, "text/plain; charset=utf-8", "");
            return;
        }
        if (file != null) {
            sendUiFile(exchange, 200, file);
            return;
        }
        UiFile notFound = uiFile("404.html");
        if (notFound != null) {
            sendUiFile(exchange, 404, notFound);
        } else if (uiFile("index.html") == null) {
            send(exchange, 404, "text/plain; charset=utf-8",
                    "The admin web panel is not part of this build; the JSON API answers under /api/.\n");
        } else {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found\n");
        }
    }

    /** The resource of a request path relative to {@link #UI_ROOT}, null when the path tries to leave it. */
    static String uiResource(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return null;
        String relative = path.substring(1);
        for (String segment : relative.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) return null;
        }
        return relative.isEmpty() || relative.endsWith("/") ? relative + "index.html" : relative;
    }

    private UiFile uiFile(String resource) {
        // A last segment without an extension is a route (a directory of the export), never a file
        String last = resource.substring(resource.lastIndexOf('/') + 1);
        if (last.indexOf('.') <= 0) return null;
        UiFile cached = uiFiles.get(resource);
        if (cached != null) return cached;
        try (java.io.InputStream in = AdminWebPanel.class.getClassLoader().getResourceAsStream(UI_ROOT + resource)) {
            if (in == null) return null;
            byte[] body = in.readAllBytes();
            String type = contentType(resource);
            UiFile file = new UiFile(body, type, cacheControl(resource),
                    type.startsWith("text/html") ? pageSecurityPolicy(new String(body, StandardCharsets.UTF_8)) : API_SECURITY_POLICY);
            // Only the files of the export are kept (a megabyte or so), never the misses
            uiFiles.put(resource, file);
            return file;
        } catch (IOException e) {
            log.warn("Admin web panel file {} unreadable: {}", resource, e.getMessage());
            return null;
        }
    }

    private record UiFile(byte[] body, String contentType, String cacheControl, String securityPolicy) {
    }

    static String contentType(String resource) {
        String extension = resource.substring(resource.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            // The RSC payloads of the client-side navigation
            case "txt" -> "text/plain; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            default -> "application/octet-stream";
        };
    }

    /** Pages are never stored; the files of _next/static have a content hash in their name and never change. */
    private static String cacheControl(String resource) {
        if (resource.endsWith(".html")) return "no-store";
        if (resource.startsWith("_next/static/")) return "public, max-age=31536000, immutable";
        return "no-cache";
    }

    private static final java.util.regex.Pattern INLINE_SCRIPT = java.util.regex.Pattern.compile(
            "<script(?![^>]*\\ssrc=)[^>]*>(.*?)</script>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The policy of a page: scripts from this origin, and the inline scripts of that page only (Next.js hydrates with
     * them), allowed by their SHA-256 hash rather than by 'unsafe-inline'.
     */
    static String pageSecurityPolicy(String html) {
        StringBuilder scripts = new StringBuilder("'self'");
        java.util.regex.Matcher matcher = INLINE_SCRIPT.matcher(html);
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            while (matcher.find()) {
                if (matcher.group(1).isEmpty()) continue;
                byte[] hash = sha256.digest(matcher.group(1).getBytes(StandardCharsets.UTF_8));
                scripts.append(" 'sha256-").append(java.util.Base64.getEncoder().encodeToString(hash)).append('\'');
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return "default-src 'self'; script-src " + scripts + "; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    }

    private static void sendUiFile(HttpExchange exchange, int status, UiFile file) throws IOException {
        if (file.contentType().startsWith("text/html")) exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        sendBytes(exchange, status, file.contentType(), file.body(), file.cacheControl(), file.securityPolicy());
    }

    /** GET of this index's figures by a relay server (relay mode): see {@link RelayIndexView}. */
    private void relayIndex(HttpExchange exchange, InetAddress remoteAddress) throws IOException {
        RelayFeedReceiver receiver = server.getRelayFeedReceiver();
        if (!exchange.getRequestMethod().equals("GET") || receiver == null
                || !receiver.authorized(remoteAddress, exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendError(exchange, 403, "Not an allowed relay server");
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("files ").append(server.getFileIndex().fileCount()).append('\n');
        if (server.getConfig() != null) body.append("max ").append(server.getConfig().maxFiles()).append('\n');
        MetricsHistory history = server.getMetricsHistory();
        if (history != null) {
            long from = System.currentTimeMillis() - RelayIndexView.DAYS * 24 * 3_600_000L;
            for (MetricsHistory.Sample sample : history.since(from)) {
                body.append(sample.time()).append(' ').append(sample.files()).append('\n');
            }
        }
        send(exchange, 200, "text/plain; charset=utf-8", body.toString());
    }

    /** POST of a relay server's files (relay mode). */
    private void relayFiles(HttpExchange exchange, InetAddress remoteAddress) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        RelayFeedReceiver receiver = server.getRelayFeedReceiver();
        if (receiver == null) {
            sendError(exchange, 404, "Not found");
            return;
        }
        RelayFeedReceiver.Answer answer = receiver.receive(remoteAddress,
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody());
        json(exchange, answer.status(), answer.body());
    }

    private Map<String, Object> status() {
        Runtime runtime = Runtime.getRuntime();
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        DatabaseManager database = server.getDatabaseManager();
        return Map.ofEntries(
                Map.entry("version", Main.VERSION),
                Map.entry("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - startedAt) / 1000)),
                Map.entry("connectedClients", server.getClientRegistry().size()),
                Map.entry("lowIdClients", server.getClientRegistry().lowIdCount()),
                Map.entry("indexedFiles", server.getFileIndex().fileCount()),
                Map.entry("ipFilterRanges", server.getIPFilter().getFilterCount()),
                Map.entry("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024),
                Map.entry("heapMaxMb", runtime.maxMemory() / 1024 / 1024),
                Map.entry("databaseReopens", database == null ? 0L : database.getReopenCount()),
                Map.entry("systemLoad", Double.isFinite(load) ? load : "unavailable")
        );
    }

    /** Headline figures of the Overview view, grouped by subject; the changes are read from the metrics history. */
    private Map<String, Object> overview() {
        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        ServerConfig config = server.getConfig();
        FileIndex index = server.getFileIndex();
        MetricsHistory history = server.getMetricsHistory();
        DatabaseManager database = server.getDatabaseManager();

        Map<String, Object> files = new java.util.LinkedHashMap<>();
        ServerRelay relayMode = server.getServerRelay();
        boolean relaying = relayMode != null && relayMode.active();
        // Relay mode: the files the searches return are the relay server's; the local index is only a fallback
        files.put("relayMode", relaying);
        files.put("indexed", relaying ? server.announcedFileCount() : index.fileCount());
        files.put("local", index.fileCount());
        files.put("max", config == null ? null : config.maxFiles());
        files.put("newSinceStart", index.totalNewFiles());
        files.put("refusedFull", index.refusedFullCount());
        files.put("ago24h", filesAt(history, now - 24 * 3_600_000L));
        files.put("ago7d", filesAt(history, now - 7 * 24 * 3_600_000L));
        MetricsHistory.Sample oldest = history == null ? null : history.oldest();
        files.put("historySince", oldest == null ? null : oldest.time());
        RelayIndexView.Snapshot relayIndex = relaying ? relayIndex() : null;
        if (relayIndex != null) {
            // The relay server's figures, never the fallback index's
            files.put("max", relayIndex.max());
            files.put("ago24h", relayIndex.filesAt(now - 24 * 3_600_000L));
            files.put("ago7d", relayIndex.filesAt(now - 7 * 24 * 3_600_000L));
            files.put("historySince", relayIndex.times().length == 0 ? null : relayIndex.times()[0]);
            files.put("relayIndexAgeSeconds", (now - relayIndex.fetchedAt()) / 1000);
        }
        if (relaying) {
            RelayIndexView view = server.getRelayIndexView();
            files.put("relayIndexError", view == null ? "" : view.lastError());
        }

        Map<String, Object> clients = new java.util.LinkedHashMap<>();
        clients.put("connected", server.getClientRegistry().size());
        clients.put("lowId", server.getClientRegistry().lowIdCount());
        clients.put("max", config == null ? null : config.maxUsers());

        Map<String, Object> memory = new java.util.LinkedHashMap<>();
        memory.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        memory.put("heapMaxMb", runtime.maxMemory() / 1024 / 1024);
        org.jemule.core.HeapGuard guard = server.getHeapGuard();
        if (guard != null && guard.maxBytes() > 0) {
            memory.put("liveOldMb", guard.liveBytes() / 1024 / 1024);
            memory.put("oldMaxMb", guard.maxBytes() / 1024 / 1024);
            memory.put("guardMb", (long) (guard.maxBytes() * org.jemule.core.HeapGuard.MAX_LIVE_RATIO) / 1024 / 1024);
            memory.put("heapFull", guard.full());
        }
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        memory.put("systemLoad", Double.isFinite(load) ? load : null);

        List<ServerGossip.ServerView> views = server.getServerViews();
        Map<String, Object> servers = new java.util.LinkedHashMap<>();
        servers.put("verified", views.stream().filter(v -> "verified".equals(v.state())).count());
        servers.put("known", views.size());

        KadSearchBridge kad = server.getKadSearchBridge();
        ServerSearchBridge serverBridge = server.getServerSearchBridge();
        GlobalSearchForwarder global = server.getGlobalSearchForwarder();
        Map<String, Object> searches = new java.util.LinkedHashMap<>();
        searches.put("kad", kad == null ? 0L : kad.getSearchesForked());
        searches.put("ed2kServers", serverBridge == null ? 0L : serverBridge.getSearchesForked());
        searches.put("udpGlobal", global == null ? 0L : global.getReceived());
        searches.put("filesIngested", (kad == null ? 0L : kad.getFilesIngested())
                + (serverBridge == null ? 0L : serverBridge.getFilesIngested()));

        Map<String, Object> protection = new java.util.LinkedHashMap<>();
        protection.put("bannedNames", database != null ? database.countBannedNames()
                : server.getFakeFileDetector() == null ? 0 : server.getFakeFileDetector().bannedNameCount());
        protection.put("childAbuseQueries", server.getFileIndex().childAbuseQueryCount());
        protection.put("childAbuseRejected", server.getFileIndex().childAbuseRejectedCount()
                + (server.getKadSearchBridge() == null ? 0 : server.getKadSearchBridge().getIllicitFilesBlocked()));
        protection.put("ipFilterRanges", server.getIPFilter().getFilterCount());

        Map<String, Object> db = new java.util.LinkedHashMap<>();
        db.put("type", database == null ? null : database.isMySql() ? "MySQL" : "H2");
        db.put("sizeMb", database == null ? null : database.fileSize() / 1024 / 1024);
        db.put("reopens", database == null ? 0L : database.getReopenCount());

        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("version", Main.VERSION);
        overview.put("uptimeSeconds", Math.max(0, (now - startedAt) / 1000));
        overview.put("files", files);
        overview.put("clients", clients);
        overview.put("memory", memory);
        overview.put("servers", servers);
        overview.put("searches", searches);
        overview.put("protection", protection);
        overview.put("database", db);
        org.jemule.core.OfferFilesV1 offerFilesV1 = server.getOfferFilesV1();
        Map<String, Object> offerFiles = offerFilesV1 == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(offerFilesV1.stats());
        offerFiles.put("enabled", config != null && config.offerFiles().enabled());
        // What the evolution brings: new files published by v1 clients, next to those of legacy clients
        offerFiles.put("newFiles", index.newFileCount(org.jemule.core.FileSource.CLIENT_PUSH_V1));
        offerFiles.put("newFilesLegacy", index.newFileCount(org.jemule.core.FileSource.CLIENT_PUSH));
        overview.put("offerFilesV1", offerFiles);
        KadSourceService kadSources = server.getKadSourceService();
        overview.put("kadSources", kadSources == null ? Map.of() : kadSources.stats());
        overview.put("serverSources", server.serverSourceStats());
        ServerIndexUpdater updater = server.getServerIndexUpdater();
        overview.put("serverIndexUpdate", updater == null ? Map.of() : updater.stats());
        ServerRelay relay = server.getServerRelay();
        Map<String, Object> relayStats = relay == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(relay.stats());
        if (relay != null && relay.active()) relayStats.put("announcedFiles", server.announcedFileCount());
        overview.put("relay", relayStats);
        RelayFeed feed = server.getRelayFeed();
        overview.put("relayFeed", feed == null ? Map.of() : feed.stats());
        RelayFeedReceiver receiver = server.getRelayFeedReceiver();
        Map<String, Object> received = receiver == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(receiver.stats());
        received.put("newFiles", index.newFileCount(org.jemule.core.FileSource.RELAY_FEED));
        overview.put("relayFeedReceived", received);
        return overview;
    }

    /** Relay mode: the relay server's index figures, null when not known. */
    private RelayIndexView.Snapshot relayIndex() {
        RelayIndexView view = server.getRelayIndexView();
        return view == null ? null : view.snapshot();
    }

    private static Integer filesAt(MetricsHistory history, long time) {
        MetricsHistory.Sample sample = history == null ? null : history.at(time);
        return sample == null ? null : sample.files();
    }

    /** Samples of the last {@code days} days (30 by default): {@code [time, files, clients]} each, oldest first. */
    private Map<String, Object> history(HttpExchange exchange) {
        int days = parseBounded(query(exchange.getRequestURI()).get("days"), 30, 1, 31);
        MetricsHistory history = server.getMetricsHistory();
        ServerRelay relay = server.getServerRelay();
        // Relay mode: the file curve is the relay server's index, the client curve this server's own clients
        RelayIndexView.Snapshot relayIndex = relay != null && relay.active() ? relayIndex() : null;
        List<List<Long>> points = new ArrayList<>();
        if (history != null) {
            for (MetricsHistory.Sample sample : history.since(System.currentTimeMillis() - days * 24 * 3_600_000L)) {
                Long files = relayIndex == null ? null : relayIndex.filesAt(sample.time());
                points.add(List.of(sample.time(), files != null ? files : (long) sample.files(), (long) sample.clients()));
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("intervalSeconds", MetricsHistory.INTERVAL_MS / 1000);
        result.put("points", points);
        return result;
    }

    private void clients(HttpExchange exchange) throws IOException {
        String query = query(exchange.getRequestURI()).getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        int offset = parseBounded(query(exchange.getRequestURI()).get("offset"), 0, 0, 1_000_000);
        int limit = parseBounded(query(exchange.getRequestURI()).get("limit"), 100, 1, 100);
        List<Map<String, Object>> page = new ArrayList<>(limit);
        int total = 0;
        long now = System.currentTimeMillis();
        for (ClientState client : server.getClientRegistry().getAllClients()) {
            String nickname = client.nickname();
            String address = client.address().getHostAddress();
            String clientId = Long.toUnsignedString(client.clientId());
            if (!query.isEmpty() && !(nickname.toLowerCase(Locale.ROOT).contains(query)
                    || address.contains(query) || clientId.contains(query))) continue;
            if (total >= offset && page.size() < limit) {
                page.add(Map.of(
                        "nickname", nickname,
                        "ip", address,
                        "port", client.port(),
                        "listenPort", client.listenPort(),
                        "clientId", clientId,
                        "idType", client.isHighId() ? "HighID" : "LowID",
                        "publishedFiles", client.publishedFilesCount().get(),
                        "connectedSeconds", Math.max(0, (now - client.connectedAt()) / 1000)
                ));
            }
            total++;
        }
        json(exchange, 200, Map.of("total", total, "offset", offset, "limit", limit, "clients", page));
    }

    private List<Map<String, Object>> peers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerGossip.VerifiedPeer peer : server.getVerifiedServerPeers()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", peer.name());
            row.put("type", "eD2K server");
            row.put("address", peer.endpoint().address().getHostAddress());
            row.put("tcpPort", peer.endpoint().tcpPort());
            row.put("udpPort", peer.endpoint().udpPort());
            row.put("kadVersion", null);
            row.put("lastSeen", peer.verifiedAtMillis());
            result.add(row);
        }
        KadNode kadNode = server.getKadNode();
        if (kadNode != null) {
            for (KadContact contact : kadNode.getRoutingTable().getAllContacts()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", "Kad node " + contact.id());
                row.put("type", "Kad contact");
                row.put("address", contact.address().getHostAddress());
                row.put("tcpPort", contact.tcpPort());
                row.put("udpPort", contact.udpPort());
                row.put("kadVersion", contact.kadVersion());
                row.put("lastSeen", contact.lastSeen());
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String, Object>> servers() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerGossip.ServerView view : server.getServerViews()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", view.name());
            row.put("state", view.state());
            row.put("source", view.source().name().toLowerCase(Locale.ROOT));
            row.put("address", view.endpoint().address().getHostAddress());
            row.put("tcpPort", view.endpoint().tcpPort());
            row.put("udpPort", view.endpoint().udpPort());
            row.put("users", view.users());
            row.put("files", view.files());
            row.put("maxUsers", view.maxUsers());
            row.put("lowIdUsers", view.lowIdUsers());
            row.put("udpKey", view.hasUdpKey());
            row.put("latencyMs", view.latencyMillis());
            row.put("lastStatusSecondsAgo", view.lastStatusAtMillis() == null ? null
                    : Math.max(0, (now - view.lastStatusAtMillis()) / 1000));
            row.put("verifiedSecondsAgo", view.verifiedAtMillis() == null ? null
                    : Math.max(0, (now - view.verifiedAtMillis()) / 1000));
            row.put("missedProbes", view.missedStatusProbes());
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> ingestion() {
        FileIndex index = server.getFileIndex();
        // Relay mode: nothing is indexed here, the files of each channel go to the relay server
        boolean relaying = index.relaying();
        Map<FileSource, Long> counts = relaying ? index.relayedFileCounts() : index.newFileCounts();
        long total = relaying ? counts.values().stream().mapToLong(Long::longValue).sum() : index.totalNewFiles();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<FileSource, Long> entry : counts.entrySet()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("source", entry.getKey().label());
            row.put(relaying ? "sentToRelay" : "newFiles", entry.getValue());
            row.put("percent", percent(entry.getValue(), total));
            rows.add(row);
        }
        Map<String, Object> totalRow = new java.util.LinkedHashMap<>();
        totalRow.put("source", "total");
        totalRow.put(relaying ? "sentToRelay" : "newFiles", total);
        totalRow.put("percent", total == 0 ? 0.0 : 100.0);
        rows.add(totalRow);
        return rows;
    }

    private static double percent(long value, long total) {
        return total == 0 ? 0.0 : Math.round(value * 1000.0 / total) / 10.0;
    }

    private List<Map<String, Object>> searches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        KadSearchBridge kad = server.getKadSearchBridge();
        Map<String, Object> kadRow = new java.util.LinkedHashMap<>();
        kadRow.put("channel", "Kad");
        kadRow.put("queries", kad == null ? 0L : kad.getSearchesForked());
        kadRow.put("requests", kad == null ? 0L : kad.getRequestsSent());
        kadRow.put("results", kad == null ? 0L : kad.getResultsReceived());
        kadRow.put("ingested", kad == null ? 0L : kad.getFilesIngested());
        kadRow.put("active", kad == null ? 0 : kad.getActiveSessionCount());
        kadRow.put("blocked", kad == null ? 0L : kad.getIllicitFilesBlocked());
        kadRow.put("skipped", 0L);
        rows.add(kadRow);
        // Pages of the nodes past the search's limit, read in the background: nodes followed, pages asked and
        // answered, files accepted for the index
        Map<String, Object> pagesRow = new java.util.LinkedHashMap<>();
        pagesRow.put("channel", "Kad background pages");
        pagesRow.put("queries", kad == null ? 0L : kad.getBackgroundNodesFollowed());
        pagesRow.put("requests", kad == null ? 0L : kad.getBackgroundPagesRequested());
        pagesRow.put("results", kad == null ? 0L : kad.getBackgroundPagesAnswered());
        pagesRow.put("ingested", kad == null ? 0L : kad.getBackgroundFilesIngested());
        pagesRow.put("active", null);
        pagesRow.put("blocked", null);
        pagesRow.put("skipped", null);
        rows.add(pagesRow);

        ServerSearchBridge servers = server.getServerSearchBridge();
        Map<String, Object> serverRow = new java.util.LinkedHashMap<>();
        serverRow.put("channel", "eD2K servers");
        serverRow.put("queries", servers == null ? 0L : servers.getSearchesForked());
        serverRow.put("requests", servers == null ? 0L : servers.getRequestsSent());
        serverRow.put("results", servers == null ? 0L : servers.getResultsReceived());
        serverRow.put("ingested", servers == null ? 0L : servers.getFilesIngested());
        serverRow.put("active", servers == null ? 0 : servers.getActiveSessionCount());
        serverRow.put("blocked", 0L);
        serverRow.put("skipped", 0L);
        rows.add(serverRow);

        // UDP global searches of other servers' clients: queries received, requests = searches started on Kad,
        // results = clean files found (all handed over to ingestion), blocked = refused (rate limits, child abuse
        // filter), skipped = duplicates, no keyword or Kad off. Their Kad traffic is also counted in the Kad row.
        GlobalSearchForwarder global = server.getGlobalSearchForwarder();
        Map<String, Object> globalRow = new java.util.LinkedHashMap<>();
        globalRow.put("channel", "UDP global searches (Kad)");
        globalRow.put("queries", global == null ? 0L : global.getReceived());
        globalRow.put("requests", global == null ? 0L : global.getForwarded());
        globalRow.put("results", global == null ? 0L : global.getResults());
        globalRow.put("ingested", global == null ? 0L : global.getResults());
        globalRow.put("active", null);
        globalRow.put("blocked", global == null ? 0L : global.getRefused());
        globalRow.put("skipped", global == null ? 0L : global.getSkipped());
        rows.add(globalRow);
        return rows;
    }

    private Map<String, Object> filters() {
        IPFilter ipFilter = server.getIPFilter();
        FakeFileDetector detector = server.getFakeFileDetector();
        int filterLimit = 100;
        List<String> ranges = ipFilter.getBlockedRanges(filterLimit);
        return Map.of(
                "ipFilterBlockedRanges", ipFilter.getFilterCount(),
                "ipFilterRangesShown", ranges.size(),
                "ipFilterRanges", ranges,
                "fakeFileHeuristicsEnabled", detector.isEnabled(),
                "heuristics", "multiple executable extensions, suspicious keywords, abnormal special-character density"
        );
    }

    private List<Map<String, Object>> bots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClientState client : server.getClientRegistry().getAllClients()) {
            if (AdminInterface.isPotentialBot(client.nickname())) {
                result.add(Map.of("nickname", client.nickname(), "ip", client.address().getHostAddress(),
                        "clientId", Long.toUnsignedString(client.clientId()), "classification", "nickname heuristic only"));
            }
        }
        return result;
    }

    /**
     * Every banned name, one per line in name order: the administrator's and those the child abuse filter rejected.
     * Read from the database 5,000 at a time and streamed, so that hundreds of thousands of names never sit in the
     * heap at once.
     */
    private void exportBannedNames(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"banned-names.txt\"");
        DatabaseManager database = server.getDatabaseManager();
        if (database == null) {
            String names = String.join("\n", server.getFakeFileDetector().bannedNames());
            send(exchange, 200, "text/plain; charset=utf-8", names.isEmpty() ? "" : names + "\n");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0); // chunked
        try (java.io.Writer out = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
            String last = null;
            List<String> page;
            do {
                page = database.bannedNamesAfter(last, 5_000);
                for (String name : page) out.write(name + "\n");
                if (!page.isEmpty()) last = page.get(page.size() - 1);
            } while (page.size() == 5_000);
        }
    }

    /** The 200 most recently banned names. */
    private List<Map<String, Object>> blocks() {
        DatabaseManager database = server.getDatabaseManager();
        if (database == null) return List.of();
        return database.loadRecentBannedNames(200).stream().map(n -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", n.name());
            row.put("reason", n.reason() == null ? "" : n.reason());
            row.put("bannedAt", n.addedAt() == null ? "" : n.addedAt().toString());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> settings() {
        var config = server.getConfig();
        List<Map<String, Object>> parameters = new ArrayList<>(ConfigParameter.ordered().size());
        Map<String, String> pending = server.pendingRestart();
        for (ConfigParameter parameter : ConfigParameter.ordered()) {
            Object value;
            if (parameter == ConfigParameter.FAKE_FILE_DETECTION_ENABLED) {
                value = server.getFakeFileDetector().isEnabled();
            } else {
                value = parameter.jsonValue(String.valueOf(config.value(parameter)));
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("key", parameter.key());
            entry.put("value", value);
            entry.put("type", parameter.type().name());
            entry.put("scope", parameter.scope().id());
            entry.put("scopeLabel", parameter.scope().label());
            entry.put("description", parameter.description());
            entry.put("pending", pending.getOrDefault(parameter.key(), ""));
            parameters.add(entry);
        }
        return parameters;
    }

    /** Serializes an applied/reloaded configuration result for the admin API. */
    private static Map<String, Object> configResult(ConfigApplyResult result) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("success", result.success());
        map.put("applied", result.applied());
        map.put("deferred", result.deferred());
        map.put("errors", result.errors());
        return map;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body is too large");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Expected form-encoded data");
        }
        return form(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery());
    }

    private static Map<String, String> form(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> values = new java.util.HashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static int parseBounded(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            return Math.clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isAllowedOrigin(String origin, int panelPort) {
        if (origin == null) return false;
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null || uri.getPort() != panelPort) return false;
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                return true;
            }
            try {
                InetAddress originAddr = InetAddress.getByName(host);
                return server.getAdminAccessFilter() == null || server.getAdminAccessFilter().isAllowed(originAddr);
            } catch (Exception e) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null) return false;
        String host = hostHeader;
        int requestPort = -1;
        int separator = hostHeader.lastIndexOf(':');
        if (separator > 0 && hostHeader.indexOf(':') == separator) {
            host = hostHeader.substring(0, separator);
            try {
                requestPort = Integer.parseInt(hostHeader.substring(separator + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (requestPort != -1 && requestPort != port()) {
            return false;
        }
        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return server.getAdminAccessFilter() == null || server.getAdminAccessFilter().isAllowed(addr);
        } catch (Exception e) {
            return false;
        }
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", encode(value));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message == null ? "Invalid request" : message));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        sendBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8), "no-store", API_SECURITY_POLICY);
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] bytes, String cacheControl,
                                  String securityPolicy) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", securityPolicy);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }

    private static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + escape(text) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> encode(String.valueOf(e.getKey())) + ":" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(encode(item)));
            return String.join(",", items).transform(s -> "[" + s + "]");
        }
        return encode(value.toString());
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Stops the listener but keeps the panel reusable for a restart on another port. */
    synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        log.info("Admin web panel stopped");
    }

    @Override
    public synchronized void close() {
        stop();
        if (executor != null) {
            // Not executor.close(), which waits for every request: one blocked for ever held back the shutdown
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Admin web requests still running {} s after the stop; the shutdown goes on",
                            CLOSE_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }
}

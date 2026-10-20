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
import java.util.regex.Pattern;

/**
 * Loopback-only HTTP dashboard and JSON API for local server administration.
 */
final class AdminWebPanel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AdminWebPanel.class);
    private static final int MAX_BODY_BYTES = 4096;
    private static final Pattern HASH = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final String PAGE = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>JEmuleServer admin</title>
            <style>
            body{font:15px system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;background:#101820;color:#e9f0f4}
            h1{color:#72d6c9}nav{display:flex;gap:.5rem;flex-wrap:wrap}button{cursor:pointer;padding:.55rem .8rem}
            section{margin:1rem 0;padding:1rem;background:#1b2a34;border-radius:.5rem}table{border-collapse:collapse;width:100%;overflow-wrap:anywhere}
            th,td{text-align:left;padding:.5rem;border-bottom:1px solid #38505d}input{padding:.5rem;margin:.3rem}
            pre{white-space:pre-wrap}#notice{color:#ffca70}
            </style><body><h1>JEmuleServer admin</h1>
            <p>Administration only. Potential bots are identified by nickname heuristic, not verified.</p>
            <nav><button data-view="status">Status</button><button data-view="clients">Clients</button>
            <button data-view="peers">Peers</button><button data-view="servers">Servers</button>
            <button data-view="ingestion">Ingestion</button><button data-view="searches">Searches</button>
            <button data-view="filters">Filters</button>
            <button data-view="bots">Bots</button><button data-view="blocks">Blocks</button>
            <button data-view="settings">Settings</button></nav>
            <section><div id="controls"></div><p id="notice"></p><div id="content">Loading…</div></section>
            <script>
            const content=document.querySelector('#content'),controls=document.querySelector('#controls'),notice=document.querySelector('#notice');
            const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
            async function show(view){
              notice.textContent=''; controls.replaceChildren();
              if(view==='clients'){controls.innerHTML='<input id="query" placeholder="Search nickname, IP, or client ID"><button id="search">Search</button>';
                controls.querySelector('#search').onclick=()=>load(view,controls.querySelector('#query').value);}
              else if(view==='blocks'){controls.innerHTML='<input id="hash" placeholder="32-character MD4 hash"><input id="reason" placeholder="Reason"><button id="ban">Ban hash</button><button id="unban">Unban hash</button>';
                controls.querySelector('#ban').onclick=async()=>{try{await post('/api/blocks',{hash:controls.querySelector('#hash').value,reason:controls.querySelector('#reason').value});notice.textContent='Hash banned.';load(view)}catch(e){notice.textContent=e.message}};
                controls.querySelector('#unban').onclick=async()=>{try{await post('/api/unblock',{hash:controls.querySelector('#hash').value});notice.textContent='Hash unbanned.';load(view)}catch(e){notice.textContent=e.message}}}
              else if(view==='settings'){controls.innerHTML='<button id="reload">Reload from file</button><button id="save">Save changes</button>';
                controls.querySelector('#reload').onclick=async()=>{try{const r=await post('/api/settings/reload',{});notice.textContent=summarize(r);load(view)}catch(e){notice.textContent=e.message}};
                controls.querySelector('#save').onclick=()=>saveSettings();}
              else if(view==='filters'){controls.innerHTML='<button id="reloadIp">Reload IP filter</button>';
                controls.querySelector('#reloadIp').onclick=async()=>{try{const r=await post('/api/filters/reload',{});notice.textContent=(r.reloaded?'IP filter reload requested.':'IP filter path is empty.')+' Child abuse keywords reloaded.';load(view)}catch(e){notice.textContent=e.message}}}
              load(view);
            }
            function summarize(r){const parts=[];if(r.applied&&r.applied.length)parts.push('applied: '+r.applied.join(', '));if(r.deferred&&r.deferred.length)parts.push('restart required: '+r.deferred.join(', '));if(r.errors&&r.errors.length)parts.push('errors: '+r.errors.join('; '));return parts.join(' | ')||'No change.'}
            async function post(path,data){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});const x=await r.json();if(!r.ok)throw Error(x.error||(x.errors&&x.errors.join('; '))||r.statusText);return x}
            async function load(view,q=''){content.textContent='Loading…';try{
              const r=await fetch('/api/'+view+(view==='clients'&&q?'?q='+encodeURIComponent(q):''));const data=await r.json();if(!r.ok)throw Error(data.error);
              if(view==='status'||view==='filters'){content.innerHTML='<table><tbody>'+Object.entries(data).map(([k,v])=>'<tr><th>'+esc(k)+'</th><td>'+esc(v)+'</td></tr>').join('')+'</tbody></table>';return}
              if(view==='settings'){renderSettings(data);return}
              const rows=Array.isArray(data)?data:(data.clients||[]);
              if(view==='clients')notice.textContent='Matching clients: '+data.total+' (showing '+rows.length+'; limit '+data.limit+')';
              if(view==='bots')notice.textContent='Potential bots matched by nickname only: '+rows.length;
              if(!rows.length){content.textContent='No entries.';return}
              content.innerHTML='<table><thead><tr>'+Object.keys(rows[0]).map(k=>'<th>'+esc(k)+'</th>').join('')+'<th>Action</th></tr></thead><tbody>'+
                rows.map((row,i)=>'<tr>'+Object.keys(rows[0]).map(k=>'<td>'+esc(row[k])+'</td>').join('')+
                '<td>'+(row.clientId?'<button data-kick="'+esc(row.clientId)+'">Kick</button>':'')+'</td></tr>').join('')+'</tbody></table>';
              content.querySelectorAll('[data-kick]').forEach(b=>b.onclick=async()=>{try{await post('/api/kick',{clientId:b.dataset.kick});notice.textContent='Client kicked.';load(view,q)}catch(e){notice.textContent=e.message}});
            }catch(e){content.textContent='Request failed: '+e.message}}
            function renderSettings(list){
              content.innerHTML='<table><thead><tr><th>Key</th><th>Value</th><th>Scope</th><th>Description</th></tr></thead><tbody>'+
                list.map(p=>'<tr><th>'+esc(p.key)+'</th><td>'+field(p)+(p.pending!==''?'<br><small>After restart: '+esc(p.pending)+'</small>':'')+'</td><td>'+esc(p.scope)+'</td><td>'+esc(p.description)+'</td></tr>').join('')+'</tbody></table>';
            }
            function field(p){const id='set_'+p.key,orig=esc(String(p.value));
              if(p.type==='BOOLEAN')return '<input type="checkbox" id="'+id+'" data-orig="'+orig+'" '+(p.value?'checked':'')+'>';
              return '<input id="'+id+'" data-orig="'+orig+'" value="'+orig+'">';}
            async function saveSettings(){const data={};
              document.querySelectorAll('#content input[id^=set_]').forEach(el=>{const key=el.id.slice(4);const value=el.type==='checkbox'?String(el.checked):el.value;if(value!==el.dataset.orig)data[key]=value});
              if(!Object.keys(data).length){notice.textContent='No change.';return}
              try{const r=await post('/api/settings',data);notice.textContent=summarize(r);if(r.errors&&r.errors.length)return;load('settings')}catch(e){notice.textContent=e.message}}
            document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>show(b.dataset.view));show('status');
            </script></body></html>
            """;

    private final Server server;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer http;
    private ExecutorService executor;

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
        if (path.equals("/") && method.equals("GET")) {
            send(exchange, 200, "text/html; charset=utf-8", PAGE);
            return;
        }
        if (method.equals("GET")) {
            switch (path) {
                case "/api/status" -> json(exchange, 200, status());
                case "/api/clients" -> clients(exchange);
                case "/api/peers" -> json(exchange, 200, peers());
                case "/api/servers" -> json(exchange, 200, servers());
                case "/api/ingestion" -> json(exchange, 200, ingestion());
                case "/api/searches" -> json(exchange, 200, searches());
                case "/api/filters" -> json(exchange, 200, filters());
                case "/api/bots" -> json(exchange, 200, bots());
                case "/api/blocks" -> json(exchange, 200, blocks());
                case "/api/settings" -> json(exchange, 200, settings());
                default -> sendError(exchange, 404, "Not found");
            }
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
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
                String hash = form.getOrDefault("hash", "").trim();
                if (!HASH.matcher(hash).matches())
                    throw new IllegalArgumentException("Hash must contain 32 hexadecimal characters");
                String reason = form.getOrDefault("reason", "Admin web panel").trim();
                if (reason.length() > 240) throw new IllegalArgumentException("Reason is too long");
                String normalized = hash.toUpperCase(Locale.ROOT);
                server.getFakeFileDetector().ban(normalized, "Admin web panel" + (reason.isEmpty() ? "" : ": " + reason));
                server.getFileIndex().removeFile(normalized);
                json(exchange, 200, Map.of("banned", normalized));
            }
            case "/api/unblock" -> {
                String hash = form.getOrDefault("hash", "").trim();
                if (!HASH.matcher(hash).matches())
                    throw new IllegalArgumentException("Hash must contain 32 hexadecimal characters");
                String normalized = hash.toUpperCase(Locale.ROOT);
                boolean wasBanned = server.getFakeFileDetector().unban(normalized);
                json(exchange, 200, Map.of("unbanned", normalized, "wasBanned", wasBanned));
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

    private Map<String, Object> status() {
        Runtime runtime = Runtime.getRuntime();
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return Map.of(
                "version", Main.VERSION,
                "uptimeSeconds", Math.max(0, (System.currentTimeMillis() - startedAt) / 1000),
                "connectedClients", server.getClientRegistry().size(),
                "lowIdClients", server.getClientRegistry().lowIdCount(),
                "indexedFiles", server.getFileIndex().fileCount(),
                "ipFilterRanges", server.getIPFilter().getFilterCount(),
                "heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
                "systemLoad", Double.isFinite(load) ? load : "unavailable"
        );
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
        Map<FileSource, Long> counts = index.newFileCounts();
        long total = index.totalNewFiles();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<FileSource, Long> entry : counts.entrySet()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("source", entry.getKey().label());
            row.put("newFiles", entry.getValue());
            row.put("percent", percent(entry.getValue(), total));
            rows.add(row);
        }
        Map<String, Object> totalRow = new java.util.LinkedHashMap<>();
        totalRow.put("source", "total");
        totalRow.put("newFiles", total);
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

    private List<String> blocks() {
        DatabaseManager database = server.getDatabaseManager();
        return database == null ? List.of() : database.loadRecentBannedHashes(3);
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
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
            executor.close();
            executor = null;
        }
    }
}

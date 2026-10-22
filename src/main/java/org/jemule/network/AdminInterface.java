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

import org.jemule.Main;
import org.jemule.config.ConfigApplyResult;
import org.jemule.config.ConfigParameter;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileSource;
import org.jemule.kad.GlobalSearchForwarder;
import org.jemule.kad.KadBootstrapManager;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadHashBanService;
import org.jemule.kad.KadMaintenanceService;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadNodesUpdater;
import org.jemule.kad.KadSearchBridge;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provides a command-line interface for server administration: on the standard input of the server, and on the
 * local admin port (see {@code adminPort}) when the server runs as a service without a terminal.
 */
public class AdminInterface implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AdminInterface.class);
    /** Maximum number of contacts listed by 'kad contacts'. */
    static final int MAX_LISTED_KAD_CONTACTS = 20;
    private static final Pattern POSSIBLE_BOT_NAME = Pattern.compile("\\b(bot|scanner|spider|crawler)\\b",
            Pattern.CASE_INSENSITIVE);
    private final Server server;
    private final ClientRegistry registry;
    private final FileIndex fileIndex;
    private final IPFilter ipFilter;
    private final FakeFileDetector fakeFileDetector;
    private final long startTime;
    /** Destination of the answers; null for the current System.out (the console). */
    private final PrintStream output;

    public AdminInterface(Server server, ClientRegistry registry, FileIndex fileIndex, IPFilter ipFilter, FakeFileDetector fakeFileDetector) {
        this.server = server;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.ipFilter = ipFilter;
        this.fakeFileDetector = fakeFileDetector;
        this.startTime = System.currentTimeMillis();
        this.output = null;
    }

    /** An interface answering on {@code output}, for one session of the admin port. */
    AdminInterface(AdminInterface console, PrintStream output) {
        this.server = console.server;
        this.registry = console.registry;
        this.fileIndex = console.fileIndex;
        this.ipFilter = console.ipFilter;
        this.fakeFileDetector = console.fakeFileDetector;
        this.startTime = console.startTime;
        this.output = output;
    }

    private PrintStream out() {
        return output != null ? output : System.out;
    }

    public void handleCommand(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String[] parts = line.trim().split("\\s+", 3);
        String command = parts[0].toLowerCase();
        switch (command) {
            case "status" -> showStatus();
            case "clients" -> showClients(parts.length > 2 ? parts[1] + " " + parts[2]
                    : parts.length > 1 ? parts[1] : "");
            case "files" -> showFiles();
            case "ingestion" -> showIngestion();
            case "searches" -> showSearches();
            case "ban" -> handleBan(parts);
            case "unban" -> handleUnban(parts);
            case "events" -> showEvents();
            case "kad" -> handleKad(parts);
            case "peers" -> showPeers();
            case "servers" -> showServers();
            case "filters" -> handleFilters(parts);
            case "bots" -> showBots();
            case "blocks" -> showBlocks();
            case "settings" -> handleSettings(parts);
            case "kick" -> handleKick(parts);
            case "help" -> showHelp();
            case "stop", "exit", "quit" -> {
                out().println("Stopping server...");
                server.stop();
            }
            default -> out().println("Unknown command: " + command + ". Type 'help' for list.");
        }
    }

    /** Console session on the standard input; 'stop', 'exit' and 'quit' shut the server down. */
    @Override
    public void run() {
        try {
            if (session(new BufferedReader(new InputStreamReader(System.in)), false) == 0) {
                log.info("No standard input (service mode): the admin console is closed, use the admin port (adminPort)");
            }
        } catch (IOException e) {
            log.warn("Admin console closed: {}", e.getMessage());
        }
    }

    /**
     * Reads and runs commands until the end of the input. In a remote session, 'exit' and 'quit' only end the
     * session; 'stop' always shuts the server down.
     *
     * @return the number of lines read
     */
    int session(BufferedReader in, boolean remote) throws IOException {
        out().println("=== JEmuleServer Admin Interface (" + Main.VERSION + ") ===");
        out().println("Type 'help' for available commands.");
        int lines = 0;
        while (true) {
            out().print("> ");
            out().flush();
            String line = in.readLine();
            if (line == null) return lines;
            lines++;
            String command = line.trim().toLowerCase();
            if (remote && (command.equals("exit") || command.equals("quit"))) return lines;
            handleCommand(line);
        }
    }

    private void showStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        out().println("--- Server Status ---");
        out().println("Version: " + Main.VERSION);
        out().println("Uptime: " + uptime + "s");
        out().println("Registered Clients: " + registry.size());
        out().println("Indexed Files: " + fileIndex.fileCount());
        out().println("IP Filter Ranges: " + ipFilter.getFilterCount());
        out().println("System Load: " + String.format("%.2f", os.getSystemLoadAverage()));
        out().println("Memory Used: " + (mem.getHeapMemoryUsage().getUsed() / 1024 / 1024) + " MB");
        out().println("---------------------");
    }

    private void showClients(String search) {
        String query = search.toLowerCase(Locale.ROOT);
        out().println("--- Connected Clients (" + registry.size() + (query.isEmpty() ? "" : ", search: " + search) + ") ---");
        out().printf("%-15s | %-24s | %-10s | %-8s | %s%n", "IP", "Nickname", "Type", "Files", "ID");
        out().println("---------------------------------------------------------------");
        int count = 0;
        for (org.jemule.core.ClientState client : registry.getAllClients()) {
            String ip = client.address().getHostAddress();
            String nickname = client.nickname();
            String id = Long.toUnsignedString(client.clientId());
            if (!query.isEmpty() && !ip.contains(query) && !nickname.toLowerCase(Locale.ROOT).contains(query)
                    && !id.contains(query)) continue;
            if (count++ >= 50) {
                out().println("... and more");
                break;
            }
            out().printf("%-15s | %-24s | %-10s | %-8d | %s%n",
                    ip, nickname, client.isHighId() ? "HighID" : "LowID", client.publishedFilesCount().get(), id);
        }
        out().println("---------------------------------------------------------------");
    }

    private void showFiles() {
        out().println("--- File Statistics ---");
        out().println("Total files indexed: " + fileIndex.fileCount());
        out().println("Fake file detection: " + (fakeFileDetector.isEnabled() ? "ENABLED" : "DISABLED"));
        out().println("------------------------");
    }

    private void showIngestion() {
        out().println("--- New files inserted into the database (since start) ---");
        out().printf("%-16s %12s %10s%n", "Channel", "New files", "Share");
        long total = fileIndex.totalNewFiles();
        for (Map.Entry<FileSource, Long> entry : fileIndex.newFileCounts().entrySet()) {
            long count = entry.getValue();
            out().printf("%-16s %12d %9.1f%%%n", entry.getKey().label(), count,
                    total == 0 ? 0.0 : count * 100.0 / total);
        }
        out().printf("%-16s %12d %9.1f%%%n", "total", total, total == 0 ? 0.0 : 100.0);
        out().println("Current files indexed: " + fileIndex.fileCount());
        out().println("--------------------------------------------------------");
    }

    private void showSearches() {
        out().println("--- Search queries sent and results returned (since start) ---");
        out().printf("%-14s %10s %10s %10s %10s %8s%n",
                "Channel", "Queries", "Requests", "Results", "Ingested", "Active");
        KadNode node = server != null ? server.getKadNode() : null;
        KadSearchBridge kad = node != null ? node.getSearchBridge() : null;
        out().printf("%-14s %10d %10d %10d %10d %8d%n", "Kad",
                kad == null ? 0 : kad.getSearchesForked(),
                kad == null ? 0 : kad.getRequestsSent(),
                kad == null ? 0 : kad.getResultsReceived(),
                kad == null ? 0 : kad.getFilesIngested(),
                kad == null ? 0 : kad.getActiveSessionCount());
        ServerSearchBridge servers = server != null ? server.getServerSearchBridge() : null;
        out().printf("%-14s %10d %10d %10d %10d %8d%n", "eD2K servers",
                servers == null ? 0 : servers.getSearchesForked(),
                servers == null ? 0 : servers.getRequestsSent(),
                servers == null ? 0 : servers.getResultsReceived(),
                servers == null ? 0 : servers.getFilesIngested(),
                servers == null ? 0 : servers.getActiveSessionCount());
        out().printf("Not sent (identical query already sent %d times in %d min): Kad %d, eD2K servers %d%n",
                KadSearchBridge.MAX_REPLAYS, KadSearchBridge.REPLAY_WINDOW_MS / 60_000,
                kad == null ? 0 : kad.getSearchesSkippedAsRecent(),
                servers == null ? 0 : servers.getSearchesSkippedAsRecent());
        GlobalSearchForwarder global = server != null ? server.getGlobalSearchForwarder() : null;
        if (global != null) {
            out().printf("UDP global searches of other servers' clients: %d received, %d searched on Kad%s,%n"
                            + "  %d skipped (duplicate within 30 min, no keyword, Kad off), %d refused (rate limit, child abuse filter)%n",
                    global.getReceived(), global.getForwarded(), global.isEnabled() ? "" : " (kadForkGlobalSearches=false)",
                    global.getSkipped(), global.getRefused());
        }
        out().println("-------------------------------------------------------------");
    }

    private void handleBan(String[] parts) {
        if (parts.length < 2) {
            out().println("Usage: ban <hash> [reason]");
            return;
        }
        String hash = parts[1].toUpperCase();
        if (!hash.matches("[0-9A-F]{32}")) {
            out().println("Error: Hash must be 32 characters (hex).");
            return;
        }
        String reason = "Manual ban" + (parts.length > 2 ? ": " + parts[2] : "");
        fakeFileDetector.ban(hash, reason);
        boolean removed = fileIndex.removeFile(hash);
        out().println("Hash " + hash + " added to blacklist" + (removed ? " and removed from the index." : "."));
    }

    private void handleUnban(String[] parts) {
        if (parts.length < 2) {
            out().println("Usage: unban <hash>");
            return;
        }
        String hash = parts[1].toUpperCase(Locale.ROOT);
        if (!hash.matches("[0-9A-F]{32}")) {
            out().println("Error: Hash must be 32 characters (hex).");
            return;
        }
        boolean wasBanned = fakeFileDetector.unban(hash);
        out().println(wasBanned ? "Hash " + hash + " removed from the blacklist."
                : "Hash " + hash + " was not in the blacklist.");
    }

    private void handleKick(String[] parts) {
        if (parts.length < 2) {
            out().println("Usage: kick <client ID | nickname | ip>");
            return;
        }
        if (server == null) {
            out().println("Client disconnection is unavailable.");
            return;
        }
        String target = (parts.length > 2 ? parts[1] + " " + parts[2] : parts[1]).trim();
        if (target.chars().filter(c -> c == '.' || c == ':').count() >= 3) {
            int kicked = server.kickIp(target);
            out().println(kicked == 0 ? "No client matches " + target + "." : kicked + " client(s) disconnected.");
            return;
        }
        boolean kicked = server.kickClient(target);
        out().println(kicked ? "Client " + target + " disconnected." : "No client matches " + target + ".");
    }

    private void showEvents() {
        org.jemule.core.event.EventStatistics stats = server != null ? server.getEventStatistics() : null;
        if (stats == null) {
            out().println("Event statistics are not available.");
            return;
        }
        Map<String, Long> totals = stats.totals();
        Map<String, Long> lastMinute = stats.lastMinute(java.time.Instant.now().getEpochSecond());
        out().println("--- Events (total since startup / last minute) ---");
        totals.forEach((type, total) -> out().printf("%-22s %10d %8d%n", type, total, lastMinute.getOrDefault(type, 0L)));
        out().println("---------------------------------------------------");
    }

    private void showPeers() {
        List<ServerGossip.VerifiedPeer> peers = server != null ? server.getVerifiedServerPeers() : List.of();
        out().println("--- Verified Server Peers (" + peers.size() + ") ---");
        out().printf("%-28s | %-21s | %s%n", "Name", "Address", "Verified");
        for (ServerGossip.VerifiedPeer peer : peers) {
            out().printf("%-28s | %s:%d | %ds ago%n", peer.name(), peer.endpoint().address().getHostAddress(),
                    peer.endpoint().tcpPort(), Math.max(0, (System.currentTimeMillis() - peer.verifiedAtMillis()) / 1000));
        }

        KadNode kadNode = server != null ? server.getKadNode() : null;
        List<KadContact> contacts = kadNode != null ? kadNode.getRoutingTable().getAllContacts() : List.of();
        out().println("--- Kad Contacts (" + contacts.size() + ") ---");
        out().printf("%-34s | %-21s | %-6s | %s%n", "Node ID", "Address", "UDP", "Seen");
        long now = System.currentTimeMillis();
        for (KadContact contact : contacts) {
            out().printf("%-34s | %s:%d | %-6d | %ds ago%n", contact.id(),
                    contact.address().getHostAddress(), contact.udpPort(), contact.tcpPort(),
                    Math.max(0, (now - contact.lastSeen()) / 1000));
        }
    }

    private void showServers() {
        List<ServerGossip.ServerView> servers = server != null ? server.getServerViews() : List.of();
        out().println("--- eD2K Servers (" + servers.size() + ") ---");
        out().printf("%-24s %-6s %-16s %-21s %8s %10s %8s %8s%n",
                "Name", "State", "Source", "Address", "Users", "Files", "Latency", "Missed");
        for (ServerGossip.ServerView view : servers) {
            out().printf("%-24s %-6s %-16s %-21s %8s %10s %7sms %8d%n",
                    view.name().isBlank() ? "-" : view.name(),
                    view.state(),
                    view.source().name().toLowerCase(Locale.ROOT),
                    view.endpoint().address().getHostAddress() + ":" + view.endpoint().tcpPort(),
                    view.users() == null ? "-" : view.users(),
                    view.files() == null ? "-" : view.files(),
                    view.latencyMillis() == null ? "-" : view.latencyMillis(),
                    view.missedStatusProbes());
        }
    }

    private void handleFilters(String[] parts) {
        if (parts.length > 1 && parts[1].equalsIgnoreCase("reload")) {
            if (server == null) {
                out().println("Filter reload is unavailable.");
                return;
            }
            String path = server.getConfig().ipFilterPath();
            if (path == null || path.isBlank()) {
                out().println("IP filter path is empty: nothing to reload.");
            } else {
                server.reloadIpFilter(path);
                out().println("IP filter reload requested from " + path + ".");
            }
            server.reloadChildAbuseKeywords(server.getConfig().childAbuseKeywordsPath());
            out().println("Child abuse keywords reloaded.");
            return;
        }
        showFilters();
    }

    private void showFilters() {
        out().println("--- Filters ---");
        out().println("Blocked IP ranges: " + ipFilter.getFilterCount());
        ipFilter.getBlockedRanges(50).forEach(out()::println);
        out().println("Fake-file heuristics: " + (fakeFileDetector.isEnabled() ? "ENABLED" : "DISABLED"));
        out().println("Bot detection: nickname heuristic only (use 'bots' to list matches)");
    }

    private void showBots() {
        int matches = 0;
        out().println("--- Potential Bots (nickname heuristic only) ---");
        for (org.jemule.core.ClientState client : registry.getAllClients()) {
            if (!isPotentialBot(client.nickname())) continue;
            out().printf("%s | %s | %s%n", client.nickname(), client.address().getHostAddress(),
                    Long.toUnsignedString(client.clientId()));
            matches++;
        }
        out().println("Potential bot matches: " + matches + " (not verified or automatically blocked)");
    }

    static boolean isPotentialBot(String nickname) {
        return nickname != null && POSSIBLE_BOT_NAME.matcher(nickname).find();
    }

    private void showBlocks() {
        org.jemule.core.DatabaseManager database = server != null ? server.getDatabaseManager() : null;
        if (database == null) {
            out().println("Persistent hash blocks are unavailable.");
            return;
        }
        List<String> hashes = database.loadRecentBannedHashes(3);
        out().println("--- Last 3 Blocked Hashes ---");
        hashes.forEach(out()::println);
    }

    private void handleSettings(String[] parts) {
        if (parts.length == 1) {
            out().println("--- Runtime Settings ---");
            out().println("Fake-file detection: " + (fakeFileDetector.isEnabled() ? "ENABLED" : "DISABLED"));
            if (server != null) {
                out().println("Admin web port: " + server.getConfig().adminWebPort());
                out().println("Admin console port: " + server.getConfig().adminPort());
                out().println("Admin bind address: " + server.getConfig().adminBindAddress());
                out().println("Admin allowed IPs: " + server.getConfig().adminAllowedIps());
                if (!server.getConfig().adminAllowedMacs().isEmpty()) {
                    out().println("Admin allowed MACs: " + server.getConfig().adminAllowedMacs());
                }
            }
            out().println("--- Configuration parameters (key=value [scope]) ---");
            Map<String, String> pending = server != null ? server.pendingRestart() : Map.of();
            for (ConfigParameter parameter : ConfigParameter.ordered()) {
                Object value = parameter == ConfigParameter.FAKE_FILE_DETECTION_ENABLED
                        ? fakeFileDetector.isEnabled()
                        : server != null ? server.getConfig().value(parameter) : "";
                String next = pending.get(parameter.key());
                out().println("  " + parameter.key() + "=" + value + " [" + parameter.scope().id() + "]"
                        + (next != null ? " (after restart: " + next + ")" : ""));
            }
            out().println("Usage: settings <key> <value> | settings reload | settings fakeFileDetectionEnabled <on|off>");
            return;
        }
        String[] args = parts.length > 2
                ? (parts[1] + " " + parts[2]).trim().split("\\s+")
                : parts[1].trim().split("\\s+");
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (server == null) {
                out().println("Configuration reload is unavailable.");
                return;
            }
            printConfigResult(server.reloadConfig());
            return;
        }
        if (args.length != 2) {
            out().println("Usage: settings <key> <value> | settings reload | settings fakeFileDetectionEnabled <on|off>");
            return;
        }
        String key = args[0];
        String value = args[1];
        if (key.equalsIgnoreCase(ConfigParameter.FAKE_FILE_DETECTION_ENABLED.key())) {
            if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off")) {
                value = String.valueOf(value.equalsIgnoreCase("on"));
            }
            ConfigApplyResult result = server == null ? null : server.updateConfig(Map.of(key, value));
            if (result == null) {
                fakeFileDetector.setEnabled(Boolean.parseBoolean(value));
                out().println("Fake-file detection " + (fakeFileDetector.isEnabled() ? "enabled." : "disabled."));
            } else {
                printConfigResult(result);
            }
            return;
        }
        if (ConfigParameter.byKey(key) == null) {
            out().println("Unknown configuration key: " + key);
            return;
        }
        if (server == null) {
            out().println("Configuration changes are unavailable.");
            return;
        }
        printConfigResult(server.updateConfig(Map.of(key, value)));
    }

    private void printConfigResult(ConfigApplyResult result) {
        if (result == null) {
            out().println("Configuration change failed.");
            return;
        }
        if (!result.errors().isEmpty()) {
            result.errors().forEach(error -> out().println("Error: " + error));
            return;
        }
        if (result.applied().isEmpty() && result.deferred().isEmpty()) {
            out().println("No change.");
            return;
        }
        if (!result.applied().isEmpty()) {
            out().println("Applied: " + String.join(", ", result.applied()));
        }
        if (!result.deferred().isEmpty()) {
            out().println("Restart required: " + String.join(", ", result.deferred()));
        }
    }

    /** 'kad [status|contacts|bootstrap &lt;ip&gt; &lt;port&gt;|stats]': supervision of the embedded Kademlia node. */
    private void handleKad(String[] parts) {
        KadNode node = server != null ? server.getKadNode() : null;
        if (node == null) {
            out().println("Kademlia is disabled (kadEnabled=false).");
            return;
        }
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "status";
        switch (sub) {
            case "status" -> showKadStatus(node);
            case "contacts" -> showKadContacts(node);
            case "bootstrap" -> handleKadBootstrap(node, parts);
            case "stats" -> showKadStats(node);
            default -> out().println("Usage: kad [status|contacts|bootstrap <ip> <port>|stats]");
        }
    }

    private void showKadStatus(KadNode node) {
        out().println("--- Kademlia Status ---");
        out().println("Status: " + node.status());
        out().println("Node ID: " + node.localId().toHexString().toUpperCase());
        out().println("UDP Port: " + node.getUdpPort() + (node.isRunning() ? "" : " (not bound)"));
        out().println("Contacts: " + node.getRoutingTable().size() + " / " + node.getRoutingTable().maxContacts());
        out().println("Bootstrap from clients: " + (node.getConfig().bootstrapFromClients() ? "ENABLED" : "DISABLED"));
        KadNodesUpdater nodes = node.getNodesUpdater();
        out().println("Nodes.dat sources: " + (nodes == null ? "DISABLED"
                : nodes.getSources().size() + " configured, " + nodes.getLastContactCount()
                + " contacts loaded from " + (nodes.getLastSource().isBlank() ? "none" : nodes.getLastSource())));
        out().println("Forked search: " + (node.getConfig().forkSearchEnabled() ? "ENABLED" : "DISABLED"));
        out().println("H2 ingestion: " + (node.getConfig().autoIngestToH2() ? "ENABLED" : "DISABLED")
                + " (min sources: " + node.getConfig().minSourcesForIngest() + ")");
        out().println("-----------------------");
    }

    private void showKadContacts(KadNode node) {
        List<KadContact> contacts = node.getRoutingTable().getAllContacts();
        contacts.sort(Comparator.comparingLong(KadContact::lastSeen).reversed());
        out().println("--- Kademlia Contacts (" + contacts.size() + ") ---");
        out().printf("%-32s | %-21s | %-7s | %-9s | %s%n", "ID", "Address", "Version", "Last seen", "Liveness");
        out().println("-------------------------------------------------------------------------------------------");
        long now = System.currentTimeMillis();
        for (KadContact c : contacts.subList(0, Math.min(MAX_LISTED_KAD_CONTACTS, contacts.size()))) {
            String liveness = c.isDead() ? "DEAD" : c.failedPings() > 0 ? "UNANSWERED (" + c.failedPings() + ")" : "ALIVE";
            out().printf("%-32s | %-21s | %-7d | %-9s | %s%n",
                    c.id().toHexString().toUpperCase(),
                    c.address().getHostAddress() + ":" + c.udpPort(),
                    c.kadVersion() & 0xFF,
                    Math.max(0, (now - c.lastSeen()) / 1000) + "s",
                    liveness);
        }
        if (contacts.size() > MAX_LISTED_KAD_CONTACTS) {
            out().println("... and " + (contacts.size() - MAX_LISTED_KAD_CONTACTS) + " more");
        }
        out().println("-------------------------------------------------------------------------------------------");
    }

    private void handleKadBootstrap(KadNode node, String[] parts) {
        String[] args = parts.length > 2 ? parts[2].trim().split("\\s+") : new String[0];
        if (args.length != 2) {
            out().println("Usage: kad bootstrap <ip> <port>");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            out().println("Error: Port must be between 1 and 65535.");
            return;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(args[0]);
        } catch (java.net.UnknownHostException e) {
            out().println("Error: Unknown host " + args[0] + ".");
            return;
        }
        if (node.bootstrap(address, port)) {
            out().println("Bootstrap request sent to " + address.getHostAddress() + ":" + port + ".");
        } else {
            out().println("Error: The Kademlia node is not running.");
        }
    }

    private void showKadStats(KadNode node) {
        KadSearchBridge bridge = node.getSearchBridge();
        KadBootstrapManager bootstrap = node.getBootstrapManager();
        KadMaintenanceService maintenance = node.getMaintenanceService();
        KadHashBanService bans = node.getHashBanService();
        out().println("--- Kademlia Statistics ---");
        out().println("Searches forked: " + bridge.getSearchesForked());
        out().println("Active searches: " + bridge.getActiveSessionCount());
        out().println("Searches not sent (already sent " + KadSearchBridge.MAX_REPLAYS + " times in "
                + KadSearchBridge.REPLAY_WINDOW_MS / 60_000 + " min): "
                + bridge.getSearchesSkippedAsRecent());
        out().println("Results received: " + bridge.getResultsReceived());
        out().println("Files ingested: " + bridge.getFilesIngested());
        out().println("Illicit files blocked: " + bridge.getIllicitFilesBlocked());
        out().println("Hashes banned: " + (bans != null ? bans.getTotalBanned() : 0));
        out().println("Bootstrap requests: " + bootstrap.getBootstrapAttempts()
                + " (answered: " + bootstrap.getBootstrapSuccesses()
                + ", contacts discovered: " + bootstrap.getContactsDiscovered() + ")");
        KadNodesUpdater nodes = node.getNodesUpdater();
        out().println("Nodes.dat list: " + (nodes == null ? "disabled"
                : nodes.getLastContactCount() + " contacts, last refresh "
                + (nodes.getLastRefresh() == 0 ? "never" : (System.currentTimeMillis() - nodes.getLastRefresh()) / 1000 + "s ago")));
        out().println("Maintenance: " + maintenance.getPingsSent() + " pings, " + maintenance.getRefreshCount()
                + " bucket refreshes, " + maintenance.getContactsEvicted() + " contacts evicted");
        out().println("---------------------------");
    }

    private void showHelp() {
        out().println("Available commands:");
        out().println("  status          - Show server performance and stats");
        out().println("  clients         - Show connected clients count");
        out().println("  clients <text>  - Search clients by nickname, IP, or client ID");
        out().println("  files           - Show file indexing stats");
        out().println("  ingestion       - New files inserted into the database by channel (client push, Kad, servers)");
        out().println("  searches        - Kad and eD2K server search queries and results returned");
        out().println("  ban <hash> [R]  - Ban a file hash (32 hex chars)");
        out().println("  unban <hash>    - Remove a hash from the blacklist");
        out().println("  kick <target>   - Disconnect a client by ID, nickname or IP");
        out().println("  events          - Show event counts (total and last minute)");
        out().println("  peers           - List verified server peers and Kad contacts");
        out().println("  servers         - Monitor known eD2K servers and their state");
        out().println("  filters         - Show active IP and fake-file filters");
        out().println("  filters reload  - Reload the IP filter and the child abuse keywords");
        out().println("  bots            - List potential bots by nickname heuristic");
        out().println("  blocks          - List the 3 most recently blocked file hashes");
        out().println("  settings        - Show runtime settings");
        out().println("  settings <key> <value> - Change a configuration parameter");
        out().println("  settings reload - Reload the configuration file without restarting");
        out().println("  kad status      - Show the Kademlia node status, UDP port and contact count");
        out().println("  kad contacts    - List the " + MAX_LISTED_KAD_CONTACTS + " most recent Kademlia contacts");
        out().println("  kad bootstrap <ip> <port> - Send a bootstrap request to a Kademlia node");
        out().println("  kad stats       - Show the Kademlia counters (searches, ingestion, bans)");
        out().println("  help            - Show this help message");
        out().println("  stop/exit/quit  - Shutdown the server");
    }
}

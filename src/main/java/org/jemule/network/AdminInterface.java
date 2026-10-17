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
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.kad.KadBootstrapManager;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadHashBanService;
import org.jemule.kad.KadMaintenanceService;
import org.jemule.kad.KadNode;
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

/**
 * Provides a command-line interface for server administration: on the standard input of the server, and on the
 * local admin port (see {@code adminPort}) when the server runs as a service without a terminal.
 */
public class AdminInterface implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AdminInterface.class);
    /** Maximum number of contacts listed by 'kad contacts'. */
    static final int MAX_LISTED_KAD_CONTACTS = 20;
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
            case "clients" -> showClients();
            case "files" -> showFiles();
            case "ban" -> handleBan(parts);
            case "events" -> showEvents();
            case "kad" -> handleKad(parts);
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

    private void showClients() {
        out().println("--- Connected Clients (" + registry.size() + ") ---");
        out().printf("%-15s | %-10s | %-10s | %-8s | %s%n", "IP", "ID", "Type", "Files", "Uptime");
        out().println("---------------------------------------------------------------");
        long now = System.currentTimeMillis();
        int count = 0;
        for (org.jemule.core.ClientState client : registry.getAllClients()) {
            if (count++ > 50) {
                out().println("... and more");
                break;
            }
            String type = client.isHighId() ? "HighID" : "LowID";
            long uptime = (now - client.connectedAt()) / 1000;
            out().printf("%-15s | %-10s | %-10s | %-8d | %ds%n",
                    client.address().getHostAddress(),
                    Long.toUnsignedString(client.clientId()),
                    type,
                    client.publishedFilesCount().get(),
                    uptime);
        }
        out().println("---------------------------------------------------------------");
    }

    private void showFiles() {
        out().println("--- File Statistics ---");
        out().println("Total files indexed: " + fileIndex.fileCount());
        out().println("Fake file detection: " + (fakeFileDetector.isEnabled() ? "ENABLED" : "DISABLED"));
        out().println("------------------------");
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
        out().println("Results received: " + bridge.getResultsReceived());
        out().println("Files ingested: " + bridge.getFilesIngested());
        out().println("Illicit files blocked: " + bridge.getIllicitFilesBlocked());
        out().println("Hashes banned: " + (bans != null ? bans.getTotalBanned() : 0));
        out().println("Bootstrap requests: " + bootstrap.getBootstrapAttempts()
                + " (answered: " + bootstrap.getBootstrapSuccesses()
                + ", contacts discovered: " + bootstrap.getContactsDiscovered() + ")");
        out().println("Maintenance: " + maintenance.getPingsSent() + " pings, " + maintenance.getRefreshCount()
                + " bucket refreshes, " + maintenance.getContactsEvicted() + " contacts evicted");
        out().println("---------------------------");
    }

    private void showHelp() {
        out().println("Available commands:");
        out().println("  status          - Show server performance and stats");
        out().println("  clients         - Show connected clients count");
        out().println("  files           - Show file indexing stats");
        out().println("  ban <hash> [R]  - Ban a file hash (32 hex chars)");
        out().println("  events          - Show event counts (total and last minute)");
        out().println("  kad status      - Show the Kademlia node status, UDP port and contact count");
        out().println("  kad contacts    - List the " + MAX_LISTED_KAD_CONTACTS + " most recent Kademlia contacts");
        out().println("  kad bootstrap <ip> <port> - Send a bootstrap request to a Kademlia node");
        out().println("  kad stats       - Show the Kademlia counters (searches, ingestion, bans)");
        out().println("  help            - Show this help message");
        out().println("  stop/exit/quit  - Shutdown the server");
    }
}

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

/**
 * Provides a command-line interface for server administration: on the standard input of the server, and on the
 * local admin port (see {@code adminPort}) when the server runs as a service without a terminal.
 */
public class AdminInterface implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AdminInterface.class);
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

    private void showHelp() {
        out().println("Available commands:");
        out().println("  status          - Show server performance and stats");
        out().println("  clients         - Show connected clients count");
        out().println("  files           - Show file indexing stats");
        out().println("  ban <hash> [R]  - Ban a file hash (32 hex chars)");
        out().println("  help            - Show this help message");
        out().println("  stop/exit/quit  - Shutdown the server");
    }
}

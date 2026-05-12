/*
 * JEmuleServer - An experimental eMule server.
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
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.Scanner;

/**
 * Provides a command-line interface for server administration.
 */
public class AdminInterface implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AdminInterface.class);
    private final Server server;
    private final ClientRegistry registry;
    private final FileIndex fileIndex;
    private final IPFilter ipFilter;
    private final FakeFileDetector fakeFileDetector;
    private final long startTime;

    public AdminInterface(Server server, ClientRegistry registry, FileIndex fileIndex, IPFilter ipFilter, FakeFileDetector fakeFileDetector) {
        this.server = server;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.ipFilter = ipFilter;
        this.fakeFileDetector = fakeFileDetector;
        this.startTime = System.currentTimeMillis();
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
                System.out.println("Stopping server...");
                server.stop();
            }
            default -> System.out.println("Unknown command: " + command + ". Type 'help' for list.");
        }
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== JEmuleServer Admin Interface (" + Main.VERSION + ") ===");
        System.out.println("Type 'help' for available commands.");

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            handleCommand(scanner.nextLine());
        }
    }

    private void showStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        System.out.println("--- Server Status ---");
        System.out.println("Version: " + Main.VERSION);
        System.out.println("Uptime: " + uptime + "s");
        System.out.println("Registered Clients: " + registry.size());
        System.out.println("Indexed Files: " + fileIndex.fileCount());
        System.out.println("IP Filter Ranges: " + ipFilter.getFilterCount());
        System.out.println("System Load: " + String.format("%.2f", os.getSystemLoadAverage()));
        System.out.println("Memory Used: " + (mem.getHeapMemoryUsage().getUsed() / 1024 / 1024) + " MB");
        System.out.println("---------------------");
    }

    private void showClients() {
        System.out.println("--- Connected Clients (" + registry.size() + ") ---");
        System.out.printf("%-15s | %-10s | %-10s | %-8s | %s%n", "IP", "ID", "Type", "Files", "Uptime");
        System.out.println("---------------------------------------------------------------");
        long now = System.currentTimeMillis();
        int count = 0;
        for (org.jemule.core.ClientState client : registry.getAllClients()) {
            if (count++ > 50) {
                System.out.println("... and more");
                break;
            }
            String type = client.isHighId() ? "HighID" : "LowID";
            long uptime = (now - client.connectedAt()) / 1000;
            System.out.printf("%-15s | %-10d | %-10s | %-8d | %ds%n",
                    client.address().getHostAddress(),
                    client.clientId(),
                    type,
                    client.publishedFilesCount().get(),
                    uptime);
        }
        System.out.println("---------------------------------------------------------------");
    }

    private void showFiles() {
        System.out.println("--- File Statistics ---");
        System.out.println("Total files indexed: " + fileIndex.fileCount());
        System.out.println("Fake file detection: " + (fakeFileDetector.isEnabled() ? "ENABLED" : "DISABLED"));
        System.out.println("------------------------");
    }

    private void handleBan(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: ban <hash> [reason]");
            return;
        }
        String hash = parts[1].toUpperCase();
        if (hash.length() != 32) {
            System.out.println("Error: Hash must be 32 characters (hex).");
            return;
        }
        String reason = parts.length > 2 ? parts[2] : "Manual ban";
        fakeFileDetector.addBannedHash(hash);
        // We could also call db.addBannedHash if we passed it in.
        System.out.println("Hash " + hash + " added to blacklist.");
    }

    private void showHelp() {
        System.out.println("Available commands:");
        System.out.println("  status          - Show server performance and stats");
        System.out.println("  clients         - Show connected clients count");
        System.out.println("  files           - Show file indexing stats");
        System.out.println("  ban <hash> [R]  - Ban a file hash (32 hex chars)");
        System.out.println("  help            - Show this help message");
        System.out.println("  stop/exit/quit  - Shutdown the server");
    }
}

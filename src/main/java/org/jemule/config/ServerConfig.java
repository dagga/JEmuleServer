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


package org.jemule.config;

/**
 * Configuration parameters for the JEmuleServer.
 *
 * @param port                       The port to listen on for TCP/UDP connections.
 * @param maxPacketSize              Maximum allowed size for incoming packets.
 * @param maxSearchResults           Limit of results returned per search.
 * @param floodMaxRequestsPerSecond  Anti-flood threshold per IP.
 * @param maxUsers                   Maximum concurrent users allowed on server.
 * @param maxFiles                   Maximum global indexed files.
 * @param maxFilesPerUser            Quota of files a single user can publish.
 * @param maxSourcesPerFile          Maximum sources returned per file request.
 * @param databasePath               Path to the H2 database file.
 * @param cbFailureRateThreshold     Circuit Breaker: threshold percentage for opening.
 * @param cbMinimumNumberOfCalls     Circuit Breaker: min calls before calculating rate.
 * @param cbWaitDurationInSeconds    Circuit Breaker: how long to stay open.
 */
public record ServerConfig(
        int port,
        int maxPacketSize,
        int maxSearchResults,
        int floodMaxRequestsPerSecond,
        int maxUsers,
        int maxFiles,
        int maxFilesPerUser,
        int maxSourcesPerFile,
        String databasePath,
        
        // Circuit Breaker settings
        float cbFailureRateThreshold,
        int cbMinimumNumberOfCalls,
        int cbWaitDurationInSeconds
) {
    public ServerConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (port < 1024) {
            // Non-blocking warning for privileged ports
            System.err.println("[WARNING] Running on a privileged port (" + port + "). This might require root privileges.");
        }
    }

    /**
     * Default configuration for a standard eMule server.
     */
    public static final ServerConfig DEFAULT = new ServerConfig(
            4661,
            2 * 1024 * 1024,
            300,
            50,
            100000,
            10000000,
            5000,
            200,
            "./jemule_db",
            50.0f,
            10,
            60
    );
}

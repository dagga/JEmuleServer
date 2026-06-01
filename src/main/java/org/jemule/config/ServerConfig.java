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

import java.util.Properties;

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
 * @param ipFilterPath               Path to the ipfilter.dat file.
 * @param fakeFileDetectionEnabled   Whether to enable heuristics for fake files.
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
        String ipFilterPath,
        boolean fakeFileDetectionEnabled,
        
        // Circuit Breaker settings
        float cbFailureRateThreshold,
        int cbMinimumNumberOfCalls,
        int cbWaitDurationInSeconds,
        
        // Timeout settings
        int tcpKeepAliveTimeoutInSeconds,
        
        // Heartbeat settings
        int heartbeatIntervalSeconds,
        
        // Public IP (optional – null or empty means auto-detect)
        String publicIp
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
            null,
            true,
            50.0f,
            10,
            60,
            1800,
            120,
            null
    );

    /**
     * Creates a ServerConfig from a Properties object, using DEFAULT values for missing keys.
     */
    public static ServerConfig fromProperties(Properties props) {
        return new ServerConfig(
                Integer.parseInt(props.getProperty("port", String.valueOf(DEFAULT.port()))),
                Integer.parseInt(props.getProperty("maxPacketSize", String.valueOf(DEFAULT.maxPacketSize()))),
                Integer.parseInt(props.getProperty("maxSearchResults", String.valueOf(DEFAULT.maxSearchResults()))),
                Integer.parseInt(props.getProperty("floodMaxRequestsPerSecond", String.valueOf(DEFAULT.floodMaxRequestsPerSecond()))),
                Integer.parseInt(props.getProperty("maxUsers", String.valueOf(DEFAULT.maxUsers()))),
                Integer.parseInt(props.getProperty("maxFiles", String.valueOf(DEFAULT.maxFiles()))),
                Integer.parseInt(props.getProperty("maxFilesPerUser", String.valueOf(DEFAULT.maxFilesPerUser()))),
                Integer.parseInt(props.getProperty("maxSourcesPerFile", String.valueOf(DEFAULT.maxSourcesPerFile()))),
                props.getProperty("databasePath", DEFAULT.databasePath()),
                props.getProperty("ipFilterPath", DEFAULT.ipFilterPath()),
                Boolean.parseBoolean(props.getProperty("fakeFileDetectionEnabled", String.valueOf(DEFAULT.fakeFileDetectionEnabled()))),
                Float.parseFloat(props.getProperty("cbFailureRateThreshold", String.valueOf(DEFAULT.cbFailureRateThreshold()))),
                Integer.parseInt(props.getProperty("cbMinimumNumberOfCalls", String.valueOf(DEFAULT.cbMinimumNumberOfCalls()))),
                Integer.parseInt(props.getProperty("cbWaitDurationInSeconds", String.valueOf(DEFAULT.cbWaitDurationInSeconds()))),
                Integer.parseInt(props.getProperty("tcpKeepAliveTimeoutInSeconds", String.valueOf(DEFAULT.tcpKeepAliveTimeoutInSeconds()))),
                Integer.parseInt(props.getProperty("heartbeatIntervalSeconds", String.valueOf(DEFAULT.heartbeatIntervalSeconds()))),
                props.getProperty("publicIp", DEFAULT.publicIp())
        );
    }
}

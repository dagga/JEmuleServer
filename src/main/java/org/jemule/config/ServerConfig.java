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


package org.jemule.config;

import java.util.Properties;

/**
 * Configuration parameters for the JEmuleServer.
 *
 * @param port                      The port to listen on for TCP/UDP connections.
 * @param maxPacketSize             Maximum allowed size for incoming packets.
 * @param maxSearchResults          Limit of results returned per search.
 * @param floodMaxRequestsPerSecond Anti-flood threshold per IP.
 * @param maxUsers                  Maximum concurrent users allowed on server.
 * @param maxFiles                  Maximum global indexed files.
 * @param maxFilesPerUser           Quota of files a single user can publish.
 * @param maxSourcesPerFile         Maximum sources returned per file request.
 * @param databasePath              Path to the H2 database file.
 * @param ipFilterPath              Path to the ipfilter.dat file.
 * @param fakeFileDetectionEnabled  Whether to enable heuristics for fake files.
 * @param cbFailureRateThreshold    Circuit Breaker: threshold percentage for opening.
 * @param cbMinimumNumberOfCalls    Circuit Breaker: min calls before calculating rate.
 * @param cbWaitDurationInSeconds   Circuit Breaker: how long to stay open.
 * @param childAbuseKeywordsPath    Keyword file of the child abuse filter (null: the embedded list).
 * @param adminPort                 Local TCP port of the admin console (0: disabled).
 * @param kad                       Configuration of the embedded Kademlia node (SFD 1.2).
 * @param adminBindAddress          Bind address for admin interfaces (default: 0.0.0.0).
 * @param adminAllowedIps           Allowed IPv4/IPv6 addresses or CIDR blocks (comma-separated).
 * @param adminAllowedMacs          Allowed MAC addresses (comma-separated, empty: disabled).
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
        String publicIp,

        // HighID test: time allowed to connect to the client's listen port and get its OP_HELLOANSWER
        // (0 disables the test: every client gets a LowID)
        int lowIdCheckTimeoutSeconds,

        // Keyword file of the child abuse filter (optional – null or empty means the list embedded in the jar)
        String childAbuseKeywordsPath,

        // Admin console on a TCP port bound to the loopback address (0 disables it)
        int adminPort,

        // Embedded Kademlia node, forked searches and Kad file ingestion (null means KadConfig.DEFAULT)
        KadConfig kad,

        // Comma-separated IPv4 server seed endpoints in host:TCP-port form
        String serverPeers,

        // HTTP admin panel on the loopback address (0 disables it)
        int adminWebPort,

        // HTTPS server.met source merged into the candidate server list at startup
        String serverMetUrl,

        // Admin bind address (default: 0.0.0.0 to allow filtered remote connections, or 127.0.0.1)
        String adminBindAddress,

        // Allowed IPv4 / IPv6 addresses or CIDR blocks for admin interfaces (comma-separated, default: 127.0.0.1,::1)
        String adminAllowedIps,

        // Allowed MAC addresses for admin interfaces (comma-separated, empty: disabled)
        String adminAllowedMacs
) {
    /**
     * Default time allowed for the HighID test, in seconds.
     */
    public static final int DEFAULT_LOWID_CHECK_TIMEOUT_SECONDS = 5;
    public static final String DEFAULT_SERVER_MET_URL = "https://upd.emule-security.org/server.met";
    public static final String DEFAULT_IP_FILTER_PATH = "./ipfilter.dat";
    public static final String DEFAULT_ADMIN_BIND_ADDRESS = "0.0.0.0";
    public static final String DEFAULT_ADMIN_ALLOWED_IPS = "127.0.0.1,::1";
    public static final String DEFAULT_ADMIN_ALLOWED_MACS = "";

    /**
     * Constructor without the HighID test timeout (uses {@link #DEFAULT_LOWID_CHECK_TIMEOUT_SECONDS}).
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, DEFAULT_LOWID_CHECK_TIMEOUT_SECONDS, null, 0);
    }

    /**
     * Constructor without the child abuse keyword file (uses the list embedded in the jar).
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, null, 0);
    }

    /**
     * Constructor without the Kademlia settings (uses {@link KadConfig#DEFAULT}).
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds, String childAbuseKeywordsPath, int adminPort) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, KadConfig.DEFAULT, "");
    }

    /**
     * Constructor retaining source compatibility for callers that provide Kademlia settings.
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds, String childAbuseKeywordsPath, int adminPort,
                        KadConfig kad) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, "");
    }

    /**
     * Constructor retaining source compatibility for callers that also provide gossip peers.
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds, String childAbuseKeywordsPath, int adminPort,
                        KadConfig kad, String serverPeers) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, 0);
    }

    /**
     * Constructor retaining source compatibility for callers that provide the web admin port.
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds, String childAbuseKeywordsPath, int adminPort,
                        KadConfig kad, String serverPeers, int adminWebPort) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                DEFAULT_SERVER_MET_URL);
    }

    /**
     * Constructor retaining source compatibility for callers that provide the server.met URL.
     */
    public ServerConfig(int port, int maxPacketSize, int maxSearchResults, int floodMaxRequestsPerSecond, int maxUsers,
                        int maxFiles, int maxFilesPerUser, int maxSourcesPerFile, String databasePath, String ipFilterPath,
                        boolean fakeFileDetectionEnabled, float cbFailureRateThreshold, int cbMinimumNumberOfCalls,
                        int cbWaitDurationInSeconds, int tcpKeepAliveTimeoutInSeconds, int heartbeatIntervalSeconds,
                        String publicIp, int lowIdCheckTimeoutSeconds, String childAbuseKeywordsPath, int adminPort,
                        KadConfig kad, String serverPeers, int adminWebPort, String serverMetUrl) {
        this(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles, maxFilesPerUser,
                maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                serverMetUrl, DEFAULT_ADMIN_BIND_ADDRESS, DEFAULT_ADMIN_ALLOWED_IPS, DEFAULT_ADMIN_ALLOWED_MACS);
    }

    public ServerConfig {
        if (kad == null) {
            kad = KadConfig.DEFAULT;
        }
        if (serverPeers == null) {
            serverPeers = "";
        }
        if (serverMetUrl == null) {
            serverMetUrl = "";
        }
        if (adminBindAddress == null || adminBindAddress.isBlank()) {
            adminBindAddress = DEFAULT_ADMIN_BIND_ADDRESS;
        }
        if (adminAllowedIps == null) {
            adminAllowedIps = DEFAULT_ADMIN_ALLOWED_IPS;
        }
        if (adminAllowedMacs == null) {
            adminAllowedMacs = DEFAULT_ADMIN_ALLOWED_MACS;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (adminPort < 0 || adminPort > 65535) {
            throw new IllegalArgumentException("adminPort must be between 0 (disabled) and 65535");
        }
        if (adminWebPort < 0 || adminWebPort > 65535) {
            throw new IllegalArgumentException("adminWebPort must be between 0 (disabled) and 65535");
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
            1000000,
            10000000,
            5000,
            200,
            "./jemule_db",
            DEFAULT_IP_FILTER_PATH,
            true,
            50.0f,
            10,
            60,
            1800,
            120,
            null
    ).withAdminWebPort(8080);

    /**
     * Same configuration listening on another port.
     */
    public ServerConfig withPort(int newPort) {
        return new ServerConfig(newPort, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled,
                cbFailureRateThreshold, cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds,
                heartbeatIntervalSeconds, publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad,
                serverPeers, adminWebPort, serverMetUrl, adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    /**
     * Same configuration with other Kademlia settings.
     */
    public ServerConfig withKad(KadConfig newKad) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled,
                cbFailureRateThreshold, cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds,
                heartbeatIntervalSeconds, publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, newKad,
                serverPeers, adminWebPort, serverMetUrl, adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    /**
     * Same configuration with relative database / IP filter / keyword file paths resolved against the given directory.
     */
    public ServerConfig withPathsRelativeTo(java.nio.file.Path baseDir) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, resolve(baseDir, databasePath), resolve(baseDir, ipFilterPath),
                fakeFileDetectionEnabled, cbFailureRateThreshold, cbMinimumNumberOfCalls, cbWaitDurationInSeconds,
                tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds, publicIp, lowIdCheckTimeoutSeconds,
                resolve(baseDir, childAbuseKeywordsPath), adminPort, kad, serverPeers, adminWebPort, serverMetUrl,
                adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withAdminPort(int newAdminPort) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled,
                cbFailureRateThreshold, cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds,
                heartbeatIntervalSeconds, publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, newAdminPort, kad,
                serverPeers, adminWebPort, serverMetUrl, adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withAdminWebPort(int newAdminWebPort) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled,
                cbFailureRateThreshold, cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds,
                heartbeatIntervalSeconds, publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad,
                serverPeers, newAdminWebPort, serverMetUrl, adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withServerMetUrl(String newServerMetUrl) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                newServerMetUrl, adminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withAdminBindAddress(String newAdminBindAddress) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                serverMetUrl, newAdminBindAddress, adminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withAdminAllowedIps(String newAdminAllowedIps) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                serverMetUrl, adminBindAddress, newAdminAllowedIps, adminAllowedMacs);
    }

    public ServerConfig withAdminAllowedMacs(String newAdminAllowedMacs) {
        return new ServerConfig(port, maxPacketSize, maxSearchResults, floodMaxRequestsPerSecond, maxUsers, maxFiles,
                maxFilesPerUser, maxSourcesPerFile, databasePath, ipFilterPath, fakeFileDetectionEnabled, cbFailureRateThreshold,
                cbMinimumNumberOfCalls, cbWaitDurationInSeconds, tcpKeepAliveTimeoutInSeconds, heartbeatIntervalSeconds,
                publicIp, lowIdCheckTimeoutSeconds, childAbuseKeywordsPath, adminPort, kad, serverPeers, adminWebPort,
                serverMetUrl, adminBindAddress, adminAllowedIps, newAdminAllowedMacs);
    }

    /**
     * Rooted paths are kept as they are, including "\dir" or "/dir" on Windows, which are not absolute there.
     */
    private static String resolve(java.nio.file.Path baseDir, String path) {
        if (path == null || path.isBlank() || java.nio.file.Paths.get(path).getRoot() != null) return path;
        return baseDir.resolve(path).normalize().toString();
    }

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
                props.getProperty("publicIp", DEFAULT.publicIp()),
                Integer.parseInt(props.getProperty("lowIdCheckTimeoutSeconds", String.valueOf(DEFAULT.lowIdCheckTimeoutSeconds()))),
                props.getProperty("childAbuseKeywordsPath", DEFAULT.childAbuseKeywordsPath()),
                Integer.parseInt(props.getProperty("adminPort", String.valueOf(DEFAULT.adminPort()))),
                KadConfig.fromProperties(props),
                props.getProperty("serverPeers", DEFAULT.serverPeers()),
                Integer.parseInt(props.getProperty("adminWebPort", String.valueOf(DEFAULT.adminWebPort()))),
                props.getProperty("serverMetUrl", DEFAULT.serverMetUrl()),
                props.getProperty("adminBindAddress", DEFAULT.adminBindAddress()),
                props.getProperty("adminAllowedIps", DEFAULT.adminAllowedIps()),
                props.getProperty("adminAllowedMacs", DEFAULT.adminAllowedMacs())
        );
    }
}

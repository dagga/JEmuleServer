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
import org.jemule.config.ConfigFile;
import org.jemule.config.ConfigParameter;
import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.core.ServerThreads.Tier;
import org.jemule.protocol.Tag;
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.core.event.EventStatistics;
import org.jemule.kad.GlobalSearchForwarder;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadSearchBridge;
import org.jemule.security.AdminAccessFilter;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.jemule.security.IPFilter;
import org.jemule.security.IPFilterUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main server class that coordinates networking, security, and data management.
 * Uses Java 21 Virtual Threads to handle massive concurrency.
 */
public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private volatile ServerConfig config;
    private final org.jemule.core.HeapGuard heapGuard;
    private volatile ConfigFile configFile;
    private final ServerGossip serverGossip;
    private final ServerSearchBridge serverSearchBridge;
    /** Source searches on the verified eD2K servers, beside Kad, for the clients' OP_GETSOURCES. */
    private final ServerSourceSearcher serverSourceSearcher;
    private final ClientRegistry registry = new ClientRegistry();
    private final java.util.Set<Socket> clientSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final FileIndex fileIndex;
    private final org.jemule.core.MetricsHistory metricsHistory;
    private volatile KadSourceService kadSourceService;
    /** OFFERFILES v1 ceiling shared by all v1 connections, and its counters. */
    private final org.jemule.core.OfferFilesV1 offerFilesV1 = new org.jemule.core.OfferFilesV1();
    private final FloodProtector floodProtector;
    private final IPFilter ipFilter;
    private final IPFilterUpdater ipFilterUpdater;
    private final FakeFileDetector fakeFileDetector;
    private final ExecutorService executor;
    private final DatabaseManager db;
    private final byte[] serverHash = new byte[16];

    // Map of UDP sockets bound by startUdpResponder so other components can send via the same source port
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.net.DatagramSocket> boundUdpSockets = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Send a UDP packet using a previously-bound DatagramSocket for the given local port.
     * Returns true if the packet was sent, false if no bound socket was available or send failed.
     */
    public static boolean sendUdpFromBoundPort(int localPort, byte[] data, InetAddress destAddr, int destPort) {
        java.net.DatagramSocket ds = boundUdpSockets.get(localPort);
        if (ds == null) {
            log.debug("No bound UDP socket for local port {}", localPort);
            return false;
        }
        try {
            ds.send(new java.net.DatagramPacket(data, data.length, destAddr, destPort));
            return true;
        } catch (IOException e) {
            log.debug("Failed to send UDP from bound port {} to {}:{} - {}", localPort, destAddr, destPort, e.getMessage());
            return false;
        }
    }

    private final EventManager eventManager;
    private final EventStatistics eventStatistics = new EventStatistics();
    private final ClientFactory clientFactory;
    private final AdminInterface admin;
    private volatile AdminWebPanel adminWebPanel;
    private volatile AdminAccessFilter adminAccessFilter;
    private volatile int boundAdminWebPort;
    private volatile boolean stopAdminWeb;
    private volatile ServerSocket adminSocket;
    private final java.util.Set<ClientHandler> connections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingRestart = new java.util.concurrent.ConcurrentHashMap<>();
    private final ChildAbuseFilter abuseFilter;
    private final InetAddress publicIp;
    private final KadNode kadNode;
    private volatile KadSearchBridge kadSearchBridge;
    private final GlobalSearchForwarder globalSearchForwarder;
    /** Indexing and database writes, below the clients and the searches (ingestion tier). */
    private final IngestionExecutor ingestion = IngestionExecutor.create();
    /** Regular operations of the maintenance tier (server gossip polling). */
    private final java.util.concurrent.ScheduledExecutorService maintenance = ServerThreads.maintenanceScheduler("maintenance");
    private volatile boolean running = true;

    public byte[] getServerHash() {
        return serverHash;
    }

    public KadSearchBridge getKadSearchBridge() {
        return kadSearchBridge;
    }

    /** Kad source searches for the clients' OP_GETSOURCES; finds nothing while no Kad node runs. */
    public KadSourceService getKadSourceService() {
        KadSourceService service = kadSourceService;
        if (service == null) {
            synchronized (this) {
                if (kadSourceService == null) {
                    // Kad and the verified eD2K servers are asked at once; Kad's sources come first (they carry
                    // the user hash and crypt options)
                    kadSourceService = new KadSourceService((hash, size) -> {
                        KadSearchBridge bridge = kadSearchBridge;
                        java.util.concurrent.CompletableFuture<java.util.List<org.jemule.kad.KadPacket.KadSource>> kad =
                                bridge == null ? java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of())
                                        : bridge.searchSources(hash, size);
                        return kad.thenCombine(serverSourceSearcher.search(hash, size), (fromKad, fromServers) -> {
                            java.util.List<org.jemule.kad.KadPacket.KadSource> all = new java.util.ArrayList<>(fromKad);
                            all.addAll(fromServers);
                            return all;
                        });
                    }, registry, ipFilter::isBlocked, this::configSnapshot, System::currentTimeMillis, () -> {
                        KadSearchBridge bridge = kadSearchBridge;
                        return bridge == null ? 0 : bridge.getSourceRequestsRefused();
                    });
                }
                service = kadSourceService;
            }
        }
        return service;
    }

    public void setKadSearchBridge(KadSearchBridge kadSearchBridge) {
        this.kadSearchBridge = kadSearchBridge;
    }

    private static final long MB = 1024 * 1024;
    /** Heap for everything but the index: young generation, Kad, searches, client sessions, H2 cache. */
    private static final long BASE_HEAP_BYTES = 160 * MB;
    /** Heap per indexed file measured in production with 1.2.19: 252 MB for 706,165 files, banned hashes included. */
    private static final long HEAP_BYTES_PER_FILE = 360;

    /**
     * Warns when the maximum heap is too small for the indexed files. The JVM only takes a quarter of the RAM by
     * default: on a 1 GB server, that is about 240 MB, and an OutOfMemoryError makes H2 close the database.
     *
     * @return the warning, or null if the heap is large enough
     */
    static String heapWarning(long maxHeapBytes, long files) {
        long recommended = BASE_HEAP_BYTES + files * HEAP_BYTES_PER_FILE;
        if (maxHeapBytes >= recommended) return null;
        return String.format("Heap: %d MB at most for %d indexed files, about %d MB recommended. Start the JVM with "
                + "-XX:MaxRAMPercentage=75 (or -Xmx) to avoid an OutOfMemoryError, which closes the database",
                maxHeapBytes / MB, files, recommended / MB);
    }

    /**
     * Background Kad searches of the UDP global searches received from clients of other servers.
     */
    public GlobalSearchForwarder getGlobalSearchForwarder() {
        return globalSearchForwarder;
    }

    /**
     * Active bridge for keyword searches against verified server peers.
     */
    public ServerSearchBridge getServerSearchBridge() {
        return serverSearchBridge;
    }

    /**
     * Adds server endpoints explicitly reported by a connected client for normal gossip verification.
     */
    public int onClientReportedServerList(byte[] data) {
        return serverGossip.addClientReportedServerList(data);
    }

    public byte[] getClientServerList() {
        return serverGossip.clientServerList(publicIp, config.port());
    }

    /**
     * The embedded Kademlia node, or null when {@code kadEnabled=false}.
     */
    public KadNode getKadNode() {
        return kadNode;
    }

    /**
     * Called once a client has logged in: HighID clients bootstrap the Kad routing table.
     */
    public void onClientLoggedIn(ClientState client) {
        KadNode node = kadNode;
        if (node != null) {
            node.onClientConnected(client);
        }
    }

    /**
     * Initializes the server with configuration and core components.
     *
     * @param config        The server configuration.
     * @param clientFactory The factory for creating client states.
     */
    public Server(ServerConfig config, ClientFactory clientFactory) {
        this.config = config;
        this.clientFactory = clientFactory;
        this.eventManager = new EventManager();
        setupDefaultListeners();
        this.floodProtector = new FloodProtector(config.floodMaxRequestsPerSecond());
        this.ipFilter = new IPFilter();
        this.ipFilterUpdater = createIpFilterUpdater(config.ipFilterPath());
        this.serverGossip = new ServerGossip(config.serverPeers(), this.ipFilter);
        this.fakeFileDetector = new FakeFileDetector();
        this.fakeFileDetector.setEnabled(config.fakeFileDetectionEnabled());
        // TCP and UDP access of the clients; admin, searches, ingestion and maintenance have their own threads
        this.executor = ServerThreads.clientExecutor();

        DatabaseManager dbMgr = null;
        try {
            dbMgr = new DatabaseManager(
                    config.databasePath(),
                    config.cbFailureRateThreshold(),
                    config.cbMinimumNumberOfCalls(),
                    config.cbWaitDurationInSeconds()
            );
            this.fakeFileDetector.setDatabaseManager(dbMgr);
            // Bans apply even when the fake file heuristics are disabled
            List<DatabaseManager.BannedName> banned = dbMgr.loadBannedNames();
            banned.forEach(n -> this.fakeFileDetector.addBannedName(n.name()));
            log.info("Loaded {} banned file names from database", banned.size());
        } catch (SQLException e) {
            log.error("Failed to initialize database: {}", e.getMessage());
        }

        this.db = dbMgr;
        this.abuseFilter = ChildAbuseFilter.load(config.childAbuseKeywordsPath());
        this.fileIndex = new FileIndex(db, eventManager, abuseFilter);
        this.fileIndex.setPersistenceExecutor(ingestion);
        // Every channel (clients, Kad, other servers) stops bringing new files at maxFiles or when the heap is full
        this.heapGuard = org.jemule.core.HeapGuard.jvm();
        this.fileIndex.setCapacity(() -> config.maxFiles(), heapGuard::full);
        this.metricsHistory = new org.jemule.core.MetricsHistory(db);
        String heapWarning = heapWarning(Runtime.getRuntime().maxMemory(), fileIndex.fileCount());
        if (heapWarning != null) {
            log.warn(heapWarning);
        } else {
            log.info("Heap: {} MB at most for {} indexed files", Runtime.getRuntime().maxMemory() / MB, fileIndex.fileCount());
        }
        this.globalSearchForwarder = new GlobalSearchForwarder(() -> kadSearchBridge, abuseFilter,
                config.kad().enabled() && config.kad().forkSearchEnabled() && config.kad().forkGlobalSearches());
        this.serverSourceSearcher = new ServerSourceSearcher(serverGossip::verifiedPeers,
                () -> boundUdpSockets.get(config.port() + 4));
        this.serverSearchBridge = new ServerSearchBridge(serverGossip::verifiedPeers,
                () -> boundUdpSockets.get(config.port() + 4), fileIndex, fakeFileDetector);
        this.serverSearchBridge.setIngestionExecutor(ingestion);
        this.kadNode = config.kad().enabled()
                ? new KadNode(config.kad(), config.port(), db, fileIndex, abuseFilter, fakeFileDetector,
                        kadNodesCachePath(config))
                : null;
        if (kadNode != null) kadNode.setIngestionExecutor(ingestion);
        this.adminAccessFilter = new AdminAccessFilter(config.adminAllowedIps(), config.adminAllowedMacs());
        this.admin = new AdminInterface(this, registry, fileIndex, ipFilter, fakeFileDetector);
        this.adminWebPanel = new AdminWebPanel(this);
        this.publicIp = resolvePublicIp(config.publicIp());

        // Generate a stable server hash based on public IP and port
        byte[] ipPort = (publicIp.getHostAddress() + ":" + config.port()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] md5;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            md5 = digest.digest(ipPort);
        } catch (java.security.NoSuchAlgorithmException e) {
            md5 = new byte[16];
        }
        System.arraycopy(md5, 0, serverHash, 0, 16);
    }

    /** Cache of the downloaded Kad nodes.dat list, kept next to the H2 database when its path is a real file. */
    private static java.nio.file.Path kadNodesCachePath(ServerConfig config) {
        String path = config.databasePath();
        if (path == null || path.isBlank() || path.trim().startsWith("mem:")) return null;
        try {
            return java.nio.file.Path.of(path + ".nodes.dat");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private IPFilterUpdater createIpFilterUpdater(String filterPath) {
        if (filterPath == null || filterPath.isBlank()) return null;
        try {
            // Started once the local filter is loaded (startIpFilterLoading): an update is checked against it
            return new IPFilterUpdater(ipFilter, Path.of(filterPath), IPFilterUpdater.bundledSources());
        } catch (IOException | IllegalArgumentException e) {
            log.error("Could not start IP filter updates for {}: {}", filterPath, e.getMessage());
            return null;
        }
    }

    /**
     * The IPv4 address announced to the clients (OP_SERVERIDENT, server lists): ed2k carries only 4-byte addresses,
     * so an IPv6 address is never returned.
     */
    static InetAddress resolvePublicIp(String configIp) {
        if (configIp != null && !configIp.isBlank()) {
            try {
                InetAddress configured = InetAddress.getByName(configIp);
                if (configured instanceof java.net.Inet4Address) return configured;
                log.warn("publicIp {} is not an IPv4 address (ed2k announces IPv4 only), falling back to auto-detect", configIp);
            } catch (Exception e) {
                log.warn("Invalid publicIp in config ({}), falling back to auto-detect", configIp);
            }
        }
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (var iface : Collections.list(interfaces)) {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    var addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            log.info("Auto-detected public IPv4: {} (interface: {})", a.getHostAddress(), iface.getName());
                            return a;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-detect public IP: {}", e.getMessage());
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local instanceof java.net.Inet4Address && !local.isLoopbackAddress()) {
                log.info("Using InetAddress.getLocalHost(): {}", local.getHostAddress());
                return local;
            }
        } catch (Exception ignored) {
        }
        log.warn("Could not resolve public IP, using 127.0.0.1 as fallback");
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e); // never thrown for a 4-byte address
        }
    }

    public InetAddress getPublicIp() {
        return publicIp;
    }

    public EventStatistics getEventStatistics() {
        return eventStatistics;
    }

    ClientRegistry getClientRegistry() {
        return registry;
    }

    IPFilter getIPFilter() {
        return ipFilter;
    }

    public AdminAccessFilter getAdminAccessFilter() {
        return adminAccessFilter;
    }

    FakeFileDetector getFakeFileDetector() {
        return fakeFileDetector;
    }

    DatabaseManager getDatabaseManager() {
        return db;
    }

    ServerConfig getConfig() {
        return config;
    }

    /** The configuration currently in effect, for the admin interfaces of other packages. */
    /** Counters of the source searches on the other eD2K servers. */
    public java.util.Map<String, Object> serverSourceStats() {
        return serverSourceSearcher.stats();
    }

    /** Tells when the heap cannot take more indexed files. */
    public org.jemule.core.HeapGuard getHeapGuard() {
        return heapGuard;
    }

    public ServerConfig configSnapshot() {
        return config;
    }

    /** The file the configuration was loaded from and is written back to, or null when the server has none. */
    public ConfigFile getConfigFile() {
        return configFile;
    }

    /** Sets the configuration file used by {@code reloadConfig()} and by the admin edits. */
    public void setConfigFile(ConfigFile configFile) {
        this.configFile = configFile;
    }

    AdminWebPanel getAdminWebPanel() {
        return adminWebPanel;
    }

    List<ServerGossip.VerifiedPeer> getVerifiedServerPeers() {
        return List.copyOf(serverGossip.verifiedPeers());
    }

    List<ServerGossip.ServerView> getServerViews() {
        return serverGossip.serverViews();
    }

    int getAdminWebPort() {
        AdminWebPanel panel = adminWebPanel;
        return panel == null ? 0 : panel.port();
    }

    /** The port the web admin panel is currently listening on (0 when it is disabled). */
    public int adminWebPort() {
        return getAdminWebPort();
    }

    /**
     * Applies new configuration values while the server keeps running and writes them to the configuration file.
     * Values whose scope is {@link ConfigParameter.Scope#RESTART} are written but the running server keeps its current
     * value until the next start; they are listed by {@link #pendingRestart()}. An invalid value is reported as an
     * error and leaves both the configuration and the file unchanged.
     *
     * @param values raw values indexed by their {@code server.properties} key
     */
    public synchronized ConfigApplyResult updateConfig(Map<String, String> values) {
        ConfigApplyResult.Builder result = ConfigApplyResult.builder();
        Map<String, String> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            ConfigParameter parameter = ConfigParameter.byKey(entry.getKey());
            if (parameter == null) {
                result.error("Unknown configuration key: " + entry.getKey());
                continue;
            }
            try {
                parameter.validate(entry.getValue());
                normalized.put(parameter.key(), parameter.normalize(entry.getValue()));
            } catch (IllegalArgumentException e) {
                result.error(e.getMessage());
            }
        }
        if (!result.build().success()) {
            return result.build();
        }

        Properties target = new Properties();
        config.toProperties().forEach(target::setProperty);
        normalized.forEach(target::setProperty);
        ServerConfig requested;
        try {
            requested = resolvePaths(ServerConfig.fromProperties(target));
        } catch (RuntimeException e) {
            return result.error("Invalid configuration: " + e.getMessage()).build();
        }

        ConfigFile file = configFile;
        if (file != null) {
            try {
                file.update(normalized);
            } catch (IOException e) {
                log.error("Could not write the configuration to {}: {}", file.path(), e.getMessage());
                return result.error("Could not write " + file.path() + ": " + e.getMessage()).build();
            }
        }
        apply(requested, normalized.keySet(), result);
        log.info("Configuration updated: {}", result.build().summary());
        return result.build();
    }

    /**
     * Applies the configuration file as it is now on disk, with the same scopes as {@link #updateConfig(Map)}. A
     * file holding an invalid value is rejected as a whole.
     */
    public synchronized ConfigApplyResult reloadConfig() {
        ConfigFile file = configFile;
        if (file == null) {
            return ConfigApplyResult.builder().error("No configuration file is associated with this server").build();
        }
        if (!file.exists()) {
            return ConfigApplyResult.builder().error("Configuration file not found: " + file.path()).build();
        }
        ConfigApplyResult.Builder result = ConfigApplyResult.builder();
        Properties loaded;
        try {
            loaded = file.load();
        } catch (IOException e) {
            return result.error("Could not reload " + file.path() + ": " + e.getMessage()).build();
        }
        for (ConfigParameter parameter : ConfigParameter.ordered()) {
            String value = loaded.getProperty(parameter.key());
            if (value == null) continue;
            try {
                parameter.validate(value);
            } catch (IllegalArgumentException e) {
                result.error(e.getMessage());
            }
        }
        if (!result.build().success()) {
            return result.build();
        }
        ServerConfig requested;
        try {
            requested = resolvePaths(ServerConfig.fromProperties(loaded));
        } catch (RuntimeException e) {
            return result.error("Could not reload " + file.path() + ": " + e.getMessage()).build();
        }
        apply(requested, requested.toProperties().keySet(), result);
        log.info("Configuration reloaded from {}: {}", file.path(),
                result.build().summary().isEmpty() ? "no change" : result.build().summary());
        return result.build();
    }

    /** Values written to the configuration file that the running server will only use after a restart. */
    public Map<String, String> pendingRestart() {
        return Map.copyOf(pendingRestart);
    }

    private ServerConfig resolvePaths(ServerConfig requested) {
        ConfigFile file = configFile;
        return file == null ? requested : file.resolve(requested);
    }

    /**
     * Makes the hot and admin parameters of {@code requested} the running configuration and records its other
     * changed values as pending until the next restart.
     */
    private void apply(ServerConfig requested, java.util.Set<String> keys, ConfigApplyResult.Builder result) {
        ServerConfig previous = config;
        Map<String, String> running = previous.toProperties();
        Map<String, String> wanted = requested.toProperties();
        Properties effective = new Properties();
        running.forEach(effective::setProperty);
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            ConfigParameter parameter = ConfigParameter.byKey(key);
            if (parameter == null) continue;
            String value = wanted.get(parameter.key());
            boolean same = parameter.sameValue(running.get(parameter.key()), value);
            if (parameter.scope() == ConfigParameter.Scope.RESTART) {
                if (same) {
                    pendingRestart.remove(parameter.key());
                } else {
                    pendingRestart.put(parameter.key(), value);
                    result.deferred(parameter.key());
                }
            } else if (!same) {
                effective.setProperty(parameter.key(), value);
                changed.add(parameter.key());
            }
        }
        if (changed.isEmpty()) return;
        ServerConfig updated = ServerConfig.fromProperties(effective);
        this.config = updated;

        if (changed.contains("floodMaxRequestsPerSecond")) {
            floodProtector.setMaxPerSec(updated.floodMaxRequestsPerSecond());
        }
        if (changed.contains("fakeFileDetectionEnabled")) {
            fakeFileDetector.setEnabled(updated.fakeFileDetectionEnabled());
        }
        if (changed.contains("ipFilterPath")) {
            reloadIpFilter(updated.ipFilterPath());
        }
        if (changed.contains("childAbuseKeywordsPath")) {
            reloadChildAbuseKeywords(updated.childAbuseKeywordsPath());
        }
        if (changed.stream().anyMatch(key -> ConfigParameter.byKey(key).scope() == ConfigParameter.Scope.ADMIN)) {
            rebuildAdminAccess(previous, updated);
        }
        changed.forEach(result::applied);
    }

    /**
     * Rebuilds the admin access filter and restarts the web panel when its port or bind address changed. The panel is
     * always stopped when {@code adminWebPort} becomes 0.
     */
    private void rebuildAdminAccess(ServerConfig previous, ServerConfig updated) {
        this.adminAccessFilter = new AdminAccessFilter(updated.adminAllowedIps(), updated.adminAllowedMacs());
        boolean bindChanged = !java.util.Objects.equals(previous.adminBindAddress(), updated.adminBindAddress());
        if (previous.adminPort() != updated.adminPort() || bindChanged) {
            restartAdminConsole(updated.adminPort());
        }
        AdminWebPanel panel = adminWebPanel;
        boolean portChanged = previous.adminWebPort() != updated.adminWebPort();
        if (updated.adminWebPort() == 0) {
            if (panel != null) {
                adminWebPanel = null;
                boundAdminWebPort = 0;
                afterAdminResponse(() -> {
                    panel.close();
                    log.info("Web admin panel disabled");
                });
            }
            return;
        }
        if (panel == null) {
            adminWebPanel = new AdminWebPanel(this);
            boundAdminWebPort = 0;
            startWebPanel(adminWebPanel, updated.adminWebPort());
            return;
        }
        if (portChanged || bindChanged) {
            afterAdminResponse(() -> {
                panel.stop();
                startWebPanel(panel, updated.adminWebPort());
            });
        }
    }

    /**
     * Runs a web panel restart shortly after the current request: the change may come from the panel itself, whose
     * answer must be sent before its listener is stopped.
     */
    private static void afterAdminResponse(Runnable action) {
        ServerThreads.start(Tier.ADMIN, "AdminWebRestart", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            action.run();
        });
    }

    /** Closes the TCP admin console and starts a new one when its port is not 0. */
    private void restartAdminConsole(int newPort) {
        ServerSocket socket = adminSocket;
        adminSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("Error closing the admin console: {}", e.getMessage());
            }
        }
        if (newPort > 0) {
            ServerThreads.start(Tier.ADMIN, "AdminPort", this::serveAdminPort);
        }
    }

    private void startWebPanel(AdminWebPanel panel, int port) {
        stopAdminWeb = false;
        ServerThreads.start(Tier.ADMIN, "AdminWebSupervisor", () -> {
            while (running && !stopAdminWeb) {
                try {
                    panel.start(port);
                    boundAdminWebPort = panel.port();
                    return;
                } catch (IOException e) {
                    log.error("Admin web panel port {} unavailable (retrying in 5s): {}", port, e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    /** Reloads the IP filter from the given path; an empty path disables the filter and its updates. */
    public void reloadIpFilter(String path) {
        if (path == null || path.isBlank()) {
            ipFilter.clear();
            log.info("IP filter disabled (empty ipFilterPath)");
            return;
        }
        ServerThreads.start(Tier.MAINTENANCE, "IpFilterReload", () -> {
            try {
                ipFilter.loadFromFile(path);
            } catch (RuntimeException e) {
                log.error("Could not reload IP filter from {}: {}", path, e.getMessage());
            } catch (Exception e) {
                log.error("Could not reload IP filter from {}: {}", path, e.getMessage());
            }
        });
    }

    /** Disconnects one client by its numeric client ID, or by nickname when it does not match an ID. */
    public boolean kickClient(String identifier) {
        if (identifier == null || identifier.isBlank()) return false;
        String wanted = identifier.trim();
        long id = -1;
        try {
            id = Long.parseUnsignedLong(wanted);
        } catch (NumberFormatException ignored) {
        }
        boolean kicked = false;
        for (ClientState client : registry.getAllClients()) {
            boolean match = id >= 0 ? client.clientId() == id : wanted.equalsIgnoreCase(client.nickname());
            if (match && disconnectClient(client)) {
                kicked = true;
            }
        }
        return kicked;
    }

    /** Disconnects every client whose remote address matches the given IP address. */
    public int kickIp(String ip) {
        if (ip == null || ip.isBlank()) return 0;
        int kicked = 0;
        for (ClientState client : registry.getAllClients()) {
            if (client.address().getHostAddress().equals(ip.trim()) && disconnectClient(client)) {
                kicked++;
            }
        }
        return kicked;
    }

    /** Closes the connection of the client; false when no open connection belongs to it. */
    private boolean disconnectClient(ClientState client) {
        boolean found = false;
        for (ClientHandler handler : connections) {
            if (handler.matches(client)) {
                handler.disconnectNow();
                found = true;
            }
        }
        if (found) {
            log.info("Admin kick: client {} ({}) disconnected", client.nickname(),
                    Long.toUnsignedString(client.clientId()));
        }
        return found;
    }

    void registerConnection(ClientHandler handler) {
        connections.add(handler);
    }

    void unregisterConnection(ClientHandler handler) {
        connections.remove(handler);
    }

    /** Reloads the child-abuse keyword list (the embedded one for an empty path). Only new publications are checked. */
    public void reloadChildAbuseKeywords(String path) {
        abuseFilter.reload(path);
        log.info("Child abuse keywords reloaded from {}", path == null || path.isBlank() ? "the embedded list" : path);
    }

    private void setupDefaultListeners() {
        eventManager.subscribeAll(eventStatistics);
        eventManager.subscribeAll(event -> {
            if (event instanceof ClientEvent ce) {
                log.info("[CLIENT] {} - {} ({})", ce.getType(), ce.getUsername(), ce.getClientIp());
            } else if (event instanceof FileEvent fe) {
                log.info("[FILE] {} - {} ({})", fe.getType(), fe.getFileName(), fe.getFileHash());
            } else {
                log.info("[EVENT] {}: {}", event.getType(), event.getMessage());
            }
        });
    }

    /**
     * Starts the TCP server loop and the UDP responder.
     *
     * @throws IOException If the server cannot bind to the configured port.
     */
    public void start() throws IOException {
        if (ServerThreads.prioritiesApplied()) {
            boolean linuxUser = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")
                    && !"root".equals(System.getProperty("user.name"));
            log.info("Thread priorities applied: admin > clients > searches > ingestion > maintenance{}", linuxUser
                    ? " (the admin tier stays at the clients' level unless the process has CAP_SYS_NICE)" : "");
        } else {
            log.warn("Thread priorities are ignored by Linux: start the JVM with -XX:ThreadPriorityPolicy=1 so that the "
                    + "admin interfaces and the clients come before searches, ingestion and maintenance");
        }
        // Bind server socket preferring IPv6 wildcard, fall back to IPv4 if not available
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), config.port()));
            ss.setSoTimeout(1000);
            log.info("JEmuleServer listening on port {} (bound to IPv6 wildcard)", config.port());
        } catch (IOException e) {
            // Fallback to IPv4 wildcard
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
            ss = new ServerSocket(config.port());
            ss.setSoTimeout(1000);
            log.info("JEmuleServer listening on port {} (bound to IPv4 wildcard)", config.port());
        }

        try {
            log.info("Virtual Threads ready for 50k+ concurrent clients");

            startIpFilterLoading();
            startServerMetLoading();
            // Curves of the admin panel: one sample now, then every 5 minutes (maintenance tier)
            maintenance.scheduleAtFixedRate(this::recordMetrics, 0, org.jemule.core.MetricsHistory.INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            // The startup load is over (FileIndex reads the database in its constructor): the database file is now
            // compacted while the server runs, or the writes make it grow by about a gigabyte a day (maintenance tier)
            if (db != null) {
                maintenance.scheduleWithFixedDelay(db::compactOnline, 10, 10, TimeUnit.MINUTES);
            }
            startUdpResponder();
            startKadNode();
            startAdminInterface();

            while (running && !ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    if (!running) {
                        c.close();
                        break;
                    }
                    c.setTcpNoDelay(true);
                    clientSockets.add(c);
                    executor.submit(() -> {
                        try {
                            new ClientHandler(c, config, registry, fileIndex, floodProtector, fakeFileDetector,
                                    eventManager, clientFactory, this).run();
                        } finally {
                            clientSockets.remove(c);
                        }
                    });
                } catch (java.net.SocketTimeoutException e) {
                    // Just a timeout, go back to loop start and check 'running'
                } catch (IOException e) {
                    if (running) log.error("Accept error: {}", e.getMessage());
                }
            }
        } finally {
            if (ss != null && !ss.isClosed()) try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void startIpFilterLoading() {
        if (config.ipFilterPath() == null || config.ipFilterPath().isBlank()) return;
        ServerThreads.start(Tier.MAINTENANCE, "IpFilterLoad", () -> {
            try {
                ipFilter.loadFromFile(config.ipFilterPath());
            } catch (Exception e) {
                log.error("Could not load IP filter from {}: {}", config.ipFilterPath(), e.getMessage());
            }
            if (ipFilterUpdater != null) ipFilterUpdater.start();
        });
    }

    private void startServerMetLoading() {
        if (config.serverMetUrl().isBlank()) return;
        ServerThreads.start(Tier.MAINTENANCE, "ServerMetLoad", () -> {
            try {
                int added = ServerMetLoader.load(config.serverMetUrl(), serverGossip);
                log.info("Loaded {} new server candidates from server.met; existing candidates were retained", added);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while loading server.met from {}", config.serverMetUrl());
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Could not load server.met from {}: {}", config.serverMetUrl(), e.getMessage());
            }
        });
    }

    private void startUdpResponder() {
        // Bind UDP responder on both the configured TCP port and the common UDP offset (port+4)
        java.util.Set<Integer> ports = new java.util.LinkedHashSet<>();
        ports.add(config.port());
        if (config.port() <= 0xFFFF - 4) ports.add(config.port() + 4);
        if (config.port() <= 0xFFFF - 12) ports.add(config.port() + 12);

        // Server gossip polling is a regular operation: maintenance tier, through the port + 4 socket
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                java.net.DatagramSocket gossipSocket = boundUdpSockets.get(config.port() + 4);
                if (running && gossipSocket != null) serverGossip.poll(gossipSocket, System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("Server gossip polling failed: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);

        for (int port : ports) {
            final int bindPort = port;
            executor.submit(() -> {
                while (running) {
                    java.net.DatagramSocket ds = null;
                    try {
                        // Prefer IPv6 wildcard; if unavailable, fallback to IPv4
                        try {
                            ds = new java.net.DatagramSocket(null);
                            ds.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), bindPort));
                            log.info("UDP Responder listening on port {} (IPv6 wildcard)", bindPort);
                        } catch (IOException e) {
                            if (ds != null && !ds.isClosed()) {
                                ds.close();
                            }
                            ds = new java.net.DatagramSocket(bindPort);
                            log.info("UDP Responder listening on port {} (IPv4 wildcard)", bindPort);
                        }

                        // Register bound socket so other threads can send via the same source port
                        boundUdpSockets.put(bindPort, ds);

                        ds.setSoTimeout(1000);
                        byte[] buf = new byte[4096];
                        while (running) {
                            try {
                                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                                ds.receive(p);
                                handleUdp(ds, p);
                            } catch (java.net.SocketTimeoutException e) {
                                // Timeout, check 'running'
                            }
                        }
                    } catch (IOException e) {
                        if (running) {
                            log.error("UDP Error on port {} (retrying in 5s): {}", bindPort, e.getMessage());
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } finally {
                        if (ds != null) {
                            boundUdpSockets.remove(bindPort, ds);
                            if (!ds.isClosed()) ds.close();
                        }
                    }
                }
            });
        }
    }

    /**
     * Starts the embedded Kademlia node asynchronously. The server keeps working on its local index if the Kad UDP port
     * cannot be bound.
     */
    private void startKadNode() {
        if (kadNode == null) {
            log.info("Kademlia node disabled (kadEnabled=false)");
            return;
        }
        ServerThreads.start(Tier.MAINTENANCE, "KadStart", () -> {
            try {
                kadNode.start();
                if (kadNode.getConfig().forkSearchEnabled() && kadSearchBridge == null) {
                    kadSearchBridge = kadNode.getSearchBridge();
                }
            } catch (IOException e) {
                log.error("Kad UDP port {} unavailable, Kademlia disabled: {}", kadNode.getUdpPort(), e.getMessage());
            }
        });
    }

    private void startAdminInterface() {
        ServerThreads.start(Tier.ADMIN, "AdminInterface", admin);
        if (config.adminPort() > 0) {
            ServerThreads.start(Tier.ADMIN, "AdminPort", this::serveAdminPort);
        }
        if (config.adminWebPort() > 0) {
            startWebPanel(adminWebPanel, config.adminWebPort());
        }
    }

    /**
     * Admin console over TCP, for a server running as a service without a terminal ("nc &lt;host&gt; &lt;adminPort&gt;").
     * Filtered at application level by {@link AdminAccessFilter}.
     */
    private void serveAdminPort() {
        ServerConfig current = config;
        int port = current.adminPort();
        if (port <= 0) return;
        InetAddress bindAddr;
        try {
            bindAddr = InetAddress.getByName(current.adminBindAddress());
        } catch (IOException e) {
            log.error("Invalid adminBindAddress {}: {}", current.adminBindAddress(), e.getMessage());
            bindAddr = InetAddress.getLoopbackAddress();
        }
        ServerSocket socket;
        try {
            socket = new ServerSocket(port, 5, bindAddr);
        } catch (IOException e) {
            log.error("Admin port {} unavailable: {}", port, e.getMessage());
            return;
        }
        adminSocket = socket;
        try (socket) {
            log.info("Admin console listening on {}:{}", socket.getInetAddress().getHostAddress(), port);
            while (running && adminSocket == socket) {
                Socket s = socket.accept();
                if (!adminAccessFilter.isAllowed(s.getInetAddress())) {
                    log.warn("Admin connection refused from {}: unauthorized IP or MAC", s.getRemoteSocketAddress());
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                ServerThreads.start(Tier.ADMIN, "AdminSession", () -> {
                    try (s; var out = new java.io.PrintStream(s.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8)) {
                        log.info("Admin session opened from {}", s.getRemoteSocketAddress());
                        new AdminInterface(admin, out).session(new java.io.BufferedReader(
                                new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)), true);
                    } catch (IOException e) {
                        log.debug("Admin session closed: {}", e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            if (running && adminSocket == socket) {
                log.error("Admin port {} failed: {}", port, e.getMessage());
            }
        } finally {
            if (adminSocket == socket) adminSocket = null;
        }
    }

    /**
     * Destination of the answers to one UDP request; answers are obfuscated when {@code key != 0}.
     */
    private record UdpReply(java.net.DatagramSocket socket, InetAddress address, int port, int key,
                            boolean serverObfuscated) {
        private UdpReply(java.net.DatagramSocket socket, InetAddress address, int port, int key) {
            this(socket, address, port, key, false);
        }
    }

    private void handleUdp(java.net.DatagramSocket ds, java.net.DatagramPacket p) throws IOException {
        if (p.getLength() < 2) return;
        byte[] recv = java.util.Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
        if (log.isDebugEnabled()) {
            log.debug("UDP recv {} bytes from {}:{} - {}", recv.length, p.getAddress(), p.getPort(), hex(recv, recv.length));
        }

        int replyKey = 0;
        boolean serverObfuscated = false;
        if (recv[0] != Packet.PROTOCOL_ED2K) {
            // Obfuscated packet (CEncryptedDatagramSocket::EncryptSendServer), keyed with this client's server key
            int serverKey = org.jemule.security.UdpObfuscation.serverKeyFor(p.getAddress());
            byte[] plain = org.jemule.security.UdpObfuscation.decryptFromClient(recv, serverKey);
            if (plain != null && plain.length >= 2 && plain[0] == Packet.PROTOCOL_ED2K) {
                recv = plain;
                replyKey = serverKey;
            } else {
                byte[] serverPlain = org.jemule.security.ServerObfuscation.decode(
                        recv, org.jemule.security.UdpObfuscation.serverKeyFor(p.getAddress()));
                if (serverPlain != null && serverPlain.length >= 2 && serverPlain[0] == Packet.PROTOCOL_ED2K) {
                    recv = serverPlain;
                    serverObfuscated = true;
                } else if (recv.length >= 4 && recv.length < 4 + 16 && ds.getLocalPort() == config.port() + 12) {
                    // "Crypt ping" (CServerList::ServerStats): raw <challenge 4><random 0-15> sent to port+12;
                    // the OP_GLOBSERVSTATRES answer is obfuscated with the challenge as base key.
                    int challenge = ByteBuffer.wrap(recv, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    UdpReply reply = new UdpReply(ds, p.getAddress(), p.getPort(), challenge);
                    sendUdp(reply, buildGlobServStatRes(challenge, p.getAddress()), "STAT (crypt ping)");
                    return;
                } else {
                    log.debug("Dropping undecodable UDP packet ({} bytes) from {}", recv.length, p.getAddress());
                    return;
                }
            }
        }
        dispatchUdp(new UdpReply(ds, p.getAddress(), p.getPort(), replyKey, serverObfuscated), recv);
    }

    private void dispatchUdp(UdpReply reply, byte[] recv) throws IOException {
        byte opcode = recv[1];
        if (opcode == (byte) 0x96) { // OP_GLOBSERVSTATREQ <challenge 4>
            if (recv.length < 6) return;
            int challenge = ByteBuffer.wrap(recv, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            sendUdp(reply, buildGlobServStatRes(challenge, reply.address()), "STAT");
        } else if (opcode == (byte) 0xA2) { // OP_SERVER_DESC_REQ [<challenge 4>]
            int challenge = recv.length >= 6 ? ByteBuffer.wrap(recv, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
            sendUdp(reply, buildServerDescPacket(challenge, reply.address()), "DESC");
        } else if (serverGossip.handle(reply.socket(), reply.address(), reply.port(), recv,
                reply.serverObfuscated(), publicIp, config.port(), System.currentTimeMillis())) {
            return;
        } else if (serverSearchBridge.handle(reply.address(), reply.port(), recv)) {
            return;
        } else if (serverSourceSearcher.handle(reply.address(), reply.port(), recv)) {
            return;
        } else if (opcode == (byte) 0x98 || opcode == (byte) 0x92 || opcode == (byte) 0x90) {
            handleGlobalSearch(reply, opcode, recv);
        } else if (opcode == (byte) 0x9A) { // OP_GLOBGETSOURCES: <hash 16>[<hash 16>...] (up to 35 with SRV_UDPFLG_EXT_GETSOURCES)
            int hashCount = (recv.length - 2) / 16;
            if (hashCount < 1) return;
            log.debug("UDP Source Request from {} for {} file(s)", reply.address(), hashCount);

            // The client accepts several <hash><count>(<ID><port>)* blocks in one datagram, each further block
            // being preceded by its own <0xE3><0x9B> header (CUDPSocket::ProcessPacket, OP_GLOBFOUNDSOURCES).
            java.io.ByteArrayOutputStream datagram = new java.io.ByteArrayOutputStream();
            for (int h = 0; h < hashCount; h++) {
                byte[] hashBytes = java.util.Arrays.copyOfRange(recv, 2 + h * 16, 2 + (h + 1) * 16);
                String hash = org.jemule.network.handler.HandlerUtils.bytesToHashString(hashBytes);

                var sources = org.jemule.network.handler.SourceHandler.reachableSources(
                        fileIndex.getSources(hash, null, config.maxSourcesPerFile()));
                if (sources.isEmpty()) continue;

                // ed2k IDs are IPv4 addresses (or LowIDs): an IPv6-only client (ID 0) is never handed out.
                byte[] block = org.jemule.network.handler.SourceHandler.encodeFoundSources(hashBytes, sources);
                if (datagram.size() > 0 && datagram.size() + 2 + block.length > MAX_UDP_PAYLOAD) {
                    sendUdp(reply, datagram.toByteArray(), "SRCS");
                    datagram.reset();
                }
                datagram.write(Packet.PROTOCOL_ED2K);
                datagram.write(0x9B); // OP_GLOBFOUNDSOURCES
                datagram.writeBytes(block);
            }
            if (datagram.size() > 0) {
                sendUdp(reply, datagram.toByteArray(), "SRCS");
            }
        }
    }

    /**
     * OP_GLOBSERVSTATRES as read by CUDPSocket::ProcessPacket:
     * <challenge 4><users 4><files 4><maxusers 4><softfiles 4><hardfiles 4><UDP flags 4><lowid users 4>
     * <UDP obfuscation port 2><TCP obfuscation port 2><server UDP key 4>. The key is specific to the client's IP.
     * Obfuscated UDP is received on port+12 (where eMule also sends its crypt pings); obfuscated TCP connections are
     * detected on the main port.
     */
    private byte[] buildGlobServStatRes(int challenge, InetAddress client) {
        ByteBuffer resp = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN);
        resp.put(Packet.PROTOCOL_ED2K);
        resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
        resp.putInt(challenge);
        resp.putInt(registry.size());
        resp.putInt(fileIndex.fileCount());
        resp.putInt(config.maxUsers());
        resp.putInt(config.maxFilesPerUser()); // SoftFiles: distinct files indexed per client
        resp.putInt(org.jemule.network.handler.PublishHandler.HARD_FILES); // HardFiles: an OP_OFFERFILES must declare fewer
        resp.putInt(Tag.SERVER_UDP_FLAGS);
        resp.putInt(registry.lowIdCount());
        resp.putShort((short) udpObfuscationPort()); // CServer::SetObfuscationPortUDP
        resp.putShort((short) config.port());        // CServer::SetObfuscationPortTCP
        resp.putInt(org.jemule.security.UdpObfuscation.serverKeyFor(client)); // server UDP key for this client
        return resp.array();
    }

    /**
     * Port receiving obfuscated UDP packets (also bound for eMule's crypt pings, sent to TCP port + 12).
     */
    private int udpObfuscationPort() {
        return config.port() + 12;
    }

    /**
     * eMule keeps at most MAX_RESULTS (100) results of a global search per server.
     */
    private static final int MAX_GLOBAL_SEARCH_RESULTS = 100;

    /**
     * Global (UDP) search sent by CSearchResultsWnd to every server of the client's list:
     * OP_GLOBSEARCHREQ (0x98) and OP_GLOBSEARCHREQ2 (0x92) carry the search tree, OP_GLOBSEARCHREQ3 (0x90)
     * prefixes it with a tag list whose CT_SERVER_UDPSEARCH_FLAGS (0x0E) announces large file support.
     * Each result is sent as an OP_GLOBSEARCHRES (0x99) block; several blocks, each with its own E3/99 header,
     * may share one datagram (CUDPSocket::ProcessPacket).
     */
    private void handleGlobalSearch(UdpReply reply, byte opcode, byte[] recv) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(recv, 2, recv.length - 2).slice().order(ByteOrder.LITTLE_ENDIAN);
        boolean largeFiles = false;
        List<FileMetadata> results;
        SearchQuery query;
        try {
            if (opcode == (byte) 0x90) {
                for (Tag t : Tag.readList(buf)) {
                    if (t.name().equals("\u000E") && t.isInt() && (t.unsignedValue() & 0x01) != 0) { // SRVCAP_UDP_NEWTAGS_LARGEFILES
                        largeFiles = true;
                    }
                }
            }
            query = SearchQuery.parse(buf.slice());
            results = fileIndex.searchComplex(query, Math.min(config.maxSearchResults(), MAX_GLOBAL_SEARCH_RESULTS));
        } catch (RuntimeException e) {
            log.debug("Malformed UDP search request from {}: {}", reply.address(), e.getMessage());
            return;
        }

        java.io.ByteArrayOutputStream datagram = new java.io.ByteArrayOutputStream();
        for (FileMetadata m : results) {
            if (!largeFiles && m.size() > 0xFFFFFFFFL) continue; // the requester cannot handle files > 4 GB
            byte[] item = org.jemule.network.handler.SearchHandler.encodeSearchResult(m);
            if (datagram.size() > 0 && datagram.size() + 2 + item.length > MAX_UDP_PAYLOAD) {
                sendUdp(reply, datagram.toByteArray(), "SEARCH");
                datagram.reset();
            }
            datagram.write(Packet.PROTOCOL_ED2K);
            datagram.write(0x99); // OP_GLOBSEARCHRES
            datagram.writeBytes(item);
        }
        if (datagram.size() > 0) {
            sendUdp(reply, datagram.toByteArray(), "SEARCH");
        }

        // Only searched on Kad: forwarding it to other eD2K servers could make it loop between servers
        GlobalSearchForwarder.Outcome outcome = globalSearchForwarder.forward(query, reply.address());
        log.info("UDP global search from {}: {} ({} local results, Kad: {})", reply.address().getHostAddress(),
                org.jemule.network.handler.HandlerUtils.sanitize(query.toString()), results.size(),
                outcome.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }

    /**
     * Keeps answers below the usual path MTU (the client reads up to 5000 bytes per datagram).
     */
    private static final int MAX_UDP_PAYLOAD = 1300;

    private void sendUdp(UdpReply reply, byte[] plain, String what) throws IOException {
        byte[] out;
        if (reply.serverObfuscated()) {
            int peerKey = serverGossip.keyFor(reply.address(), reply.port());
            out = peerKey != 0 ? org.jemule.security.ServerObfuscation.encode(plain, peerKey) : plain;
        } else {
            out = reply.key() != 0 ? org.jemule.security.UdpObfuscation.encryptForClient(plain, reply.key()) : plain;
        }
        if (log.isDebugEnabled()) {
            log.debug("UDP send ({}{}) {} bytes to {}:{} - {}", what,
                    reply.key() != 0 || reply.serverObfuscated() ? ", obfuscated" : "",
                    out.length, reply.address(), reply.port(), hex(plain, plain.length));
        }
        reply.socket().send(new java.net.DatagramPacket(out, out.length, reply.address(), reply.port()));
    }

    // Hex dump helper for UDP debugging
    private static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < Math.min(len, data.length); i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private byte[] buildServerDescPacket(int challenge, InetAddress client) {
        String sName = Main.SERVER_NAME;
        String sDesc = Main.SERVER_DESCRIPTION;

        int maxFiles = config.maxFiles();
        int maxUsers = config.maxUsers();

        int tcpFlags = Tag.SERVER_TCP_FLAGS;
        int udpFlags = Tag.SERVER_UDP_FLAGS;

        java.util.List<org.jemule.protocol.Tag> tags = new java.util.ArrayList<>();
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_HASH, "\u0011", getServerHash())); // ST_SERVERHASH (mapped to version tag ID in some contexts, but usually hash is first)
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAXUSERS, maxUsers));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SOFTFILES, config.maxFilesPerUser()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_HARDFILES, org.jemule.network.handler.PublishHandler.HARD_FILES));

        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_SERVERNAME, sName));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_DESCRIPTION, sDesc));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SERVER_VERSION, org.jemule.Main.ESERVER_VERSION_INT));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_LOWIDUSERS, registry.lowIdCount()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPFLAGS, udpFlags));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY, org.jemule.security.UdpObfuscation.serverKeyFor(client)));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY_IP, (int) ClientState.ipToLong(client))); // IP the key is valid for
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_OBFUSCATION_PORT, config.port()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPPORTOBFUSCATION, udpObfuscationPort()));

        ByteBuffer resp = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        resp.put(Packet.PROTOCOL_ED2K);
        resp.put((byte) 0xA3); // OP_SERVER_DESC_RES

        if (challenge != 0 && (challenge & 0xFFFF) == 0xF0FF) {
            // New format: <challenge 4><taglist>
            // Note: challenge must have 0xF0FF in the lower 16 bits to be recognized as "new format" by eMule
            resp.putInt(challenge);
            org.jemule.protocol.Tag.writeList(resp, tags);
        } else {
            // Old format: <name_len 2><name><desc_len 2><desc>
            byte[] nameBytes = sName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] descBytes = sDesc.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            resp.putShort((short) nameBytes.length);
            resp.put(nameBytes);
            resp.putShort((short) descBytes.length);
            resp.put(descBytes);
        }

        resp.flip();
        byte[] outBuf = new byte[resp.remaining()];
        resp.get(outBuf);
        return outBuf;
    }

    /**
     * Exposes the internal file index (primarily for testing).
     */
    public FileIndex getFileIndex() {
        return fileIndex;
    }

    /** Samples of the index size and of the connected clients, for the curves of the admin panel. */
    org.jemule.core.MetricsHistory getMetricsHistory() {
        return metricsHistory;
    }

    public org.jemule.core.OfferFilesV1 getOfferFilesV1() {
        return offerFilesV1;
    }

    private void recordMetrics() {
        try {
            metricsHistory.record(System.currentTimeMillis(), fileIndex.fileCount(), registry.size());
        } catch (RuntimeException e) {
            log.warn("Could not record the metrics sample: {}", e.getMessage());
        }
    }

    /**
     * Stops the server, shuts down executors, and closes the database.
     */
    public void stop() {
        running = false;
        stopAdminWeb = true;
        ServerSocket consoleSocket = adminSocket;
        adminSocket = null;
        if (consoleSocket != null) {
            try {
                consoleSocket.close();
            } catch (IOException e) {
                log.debug("Error closing the admin console: {}", e.getMessage());
            }
        }
        AdminWebPanel panel = adminWebPanel;
        if (panel != null) panel.close();
        maintenance.shutdownNow();
        serverSearchBridge.close();
        if (ipFilterUpdater != null) ipFilterUpdater.close();
        kadSearchBridge = null;
        if (kadNode != null) {
            kadNode.close();
        }
        clientSockets.forEach(socket -> {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("Error closing client socket during shutdown: {}", e.getMessage());
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Client handlers did not stop in time; interrupting remaining tasks");
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Some client handlers remain active while shutting down");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // The files still waiting for ingestion are written before the database closes
        ingestion.close();
        if (db != null) {
            try {
                db.close();
            } catch (SQLException e) {
                log.error("Error closing database: {}", e.getMessage());
            }
        }
        log.info("Server halted.");
    }
}
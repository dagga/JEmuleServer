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

package org.jemule.kad;

import org.jemule.config.KadConfig;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The embedded Kademlia node: assembles the routing table, the UDP service, the bootstrap and maintenance engines,
 * the search bridge and the hash ban service according to the {@link KadConfig} read from server.properties.
 * <p>
 * The 128-bit node ID is persisted in the H2 {@code stats} table (as two 64-bit halves, see
 * {@link #STAT_NODE_ID_HIGH} and {@link #STAT_NODE_ID_LOW}) so that the node keeps its identity across restarts.
 */
public class KadNode implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadNode.class);

    /** Key of the 64 most significant bits of the node ID in the {@code stats} table. */
    public static final String STAT_NODE_ID_HIGH = "kad_node_id_hi";
    /** Key of the 64 least significant bits of the node ID in the {@code stats} table. */
    public static final String STAT_NODE_ID_LOW = "kad_node_id_lo";

    private final KadConfig config;
    private final int tcpPort;
    private final int udpPort;
    private final KadRoutingTable routingTable;
    private final KadUdpService udpService;
    private final KadBootstrapManager bootstrapManager;
    private final KadMaintenanceService maintenanceService;
    private final KadSearchBridge searchBridge;
    private final KadHashBanService hashBanService;
    private final KadNodesUpdater nodesUpdater;

    private volatile boolean running = false;

    /**
     * @param config       Kademlia settings.
     * @param tcpPort      TCP port of the server, used to derive the default Kad UDP port.
     * @param db           H2 database (node ID, banned hashes, ingested files); may be null.
     * @param fileIndex    In-memory index receiving the ingested Kad files; may be null.
     * @param abuseFilter  Child abuse filter applied to every Kad result.
     * @param fakeDetector Fake file detector applied to every Kad result and receiving the Kad bans.
     */
    public KadNode(KadConfig config, int tcpPort, DatabaseManager db, FileIndex fileIndex,
                   ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector) {
        this(config, tcpPort, db, fileIndex, abuseFilter, fakeDetector, null);
    }

    /**
     * @param nodesCache Path of the downloaded nodes.dat cache; when null the configured sources are not fetched.
     */
    public KadNode(KadConfig config, int tcpPort, DatabaseManager db, FileIndex fileIndex,
                   ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector, Path nodesCache) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.tcpPort = tcpPort;
        this.udpPort = config.effectivePort(tcpPort);
        this.routingTable = new KadRoutingTable(loadOrCreateNodeId(db), config.maxRoutingContacts());
        this.udpService = new KadUdpService(udpPort);
        this.bootstrapManager = new KadBootstrapManager(routingTable, udpService);
        this.maintenanceService = new KadMaintenanceService(routingTable, udpService, tcpPort,
                KadMaintenanceService.DEFAULT_CHECK_INTERVAL_MS,
                KadMaintenanceService.DEFAULT_BUCKET_MAX_IDLE_MS,
                KadMaintenanceService.DEFAULT_CONTACT_MAX_IDLE_MS);
        this.searchBridge = new KadSearchBridge(routingTable, udpService, abuseFilter, fakeDetector, fileIndex, db,
                KadSearchBridge.DEFAULT_ALPHA_NODES, config.searchTimeoutSeconds() * 1000L,
                config.autoIngestToH2(), config.minSourcesForIngest());
        this.hashBanService = db != null ? new KadHashBanService(db, fakeDetector, searchBridge) : null;
        this.nodesUpdater = nodesCache != null && !config.nodesUrls().isBlank()
                ? new KadNodesUpdater(bootstrapManager, nodesCache, config.nodesSources())
                : null;
    }

    /**
     * Reads the persisted node ID, or generates and persists a new random one on the first start.
     */
    static KadId loadOrCreateNodeId(DatabaseManager db) {
        if (db != null) {
            long high = db.getStat(STAT_NODE_ID_HIGH, 0L);
            long low = db.getStat(STAT_NODE_ID_LOW, 0L);
            if (high != 0L || low != 0L) {
                return new KadId(ByteBuffer.allocate(KadId.ID_BYTES).putLong(high).putLong(low).array());
            }
        }
        KadId id = KadId.random();
        if (db != null) {
            ByteBuffer buf = ByteBuffer.wrap(id.getBytes());
            db.setStat(STAT_NODE_ID_HIGH, buf.getLong());
            db.setStat(STAT_NODE_ID_LOW, buf.getLong());
            log.info("Generated new Kad node ID {}", id);
        }
        return id;
    }

    /**
     * Binds the Kad UDP socket and starts every component.
     *
     * @throws SocketException If the Kad UDP port cannot be bound.
     */
    public synchronized void start() throws SocketException {
        if (running) return;
        udpService.start();
        if (hashBanService != null) {
            hashBanService.initialize();
        }
        bootstrapManager.start();
        if (nodesUpdater != null) {
            nodesUpdater.start();
        }
        maintenanceService.start();
        searchBridge.start();
        running = true;
        log.info("Kad node {} started on UDP port {} (bootstrap from clients: {}, forked search: {}, H2 ingestion: {})",
                routingTable.localId(), udpService.getPort(), config.bootstrapFromClients(),
                config.forkSearchEnabled(), config.autoIngestToH2());
    }

    /**
     * Uses a newly logged-in client to bootstrap the routing table, when enabled: only HighID clients can be
     * reached by the Kad network.
     */
    public void onClientConnected(ClientState client) {
        if (!running || !config.bootstrapFromClients() || client == null || !client.isHighId()) return;
        bootstrapManager.onClientConnected(client);
    }

    /** Sends a bootstrap request to a given node (admin command {@code kad bootstrap}). */
    public boolean bootstrap(InetAddress address, int port) {
        return running && bootstrapManager.bootstrap(address, port);
    }

    /** Status of the node: DISCONNECTED while it is stopped, else the bootstrap status. */
    public KadBootstrapManager.Status status() {
        return running ? bootstrapManager.getStatus() : KadBootstrapManager.Status.DISCONNECTED;
    }

    public boolean isRunning() {
        return running;
    }

    public KadConfig getConfig() {
        return config;
    }

    public KadId localId() {
        return routingTable.localId();
    }

    /** UDP port of the node: the bound port once started, else the configured one. */
    public int getUdpPort() {
        return running ? udpService.getPort() : udpPort;
    }

    public KadRoutingTable getRoutingTable() {
        return routingTable;
    }

    public KadUdpService getUdpService() {
        return udpService;
    }

    public KadBootstrapManager getBootstrapManager() {
        return bootstrapManager;
    }

    /** The downloaded nodes.dat updater, or null when the sources or the cache path are not configured. */
    public KadNodesUpdater getNodesUpdater() {
        return nodesUpdater;
    }

    public KadMaintenanceService getMaintenanceService() {
        return maintenanceService;
    }

    public KadSearchBridge getSearchBridge() {
        return searchBridge;
    }

    /** The hash ban service, or null without a database. */
    public KadHashBanService getHashBanService() {
        return hashBanService;
    }

    @Override
    public synchronized void close() {
        if (!running) {
            udpService.close();
            return;
        }
        running = false;
        searchBridge.close();
        if (hashBanService != null) {
            hashBanService.shutdown();
        }
        maintenanceService.close();
        if (nodesUpdater != null) {
            nodesUpdater.close();
        }
        bootstrapManager.close();
        udpService.close();
        log.info("Kad node stopped");
    }
}

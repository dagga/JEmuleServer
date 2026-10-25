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
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.network.Server;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class KadNodeTest {

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static KadConfig config(int port, boolean bootstrapFromClients, boolean forkSearch) {
        return new KadConfig(true, port, bootstrapFromClients, 50, forkSearch, 2, true, 1);
    }

    private static ClientState client(String ip, long clientId) throws Exception {
        ClientState state = new ClientState(InetAddress.getByName(ip), 50000, clientId,
                System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        state.setListenPort(4662);
        return state;
    }

    @Test
    void nodeIdIsPersistedAndSurvivesRestart(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("kad_node").toString();
        KadId first;
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            KadNode node = new KadNode(KadConfig.DEFAULT, 4661, db, null, ChildAbuseFilter.defaults(), new FakeFileDetector());
            first = node.localId();
            assertNotEquals(0L, db.getStat(KadNode.STAT_NODE_ID_HIGH, 0L) | db.getStat(KadNode.STAT_NODE_ID_LOW, 0L));
            node.close();
        }
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            KadNode node = new KadNode(KadConfig.DEFAULT, 4661, db, null, ChildAbuseFilter.defaults(), new FakeFileDetector());
            assertEquals(first, node.localId(), "the node keeps its identity across restarts");
            node.close();
        }
    }

    @Test
    void configurationIsAppliedToTheComponents() throws Exception {
        int port = freeUdpPort();
        try (DatabaseManager db = new DatabaseManager("mem:kad_node_cfg_" + System.nanoTime());
             KadNode node = new KadNode(new KadConfig(true, port, true, 42, true, 3, false, 2), 4661, db, null,
                     ChildAbuseFilter.defaults(), new FakeFileDetector())) {
            assertEquals(port, node.getUdpPort());
            assertEquals(42, node.getRoutingTable().maxContacts());
            assertEquals(3000, node.getSearchBridge().getSearchTimeoutMs());
            assertFalse(node.getSearchBridge().isAutoIngestToH2());
            assertEquals(2, node.getSearchBridge().getMinSourcesForIngest());
            assertEquals(KadBootstrapManager.Status.DISCONNECTED, node.status());
            assertFalse(node.isRunning());
        }
    }

    @Test
    void startBindsTheUdpPortAndOnlyHighIdClientsBootstrap() throws Exception {
        int port = freeUdpPort();
        try (KadNode node = new KadNode(config(port, true, true), 4661, null, null,
                ChildAbuseFilter.defaults(), new FakeFileDetector())) {
            node.start();
            assertTrue(node.isRunning());
            assertTrue(node.getUdpService().isRunning());
            assertEquals(port, node.getUdpPort());

            node.onClientConnected(client("10.20.30.40", 1234)); // LowID
            assertEquals(KadBootstrapManager.Status.DISCONNECTED, node.status(), "LowID clients are not contacted");

            node.onClientConnected(client("10.20.30.40", ClientState.ipToLong(InetAddress.getByName("10.20.30.40"))));
            assertEquals(KadBootstrapManager.Status.BOOTSTRAPPING, node.status(), "HighID clients start the bootstrap");
        }
    }

    @Test
    void bootstrapFromClientsCanBeDisabled() throws Exception {
        try (KadNode node = new KadNode(config(freeUdpPort(), false, true), 4661, null, null,
                ChildAbuseFilter.defaults(), new FakeFileDetector())) {
            node.start();
            node.onClientConnected(client("10.20.30.40", ClientState.ipToLong(InetAddress.getByName("10.20.30.40"))));
            assertEquals(KadBootstrapManager.Status.DISCONNECTED, node.status());
            assertTrue(node.bootstrap(InetAddress.getLoopbackAddress(), 4672), "manual bootstrap is still possible");
        }
    }

    @Test
    void serverCreatesTheNodeOnlyWhenEnabled(@TempDir Path dir) {
        ServerConfig base = new ServerConfig(4661, 2 * 1024 * 1024, 300, 50, 100, 1000, 5, 200,
                dir.resolve("db").toString(), null, true, 50f, 10, 60, 300, 0, "127.0.0.1", 0, null, 0);

        Server disabled = new Server(base.withKad(KadConfig.DISABLED), new ClientFactory());
        try {
            assertNull(disabled.getKadNode());
            assertNull(disabled.getKadSearchBridge());
        } finally {
            disabled.stop();
        }

        Server enabled = new Server(base.withKad(config(0, true, true)), new ClientFactory());
        try {
            assertNotNull(enabled.getKadNode());
            assertEquals(4672, enabled.getKadNode().getUdpPort(), "default Kad port is TCP port + 11");
            assertNull(enabled.getKadSearchBridge(), "searches are forked only once the node is started");
        } finally {
            enabled.stop();
        }
    }
}

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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class KadNodesUpdaterTest {

    @Test
    void downloadsParsesAndCachesTheList(@TempDir Path tempDir) throws Exception {
        HttpServer server = serve(200, v2());
        try {
            KadUdpService udp = new KadUdpService(0);
            KadBootstrapManager manager = manager(udp);
            Path cache = tempDir.resolve("jemule_db.nodes.dat");
            URI source = uri(server);
            try (KadNodesUpdater updater = new KadNodesUpdater(manager, cache, List.of(source))) {
                assertTrue(updater.refreshNow());
                assertEquals(1, updater.getLastContactCount());
                assertEquals(source.toString(), updater.getLastSource());
                assertTrue(Files.isRegularFile(cache));
                assertEquals(1, KadNodesFile.read(cache).size());
            } finally {
                manager.close();
                udp.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackOnTheCachedListWhenEverySourceFails(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("nodes.dat");
        Files.write(cache, v2());
        HttpServer server = serve(503, new byte[0]);
        try {
            KadUdpService udp = new KadUdpService(0);
            KadBootstrapManager manager = manager(udp);
            try (KadNodesUpdater updater = new KadNodesUpdater(manager, cache, List.of(uri(server)))) {
                assertTrue(updater.refreshNow());
                assertEquals(1, updater.getLastContactCount());
                assertEquals(cache.toString(), updater.getLastSource());
            } finally {
                manager.close();
                udp.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedDownloadsAndMissingCache(@TempDir Path tempDir) throws Exception {
        HttpServer server = serve(200, "not a nodes.dat".getBytes());
        try {
            KadUdpService udp = new KadUdpService(0);
            KadBootstrapManager manager = manager(udp);
            try (KadNodesUpdater updater = new KadNodesUpdater(manager, tempDir.resolve("nodes.dat"),
                    List.of(uri(server)))) {
                assertFalse(updater.refreshNow());
                assertEquals(0, updater.getLastRefresh());
            } finally {
                manager.close();
                udp.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsAGzippedList(@TempDir Path tempDir) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(v2());
        }
        HttpServer server = serve(200, output.toByteArray());
        try {
            KadUdpService udp = new KadUdpService(0);
            KadBootstrapManager manager = manager(udp);
            try (KadNodesUpdater updater = new KadNodesUpdater(manager, tempDir.resolve("nodes.dat"),
                    List.of(uri(server)))) {
                assertTrue(updater.refreshNow());
                assertEquals(1, updater.getLastContactCount());
            } finally {
                manager.close();
                udp.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static KadBootstrapManager manager(KadUdpService udp) {
        KadBootstrapManager manager = new KadBootstrapManager(new KadRoutingTable(KadId.random(), 50), udp);
        manager.start();
        return manager;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/nodes");
    }

    private static HttpServer serve(int status, byte[] content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nodes", exchange -> {
            exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
            if (content.length > 0) exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] v2() {
        ByteBuffer buf = ByteBuffer.allocate(12 + 34).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(2).putInt(1);
        buf.put(new byte[16]);
        buf.put(new byte[]{(byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE});
        buf.putShort((short) 4672).putShort((short) 4662);
        buf.put((byte) 8);
        buf.putInt(0).putInt(0).put((byte) 1);
        return buf.array();
    }
}

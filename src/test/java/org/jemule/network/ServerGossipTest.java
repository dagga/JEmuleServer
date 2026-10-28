package org.jemule.network;

import org.jemule.protocol.Tag;
import org.jemule.security.IPFilter;
import org.jemule.security.ServerObfuscation;
import org.jemule.security.UdpObfuscation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.HexFormat;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerGossipTest {
    private static final int CHALLENGE = 0x12345678;

    @Test
    void parsingWithTheIpFilterDropsTheBlockedEndpoints(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of("192.0.2.10 - 192.0.2.10 , 000 , Blocked Seed"));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        var peers = ServerGossip.parseEndpoints("127.0.0.1:4661, 192.0.2.10:5000", filter);
        assertEquals(1, peers.size());
        assertEquals(InetAddress.getByName("127.0.0.1"), peers.iterator().next().address());
    }

    @Test
    void configuredServerPeersBypassTheIpFilter(@TempDir Path tempDir) throws Exception {
        // A blocked range holding the administrator's other server (issue #59)
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of("192.0.2.0 - 192.0.2.255 , 000 , fake emule servers | hosting"));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        ServerGossip gossip = new ServerGossip("127.0.0.1:4661, 192.0.2.10:5000", filter);
        assertEquals(java.util.Set.of(new ServerGossip.Endpoint(InetAddress.getByName("127.0.0.1"), 4661),
                new ServerGossip.Endpoint(InetAddress.getByName("192.0.2.10"), 5000)),
                java.util.Set.copyOf(gossip.candidateEndpoints()));

        byte[] statReq = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xE3).put((byte) 0x96).putInt(12345).array();
        try (DatagramSocket socket = new DatagramSocket()) {
            assertTrue(gossip.handle(socket, InetAddress.getByName("192.0.2.10"), 5004, statReq, false,
                    InetAddress.getByName("127.0.0.1"), 4661, System.currentTimeMillis()), "its datagrams are heard");
            assertFalse(gossip.handle(socket, InetAddress.getByName("192.0.2.11"), 4665, statReq, false,
                    InetAddress.getByName("127.0.0.1"), 4661, System.currentTimeMillis()),
                    "another server of the range stays blocked");
        }
        assertEquals(0, gossip.addServerMetCandidates(List.of(
                new ServerGossip.Endpoint(InetAddress.getByName("192.0.2.12"), 4661))));
    }

    @Test
    void filtersServerMetAndClientReportedServersMatchingIpFilter(@TempDir Path tempDir) throws Exception {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of("192.0.2.2 - 192.0.2.2 , 000 , Blocked Met Candidate"));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        ByteBuffer met = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        met.put((byte) 0xE0).putInt(2);
        putServerMetRecord(met, new byte[]{(byte) 192, 0, 2, 1}, 4661);
        putServerMetRecord(met, new byte[]{(byte) 192, 0, 2, 2}, 4662);

        List<ServerGossip.Endpoint> parsed = ServerMetLoader.parse(
                java.util.Arrays.copyOf(met.array(), met.position()));
        ServerGossip gossip = new ServerGossip("", filter);
        assertEquals(1, gossip.addServerMetCandidates(parsed));
        assertEquals(1, gossip.candidateEndpoints().size());
        assertEquals(InetAddress.getByName("192.0.2.1"), gossip.candidateEndpoints().iterator().next().address());

        byte[] report = ByteBuffer.allocate(1 + 2 * 6).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 2)
                .put(new byte[]{(byte) 198, 51, 100, 21}).putShort((short) 4661)
                .put(new byte[]{(byte) 192, 0, 2, 2}).putShort((short) 4662)
                .array();
        assertEquals(1, gossip.addClientReportedServerList(report));
        assertEquals(2, gossip.candidateEndpoints().size());
    }

    @Test
    void excludesBlockedServersFromClientServerListAndIgnoresGossipFromBlockedSources(@TempDir Path tempDir) throws Exception {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of("192.0.2.99 - 192.0.2.99 , 000 , Blocked Server"));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        ServerGossip gossip = new ServerGossip("", filter);
        InetAddress blockedIp = InetAddress.getByName("192.0.2.99");
        byte[] statReq = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xE3).put((byte) 0x96).putInt(12345).array();

        try (DatagramSocket socket = new DatagramSocket()) {
            assertFalse(gossip.handle(socket, blockedIp, 4665, statReq, false,
                    InetAddress.getByName("127.0.0.1"), 4661, System.currentTimeMillis()));
        }

        byte[] response = gossip.clientServerList(blockedIp, 4661);
        assertEquals(1, response.length);
        assertEquals(0, response[0]);
    }

    @Test
    void parsesConfiguredIpv4SeedEndpointsAndRejectsInvalidEntries() {
        var peers = ServerGossip.parseEndpoints("127.0.0.1:4661, 192.0.2.10:5000,");
        assertEquals(2, peers.size());
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("[::1]:4661"));
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("127.0.0.1:65532"));
    }

    @Test
    void collectsClientReportedServersAndReturnsOnlyVerifiedServers() throws Exception {
        ServerGossip gossip = new ServerGossip("");
        byte[] report = ByteBuffer.allocate(1 + 2 * 6).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 2)
                .put(new byte[]{(byte) 198, 51, 100, 21}).putShort((short) 4661)
                .put(new byte[]{127, 0, 0, 1}).putShort((short) 4661)
                .array();

        assertEquals(1, gossip.addClientReportedServerList(report));
        assertEquals(1, gossip.candidateEndpoints().size());
        assertEquals(0, gossip.addClientReportedServerList(java.util.Arrays.copyOf(report, report.length - 1)));

        byte[] response = gossip.clientServerList(InetAddress.getByName("203.0.113.2"), 4661);
        assertEquals(7, response.length);
        assertEquals(1, response[0] & 0xFF);
        List<ServerGossip.Endpoint> returned = ServerGossip.decodeServerList(
                ByteBuffer.allocate(2 + response.length).put((byte) 0xE3).put((byte) 0xA1).put(response).array());
        assertEquals(InetAddress.getByName("203.0.113.2"), returned.get(0).address());
        assertEquals(4661, returned.get(0).tcpPort());
    }

    @Test
    void acceptsOnlyChallengeMatchedPlausibleServerStatus() throws Exception {
        byte[] status = statusPacket(CHALLENGE, 100, 200, 1000, 500);

        ServerGossip.Status decoded = ServerGossip.validateStatus(status, CHALLENGE);
        assertNotNull(decoded);
        assertEquals(0x12345678, decoded.udpKey());
        assertNull(ServerGossip.validateStatus(status, CHALLENGE + 1));
        assertNull(ServerGossip.validateStatus(java.util.Arrays.copyOf(status, 17), CHALLENGE));
        assertNull(ServerGossip.validateStatus(statusPacket(CHALLENGE, 1001, 200, 1000, 500), CHALLENGE));
        // The hard limit is per client: a server indexes far more files in total
        assertNotNull(ServerGossip.validateStatus(statusPacket(CHALLENGE, 100, 5_000_000, 1000, 500), CHALLENGE));
    }

    @Test
    void acceptsRealServerStatusVariants() {
        int challenge = 0x55AA1234;
        // 46 bytes: obfuscation ports and UDP key, followed by the server IP
        ServerGossip.Status withKey = ServerGossip.validateStatus(HexFormat.of().parseHex(
                "e3973412aa55d20100009d6c050050c30000400d0300400d03003b4700007f010000961088105d657da18ff6c0c0"),
                challenge);
        assertNotNull(withKey);
        assertEquals(466, withKey.users());
        assertEquals(355485, withKey.files());
        assertEquals(50000, withKey.maxUsers());
        assertEquals(200000, withKey.hardFiles());
        assertEquals(383, withKey.lowIdUsers());
        assertEquals(0xA17D655D, withKey.udpKey());

        // 34 bytes: no obfuscation support, total files far above the per-client hard limit
        ServerGossip.Status withoutKey = ServerGossip.validateStatus(HexFormat.of().parseHex(
                "e3973412aa55e81a00000e23260050c3000070170000e02e0000fb170000e2150000"), challenge);
        assertNotNull(withoutKey);
        assertEquals(2499342, withoutKey.files());
        assertEquals(12000, withoutKey.hardFiles());
        assertEquals(0, withoutKey.udpKey());
    }

    @Test
    void acceptsRealServerDescriptions() {
        int challenge = 0x1234F0FF;
        assertEquals("eMule-Nordic", ServerGossip.validateDescription(HexFormat.of().parseHex(
                "e3a3fff0341203000000020100010c00654d756c652d4e6f726469630201000b1200214e464f2d53657276657220762e"
                        + "31382e3102010091040031382e31"), challenge));
        assertEquals("Mazinga Server", ServerGossip.validateDescription(HexFormat.of().parseHex(
                "e3a3fff0341204000000020100010e004d617a696e6761205365727665720201000b27004d617a696e676120486967"
                        + "682d706572666f726d616e63652065446f6e6b657920736572766572020100930e00343233332c343233342c3432"
                        + "3335030100910f001100"), challenge));
    }

    @Test
    void requiresWellFormedServerDescriptionAndExcludesMldonkeyIdentity() throws Exception {
        int descriptionChallenge = 0x5678F0FF;
        assertEquals("Verified eD2K", ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "Verified eD2K", "Lugdunum-compatible server"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "MLDonkey server", "eDonkey server"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "", "No usable identity"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge + 1, "Valid name", "Description"),
                descriptionChallenge));
    }

    @Test
    void decodesServerListsOnlyWhenCountAndPacketLengthAgree() throws Exception {
        byte[] response = ByteBuffer.allocate(3 + 2 * 6)
                .put((byte) 0xE3).put((byte) 0xA1).put((byte) 2)
                .put(new byte[]{(byte) 192, 0, 2, 8}).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 4661)
                .put(new byte[]{(byte) 198, 51, 100, 9}).putShort((short) 5000)
                .array();

        List<ServerGossip.Endpoint> peers = ServerGossip.decodeServerList(response);
        assertNotNull(peers);
        assertEquals(2, peers.size());
        assertEquals(InetAddress.getByName("192.0.2.8"), peers.get(0).address());
        assertEquals(4661, peers.get(0).tcpPort());
        assertEquals(5000, peers.get(1).tcpPort());
        assertNull(ServerGossip.decodeServerList(java.util.Arrays.copyOf(response, response.length - 1)));
    }

    @Test
    void parsesServerMetAndMergesCandidatesWithoutReplacingExistingSeeds() throws Exception {
        ByteBuffer met = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        met.put((byte) 0xE0).putInt(3);
        putServerMetRecord(met, new byte[]{(byte) 192, 0, 2, 1}, 4661);
        putServerMetRecord(met, new byte[]{(byte) 192, 0, 2, 2}, 4662);
        putServerMetRecord(met, new byte[]{127, 0, 0, 1}, 4663);

        List<ServerGossip.Endpoint> parsed = ServerMetLoader.parse(
                java.util.Arrays.copyOf(met.array(), met.position()));
        ServerGossip gossip = new ServerGossip("192.0.2.1:4661,192.0.2.3:4663");
        assertEquals(1, gossip.addServerMetCandidates(parsed));

        var candidates = gossip.candidateEndpoints();
        assertEquals(3, candidates.size());
        assertTrue(candidates.stream().anyMatch(peer -> peer.address().getHostAddress().equals("192.0.2.1")
                && peer.tcpPort() == 4661));
        assertTrue(candidates.stream().anyMatch(peer -> peer.address().getHostAddress().equals("192.0.2.2")
                && peer.tcpPort() == 4662));
        assertTrue(candidates.stream().anyMatch(peer -> peer.address().getHostAddress().equals("192.0.2.3")
                && peer.tcpPort() == 4663));
    }

    @Test
    void rejectsMalformedServerMetWithoutReturningPartialCandidates() {
        assertThrows(java.io.IOException.class, () -> ServerMetLoader.parse(new byte[]{(byte) 0xE0, 1, 0, 0, 0}));
    }

    @Test
    void verifiesPeerThenExchangesAnObfuscatedServerList() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            local.setSoTimeout(2000);
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            int peerTcpPort = peer.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + peerTcpPort);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            gossip.poll(local, System.currentTimeMillis());
            DatagramPacket statusRequest = receive(peer);
            assertEquals((byte) 0xE3, statusRequest.getData()[0]);
            assertEquals((byte) 0x96, statusRequest.getData()[1]);
            int statusChallenge = ByteBuffer.wrap(statusRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertEquals(0x55AA0000, statusChallenge & 0xFFFF0000, "servers send the extended status only to 0x55AA");
            byte[] status = statusPacket(statusChallenge, 100, 200, 1000, 500,
                    UdpObfuscation.serverKeyFor(loopback));
            peer.send(new DatagramPacket(status, status.length, statusRequest.getSocketAddress()));
            DatagramPacket statusResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(statusResponse.getData(), statusResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket descriptionRequest = receive(peer);
            assertEquals((byte) 0xA2, descriptionRequest.getData()[1]);
            int descriptionChallenge = ByteBuffer.wrap(descriptionRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] description = descriptionPacket(descriptionChallenge, "Verified server", "Compatible");
            peer.send(new DatagramPacket(description, description.length, descriptionRequest.getSocketAddress()));
            DatagramPacket descriptionResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(descriptionResponse.getData(), descriptionResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket encryptedListRequest = receive(peer);
            byte[] decodedListRequest = ServerObfuscation.decode(
                    java.util.Arrays.copyOf(encryptedListRequest.getData(), encryptedListRequest.getLength()),
                    UdpObfuscation.serverKeyFor(loopback));
            assertNotNull(decodedListRequest, "server-list request should use the peer's advertised UDP key");
            assertEquals((byte) 0xA0, decodedListRequest[1]);
            assertArrayEquals(loopback.getAddress(),
                    java.util.Arrays.copyOfRange(decodedListRequest, 2, 6));
            assertEquals(localTcpPort, Short.toUnsignedInt(ByteBuffer.wrap(decodedListRequest, 6, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort()));

            byte[] discovered = new byte[3 + 6];
            ByteBuffer response = ByteBuffer.wrap(discovered).order(ByteOrder.LITTLE_ENDIAN);
            response.put((byte) 0xE3).put((byte) 0xA1).put((byte) 1)
                    .put(new byte[]{(byte) 192, 0, 2, 77}).putShort((short) 4665);
            peer.send(new DatagramPacket(discovered, discovered.length, encryptedListRequest.getSocketAddress()));

            DatagramPacket listResponse = receive(local);
            byte[] responseBytes = java.util.Arrays.copyOf(listResponse.getData(), listResponse.getLength());
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(), responseBytes,
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            InetAddress discoveredAddress = InetAddress.getByName("192.0.2.77");
            assertEquals("Verified server", gossip.verifiedPeers().iterator().next().name());
            assertEquals(1, gossip.clientServerList(loopback, localTcpPort)[0] & 0xFF);
            assertTrue(gossip.candidateEndpoints().stream().anyMatch(endpoint ->
                    endpoint.address().equals(discoveredAddress) && endpoint.tcpPort() == 4665));

            ServerGossip.ServerView verifiedView = gossip.serverViews().stream()
                    .filter(view -> view.endpoint().tcpPort() == peerTcpPort).findFirst().orElseThrow();
            assertEquals("verified", verifiedView.state());
            assertEquals(ServerGossip.ServerSource.CONFIGURED, verifiedView.source());
            assertEquals("Verified server", verifiedView.name());
            assertEquals(100L, verifiedView.users());
            assertEquals(200L, verifiedView.files());
            assertEquals(1000L, verifiedView.maxUsers());
            assertEquals(5L, verifiedView.lowIdUsers());
            assertTrue(verifiedView.hasUdpKey());
            assertNotNull(verifiedView.latencyMillis());
            ServerGossip.ServerView discoveredView = gossip.serverViews().stream()
                    .filter(view -> view.endpoint().tcpPort() == 4665).findFirst().orElseThrow();
            assertEquals("candidate", discoveredView.state());
            assertEquals(ServerGossip.ServerSource.GOSSIP, discoveredView.source());
        }
    }

    @Test
    void keepsTheStatusOfAVerifiedServerThatNeverAnswersTheServerListRequest() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + (peer.getLocalPort() - 4));
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            long now = System.currentTimeMillis();

            gossip.poll(local, now);
            DatagramPacket statusRequest = receive(peer);
            int statusChallenge = ByteBuffer.wrap(statusRequest.getData(), 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    statusPacket(statusChallenge, 100, 200, 1000, 500, 0), false, loopback, localTcpPort, now));
            DatagramPacket descriptionRequest = receive(peer);
            int descriptionChallenge = ByteBuffer.wrap(descriptionRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    descriptionPacket(descriptionChallenge, "Quiet server", "No list"), false, loopback, localTcpPort, now));
            assertEquals((byte) 0xA0, receive(peer).getData()[1], "server list requested");

            // The server list request times out, as with most real servers
            gossip.poll(local, now + 11_000);

            ServerGossip.ServerView view = gossip.serverViews().get(0);
            assertEquals("verified", view.state());
            assertEquals(100L, view.users(), "the status answered just before is kept");
            assertEquals(200L, view.files());
            assertEquals(0, view.missedStatusProbes());
        }
    }

    @Test
    void aLateAnswerToAnEarlierProbeDoesNotRejectTheServer() throws Exception {
        // Production: a long GC pause expired every probe; the next poll sent new challenges, then the answers to
        // the old ones were read, and all the servers were rejected for good
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + (peer.getLocalPort() - 4));
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            long now = System.currentTimeMillis();

            gossip.poll(local, now);
            int oldChallenge = ByteBuffer.wrap(receive(peer).getData(), 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            gossip.poll(local, now + 61_000); // the first probe expired, a second one is sent
            int newChallenge = ByteBuffer.wrap(receive(peer).getData(), 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    statusPacket(oldChallenge, 100, 200, 1000, 500, 0), false, loopback, localTcpPort, now + 61_001));
            assertEquals("probing", gossip.serverViews().get(0).state(), "the late answer is ignored");

            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    statusPacket(newChallenge, 100, 200, 1000, 500, 0), false, loopback, localTcpPort, now + 61_002));
            int descriptionChallenge = ByteBuffer.wrap(receive(peer).getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    descriptionPacket(descriptionChallenge, "Busy server", "Late"), false, loopback, localTcpPort,
                    now + 61_003));
            assertEquals("verified", gossip.serverViews().get(0).state());
        }
    }

    @Test
    void aRejectedServerIsProbedAgainLater() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + (peer.getLocalPort() - 4));
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            long now = System.currentTimeMillis();

            gossip.poll(local, now);
            int challenge = ByteBuffer.wrap(receive(peer).getData(), 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            // More users than its maximum: an implausible status
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    statusPacket(challenge, 5000, 200, 1000, 500, 0), false, loopback, localTcpPort, now));
            assertEquals("rejected", gossip.serverViews().get(0).state());

            gossip.poll(local, now + ServerGossip.REJECT_RETRY_MILLIS - 1);
            assertEquals("rejected", gossip.serverViews().get(0).state());
            gossip.poll(local, now + ServerGossip.REJECT_RETRY_MILLIS + 60_000);
            assertEquals("probing", gossip.serverViews().get(0).state());
            assertEquals((byte) 0x96, receive(peer).getData()[1], "probed again");
        }
    }

    @Test
    void monitoringViewsTrackSourcesAndCandidates() throws Exception {
        ServerGossip gossip = new ServerGossip("127.0.0.1:4661");
        gossip.addServerMetCandidates(List.of(new ServerGossip.Endpoint(InetAddress.getByName("192.0.2.10"), 5000)));
        byte[] reported = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 1).put(new byte[]{(byte) 192, 0, 2, 20}).putShort((short) 4662).array();
        assertEquals(1, gossip.addClientReportedServerList(reported));

        List<ServerGossip.ServerView> views = gossip.serverViews();
        assertEquals(3, views.size());
        assertEquals(ServerGossip.ServerSource.CONFIGURED, viewFor(views, 4661).source());
        assertEquals(ServerGossip.ServerSource.SERVER_MET, viewFor(views, 5000).source());
        ServerGossip.ServerView clientReported = viewFor(views, 4662);
        assertEquals(ServerGossip.ServerSource.CLIENT_REPORTED, clientReported.source());
        assertEquals("candidate", clientReported.state());
        assertNull(clientReported.users());
        assertEquals(0, clientReported.missedStatusProbes());
    }

    private static ServerGossip.ServerView viewFor(List<ServerGossip.ServerView> views, int tcpPort) {
        return views.stream().filter(view -> view.endpoint().tcpPort() == tcpPort).findFirst().orElseThrow();
    }

    @Test
    void rejectsMldonkeyPeerAfterChallengeMatchedStatusAndDescription() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            local.setSoTimeout(2000);
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            int peerTcpPort = peer.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + peerTcpPort);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            gossip.poll(local, System.currentTimeMillis());
            DatagramPacket statusRequest = receive(peer);
            int statusChallenge = ByteBuffer.wrap(statusRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] status = statusPacket(statusChallenge, 100, 200, 1000, 500);
            peer.send(new DatagramPacket(status, status.length, statusRequest.getSocketAddress()));
            DatagramPacket statusResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(statusResponse.getData(), statusResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket descriptionRequest = receive(peer);
            int descriptionChallenge = ByteBuffer.wrap(descriptionRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] description = descriptionPacket(descriptionChallenge, "MLDonkey server", "Legacy server");
            peer.send(new DatagramPacket(description, description.length, descriptionRequest.getSocketAddress()));
            DatagramPacket descriptionResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(descriptionResponse.getData(), descriptionResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            assertTrue(gossip.verifiedPeers().isEmpty());
            assertTrue(gossip.candidateEndpoints().isEmpty());
        }
    }

    private static byte[] statusPacket(int challenge, long users, long files, long maxUsers, long hardFiles) {
        return statusPacket(challenge, users, files, maxUsers, hardFiles, 0x12345678);
    }

    private static byte[] statusPacket(int challenge, long users, long files, long maxUsers, long hardFiles,
                                       int udpKey) {
        return ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xE3).put((byte) 0x97).putInt(challenge)
                .putInt((int) users).putInt((int) files).putInt((int) maxUsers)
                .putInt(1000).putInt((int) hardFiles).putInt(0x200).putInt(5)
                .putShort((short) 4673).putShort((short) 4661).putInt(udpKey)
                .array();
    }

    private static DatagramSocket bindServerUdpSocket() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            if (socket.getLocalPort() >= 5 && socket.getLocalPort() <= 65535) return socket;
            socket.close();
        }
        throw new IllegalStateException("Could not bind a server UDP socket");
    }

    private static DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return packet;
    }

    private static byte[] descriptionPacket(int challenge, String name, String description) {
        ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0xE3).put((byte) 0xA3).putInt(challenge);
        Tag.writeList(buffer, List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, name),
                new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, description)));
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static void putServerMetRecord(ByteBuffer buffer, byte[] address, int port) {
        buffer.put(address).putShort((short) port);
        Tag.writeList(buffer, List.of(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, "test server")));
    }
}

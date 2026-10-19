package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class AdminInterfaceTest {
    private Server server;
    private ClientRegistry registry;
    private FileIndex fileIndex;
    private IPFilter ipFilter;
    private FakeFileDetector fakeFileDetector;
    private AdminInterface admin;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        server = mock(Server.class);
        registry = mock(ClientRegistry.class);
        fileIndex = mock(FileIndex.class);
        ipFilter = mock(IPFilter.class);
        fakeFileDetector = mock(FakeFileDetector.class);
        admin = new AdminInterface(server, registry, fileIndex, ipFilter, fakeFileDetector);
        System.setOut(new PrintStream(outContent));
    }

    @Test
    void testHelpCommand() {
        admin.handleCommand("help");
        assertTrue(outContent.toString().contains("Available commands"));
    }

    @Test
    void testStatusCommand() {
        admin.handleCommand("status");
        assertTrue(outContent.toString().contains("Server Status"));
        assertTrue(outContent.toString().contains("Uptime"));
    }

    @Test
    void testBanCommand() {
        String hash = "1234567890ABCDEF1234567890ABCDEF";
        admin.handleCommand("ban " + hash + " SpamFile");
        verify(fakeFileDetector).ban(hash, "Manual ban: SpamFile");
        verify(fileIndex).removeFile(hash);
        assertTrue(outContent.toString().contains("added to blacklist"));
    }

    @Test
    void testBanRejectsInvalidHash() {
        admin.handleCommand("ban 1234567890ABCDEF1234567890ABCDEZ");
        verifyNoInteractions(fakeFileDetector, fileIndex);
        assertTrue(outContent.toString().contains("Error"));
    }

    @Test
    void testStopCommand() {
        admin.handleCommand("stop");
        verify(server).stop();
        assertTrue(outContent.toString().contains("Stopping server"));
    }

    @Test
    void remoteSessionAnswersOnItsOwnStreamAndQuitEndsOnlyTheSession() throws Exception {
        ByteArrayOutputStream remote = new ByteArrayOutputStream();
        AdminInterface session = new AdminInterface(admin, new PrintStream(remote, true));
        int lines = session.session(new java.io.BufferedReader(new java.io.StringReader("help\nquit\nstatus\n")), true);

        assertEquals(2, lines, "quit ends the session");
        assertTrue(remote.toString().contains("Available commands"));
        assertFalse(outContent.toString().contains("Available commands"), "nothing is written on the console");
        verifyNoInteractions(server);
    }

    @Test
    void stopInARemoteSessionStopsTheServer() throws Exception {
        AdminInterface session = new AdminInterface(admin, new PrintStream(new ByteArrayOutputStream(), true));
        session.session(new java.io.BufferedReader(new java.io.StringReader("stop\n")), true);
        verify(server).stop();
    }

    private static org.jemule.kad.KadNode startedKadNode() throws Exception {
        int port;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
            port = s.getLocalPort();
        }
        org.jemule.kad.KadNode node = new org.jemule.kad.KadNode(
                new org.jemule.config.KadConfig(true, port, true, 500, true, 4, true, 1), 4661, null, null,
                org.jemule.security.ChildAbuseFilter.defaults(), new FakeFileDetector());
        node.start();
        return node;
    }

    @Test
    void kadCommandsReportADisabledNode() {
        admin.handleCommand("kad status");
        assertTrue(outContent.toString().contains("Kademlia is disabled"));
    }

    @Test
    void kadStatusShowsStatusPortAndContacts() throws Exception {
        try (org.jemule.kad.KadNode node = startedKadNode()) {
            org.mockito.Mockito.when(server.getKadNode()).thenReturn(node);
            admin.handleCommand("kad status");
            String out = outContent.toString();
            assertTrue(out.contains("Kademlia Status"), out);
            assertTrue(out.contains("Status: DISCONNECTED"), out);
            assertTrue(out.contains("UDP Port: " + node.getUdpPort()), out);
            assertTrue(out.contains("Contacts: 0 / 500"), out);
            assertTrue(out.contains(node.localId().toHexString().toUpperCase()), out);

            outContent.reset();
            admin.handleCommand("kad");
            assertTrue(outContent.toString().contains("Kademlia Status"), "'kad' alone shows the status");
        }
    }

    @Test
    void kadContactsListsAtMostTwentyContacts() throws Exception {
        try (org.jemule.kad.KadNode node = startedKadNode()) {
            org.mockito.Mockito.when(server.getKadNode()).thenReturn(node);
            org.jemule.kad.KadRoutingTable table = node.getRoutingTable();
            for (int i = 0; i < 25; i++) {
                table.addOrUpdate(new org.jemule.kad.KadContact(table.generateRandomIdForBucket(i % 100 + 1),
                        java.net.InetAddress.getByName("10.0.0." + (i + 1)), 4672));
            }
            int total = table.size();
            admin.handleCommand("kad contacts");
            String out = outContent.toString();
            assertTrue(out.contains("Kademlia Contacts (" + total + ")"), out);
            assertEquals(AdminInterface.MAX_LISTED_KAD_CONTACTS, out.split(":4672 ", -1).length - 1, out);
            assertTrue(out.contains("ALIVE"), out);
            assertTrue(out.contains("... and " + (total - AdminInterface.MAX_LISTED_KAD_CONTACTS) + " more"), out);
        }
    }

    @Test
    void kadBootstrapValidatesItsArgumentsAndSendsTheRequest() throws Exception {
        try (org.jemule.kad.KadNode node = startedKadNode()) {
            org.mockito.Mockito.when(server.getKadNode()).thenReturn(node);

            admin.handleCommand("kad bootstrap 127.0.0.1");
            assertTrue(outContent.toString().contains("Usage: kad bootstrap <ip> <port>"));

            outContent.reset();
            admin.handleCommand("kad bootstrap 127.0.0.1 70000");
            assertTrue(outContent.toString().contains("Error: Port"));

            outContent.reset();
            long before = node.getBootstrapManager().getBootstrapAttempts();
            admin.handleCommand("kad bootstrap 127.0.0.1 4672");
            assertTrue(outContent.toString().contains("Bootstrap request sent to 127.0.0.1:4672"), outContent.toString());
            assertEquals(before + 1, node.getBootstrapManager().getBootstrapAttempts());
        }
    }

    @Test
    void kadStatsShowsTheCounters() throws Exception {
        try (org.jemule.kad.KadNode node = startedKadNode()) {
            org.mockito.Mockito.when(server.getKadNode()).thenReturn(node);
            admin.handleCommand("kad stats");
            String out = outContent.toString();
            assertTrue(out.contains("Searches forked: 0"), out);
            assertTrue(out.contains("Results received: 0"), out);
            assertTrue(out.contains("Files ingested: 0"), out);
            assertTrue(out.contains("Hashes banned: 0"), out);

            outContent.reset();
            admin.handleCommand("kad unknown");
            assertTrue(outContent.toString().contains("Usage: kad"));
        }
    }

    @Test
    void helpListsTheKadCommands() {
        admin.handleCommand("help");
        String out = outContent.toString();
        assertTrue(out.contains("kad status") && out.contains("kad contacts")
                && out.contains("kad bootstrap") && out.contains("kad stats"), out);
    }

    @Test
    void eventsCommandShowsTheCounters() {
        org.jemule.core.event.EventStatistics stats = new org.jemule.core.event.EventStatistics();
        stats.accept(new org.jemule.core.event.FileEvent(org.jemule.core.event.FileEvent.SEARCHED, "q", "", "search"));
        org.mockito.Mockito.when(server.getEventStatistics()).thenReturn(stats);
        admin.handleCommand("events");
        assertTrue(outContent.toString().matches("(?s).*FILE_SEARCHED\\s+1\\s+1.*"), outContent.toString());
    }

    @Test
    void clientsCommandSearchesNicknameIpAndId() throws Exception {
        var alice = new org.jemule.core.ClientState(java.net.InetAddress.getByName("192.0.2.9"), 4662, 12345,
                System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()));
        alice.setNickname("Alice");
        var bob = new org.jemule.core.ClientState(java.net.InetAddress.getByName("192.0.2.10"), 4662, 67890,
                System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()));
        bob.setNickname("Bob");
        org.mockito.Mockito.when(registry.getAllClients()).thenReturn(java.util.List.of(alice, bob));

        admin.handleCommand("clients alice");

        assertTrue(outContent.toString().contains("Alice"), outContent.toString());
        assertFalse(outContent.toString().contains("Bob"), outContent.toString());
    }

    @Test
    void settingsCommandShowsAdminConfiguration() {
        org.jemule.config.ServerConfig config = org.jemule.config.ServerConfig.DEFAULT
                .withAdminPort(4670)
                .withAdminWebPort(8080)
                .withAdminBindAddress("0.0.0.0")
                .withAdminAllowedIps("127.0.0.1,192.168.1.0/24")
                .withAdminAllowedMacs("00:11:22:33:44:55");
        org.mockito.Mockito.when(server.getConfig()).thenReturn(config);

        admin.handleCommand("settings");
        String out = outContent.toString();
        assertTrue(out.contains("Admin console port: 4670"), out);
        assertTrue(out.contains("Admin web port: 8080"), out);
        assertTrue(out.contains("Admin bind address: 0.0.0.0"), out);
        assertTrue(out.contains("Admin allowed IPs: 127.0.0.1,192.168.1.0/24"), out);
        assertTrue(out.contains("Admin allowed MACs: 00:11:22:33:44:55"), out);
    }

    @Test
    void settingsCommandCanToggleFakeFileHeuristics() {
        admin.handleCommand("settings fakeFileDetectionEnabled off");

        verify(fakeFileDetector).setEnabled(false);
        assertTrue(outContent.toString().contains("Fake-file detection disabled."));
    }
}

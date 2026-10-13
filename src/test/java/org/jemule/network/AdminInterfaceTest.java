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

    @Test
    void eventsCommandShowsTheCounters() {
        org.jemule.core.event.EventStatistics stats = new org.jemule.core.event.EventStatistics();
        stats.accept(new org.jemule.core.event.FileEvent(org.jemule.core.event.FileEvent.SEARCHED, "q", "", "search"));
        org.mockito.Mockito.when(server.getEventStatistics()).thenReturn(stats);
        admin.handleCommand("events");
        assertTrue(outContent.toString().matches("(?s).*FILE_SEARCHED\\s+1\\s+1.*"), outContent.toString());
    }
}

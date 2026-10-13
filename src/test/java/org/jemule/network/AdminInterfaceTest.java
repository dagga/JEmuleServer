package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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
}

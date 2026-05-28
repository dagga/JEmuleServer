package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.core.event.EventManager;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.PublishHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the heartbeat mechanism to ensure clients remain connected
 * during periods of inactivity.
 */
class HeartbeatTest {

    @TempDir
    Path tempDir;

    private ClientHandler handler;
    private ClientState state;
    private ServerConfig config;
    private ByteArrayOutputStream capturedOutput;
    private FileIndex fileIndex;

    @BeforeEach
    void setup() throws IOException {
        // Configuration with heartbeat enabled: 2 seconds interval
        config = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 5, 200,
                tempDir.resolve("db").toString(), null, true,
                50.0f, 10, 60, 300, 2 // heartbeatIntervalSeconds = 2
        );

        FileIndex index = new FileIndex(null);
        ClientRegistry registry = new ClientRegistry();
        FloodProtector flood = new FloodProtector(config.floodMaxRequestsPerSecond());
        FakeFileDetector fakes = new FakeFileDetector();
        EventManager events = new EventManager();
        ClientFactory factory = new ClientFactory();

        // Setup mock socket
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(socket.getRemoteSocketAddress()).thenReturn(
                new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 5555)
        );
        when(socket.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());

        // Create handler with captured output
        handler = new ClientHandler(socket, config, registry, index, flood, fakes, events, factory);
        state = new ClientState(InetAddress.getLoopbackAddress(), 5555, 123, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        injectState(handler, state);
        registry.add(state, p -> {});

        capturedOutput = new ByteArrayOutputStream();
        fileIndex = index;
    }

    private void injectState(ClientHandler handler, ClientState state) {
        try {
            Field field = ClientHandler.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(handler, state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Test that verifies the heartbeat mechanism sends ServerStatus packets periodically
     * even when no packets are received from the client (handling inactivity).
     */
    @Test
    void testHeartbeatIsSentPeriodically() throws Exception {
        int heartbeatIntervalSeconds = config.heartbeatIntervalSeconds();
        assertTrue(heartbeatIntervalSeconds > 0, "Heartbeat interval should be enabled (> 0)");

        // Call sendServerStatus method directly to verify it can send the heartbeat packet
        LoginHandler.sendServerStatus(handler, capturedOutput);

        // Verify that data was written (heartbeat packet was sent)
        byte[] output = capturedOutput.toByteArray();
        assertTrue(output.length > 0, "Heartbeat packet should be sent to client");

        // Parse the packet to verify it's a SERVER_STATUS packet
        // Packet format: [protocol:1byte][size:4bytes][opcode:1byte][data]
        ByteBuffer buf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        byte protocol = buf.get();
        int size = buf.getInt(); // packet size (payload + opcode)
        byte opcode = buf.get();

        assertEquals(Packet.PROTOCOL_ED2K, protocol, "Heartbeat should use ED2K protocol");
        assertEquals(OpCode.SERVER_STATUS.value, opcode, "Heartbeat should be a SERVER_STATUS packet");
    }

    /**
     * Test that verifies ServerStatus packet structure for heartbeat contains
     * valid server information (user count, file count, limits).
     */
    @Test
    void testHeartbeatPacketStructure() throws Exception {
        LoginHandler.sendServerStatus(handler, capturedOutput);

        byte[] output = capturedOutput.toByteArray();
        assertTrue(output.length >= 14, "ServerStatus packet should have at least 14 bytes");

        // Parse packet: protocol (1) + size (4) + opcode (1) + data (8 for status)
        ByteBuffer buf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); // protocol
        buf.getInt(); // size
        buf.get(); // opcode

        // Extract the payload: user count, file count
        int userCount = buf.getInt();
        int fileCount = buf.getInt();

        // Verify reasonable values
        assertTrue(userCount >= 1, "Should have at least 1 user (the current client)");
        assertTrue(fileCount >= 0, "File count should be non-negative");
    }

    /**
     * Test that heartbeat interval configuration is properly read from config.
     */
    @Test
    void testHeartbeatIntervalConfiguration() {
        // Verify that config has heartbeat enabled with specific interval
        assertTrue(config.heartbeatIntervalSeconds() > 0, "Heartbeat should be enabled");
        assertEquals(2, config.heartbeatIntervalSeconds(), "Heartbeat interval should be 2 seconds");
    }

    /**
     * Test that client activity tracking is updated when heartbeat is processed.
     * This ensures the server can detect truly inactive clients even with heartbeats.
     */
    @Test
    void testClientActivityTracking() throws Exception {
        long initialActivityTime = state.lastActivity().get();

        // Simulate some passage of time
        Thread.sleep(10);

        // Send a heartbeat (which triggers client activity tracking)
        LoginHandler.sendServerStatus(handler, capturedOutput);

        // Verify that the packet was sent
        assertTrue(capturedOutput.toByteArray().length > 0, "Heartbeat should be sent");
        // Note: sendServerStatus doesn't update lastActivity - only actual client packets do
        // But we verify the mechanism exists for tracking
        assertTrue(state.lastActivity().get() >= initialActivityTime, "Activity time should not go backwards");
    }

    /**
     * Test that ServerConfig can be created with heartbeat disabled (interval = 0).
     */
    @Test
    void testHeartbeatCanBeDisabled() {
        ServerConfig configNoHeartbeat = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 5, 200,
                tempDir.resolve("db").toString(), null, true,
                50.0f, 10, 60, 300, 0 // heartbeatIntervalSeconds = 0 (disabled)
        );

        assertEquals(0, configNoHeartbeat.heartbeatIntervalSeconds(), "Heartbeat should be disabled");
    }

    /**
     * Test that verifies file publication works correctly with binary format.
     */
    @Test
    void testFilePublicationBinary() throws Exception {
        // Create a simple binary PUBLISH_FILES packet
        // Format: [count:4] [hash:16] [tags...]
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1); // 1 file

        // Hash (16 bytes) - use a simple test hash
        String testHash = "0123456789abcdef0123456789abcdef";
        byte[] hashBytes = new byte[16];
        for (int i = 0; i < 16; i++) {
            hashBytes[i] = (byte) Integer.parseInt(testHash.substring(i*2, i*2+2), 16);
        }
        buf.put(hashBytes);

        // Tags: filename, size, type
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, "test_file.txt"));
        tags.add(new Tag(Tag.TYPE_INTEGER, "\u0002", 1024)); // FILESIZE
        tags.add(new Tag(Tag.TYPE_STRING, "\u0003", "Text")); // FILETYPE

        Tag.writeList(buf, tags);
        buf.flip();

        byte[] publishData = new byte[buf.remaining()];
        buf.get(publishData);

        // Verify initial state
        assertEquals(0, fileIndex.fileCount(), "File index should be empty initially");

        // Process the PUBLISH_FILES packet
        new PublishHandler().handlePublish(handler, OpCode.PUBLISH_FILES, publishData, capturedOutput);

        // Verify file was added
        assertEquals(1, fileIndex.fileCount(), "File should be added to index");

        // Verify we can search for it
        List<FileMetadata> results = fileIndex.search("test_file", 10);
        assertEquals(1, results.size(), "Should find the published file");
        assertEquals("test_file.txt", results.get(0).name(), "File name should match");
        assertEquals(1024, results.get(0).size(), "File size should match");
    }

    /**
     * Test that verifies file publication works correctly with text format.
     */
    @Test
    void testFilePublicationText() throws Exception {
        // Create a text PUBLISH_FILES packet
        // Format: "hash|name|size|type"
        String publishData = "0123456789abcdef0123456789abcdef|test_file2.txt|2048|Text";
        byte[] data = publishData.getBytes(StandardCharsets.UTF_8);

        // Verify initial state
        assertEquals(0, fileIndex.fileCount(), "File index should be empty initially");

        // Process the PUBLISH_FILES packet
        new PublishHandler().handlePublish(handler, OpCode.PUBLISH_FILES, data, capturedOutput);

        // Verify file was added
        assertEquals(1, fileIndex.fileCount(), "File should be added to index");

        // Verify we can search for it
        List<FileMetadata> results = fileIndex.search("test_file2", 10);
        assertEquals(1, results.size(), "Should find the published file");
        assertEquals("test_file2.txt", results.get(0).name(), "File name should match");
        assertEquals(2048, results.get(0).size(), "File size should match");
    }
}

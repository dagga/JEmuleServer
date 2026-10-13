package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.event.EventManager;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishValidationTest {

    @TempDir
    Path tempDir;

    private ClientHandler handler;
    private FileIndex index;
    private ClientState state;

    @BeforeEach
    void setup() throws IOException {
        ServerConfig config = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 5, 200, tempDir.resolve("db").toString(), null, true,
                50.0f, 10, 60, 300, 120, null
        );

        index = new FileIndex(null);
        ClientRegistry registry = new ClientRegistry();
        FloodProtector flood = new FloodProtector(config.floodMaxRequestsPerSecond());
        FakeFileDetector fakes = new FakeFileDetector();
        EventManager events = new EventManager();
        ClientFactory factory = new ClientFactory();
        
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(socket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 1234));
        
        handler = new ClientHandler(socket, config, registry, index, flood, fakes, events, factory);
        
        state = new ClientState(InetAddress.getLoopbackAddress(), 1234, 123, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        
        // Inject state using reflection since it's private and initialized in handleLogin
        try {
            var field = ClientHandler.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(handler, state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeHandlePublish(byte[] hash, String name, long size) throws Exception {
        byte[] payload = EmuleTestSupport.offerFiles(EmuleTestSupport.completeFile(hash, name, size));
        new org.jemule.network.handler.PublishHandler().handlePublish(handler, org.jemule.protocol.OpCode.OFFER_FILES, payload, new ByteArrayOutputStream());
    }

    private static final byte[] HASH = EmuleTestSupport.hash("12345678901234567890123456789012");

    @Test
    void testValidPublish() throws Exception {
        invokeHandlePublish(HASH, "test.txt", 1024);
        assertEquals(1, index.fileCount());
        assertEquals(1, state.publishedFilesCount().get());
    }

    @Test
    void testTruncatedOffer() throws Exception {
        byte[] payload = EmuleTestSupport.offerFiles(EmuleTestSupport.completeFile(HASH, "test.txt", 1024));
        byte[] truncated = java.util.Arrays.copyOf(payload, 4 + 10); // count + part of the hash
        new org.jemule.network.handler.PublishHandler().handlePublish(handler, org.jemule.protocol.OpCode.OFFER_FILES, truncated, new ByteArrayOutputStream());
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testEmptyFilename() throws Exception {
        invokeHandlePublish(HASH, "", 1024);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testZeroSize() throws Exception {
        invokeHandlePublish(HASH, "test.txt", 0);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testSizeAboveEmuleMaximum() throws Exception {
        invokeHandlePublish(HASH, "test.txt", 0x4000000001L); // > MAX_EMULE_FILE_SIZE (256 GB)
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testKeepAliveOfferIsAccepted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new org.jemule.network.handler.PublishHandler().handlePublish(handler, org.jemule.protocol.OpCode.OFFER_FILES, new byte[4], out);
        assertEquals(0, index.fileCount());
    }

    @Test
    void testFakeFileDetection() throws Exception {
        // Suspect keyword
        invokeHandlePublish(HASH, "movie_crack.exe", 1024);
        assertEquals(0, index.fileCount(), "File with 'crack' keyword should be rejected");

        // Double extension
        invokeHandlePublish(EmuleTestSupport.hash("ABCDEF1234567890ABCDEF1234567890"), "document.pdf.exe", 1024);
        assertEquals(0, index.fileCount(), "File with double extension should be rejected");
    }

    @Test
    void testChildAbuseFileIsRejectedAndBanned() throws Exception {
        invokeHandlePublish(HASH, "BOYFUCK - 6Yo boy stripped.avi", 1024);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());

        // the hash stays banned under any other name
        invokeHandlePublish(HASH, "holiday video.avi", 1024);
        assertEquals(0, index.fileCount());
    }
}

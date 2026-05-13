package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.core.event.EventManager;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
                50.0f, 10, 60, 300, 120
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

    private void invokeHandlePublish(String data) throws Exception {
        Method method = ClientHandler.class.getDeclaredMethod("handlePublish", byte[].class, java.io.OutputStream.class);
        method.setAccessible(true);
        method.invoke(handler, data.getBytes(StandardCharsets.UTF_8), new ByteArrayOutputStream());
    }

    @Test
    void testValidPublish() throws Exception {
        String validData = "12345678901234567890123456789012|test.txt|1024|text";
        invokeHandlePublish(validData);
        assertEquals(1, index.fileCount());
        assertEquals(1, state.publishedFilesCount().get());
    }

    @Test
    void testInvalidHashLength() throws Exception {
        String invalidData = "short_hash|test.txt|1024|text";
        invokeHandlePublish(invalidData);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testEmptyFilename() throws Exception {
        String invalidData = "12345678901234567890123456789012||1024|text";
        invokeHandlePublish(invalidData);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testInvalidSize() throws Exception {
        String invalidData = "12345678901234567890123456789012|test.txt|abc|text";
        invokeHandlePublish(invalidData);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testNegativeSize() throws Exception {
        String invalidData = "12345678901234567890123456789012|test.txt|-100|text";
        invokeHandlePublish(invalidData);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testMissingFields() throws Exception {
        String invalidData = "12345678901234567890123456789012|test.txt";
        invokeHandlePublish(invalidData);
        assertEquals(0, index.fileCount());
        assertEquals(0, state.publishedFilesCount().get());
    }

    @Test
    void testFakeFileDetection() throws Exception {
        // Suspect keyword
        String fakeData = "12345678901234567890123456789012|movie_crack.exe|1024|Video";
        invokeHandlePublish(fakeData);
        assertEquals(0, index.fileCount(), "File with 'crack' keyword should be rejected");
        
        // Double extension
        String fakeData2 = "ABCDEF1234567890ABCDEF1234567890|document.pdf.exe|1024|Document";
        invokeHandlePublish(fakeData2);
        assertEquals(0, index.fileCount(), "File with double extension should be rejected");
    }
}

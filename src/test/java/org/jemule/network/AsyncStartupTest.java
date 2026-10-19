package org.jemule.network;

import org.jemule.config.KadConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class AsyncStartupTest {

    @Test
    public void testAsyncStartupDoesNotBlockTcpPort(@TempDir Path tempDir) throws Exception {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of(
                "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1",
                "192.168.1.1 - 192.168.1.1 , 000 , Single IP"
        ));

        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        ServerConfig config = ServerConfig.DEFAULT
                .withPathsRelativeTo(tempDir)
                .withPort(port)
                .withAdminWebPort(0);

        Server server = new Server(config, new ClientFactory());
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                // Ignore upon stop
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            String loopback = InetAddress.getLoopbackAddress().getHostAddress();

            // TCP socket should accept connections promptly
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                boolean connected = false;
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 4000) {
                    try (Socket s = new Socket()) {
                        s.connect(new java.net.InetSocketAddress(loopback, port), 500);
                        connected = true;
                        break;
                    } catch (IOException e) {
                        Thread.sleep(50);
                    }
                }
                Assertions.assertTrue(connected, "TCP server socket should accept connections quickly without being blocked");
            });

            // Verify background IP filter loading completes
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!server.getIPFilter().isBlocked("1.2.3.4")) {
                    Thread.sleep(50);
                }
                Assertions.assertTrue(server.getIPFilter().isBlocked("1.2.3.4"));
                Assertions.assertTrue(server.getIPFilter().isBlocked("192.168.1.1"));
            });

            // Verify Kad node started
            if (server.getKadNode() != null) {
                Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!server.getKadNode().isRunning()) {
                        Thread.sleep(50);
                    }
                    Assertions.assertTrue(server.getKadNode().isRunning());
                });
            }
        } finally {
            server.stop();
        }
    }
}

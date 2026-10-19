package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AdminRemoteAccessTest {

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void adminPortAllowsConnectionWhenIpIsAllowed(@TempDir Path tempDir) throws Exception {
        int ed2kPort = findFreePort();
        int adminPort = findFreePort();

        ServerConfig config = ServerConfig.DEFAULT
                .withPort(ed2kPort)
                .withAdminPort(adminPort)
                .withAdminWebPort(0)
                .withAdminBindAddress("127.0.0.1")
                .withAdminAllowedIps("127.0.0.1")
                .withPathsRelativeTo(tempDir);

        Server server = new Server(config, new ClientFactory());
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        try {
            Socket socket = null;
            for (int i = 0; i < 20; i++) {
                try {
                    socket = new Socket("127.0.0.1", adminPort);
                    break;
                } catch (Exception e) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(socket, "Should be able to connect to admin port");

            final Socket clientSocket = socket;
            try (clientSocket;
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

                String welcome = in.readLine();
                assertNotNull(welcome, "Should receive welcome line");
                assertTrue(welcome.contains("JEmuleServer Admin Interface"), welcome);
                String hint = in.readLine();
                assertTrue(hint.contains("help"), hint);

                out.println("status");
                String line = in.readLine();
                assertNotNull(line, "Should receive status output");
                assertTrue(line.contains("Server Status") || line.contains("Version:"), line);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void adminPortRejectsConnectionWhenIpIsDenied(@TempDir Path tempDir) throws Exception {
        int ed2kPort = findFreePort();
        int adminPort = findFreePort();

        // Allow only a non-loopback IP (192.0.2.1)
        ServerConfig config = ServerConfig.DEFAULT
                .withPort(ed2kPort)
                .withAdminPort(adminPort)
                .withAdminWebPort(0)
                .withAdminBindAddress("127.0.0.1")
                .withAdminAllowedIps("192.0.2.1")
                .withPathsRelativeTo(tempDir);

        Server server = new Server(config, new ClientFactory());
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        try {
            Socket socket = null;
            for (int i = 0; i < 20; i++) {
                try {
                    socket = new Socket("127.0.0.1", adminPort);
                    break;
                } catch (Exception e) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(socket, "TCP handshake may succeed before filter disconnects");

            final Socket clientSocket = socket;
            try (clientSocket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {
                // Since 127.0.0.1 is denied, server should close the socket immediately without sending output or responding
                String line = in.readLine();
                assertNull(line, "Socket should be closed by server for unauthorized client IP");
            }
        } finally {
            server.stop();
        }
    }
}

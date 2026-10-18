package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.config.ServerConfig;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWebPanelTest {
    @Test
    void panelIsLoopbackOnlyAndProvidesServerSideClientSearch() throws Exception {
        Server server = mock(Server.class);
        ClientRegistry registry = mock(ClientRegistry.class);
        FileIndex index = mock(FileIndex.class);
        IPFilter filter = mock(IPFilter.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getClientRegistry()).thenReturn(registry);
        when(server.getFileIndex()).thenReturn(index);
        when(server.getIPFilter()).thenReturn(filter);
        when(server.getFakeFileDetector()).thenReturn(detector);

        ClientState alice = new ClientState(InetAddress.getLoopbackAddress(), 4662, 1234, System.currentTimeMillis(),
                new AtomicLong(System.currentTimeMillis()));
        alice.setNickname("alice");
        when(registry.getAllClients()).thenReturn(List.of(alice));
        when(registry.size()).thenReturn(1);
        when(registry.lowIdCount()).thenReturn(1);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            assertTrue(panel.isLoopbackBound());
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/clients?q=alice");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"total\":1"), response.body());
            assertTrue(response.body().contains("\"nickname\":\"alice\""), response.body());

            HttpResponse<String> noMatch = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(uri.toString().replace("alice", "missing"))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, noMatch.statusCode());
            assertTrue(noMatch.body().contains("\"total\":0"), noMatch.body());
        }
    }

    @Test
    void rejectsCrossOriginAdministrativeMutations() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getFakeFileDetector()).thenReturn(detector);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/settings");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "https://attacker.example")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("fakeFileDetectionEnabled=false"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("\"error\":\"A loopback Origin header is required\""), response.body());
            verify(detector, never()).setEnabled(false);
        }
    }

    @Test
    void sameOriginCanChangeTheRuntimeFakeDetectionSetting() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(detector.isEnabled()).thenReturn(true);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            when(server.getConfig()).thenReturn(ServerConfig.DEFAULT.withAdminWebPort(panel.port()));
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/settings");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("fakeFileDetectionEnabled=true"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"fakeFileDetectionEnabled\":true"), response.body());
            verify(detector).setEnabled(true);
        }
    }

    @Test
    void sameOriginCanPersistentlyBanAValidFileHash() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        FileIndex index = mock(FileIndex.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(server.getFileIndex()).thenReturn(index);
        String hash = "0123456789ABCDEF0123456789ABCDEF";

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/blocks");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("hash=" + hash + "&reason=test"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"banned\":\"" + hash + "\""), response.body());
            verify(detector).ban(hash, "Admin web panel: test");
            verify(index).removeFile(hash);
        }
    }

}

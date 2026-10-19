package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.config.ServerConfig;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadId;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadRoutingTable;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertFalse(panel.isLoopbackBound());
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
    void peersExposeVerifiedServerPeersAndKadContacts() throws Exception {
        Server server = mock(Server.class);
        when(server.getVerifiedServerPeers()).thenReturn(List.of());
        KadNode kadNode = mock(KadNode.class);
        KadRoutingTable routingTable = mock(KadRoutingTable.class);
        KadContact contact = new KadContact(KadId.random(), InetAddress.getLoopbackAddress(), 4672, 4662, (byte) 8);
        when(kadNode.getRoutingTable()).thenReturn(routingTable);
        when(routingTable.getAllContacts()).thenReturn(List.of(contact));
        when(server.getKadNode()).thenReturn(kadNode);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/peers");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"type\":\"Kad contact\""), response.body());
            assertTrue(response.body().contains("\"udpPort\":4672"), response.body());
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
            assertTrue(response.body().contains("\"error\":\"A valid Origin header is required\""), response.body());
            verify(detector, never()).setEnabled(false);
        }
    }

    @Test
    void sameOriginCanChangeTheRuntimeFakeDetectionSetting() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(detector.isEnabled()).thenReturn(true);
        when(server.updateConfig(java.util.Map.of("fakeFileDetectionEnabled", "true")))
                .thenReturn(org.jemule.config.ConfigApplyResult.builder()
                        .applied("fakeFileDetectionEnabled").build());

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
            assertTrue(response.body().contains("\"applied\":[\"fakeFileDetectionEnabled\"]"), response.body());
            verify(server).updateConfig(java.util.Map.of("fakeFileDetectionEnabled", "true"));
        }
    }

    @Test
    void settingsListsEveryEditableParameter() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getConfig()).thenReturn(ServerConfig.DEFAULT.withAdminWebPort(0));
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(detector.isEnabled()).thenReturn(true);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + "/api/settings"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            for (org.jemule.config.ConfigParameter parameter : org.jemule.config.ConfigParameter.ordered()) {
                assertTrue(response.body().contains("\"key\":\"" + parameter.key() + "\""), parameter.key());
            }
            assertTrue(response.body().contains("\"scope\":\"hot\""), response.body());
        }
    }

    @Test
    void reloadsTheConfigurationFromDisk() throws Exception {
        Server server = mock(Server.class);
        when(server.reloadConfig()).thenReturn(org.jemule.config.ConfigApplyResult.builder()
                .applied("maxUsers").deferred("port").build());

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/settings/reload");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"applied\":[\"maxUsers\"]"), response.body());
            assertTrue(response.body().contains("\"deferred\":[\"port\"]"), response.body());
            verify(server).reloadConfig();
        }
    }

    @Test
    void sameOriginCanKickAClient() throws Exception {
        Server server = mock(Server.class);
        when(server.kickClient("12345")).thenReturn(true);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/kick");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("clientId=12345"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"kicked\":1"), response.body());
            verify(server).kickClient("12345");
        }
    }

    @Test
    void sameOriginCanUnbanAHash() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        String hash = "0123456789ABCDEF0123456789ABCDEF";
        when(detector.unban(hash)).thenReturn(true);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/unblock");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("hash=" + hash))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"unbanned\":\"" + hash + "\""), response.body());
            verify(detector).unban(hash);
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

    @Test
    void rejectsRequestsFromDeniedIpAddress() throws Exception {
        Server server = mock(Server.class);
        org.jemule.security.AdminAccessFilter filter = new org.jemule.security.AdminAccessFilter("192.168.1.10", "");
        when(server.getAdminAccessFilter()).thenReturn(filter);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/status");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("Access denied by admin access filter"), response.body());
        }
    }

    @Test
    void allowsRequestsFromAllowedIpAddress() throws Exception {
        Server server = mock(Server.class);
        org.jemule.security.AdminAccessFilter filter = new org.jemule.security.AdminAccessFilter("127.0.0.1", "");
        when(server.getAdminAccessFilter()).thenReturn(filter);
        when(server.getClientRegistry()).thenReturn(mock(ClientRegistry.class));
        when(server.getFileIndex()).thenReturn(mock(FileIndex.class));
        when(server.getIPFilter()).thenReturn(mock(IPFilter.class));

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/status");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"version\""), response.body());
        }
    }
}

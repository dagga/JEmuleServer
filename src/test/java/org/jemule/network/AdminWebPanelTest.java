package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
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
    void serversViewExposesKnownServersAndState() throws Exception {
        Server server = mock(Server.class);
        ServerGossip.ServerView view = new ServerGossip.ServerView("Verified eD2K", "verified",
                ServerGossip.ServerSource.SERVER_MET,
                new ServerGossip.Endpoint(InetAddress.getLoopbackAddress(), 4661),
                120L, 340L, 500L, 4000L, 6000L, 7L, true, 12L,
                System.currentTimeMillis(), System.currentTimeMillis(), 0);
        when(server.getServerViews()).thenReturn(List.of(view));

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/servers");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"state\":\"verified\""), response.body());
            assertTrue(response.body().contains("\"source\":\"server_met\""), response.body());
            assertTrue(response.body().contains("\"users\":120"), response.body());
            assertTrue(response.body().contains("\"latencyMs\":12"), response.body());
        }
    }

    @Test
    void ingestionViewReportsNewFilesByChannel() throws Exception {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "client.avi", 100, "Video"),
                FileSource.CLIENT_PUSH);
        index.addFile(new FileMetadata("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "kad.avi", 100, "Video"),
                FileSource.KAD_SEARCH);
        Server server = mock(Server.class);
        when(server.getFileIndex()).thenReturn(index);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/ingestion");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"source\":\"client push\""), response.body());
            assertTrue(response.body().contains("\"source\":\"Kad search\""), response.body());
            assertTrue(response.body().contains("\"newFiles\":2"), response.body());
            assertTrue(response.body().contains("\"source\":\"total\""), response.body());
        }
    }

    @Test
    void searchesViewReportsKadAndServerQueriesAndResults() throws Exception {
        Server server = mock(Server.class);
        org.jemule.kad.KadSearchBridge kad = mock(org.jemule.kad.KadSearchBridge.class);
        when(kad.getSearchesForked()).thenReturn(12L);
        when(kad.getRequestsSent()).thenReturn(36L);
        when(kad.getResultsReceived()).thenReturn(48L);
        when(kad.getFilesIngested()).thenReturn(20L);
        when(kad.getActiveSessionCount()).thenReturn(2);
        when(kad.getIllicitFilesBlocked()).thenReturn(1L);
        when(server.getKadSearchBridge()).thenReturn(kad);
        ServerSearchBridge servers = mock(ServerSearchBridge.class);
        when(servers.getSearchesForked()).thenReturn(5L);
        when(servers.getRequestsSent()).thenReturn(15L);
        when(servers.getResultsReceived()).thenReturn(30L);
        when(servers.getFilesIngested()).thenReturn(9L);
        when(servers.getActiveSessionCount()).thenReturn(1);
        when(server.getServerSearchBridge()).thenReturn(servers);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/searches");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"channel\":\"Kad\""), response.body());
            assertTrue(response.body().contains("\"channel\":\"eD2K servers\""), response.body());
            assertTrue(response.body().contains("\"queries\":12"), response.body());
            assertTrue(response.body().contains("\"results\":48"), response.body());
            assertTrue(response.body().contains("\"channel\":\"UDP global searches (Kad)\""), response.body());
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
    void sameOriginCanUnbanAName() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = mock(FakeFileDetector.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(detector.unban("Bad File.avi")).thenReturn(true);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/unblock");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("name=Bad+File.avi"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"unbanned\":\"bad file.avi\""), response.body());
            assertTrue(response.body().contains("\"wasBanned\":true"), response.body());
            verify(detector).unban("Bad File.avi");
        }
    }

    @Test
    void sameOriginCanPersistentlyBanAFileNameAndExportTheBannedNames() throws Exception {
        Server server = mock(Server.class);
        FakeFileDetector detector = new FakeFileDetector();
        FileIndex index = mock(FileIndex.class);
        when(server.getFakeFileDetector()).thenReturn(detector);
        when(server.getFileIndex()).thenReturn(index);
        when(index.removeFilesNamed("bad file.avi")).thenReturn(3);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI uri = URI.create("http://127.0.0.1:" + panel.port() + "/api/blocks");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("name=+Bad+File.avi&reason=test"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"banned\":\"bad file.avi\""), response.body());
            assertTrue(response.body().contains("\"filesRemoved\":3"), response.body());
            verify(index).removeFilesNamed("bad file.avi");
            detector.ban("Another.mp3", "test");

            HttpResponse<String> export = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/blocks/export")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, export.statusCode());
            assertEquals("another.mp3\nbad file.avi\n", export.body());
            assertTrue(export.headers().firstValue("Content-Disposition").orElse("").contains("banned-names.txt"));

            HttpResponse<String> blank = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri)
                    .header("Origin", "http://127.0.0.1:" + panel.port())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("name=+++")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, blank.statusCode());
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

    @Test
    void overviewAndHistoryGiveTheHeadlineFiguresAndTheSamples() throws Exception {
        Server server = mock(Server.class);
        ClientRegistry registry = mock(ClientRegistry.class);
        FileIndex index = mock(FileIndex.class);
        when(server.getClientRegistry()).thenReturn(registry);
        when(server.getFileIndex()).thenReturn(index);
        when(server.getIPFilter()).thenReturn(mock(IPFilter.class));
        when(registry.size()).thenReturn(4);
        when(registry.lowIdCount()).thenReturn(1);
        when(index.fileCount()).thenReturn(1_500);
        long now = System.currentTimeMillis();
        org.jemule.core.MetricsHistory history = new org.jemule.core.MetricsHistory(null);
        history.record(now - 2 * 24 * 3_600_000L, 1_000, 2);
        history.record(now - 3_600_000L, 1_400, 3);
        when(server.getMetricsHistory()).thenReturn(history);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            HttpResponse<String> overview = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/overview")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overview.statusCode(), overview.body());
            assertTrue(overview.body().contains("\"indexed\":1500"), overview.body());
            assertTrue(overview.body().contains("\"ago24h\":1000"), "the sample of 2 days ago: " + overview.body());
            assertTrue(overview.body().contains("\"ago7d\":null"), "no sample a week ago: " + overview.body());
            assertTrue(overview.body().contains("\"connected\":4"), overview.body());

            HttpResponse<String> samples = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/history?days=1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, samples.statusCode(), samples.body());
            assertTrue(samples.body().startsWith("{\"intervalSeconds\":300,\"points\":[["), samples.body());
            assertTrue(samples.body().contains(",1400,3]"), "only the sample of the last day: " + samples.body());
            assertFalse(samples.body().contains(",1000,2]"), samples.body());
        }
    }
}

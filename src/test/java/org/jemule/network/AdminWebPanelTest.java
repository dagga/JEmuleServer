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
import org.jemule.security.ServerBans;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /** A form POST of {@code origin} (null: no Origin header). */
    private static HttpResponse<String> post(AdminWebPanel panel, String path, String form, String origin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + panel.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (origin != null) request.header("Origin", origin);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(AdminWebPanel panel, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + panel.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sameOriginCanBanListAndUnbanAServer() throws Exception {
        Server server = mock(Server.class);
        ServerBans bans = new ServerBans();
        when(server.getServerBans()).thenReturn(bans);
        when(server.banServer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> bans.ban(call.getArgument(0), call.getArgument(1), 1_700_000_000_123L));
        when(server.unbanServer(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> bans.unban(call.getArgument(0)));
        ServerGossip.ServerView view = new ServerGossip.ServerView("Listed", "verified",
                ServerGossip.ServerSource.GOSSIP, new ServerGossip.Endpoint(InetAddress.getByName("198.51.100.9"), 4661),
                null, null, null, null, null, null, false, null, null, null, 0);
        when(server.getServerViews()).thenReturn(List.of(view));

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            String origin = "http://127.0.0.1:" + panel.port();
            assertEquals("[]", get(panel, "/api/servers/bans").body());

            HttpResponse<String> banned = post(panel, "/api/servers/ban", "ip=+192.0.2.10&reason=fake+files", origin);
            assertEquals(200, banned.statusCode(), banned.body());
            assertEquals("{\"banned\":\"192.0.2.10\"}", banned.body());
            verify(server).banServer(ServerBans.parse("192.0.2.10"), "fake files");

            HttpResponse<String> list = get(panel, "/api/servers/bans");
            assertEquals(200, list.statusCode());
            assertEquals("[{\"ip\":\"192.0.2.10\",\"reason\":\"fake files\",\"bannedAt\":1700000000123}]",
                    list.body());
            assertTrue(get(panel, "/api/servers").body().contains("\"banned\":false"), "the field of each server");

            HttpResponse<String> unbanned = post(panel, "/api/servers/unban", "ip=192.0.2.10", origin);
            assertEquals(200, unbanned.statusCode());
            assertEquals("{\"unbanned\":\"192.0.2.10\",\"wasBanned\":true}", unbanned.body());
            assertTrue(post(panel, "/api/servers/unban", "ip=192.0.2.10", origin).body()
                    .contains("\"wasBanned\":false"));
            assertEquals("[]", get(panel, "/api/servers/bans").body());
        }
    }

    @Test
    void aServerBanNeedsAnIpv4AddressAShortReasonAndTheSameOrigin() throws Exception {
        Server server = mock(Server.class);
        when(server.getServerBans()).thenReturn(new ServerBans());

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            String origin = "http://127.0.0.1:" + panel.port();
            for (String form : List.of("", "ip=", "ip=example.org", "ip=192.0.2.300", "ip=192.0.2.1:4661", "ip=::1")) {
                HttpResponse<String> response = post(panel, "/api/servers/ban", form, origin);
                assertEquals(400, response.statusCode(), form);
                assertTrue(response.body().contains("\"error\""), response.body());
                assertEquals(400, post(panel, "/api/servers/unban", form, origin).statusCode(), form);
            }
            assertEquals(400, post(panel, "/api/servers/ban", "ip=192.0.2.1&reason=" + "x".repeat(241), origin)
                    .statusCode());
            assertEquals(403, post(panel, "/api/servers/ban", "ip=192.0.2.1", null).statusCode(), "no Origin");
            assertEquals(403, post(panel, "/api/servers/ban", "ip=192.0.2.1", "https://attacker.example")
                    .statusCode());
            assertEquals(403, post(panel, "/api/servers/unban", "ip=192.0.2.1", null).statusCode());
            verify(server, never()).banServer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(server, never()).unbanServer(org.mockito.ArgumentMatchers.any());
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
    void theExportStreamsEveryBannedNameOfTheDatabase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Server server = mock(Server.class);
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dir.resolve("db").toString());
             AdminWebPanel panel = new AdminWebPanel(server)) {
            when(server.getDatabaseManager()).thenReturn(db);
            for (int i = 0; i < 12_000; i++) db.addBannedNameIfAbsent(String.format("name %05d.avi", i), "Child abuse material");
            db.addBannedName("admin ban.avi", "Admin web panel");
            panel.start(0);

            HttpResponse<String> export = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/blocks/export")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, export.statusCode());
            String[] lines = export.body().split("\n");
            assertEquals(12_001, lines.length, "every name, over several pages");
            assertEquals("admin ban.avi", lines[0]);
            assertEquals("name 11999.avi", lines[12_000]);
        }
    }

    @Test
    void theNameCleaningApiListsAddsAndRemovesRulesAndRunsTheJob(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Server server = mock(Server.class);
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dir.resolve("db").toString());
             FileIndex index = new FileIndex(db);
             AdminWebPanel panel = new AdminWebPanel(server)) {
            db.saveFiles(List.of(new FileMetadata("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Song [kad emule-qt.org].mp3", 1,
                    "Audio")));
            org.jemule.core.NameCleaner cleaner = org.jemule.core.NameCleaner.load(db);
            index.setNameCleaner(cleaner);
            org.jemule.core.NameCleaningJob job = new org.jemule.core.NameCleaningJob(db, index, cleaner);
            when(server.getNameCleaner()).thenReturn(cleaner);
            when(server.getNameCleaningJob()).thenReturn(job);
            index.addFile(new FileMetadata("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "[web emule-qt.org] Clip.avi", 1, "Video"));
            panel.start(0);
            String base = "http://127.0.0.1:" + panel.port();

            HttpResponse<String> listed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create(base + "/api/namecleaning")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().matches("\\{\"rules\":\\[\\{\"text\":\"emule-qt\\.org\",\"addedAt\":\\d+}],"
                    + "\"cleanedAtIngestion\":1,\"job\":\\{\"state\":\"idle\",\"scanned\":0,\"renamed\":0,"
                    + "\"startedAt\":0,\"finishedAt\":0,\"lastError\":\"\"}}"), listed.body());

            HttpResponse<String> added = post(panel, "/api/namecleaning", "action=add&text=+%5BSome+Tag%5D+");
            assertEquals(200, added.statusCode());
            assertTrue(added.body().contains("{\"text\":\"[some tag]\",\"addedAt\":"), added.body());
            assertTrue(added.body().contains("\"job\":{\"state\":\"idle\""), added.body());
            assertEquals(List.of("emule-qt.org", "[some tag]"),
                    db.loadNameCleaningRules().stream().map(r -> r.text()).toList(), "saved");

            HttpResponse<String> removed = post(panel, "/api/namecleaning", "action=remove&text=%5Bsome+tag%5D");
            assertEquals(200, removed.statusCode());
            assertFalse(removed.body().contains("[some tag]"), removed.body());
            assertEquals(1, db.loadNameCleaningRules().size());

            assertEquals(400, post(panel, "/api/namecleaning", "action=add&text=+++").statusCode());
            assertEquals(400, post(panel, "/api/namecleaning", "action=add&text=" + "x".repeat(121)).statusCode());
            assertEquals(200, post(panel, "/api/namecleaning", "action=add&text=" + "x".repeat(120)).statusCode());
            assertEquals(400, post(panel, "/api/namecleaning", "action=rename&text=abc").statusCode());
            assertEquals(400, post(panel, "/api/namecleaning/apply", "action=pause").statusCode());

            HttpResponse<String> started = post(panel, "/api/namecleaning/apply", "action=start");
            assertEquals(200, started.statusCode());
            assertTrue(started.body().startsWith("{\"state\":\""), started.body());
            for (int i = 0; i < 300 && job.status().state() == org.jemule.core.NameCleaningJob.State.RUNNING; i++) {
                Thread.sleep(20);
            }
            HttpResponse<String> stopped = post(panel, "/api/namecleaning/apply", "action=stop");
            assertTrue(stopped.body().matches("\\{\"state\":\"done\",\"scanned\":2,\"renamed\":1,"
                    + "\"startedAt\":\\d+,\"finishedAt\":\\d+,\"lastError\":\"\"}"), stopped.body());
            assertEquals(List.of("Clip.avi", "Song.mp3"),
                    db.loadFiles().stream().map(FileMetadata::name).sorted().toList());

            HttpRequest crossOrigin = HttpRequest.newBuilder(URI.create(base + "/api/namecleaning/apply"))
                    .header("Origin", "http://evil.example")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("action=start")).build();
            assertEquals(403, HttpClient.newHttpClient().send(crossOrigin, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
        }
    }

    private static HttpResponse<String> post(AdminWebPanel panel, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + panel.port() + path))
                .header("Origin", "http://127.0.0.1:" + panel.port())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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

    @Test
    void closingDoesNotWaitForARequestBlockedForEver() throws Exception {
        Server server = mock(Server.class);
        FileIndex index = mock(FileIndex.class);
        when(server.getFileIndex()).thenReturn(index);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        // Blocked as on the index lock of 1.2.29: not even an interruption ends the wait
        when(index.fileCount()).thenAnswer(invocation -> {
            entered.countDown();
            while (released.getCount() > 0) {
                try {
                    released.await();
                } catch (InterruptedException ignored) {
                    // keeps waiting
                }
            }
            return 0;
        });
        try {
            AdminWebPanel panel = new AdminWebPanel(server);
            panel.start(0);
            HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/overview")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the request never reached the index");
            long start = System.nanoTime();
            CompletableFuture.runAsync(panel::close).get(AdminWebPanel.CLOSE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(AdminWebPanel.CLOSE_TIMEOUT_SECONDS - 1));
        } finally {
            released.countDown();
        }
    }

    @Test
    void theRootServesTheExportedPanelWithItsSecurityHeaders() throws Exception {
        Server server = mock(Server.class);
        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + panel.port();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
            assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
            String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(policy.contains("frame-ancestors 'none'"), policy);
            assertFalse(policy.matches(".*script-src[^;]*'unsafe-inline'.*"), "inline scripts by hash only: " + policy);
            assertEquals(AdminWebPanel.pageSecurityPolicy(page.body()), policy);
            assertTrue(page.body().contains("<title>JEmuleServer admin</title>"), page.body());

            // Every script and stylesheet of the page is served from the root, with a long cache
            java.util.regex.Matcher assets = java.util.regex.Pattern.compile("(?:src|href)=\"(/_next/static/[^\"]+)\"")
                    .matcher(page.body());
            int count = 0;
            while (assets.find()) {
                HttpResponse<String> asset = client.send(HttpRequest.newBuilder(URI.create(base + assets.group(1))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, asset.statusCode(), assets.group(1));
                assertTrue(asset.headers().firstValue("Cache-Control").orElse("").contains("immutable"), assets.group(1));
                String type = asset.headers().firstValue("Content-Type").orElse("");
                assertTrue(type.startsWith(assets.group(1).endsWith(".css") ? "text/css" : "text/javascript"), assets.group(1) + ": " + type);
                count++;
            }
            assertTrue(count > 0, "no asset in the page");
        }
    }

    @Test
    void viewsAreDirectoriesOfTheExportAndUnknownPathsGetThe404Page() throws Exception {
        Server server = mock(Server.class);
        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + panel.port();
            HttpResponse<String> view = client.send(HttpRequest.newBuilder(URI.create(base + "/clients/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, view.statusCode());
            assertTrue(view.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

            HttpResponse<String> head = client.send(HttpRequest.newBuilder(URI.create(base + "/clients/"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());

            HttpResponse<String> noSlash =client.send(HttpRequest.newBuilder(URI.create(base + "/settings")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(308, noSlash.statusCode());
            assertEquals("/settings/", noSlash.headers().firstValue("Location").orElse(""));

            HttpResponse<String> payload = client.send(HttpRequest.newBuilder(URI.create(base + "/clients/index.txt")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, payload.statusCode());
            assertTrue(payload.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));

            HttpResponse<String> unknown = client.send(HttpRequest.newBuilder(URI.create(base + "/no-such-view/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
            assertTrue(unknown.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(unknown.body().contains("Page not found"), unknown.body());

            HttpResponse<String> missingFile = client.send(HttpRequest.newBuilder(URI.create(base + "/_next/static/missing.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missingFile.statusCode());

            // The API keeps its JSON answers
            HttpResponse<String> api = client.send(HttpRequest.newBuilder(URI.create(base + "/api/missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, api.statusCode());
            assertTrue(api.body().contains("\"error\":\"Not found\""), api.body());
            assertTrue(api.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));

            HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                    .header("Origin", base).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, post.statusCode(), "the panel's files are only read");
        }
    }

    @Test
    void thePanelFilesStayInsideTheExport() {
        assertEquals("index.html", AdminWebPanel.uiResource("/"));
        assertEquals("clients/index.html", AdminWebPanel.uiResource("/clients/"));
        assertEquals("_next/static/chunks/a.js", AdminWebPanel.uiResource("/_next/static/chunks/a.js"));
        assertEquals(null, AdminWebPanel.uiResource("/../server.properties"));
        assertEquals(null, AdminWebPanel.uiResource("/clients/../../x.html"));
        assertEquals(null, AdminWebPanel.uiResource("/a\\b.js"));
        assertTrue(AdminWebPanel.contentType("x/y.woff2").equals("font/woff2"));
        assertTrue(AdminWebPanel.contentType("icon.svg").equals("image/svg+xml"));
    }

    @Test
    void inlineScriptsAreAllowedByTheirHash() throws Exception {
        String script = "self.__next_f.push([1,\"x\"])";
        String hash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String policy = AdminWebPanel.pageSecurityPolicy("<html><script src=\"/_next/static/a.js\" async></script>"
                + "<script>" + script + "</script></html>");
        assertTrue(policy.contains("script-src 'self' 'sha256-" + hash + "';"), policy);
        assertTrue(policy.contains("frame-ancestors 'none'"), policy);
    }
}

package org.jemule.network;

import org.jemule.config.ConfigApplyResult;
import org.jemule.config.ConfigFile;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigAdminTest {
    @TempDir
    Path tempDir;

    private Server server(Path configFile) {
        Properties properties = new Properties();
        properties.setProperty("databasePath", tempDir.resolve("db").toString());
        properties.setProperty("ipFilterPath", "");
        properties.setProperty("kadEnabled", "false");
        properties.setProperty("adminPort", "0");
        properties.setProperty("adminWebPort", "0");
        properties.setProperty("port", "4661");
        Server server = new Server(ServerConfig.fromProperties(properties), new ClientFactory());
        if (configFile != null) server.setConfigFile(new ConfigFile(configFile));
        return server;
    }

    @Test
    void updateAppliesHotParametersAndWritesTheFile() throws Exception {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "# keep this comment\nmaxUsers=100\nport=4661\n");
        Server server = server(file);
        try {
            ConfigApplyResult result = server.updateConfig(Map.of("maxUsers", "250"));

            assertTrue(result.success(), result.summary());
            assertTrue(result.applied().contains("maxUsers"), result.summary());
            assertEquals(250, server.configSnapshot().maxUsers());
            String content = Files.readString(file);
            assertTrue(content.contains("# keep this comment"), content);
            assertTrue(content.contains("maxUsers=250"), content);
            assertEquals(250, Integer.parseInt(properties(file).getProperty("maxUsers")));
        } finally {
            server.stop();
        }
    }

    @Test
    void aNewMaxFilesAppliesToTheIndexAtOnce() throws Exception {
        // 1.2.24 kept the maxFiles of the start: raised from 1,200,000 to 3,000,000 with 1,521,140 files indexed,
        // the index still refused every new file
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "maxFiles=2\n");
        Server server = server(file);
        try {
            assertTrue(server.updateConfig(Map.of("maxFiles", "2")).success());
            server.getFileIndex().addFile(new org.jemule.core.FileMetadata("a".repeat(32), "one.avi", 1, "Video"));
            server.getFileIndex().addFile(new org.jemule.core.FileMetadata("b".repeat(32), "two.avi", 1, "Video"));
            assertTrue(server.getFileIndex().isFull());

            assertTrue(server.updateConfig(Map.of("maxFiles", "3")).success());
            assertFalse(server.getFileIndex().isFull(), "the index follows the new maxFiles");
            assertTrue(server.getFileIndex().addFile(new org.jemule.core.FileMetadata("c".repeat(32), "three.avi", 1,
                    "Video")) != null);
        } finally {
            server.stop();
        }
    }

    @Test
    void invalidValueLeavesTheConfigurationUntouched() {
        Server server = server(null);
        try {
            int before = server.configSnapshot().maxUsers();
            ConfigApplyResult result = server.updateConfig(Map.of("maxUsers", "-5"));

            assertFalse(result.success());
            assertTrue(result.errors().getFirst().contains("maxUsers"), result.summary());
            assertEquals(before, server.configSnapshot().maxUsers());
        } finally {
            server.stop();
        }
    }

    @Test
    void reloadAppliesTheFileWithoutRestarting() throws Exception {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "maxSearchResults=123\nport=4661\n");
        Server server = server(file);
        try {
            Files.writeString(file, "maxSearchResults=321\nport=4661\n");
            ConfigApplyResult result = server.reloadConfig();

            assertTrue(result.success(), result.summary());
            assertTrue(result.applied().contains("maxSearchResults"), result.summary());
            assertEquals(321, server.configSnapshot().maxSearchResults());
        } finally {
            server.stop();
        }
    }

    @Test
    void restartScopedReloadKeepsTheRunningValueUntilRestart() throws Exception {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "port=4661\n");
        Server server = server(file);
        try {
            Files.writeString(file, "port=4662\n");
            ConfigApplyResult result = server.reloadConfig();

            assertTrue(result.success(), result.summary());
            assertTrue(result.deferred().contains("port"), result.summary());
            assertEquals(4661, server.configSnapshot().port());
            assertEquals("4662", server.pendingRestart().get("port"));

            Files.writeString(file, "port=4661\n");
            server.reloadConfig();
            assertFalse(server.pendingRestart().containsKey("port"), server.pendingRestart().toString());
        } finally {
            server.stop();
        }
    }

    @Test
    void restartScopedUpdateIsWrittenButNotApplied() throws Exception {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "port=4661\n");
        Server server = server(file);
        try {
            ConfigApplyResult result = server.updateConfig(Map.of("kadEnabled", "true"));

            assertTrue(result.deferred().contains("kadEnabled"), result.summary());
            assertFalse(server.configSnapshot().kad().enabled());
            assertEquals("true", properties(file).getProperty("kadEnabled"));
        } finally {
            server.stop();
        }
    }

    @Test
    void unchangedValueIsNotReported() {
        Server server = server(null);
        try {
            int current = server.configSnapshot().maxUsers();
            ConfigApplyResult result = server.updateConfig(Map.of("maxUsers", String.valueOf(current)));

            assertTrue(result.success(), result.summary());
            assertTrue(result.applied().isEmpty() && result.deferred().isEmpty(), result.summary());
        } finally {
            server.stop();
        }
    }

    @Test
    void reloadResolvesRelativePathsAgainstTheDataDirectory() throws Exception {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, "port=4661\ndatabasePath=db\nmaxUsers=10\n");
        Server server = server(null);
        server.setConfigFile(new ConfigFile(file, tempDir));
        try {
            ConfigApplyResult result = server.reloadConfig();

            assertTrue(result.success(), result.summary());
            assertFalse(result.deferred().contains("databasePath"), result.summary());
            assertEquals(10, server.configSnapshot().maxUsers());
        } finally {
            server.stop();
        }
    }

    @Test
    void childAbuseKeywordsAreReloadedWithoutRestart() throws Exception {
        Path keywords = tempDir.resolve("keywords.properties");
        Files.writeString(keywords, "banned=forbiddenword\nminor=\nsexual=\n");
        Server server = server(null);
        try {
            assertFalse(server.getFileIndex().isChildAbuse("0123456789ABCDEF0123456789ABCDEF", "forbiddenword movie.avi"));
            ConfigApplyResult result = server.updateConfig(Map.of("childAbuseKeywordsPath", keywords.toString()));

            assertTrue(result.applied().contains("childAbuseKeywordsPath"), result.summary());
            assertTrue(server.getFileIndex().isChildAbuse("FEDCBA9876543210FEDCBA9876543210", "forbiddenword movie.avi"));
        } finally {
            server.stop();
        }
    }

    private static Properties properties(Path file) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}

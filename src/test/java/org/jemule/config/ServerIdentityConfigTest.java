package org.jemule.config;

import org.jemule.Main;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ServerIdentityConfigTest {

    @Test
    void defaultsToTheJEmuleServerNameAndDescription() {
        assertEquals(Main.SERVER_NAME, ServerConfig.DEFAULT.serverName());
        assertEquals(Main.SERVER_DESCRIPTION, ServerConfig.DEFAULT.serverDescription());
        ServerConfig config = ServerConfig.fromProperties(new Properties());
        assertEquals(Main.SERVER_NAME, config.serverName(), "a configuration file without the key keeps the default");
        assertEquals(Main.SERVER_DESCRIPTION, config.serverDescription());
    }

    @Test
    void anEmptyValueMeansTheDefault() {
        Properties props = new Properties();
        props.setProperty("serverName", "  ");
        props.setProperty("serverDescription", "");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(Main.SERVER_NAME, config.serverName());
        assertEquals(Main.SERVER_DESCRIPTION, config.serverDescription());
    }

    @Test
    void isReadAndWrittenWithTheOtherParameters() {
        Properties props = new Properties();
        props.setProperty("serverName", " My eD2K server ");
        props.setProperty("serverDescription", "Files for everyone, éàü");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals("My eD2K server", config.serverName());
        assertEquals("Files for everyone, éàü", config.serverDescription());
        assertEquals("My eD2K server", config.toProperties().get("serverName"));
        assertEquals("Files for everyone, éàü", config.value(ConfigParameter.SERVER_DESCRIPTION));
    }

    @Test
    void isKeptByTheWithers() {
        ServerConfig config = ServerConfig.DEFAULT.withServerIdentity("Name", "Description");
        ServerConfig changed = config.withPort(4662).withAdminPort(4000).withKad(KadConfig.DEFAULT)
                .withRelay("", RelayFeedConfig.DEFAULT).withRemoteSearchCacheMinutes(5).withFileRetentionDays(30)
                .withIndexRefreshSeconds(20).withPathsRelativeTo(java.nio.file.Path.of("/tmp"));
        assertEquals("Name", changed.serverName());
        assertEquals("Description", changed.serverDescription());
    }

    @Test
    void areHotParametersWithoutControlCharactersOf255BytesAtMost() {
        for (ConfigParameter parameter : new ConfigParameter[]{
                ConfigParameter.SERVER_NAME, ConfigParameter.SERVER_DESCRIPTION}) {
            assertSame(parameter, ConfigParameter.byKey(parameter.key()));
            assertEquals(ConfigParameter.Scope.HOT, parameter.scope());
            assertEquals(ConfigParameter.Type.STRING, parameter.type());
            parameter.validate("");
            parameter.validate("A server (no pedo) - https://example.org/");
            parameter.validate("a".repeat(255));
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("a".repeat(256)));
            // 128 two-byte characters: 256 bytes in UTF-8
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("é".repeat(128)));
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("two\nlines"));
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("tab\there"));
        }
    }
}

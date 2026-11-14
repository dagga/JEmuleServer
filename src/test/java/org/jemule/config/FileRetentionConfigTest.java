package org.jemule.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FileRetentionConfigTest {

    @Test
    void defaultsToSixMonths() {
        assertEquals(180, ServerConfig.DEFAULT.fileRetentionDays());
        assertEquals(180, ServerConfig.fromProperties(new Properties()).fileRetentionDays(),
                "a configuration file without the key keeps the default");
    }

    @Test
    void isReadAndWrittenWithTheOtherParameters() {
        Properties props = new Properties();
        props.setProperty("fileRetentionDays", " 365 ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(365, config.fileRetentionDays());
        assertEquals("365", config.toProperties().get("fileRetentionDays"));
        assertEquals("365", config.value(ConfigParameter.FILE_RETENTION_DAYS));

        props.setProperty("fileRetentionDays", "0");
        assertEquals(0, ServerConfig.fromProperties(props).fileRetentionDays(), "0 never purges");
    }

    @Test
    void isKeptByTheWithers() {
        ServerConfig config = ServerConfig.DEFAULT.withFileRetentionDays(30);
        assertEquals(30, config.fileRetentionDays());
        assertEquals(30, config.withPort(4662).withAdminPort(4000).withKad(KadConfig.DEFAULT)
                .withRelay("", RelayFeedConfig.DEFAULT).withRemoteSearchCacheMinutes(5)
                .withPathsRelativeTo(java.nio.file.Path.of("/tmp")).fileRetentionDays());
    }

    @Test
    void isAHotParameterOfZeroDaysOrMore() {
        ConfigParameter parameter = ConfigParameter.byKey("fileRetentionDays");
        assertSame(ConfigParameter.FILE_RETENTION_DAYS, parameter);
        assertEquals(ConfigParameter.Scope.HOT, parameter.scope());
        assertEquals(ConfigParameter.Type.INTEGER, parameter.type());
        parameter.validate("0");
        parameter.validate("180");
        parameter.validate("36500");
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("-1"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("36501"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("six months"));
    }

    @Test
    void rejectsAnOutOfRangeValue() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withFileRetentionDays(-1));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withFileRetentionDays(36_501));
    }
}

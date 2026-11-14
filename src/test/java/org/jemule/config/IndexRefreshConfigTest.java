package org.jemule.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class IndexRefreshConfigTest {

    @Test
    void defaultsToTenSeconds() {
        assertEquals(10, ServerConfig.DEFAULT.indexRefreshSeconds());
        assertEquals(10, ServerConfig.fromProperties(new Properties()).indexRefreshSeconds(),
                "a configuration file without the key keeps the default");
    }

    @Test
    void isReadAndWrittenWithTheOtherParameters() {
        Properties props = new Properties();
        props.setProperty("indexRefreshSeconds", " 30 ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(30, config.indexRefreshSeconds());
        assertEquals("30", config.toProperties().get("indexRefreshSeconds"));
        assertEquals("30", config.value(ConfigParameter.INDEX_REFRESH_SECONDS));
    }

    @Test
    void isKeptByTheWithers() {
        ServerConfig config = ServerConfig.DEFAULT.withIndexRefreshSeconds(5);
        assertEquals(5, config.indexRefreshSeconds());
        assertEquals(5, config.withPort(4662).withAdminPort(4000).withKad(KadConfig.DEFAULT)
                .withRelay("", RelayFeedConfig.DEFAULT).withRemoteSearchCacheMinutes(3).indexRefreshSeconds());
    }

    @Test
    void isAHotParameterBetweenOneSecondAndTenMinutes() {
        ConfigParameter parameter = ConfigParameter.byKey("indexRefreshSeconds");
        assertSame(ConfigParameter.INDEX_REFRESH_SECONDS, parameter);
        assertEquals(ConfigParameter.Scope.HOT, parameter.scope());
        assertEquals(ConfigParameter.Type.INTEGER, parameter.type());
        parameter.validate("1");
        parameter.validate("10");
        parameter.validate("600");
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("0"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("601"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("ten"));
    }

    @Test
    void rejectsAnOutOfRangeValue() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withIndexRefreshSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withIndexRefreshSeconds(601));
    }
}

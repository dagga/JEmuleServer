package org.jemule.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSearchCacheConfigTest {

    @Test
    void defaultsToFifteenMinutes() {
        assertEquals(15, ServerConfig.DEFAULT.remoteSearchCacheMinutes());
        assertEquals(15, ServerConfig.fromProperties(new Properties()).remoteSearchCacheMinutes(),
                "a configuration file without the key keeps the default");
    }

    @Test
    void isReadAndWrittenWithTheOtherParameters() {
        Properties props = new Properties();
        props.setProperty("remoteSearchCacheMinutes", " 30 ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(30, config.remoteSearchCacheMinutes());
        assertEquals("30", config.toProperties().get("remoteSearchCacheMinutes"));
        assertEquals("30", config.value(ConfigParameter.REMOTE_SEARCH_CACHE_MINUTES));

        props.setProperty("remoteSearchCacheMinutes", "0");
        assertEquals(0, ServerConfig.fromProperties(props).remoteSearchCacheMinutes(), "0 disables the cache");
    }

    @Test
    void isKeptByTheWithers() {
        ServerConfig config = ServerConfig.DEFAULT.withRemoteSearchCacheMinutes(5);
        assertEquals(5, config.remoteSearchCacheMinutes());
        assertEquals(5, config.withPort(4662).withAdminPort(4000).withKad(KadConfig.DEFAULT)
                .withRelay("", RelayFeedConfig.DEFAULT).remoteSearchCacheMinutes());
    }

    @Test
    void isAHotParameterBetweenZeroAndADay() {
        ConfigParameter parameter = ConfigParameter.byKey("remoteSearchCacheMinutes");
        assertSame(ConfigParameter.REMOTE_SEARCH_CACHE_MINUTES, parameter);
        assertEquals(ConfigParameter.Scope.HOT, parameter.scope());
        assertEquals(ConfigParameter.Type.INTEGER, parameter.type());
        parameter.validate("0");
        parameter.validate("15");
        parameter.validate("1440");
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("-1"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("1441"));
        assertThrows(IllegalArgumentException.class, () -> parameter.validate("ten"));
    }

    @Test
    void rejectsAnOutOfRangeValue() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withRemoteSearchCacheMinutes(-1));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withRemoteSearchCacheMinutes(1441));
    }
}

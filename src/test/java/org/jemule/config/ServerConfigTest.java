/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void testFromProperties() {
        Properties props = new Properties();
        props.setProperty("port", "1234");
        props.setProperty("maxUsers", "500");
        props.setProperty("databasePath", "/tmp/testdb");
        props.setProperty("fakeFileDetectionEnabled", "false");
        props.setProperty("serverPeers", "127.0.0.1:4661,192.0.2.10:4661");
        props.setProperty("serverMetUrl", "https://example.org/servers.met");

        ServerConfig cfg = ServerConfig.fromProperties(props);

        assertEquals(1234, cfg.port());
        assertEquals(500, cfg.maxUsers());
        assertEquals("/tmp/testdb", cfg.databasePath());
        assertFalse(cfg.fakeFileDetectionEnabled());
        assertEquals("127.0.0.1:4661,192.0.2.10:4661", cfg.serverPeers());
        assertEquals("https://example.org/servers.met", cfg.serverMetUrl());

        // Defaults
        assertEquals(ServerConfig.DEFAULT.maxPacketSize(), cfg.maxPacketSize());
        assertEquals(ServerConfig.DEFAULT.cbFailureRateThreshold(), cfg.cbFailureRateThreshold());
    }

    @Test
    void testFromEmptyProperties() {
        Properties props = new Properties();
        ServerConfig cfg = ServerConfig.fromProperties(props);

        assertEquals(ServerConfig.DEFAULT.port(), cfg.port());
        assertEquals(ServerConfig.DEFAULT.maxFiles(), cfg.maxFiles());
        assertEquals(0, cfg.adminPort(), "the admin port is disabled by default");
        assertEquals(ServerConfig.DEFAULT_IP_FILTER_PATH, cfg.ipFilterPath());
        assertEquals(ServerConfig.DEFAULT_SERVER_MET_URL, cfg.serverMetUrl());
    }

    @Test
    void emptyIpFilterPathDisablesAutomaticUpdates() {
        Properties properties = new Properties();
        properties.setProperty("ipFilterPath", "");
        assertEquals("", ServerConfig.fromProperties(properties).ipFilterPath());
    }

    @Test
    void testAdminPort() {
        Properties props = new Properties();
        props.setProperty("adminPort", "4670");
        assertEquals(4670, ServerConfig.fromProperties(props).adminPort());
        props.setProperty("adminPort", "70000");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
    }

    @Test
    void testValidPort() {
        assertDoesNotThrow(() -> new ServerConfig(4661, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30, 300, 60, null));
    }

    @Test
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () ->
                new ServerConfig(0, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30, 300, 60, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new ServerConfig(65536, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30, 300, 60, null)
        );
    }

    @org.junit.jupiter.api.Test
    void withPortKeepsEveryOtherSetting() {
        ServerConfig cfg = new ServerConfig(4661, 1024, 10, 5, 100, 1000, 10, 20, "./db", null, true,
                50f, 10, 60, 300, 60, null, 9);
        ServerConfig moved = cfg.withPort(4242);
        org.junit.jupiter.api.Assertions.assertEquals(4242, moved.port());
        org.junit.jupiter.api.Assertions.assertEquals(9, moved.lowIdCheckTimeoutSeconds());
        org.junit.jupiter.api.Assertions.assertEquals("./db", moved.databasePath());
    }

    @Test
    void withPortKeepsServerPeerSeeds() {
        Properties properties = new Properties();
        properties.setProperty("serverPeers", "127.0.0.1:4661");
        properties.setProperty("serverMetUrl", "https://example.org/servers.met");
        ServerConfig cfg = ServerConfig.fromProperties(properties);
        assertEquals("127.0.0.1:4661", cfg.withPort(4662).serverPeers());
        assertEquals("https://example.org/servers.met", cfg.withPort(4662).serverMetUrl());
    }

    @Test
    void webAdminPortDefaultsAndSurvivesConfigCopies() {
        ServerConfig config = ServerConfig.fromProperties(new Properties());
        assertEquals(8080, config.adminWebPort());
        assertEquals(8080, config.withPort(4771).adminWebPort());
        assertEquals(8080, config.withKad(config.kad()).adminWebPort());
        assertEquals(0, config.withAdminWebPort(0).adminWebPort());

        Properties disabled = new Properties();
        disabled.setProperty("adminWebPort", "0");
        assertEquals(0, ServerConfig.fromProperties(disabled).adminWebPort());
    }

    @Test
    void testAdminAccessConfig() {
        Properties props = new Properties();
        props.setProperty("adminBindAddress", "192.168.1.1");
        props.setProperty("adminAllowedIps", "192.168.1.0/24, 10.0.0.1");
        props.setProperty("adminAllowedMacs", "00:11:22:33:44:55");

        ServerConfig cfg = ServerConfig.fromProperties(props);
        assertEquals("192.168.1.1", cfg.adminBindAddress());
        assertEquals("192.168.1.0/24, 10.0.0.1", cfg.adminAllowedIps());
        assertEquals("00:11:22:33:44:55", cfg.adminAllowedMacs());

        ServerConfig copy = cfg.withAdminBindAddress("0.0.0.0")
                .withAdminAllowedIps("127.0.0.1")
                .withAdminAllowedMacs("");
        assertEquals("0.0.0.0", copy.adminBindAddress());
        assertEquals("127.0.0.1", copy.adminAllowedIps());
        assertEquals("", copy.adminAllowedMacs());
    }

    @org.junit.jupiter.api.Test
    void relativePathsAreResolvedAgainstTheDataDirectory() {
        java.nio.file.Path home = java.nio.file.Paths.get("/var/lib/jemuleserver");
        ServerConfig cfg = new ServerConfig(4661, 1024, 10, 5, 100, 1000, 10, 20, "./jemule_db", "/etc/ipfilter.dat", true,
                50f, 10, 60, 300, 60, null).withPathsRelativeTo(home);
        org.junit.jupiter.api.Assertions.assertEquals(home.resolve("jemule_db").toString(), cfg.databasePath());
        org.junit.jupiter.api.Assertions.assertEquals("/etc/ipfilter.dat", cfg.ipFilterPath());
    }
}

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

        ServerConfig cfg = ServerConfig.fromProperties(props);

        assertEquals(1234, cfg.port());
        assertEquals(500, cfg.maxUsers());
        assertEquals("/tmp/testdb", cfg.databasePath());
        assertFalse(cfg.fakeFileDetectionEnabled());
        
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

    @org.junit.jupiter.api.Test
    void relativePathsAreResolvedAgainstTheDataDirectory() {
        java.nio.file.Path home = java.nio.file.Paths.get("/var/lib/jemuleserver");
        ServerConfig cfg = new ServerConfig(4661, 1024, 10, 5, 100, 1000, 10, 20, "./jemule_db", "/etc/ipfilter.dat", true,
                50f, 10, 60, 300, 60, null).withPathsRelativeTo(home);
        org.junit.jupiter.api.Assertions.assertEquals(home.resolve("jemule_db").toString(), cfg.databasePath());
        org.junit.jupiter.api.Assertions.assertEquals("/etc/ipfilter.dat", cfg.ipFilterPath());
    }
}

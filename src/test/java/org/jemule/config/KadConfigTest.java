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

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KadConfigTest {

    @Test
    void defaultsMatchTheSpecification() {
        KadConfig kad = KadConfig.fromProperties(new Properties());
        assertEquals(KadConfig.DEFAULT, kad);
        assertTrue(kad.enabled());
        assertEquals(0, kad.port());
        assertTrue(kad.bootstrapFromClients());
        assertEquals(500, kad.maxRoutingContacts());
        assertTrue(kad.forkSearchEnabled());
        assertEquals(4, kad.searchTimeoutSeconds());
        assertTrue(kad.autoIngestToH2());
        assertEquals(1, kad.minSourcesForIngest());
    }

    @Test
    void everyKeyIsRead() {
        Properties props = new Properties();
        props.setProperty("kadEnabled", "false");
        props.setProperty("kadPort", "5000");
        props.setProperty("kadBootstrapFromClients", "false");
        props.setProperty("kadMaxRoutingContacts", "120");
        props.setProperty("kadForkSearchEnabled", "false");
        props.setProperty("kadSearchTimeoutSeconds", "9");
        props.setProperty("kadAutoIngestToH2", "false");
        props.setProperty("kadMinSourcesForIngest", "3");

        KadConfig kad = KadConfig.fromProperties(props);
        assertEquals(new KadConfig(false, 5000, false, 120, false, 9, false, 3), kad);
    }

    @Test
    void invalidValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new KadConfig(true, 70000, true, 500, true, 4, true, 1));
        assertThrows(IllegalArgumentException.class, () -> new KadConfig(true, -1, true, 500, true, 4, true, 1));
        assertThrows(IllegalArgumentException.class, () -> new KadConfig(true, 0, true, 0, true, 4, true, 1));
        assertThrows(IllegalArgumentException.class, () -> new KadConfig(true, 0, true, 500, true, 0, true, 1));
        assertThrows(IllegalArgumentException.class, () -> new KadConfig(true, 0, true, 500, true, 4, true, -1));
    }

    @Test
    void effectivePortDefaultsToTcpPortPlusEleven() {
        assertEquals(4672, KadConfig.DEFAULT.effectivePort(4661));
        assertEquals(65535 - KadConfig.PORT_OFFSET, KadConfig.DEFAULT.effectivePort(65535),
                "near the top of the range, the port below the TCP port is used");
        assertEquals(5000, new KadConfig(true, 5000, true, 500, true, 4, true, 1).effectivePort(4661));
    }

    @Test
    void serverConfigCarriesTheKadSettings() {
        Properties props = new Properties();
        props.setProperty("kadEnabled", "false");
        props.setProperty("kadSearchTimeoutSeconds", "7");
        ServerConfig cfg = ServerConfig.fromProperties(props);
        assertFalse(cfg.kad().enabled());
        assertEquals(7, cfg.kad().searchTimeoutSeconds());

        assertEquals(KadConfig.DEFAULT, ServerConfig.DEFAULT.kad(), "legacy constructors use the Kad defaults");
        assertEquals(cfg.kad(), cfg.withPort(4242).kad(), "withPort keeps the Kad settings");
        assertEquals(cfg.kad(), cfg.withPathsRelativeTo(Paths.get("/tmp")).kad());
        assertEquals(KadConfig.DISABLED, ServerConfig.DEFAULT.withKad(KadConfig.DISABLED).kad());
    }

    @Test
    void shippedServerPropertiesMatchTheDefaults() throws Exception {
        Path file = Paths.get("server.properties");
        assertTrue(Files.exists(file), "server.properties is at the project root");
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            props.load(in);
        }
        assertEquals(KadConfig.DEFAULT, KadConfig.fromProperties(props));
        for (String key : new String[]{"kadEnabled", "kadBootstrapFromClients", "kadMaxRoutingContacts",
                "kadForkSearchEnabled", "kadSearchTimeoutSeconds", "kadAutoIngestToH2", "kadMinSourcesForIngest"}) {
            assertNotNull(props.getProperty(key), key + " is documented in server.properties");
        }
    }
}

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

class SourceCountConfigTest {

    @Test
    void theDefaultsAgeTheCountsAfterThirtyDaysAndBanTheEmuleQtBridges() {
        assertEquals(30, ServerConfig.DEFAULT.sourceCountMaxAgeDays());
        assertEquals("ED2B01", ServerConfig.DEFAULT.bannedHashPrefixes());
        ServerConfig read = ServerConfig.fromProperties(new Properties());
        assertEquals(30, read.sourceCountMaxAgeDays());
        assertEquals("ED2B01", read.bannedHashPrefixes());
    }

    @Test
    void areReadAndWrittenWithTheOtherParameters() {
        Properties props = new Properties();
        props.setProperty("sourceCountMaxAgeDays", " 7 ");
        props.setProperty("bannedHashPrefixes", " ED2B01120000,ABCDEF ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(7, config.sourceCountMaxAgeDays());
        assertEquals("ED2B01120000,ABCDEF", config.bannedHashPrefixes());
        assertEquals("7", config.value(ConfigParameter.SOURCE_COUNT_MAX_AGE_DAYS));
        assertEquals("ED2B01120000,ABCDEF", config.value(ConfigParameter.BANNED_HASH_PREFIXES));

        props.setProperty("bannedHashPrefixes", "");
        assertEquals("", ServerConfig.fromProperties(props).bannedHashPrefixes(), "empty: none banned");
    }

    @Test
    void areKeptByTheWithers() {
        ServerConfig config = ServerConfig.DEFAULT.withSourceCountSettings(0, "abcdef");
        assertEquals(0, config.sourceCountMaxAgeDays());
        ServerConfig other = config.withPort(4662).withFileRetentionDays(30).withLoginMessages("hi", "")
                .withPathsRelativeTo(java.nio.file.Path.of("/tmp"));
        assertEquals(0, other.sourceCountMaxAgeDays());
        assertEquals("abcdef", other.bannedHashPrefixes());
    }

    @Test
    void areHotParametersCheckedBeforeTheyAreSaved() {
        ConfigParameter age = ConfigParameter.byKey("sourceCountMaxAgeDays");
        assertSame(ConfigParameter.SOURCE_COUNT_MAX_AGE_DAYS, age);
        assertEquals(ConfigParameter.Scope.HOT, age.scope());
        age.validate("0");
        age.validate("30");
        assertThrows(IllegalArgumentException.class, () -> age.validate("-1"));

        ConfigParameter prefixes = ConfigParameter.byKey("bannedHashPrefixes");
        assertSame(ConfigParameter.BANNED_HASH_PREFIXES, prefixes);
        assertEquals(ConfigParameter.Scope.HOT, prefixes.scope());
        prefixes.validate("");
        prefixes.validate("ED2B01120000, abcdef");
        assertThrows(IllegalArgumentException.class, () -> prefixes.validate("ED2B0"));
        assertThrows(IllegalArgumentException.class, () -> prefixes.validate("not a prefix"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withSourceCountSettings(0, "xyz"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.DEFAULT.withSourceCountSettings(-1, ""));
    }
}

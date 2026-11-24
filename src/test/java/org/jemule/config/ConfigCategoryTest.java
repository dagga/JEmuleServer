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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The groups of the Settings menu of the web panel. */
class ConfigCategoryTest {

    @Test
    void everyGroupHoldsSettingsAndHasItsOwnIdAndLabel() {
        Set<ConfigParameter.Category> used = EnumSet.noneOf(ConfigParameter.Category.class);
        for (ConfigParameter parameter : ConfigParameter.ordered()) used.add(parameter.category());
        // An empty group would be an entry of the menu leading to nothing
        assertEquals(EnumSet.allOf(ConfigParameter.Category.class), used);
        Set<String> ids = new HashSet<>(), labels = new HashSet<>();
        for (ConfigParameter.Category category : ConfigParameter.Category.values()) {
            assertTrue(category.id().matches("[a-z]+"), category.id()); // the fragment of the menu's links
            assertTrue(ids.add(category.id()), category.id());
            assertTrue(labels.add(category.label()), category.label());
        }
    }

    @Test
    void settingsOfOneFeatureStayTogether() {
        for (ConfigParameter parameter : ConfigParameter.ordered()) {
            String key = parameter.key();
            if (key.startsWith("kad")) assertEquals(ConfigParameter.Category.KAD, parameter.category(), key);
            if (key.startsWith("nat")) assertEquals(ConfigParameter.Category.NAT, parameter.category(), key);
            if (key.startsWith("admin")) assertEquals(ConfigParameter.Category.ADMIN, parameter.category(), key);
            if (key.startsWith("database") || key.startsWith("cb")) {
                assertEquals(ConfigParameter.Category.DATABASE, parameter.category(), key);
            }
            if (key.startsWith("relay") || key.startsWith("serverIndexUpdate")) {
                assertEquals(ConfigParameter.Category.SERVERS, parameter.category(), key);
            }
        }
    }
}

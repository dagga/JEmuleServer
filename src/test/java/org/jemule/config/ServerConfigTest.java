/*
 * JEmuleServer - An experimental eMule server.
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
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void testValidPort() {
        assertDoesNotThrow(() -> new ServerConfig(4661, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30));
    }

    @Test
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ServerConfig(0, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30)
        );
        assertThrows(IllegalArgumentException.class, () -> 
            new ServerConfig(65536, 1024, 10, 5, 100, 1000, 10, 50, "./db", null, true, 50f, 5, 30)
        );
    }
}

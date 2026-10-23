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

import java.util.Properties;

/**
 * Kad sources for eD2K clients: when a client asks for the sources of a file the server knows few sources of, the
 * server searches Kad for them and sends the usable ones in a second OP_FOUNDSOURCES.
 *
 * @param enabled            Whether OP_GETSOURCES may start a Kad source search.
 * @param minLocalSources    Below this number of local sources, Kad is asked.
 * @param searchesPerMinute  New Kad source searches per minute for all clients.
 */
public record KadSourcesConfig(boolean enabled, int minLocalSources, int searchesPerMinute) {

    public static final KadSourcesConfig DEFAULT = new KadSourcesConfig(false, 10, 30);

    public KadSourcesConfig {
        if (minLocalSources < 1 || minLocalSources > 255) {
            throw new IllegalArgumentException("kadSourceSearchMinLocal must be between 1 and 255");
        }
        if (searchesPerMinute < 1 || searchesPerMinute > 600) {
            throw new IllegalArgumentException("kadSourceSearchesPerMinute must be between 1 and 600");
        }
    }

    /** Creates a KadSourcesConfig from a Properties object, using DEFAULT values for missing keys. */
    public static KadSourcesConfig fromProperties(Properties props) {
        return new KadSourcesConfig(
                Boolean.parseBoolean(props.getProperty("kadSourceSearchEnabled", String.valueOf(DEFAULT.enabled())).trim()),
                Integer.parseInt(props.getProperty("kadSourceSearchMinLocal", String.valueOf(DEFAULT.minLocalSources())).trim()),
                Integer.parseInt(props.getProperty("kadSourceSearchesPerMinute", String.valueOf(DEFAULT.searchesPerMinute())).trim())
        );
    }
}

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
 * Index update from the eD2K index peers (the verified eD2K servers): the server logs in to each of them as a client and searches there the
 * words of its index, to index the files they know.
 *
 * @param enabled     Whether the index is updated from the eD2K index peers.
 * @param maxPerHour  Searches per server and per hour at most.
 * @param maxPerDay   Searches per server and per day at most.
 * @param minSources  Sources a file found this way needs to be indexed.
 */
public record ServerIndexUpdateConfig(boolean enabled, int maxPerHour, int maxPerDay, int minSources) {

    public static final ServerIndexUpdateConfig DEFAULT = new ServerIndexUpdateConfig(false, 40, 300, 3);

    public ServerIndexUpdateConfig {
        if (maxPerHour < 1 || maxPerHour > 120) {
            throw new IllegalArgumentException("serverIndexUpdateMaxPerHour must be between 1 and 120");
        }
        if (maxPerDay < 1 || maxPerDay > 2000) {
            throw new IllegalArgumentException("serverIndexUpdateMaxPerDay must be between 1 and 2000");
        }
        if (minSources < 1 || minSources > 1000) {
            throw new IllegalArgumentException("serverIndexUpdateMinSources must be between 1 and 1000");
        }
    }

    /** Creates a ServerIndexUpdateConfig from a Properties object, using DEFAULT values for missing keys. */
    public static ServerIndexUpdateConfig fromProperties(Properties props) {
        return new ServerIndexUpdateConfig(
                Boolean.parseBoolean(props.getProperty("serverIndexUpdateEnabled", String.valueOf(DEFAULT.enabled())).trim()),
                Integer.parseInt(props.getProperty("serverIndexUpdateMaxPerHour", String.valueOf(DEFAULT.maxPerHour())).trim()),
                Integer.parseInt(props.getProperty("serverIndexUpdateMaxPerDay", String.valueOf(DEFAULT.maxPerDay())).trim()),
                Integer.parseInt(props.getProperty("serverIndexUpdateMinSources", String.valueOf(DEFAULT.minSources())).trim())
        );
    }
}

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
 * Configuration of the embedded Kademlia node (SFD 1.2, section 6.1).
 *
 * @param enabled               Whether the embedded Kademlia node is started.
 * @param port                  UDP port of the Kad2 protocol (0: TCP port + {@value #PORT_OFFSET}).
 * @param bootstrapFromClients  Whether connected eMule clients are used to bootstrap the routing table.
 * @param maxRoutingContacts    Maximum number of contacts kept in the routing table.
 * @param forkSearchEnabled     Whether client searches are forked to the Kad network.
 * @param searchTimeoutSeconds  Maximum duration of a forked Kad search, in seconds.
 * @param autoIngestToH2        Whether clean Kad files are persisted in the H2 database and indexed in memory.
 * @param minSourcesForIngest   Minimum number of sources a Kad file needs to be delivered and persisted.
 */
public record KadConfig(
        boolean enabled,
        int port,
        boolean bootstrapFromClients,
        int maxRoutingContacts,
        boolean forkSearchEnabled,
        int searchTimeoutSeconds,
        boolean autoIngestToH2,
        int minSourcesForIngest
) {
    /** Offset of the default Kad UDP port from the server TCP port (4661 + 11 = 4672). */
    public static final int PORT_OFFSET = 11;

    /** Default configuration: every Kad feature enabled, as documented in server.properties. */
    public static final KadConfig DEFAULT = new KadConfig(true, 0, true, 500, true, 4, true, 1);

    /** Configuration with the embedded Kademlia node turned off. */
    public static final KadConfig DISABLED = new KadConfig(false, 0, false, 500, false, 4, false, 1);

    public KadConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("kadPort must be between 0 (TCP port + " + PORT_OFFSET + ") and 65535");
        }
        if (maxRoutingContacts < 1) {
            throw new IllegalArgumentException("kadMaxRoutingContacts must be at least 1");
        }
        if (searchTimeoutSeconds < 1) {
            throw new IllegalArgumentException("kadSearchTimeoutSeconds must be at least 1");
        }
        if (minSourcesForIngest < 0) {
            throw new IllegalArgumentException("kadMinSourcesForIngest cannot be negative");
        }
    }

    /** UDP port actually bound by the Kad node for a server listening on {@code tcpPort}. */
    public int effectivePort(int tcpPort) {
        if (port > 0) return port;
        // The UDP responder already binds the TCP port, port + 4 and port + 12
        return tcpPort <= 65535 - PORT_OFFSET ? tcpPort + PORT_OFFSET : tcpPort - PORT_OFFSET;
    }

    /** Creates a KadConfig from a Properties object, using DEFAULT values for missing keys. */
    public static KadConfig fromProperties(Properties props) {
        return new KadConfig(
                Boolean.parseBoolean(props.getProperty("kadEnabled", String.valueOf(DEFAULT.enabled()))),
                Integer.parseInt(props.getProperty("kadPort", String.valueOf(DEFAULT.port())).trim()),
                Boolean.parseBoolean(props.getProperty("kadBootstrapFromClients", String.valueOf(DEFAULT.bootstrapFromClients()))),
                Integer.parseInt(props.getProperty("kadMaxRoutingContacts", String.valueOf(DEFAULT.maxRoutingContacts())).trim()),
                Boolean.parseBoolean(props.getProperty("kadForkSearchEnabled", String.valueOf(DEFAULT.forkSearchEnabled()))),
                Integer.parseInt(props.getProperty("kadSearchTimeoutSeconds", String.valueOf(DEFAULT.searchTimeoutSeconds())).trim()),
                Boolean.parseBoolean(props.getProperty("kadAutoIngestToH2", String.valueOf(DEFAULT.autoIngestToH2()))),
                Integer.parseInt(props.getProperty("kadMinSourcesForIngest", String.valueOf(DEFAULT.minSourcesForIngest())).trim())
        );
    }
}

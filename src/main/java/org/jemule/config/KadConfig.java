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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
 * @param nodesUrls             Comma-separated nodes.dat URLs used to bootstrap the routing table (empty disables it).
 */
public record KadConfig(
        boolean enabled,
        int port,
        boolean bootstrapFromClients,
        int maxRoutingContacts,
        boolean forkSearchEnabled,
        int searchTimeoutSeconds,
        boolean autoIngestToH2,
        int minSourcesForIngest,
        String nodesUrls
) {
    /** Offset of the default Kad UDP port from the server TCP port (4661 + 11 = 4672). */
    public static final int PORT_OFFSET = 11;

    /**
     * Default nodes.dat sources. The eMule Security URL needs its {@code .dat} suffix; the historical
     * {@code /nodes} path answers HTTP 404.
     */
    public static final String DEFAULT_NODES_URLS =
            "https://upd.emule-security.org/nodes.dat,http://www.alldivx.de/nodes/nodes.dat";

    /** Default configuration: every Kad feature enabled, as documented in server.properties. */
    public static final KadConfig DEFAULT = new KadConfig(true, 0, true, 500, true, 4, true, 1, DEFAULT_NODES_URLS);

    /** Configuration with the embedded Kademlia node turned off. */
    public static final KadConfig DISABLED = new KadConfig(false, 0, false, 500, false, 4, false, 1);

    /** Keeps source compatibility for callers that do not provide the nodes.dat sources. */
    public KadConfig(boolean enabled, int port, boolean bootstrapFromClients, int maxRoutingContacts,
                     boolean forkSearchEnabled, int searchTimeoutSeconds, boolean autoIngestToH2,
                     int minSourcesForIngest) {
        this(enabled, port, bootstrapFromClients, maxRoutingContacts, forkSearchEnabled, searchTimeoutSeconds,
                autoIngestToH2, minSourcesForIngest, DEFAULT_NODES_URLS);
    }

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
        nodesUrls = nodesUrls == null ? "" : nodesUrls.trim();
        parseNodesUrls(nodesUrls);
    }

    /** Parses the configured nodes.dat sources, separating them by commas, semicolons or whitespace. */
    public List<URI> nodesSources() {
        return parseNodesUrls(nodesUrls);
    }

    private static List<URI> parseNodesUrls(String value) {
        List<URI> sources = new ArrayList<>();
        if (value == null || value.isBlank()) return List.of();
        for (String token : value.split("[,;\\s]+")) {
            if (token.isBlank()) continue;
            URI uri;
            try {
                uri = URI.create(token);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid nodes.dat URL: " + token);
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Unsupported nodes.dat URL: " + token);
            }
            sources.add(uri);
        }
        return List.copyOf(sources);
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
                Integer.parseInt(props.getProperty("kadMinSourcesForIngest", String.valueOf(DEFAULT.minSourcesForIngest())).trim()),
                props.getProperty("kadNodesUrls", DEFAULT.nodesUrls())
        );
    }
}

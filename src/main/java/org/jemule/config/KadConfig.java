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
 * @param forkGlobalSearches    Whether the UDP global searches of other servers' clients are also searched on Kad,
 *                              in the background, to populate the database.
 * @param wordSearchMinBytes    UTF-8 bytes a word of a client search needs to be searched alone on Kad in the
 *                              background, besides the keyword of the search: at least Kad's own rule, 3 bytes.
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
        String nodesUrls,
        boolean forkGlobalSearches,
        int wordSearchMinBytes
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
    /** Default sources a Kad file needs to be kept: one source alone is often a fake or a file gone. */
    public static final int DEFAULT_MIN_SOURCES_FOR_INGEST = 2;
    /** Kad indexes no word under 3 UTF-8 bytes (CSearchManager::GetWords): the least and default length searched. */
    public static final int MIN_WORD_SEARCH_BYTES = 3;
    /** Most bytes asked of a word searched in the background. */
    public static final int MAX_WORD_SEARCH_MIN_BYTES = 64;
    public static final KadConfig DEFAULT = new KadConfig(true, 0, true, 500, true, 4, true,
            DEFAULT_MIN_SOURCES_FOR_INGEST, DEFAULT_NODES_URLS, true, MIN_WORD_SEARCH_BYTES);

    /** Configuration with the embedded Kademlia node turned off. */
    public static final KadConfig DISABLED = new KadConfig(false, 0, false, 500, false, 4, false,
            DEFAULT_MIN_SOURCES_FOR_INGEST);

    /** Keeps source compatibility for callers that do not provide the nodes.dat sources. */
    public KadConfig(boolean enabled, int port, boolean bootstrapFromClients, int maxRoutingContacts,
                     boolean forkSearchEnabled, int searchTimeoutSeconds, boolean autoIngestToH2,
                     int minSourcesForIngest) {
        this(enabled, port, bootstrapFromClients, maxRoutingContacts, forkSearchEnabled, searchTimeoutSeconds,
                autoIngestToH2, minSourcesForIngest, DEFAULT_NODES_URLS);
    }

    /** Keeps source compatibility for callers that do not choose whether global searches are forked. */
    public KadConfig(boolean enabled, int port, boolean bootstrapFromClients, int maxRoutingContacts,
                     boolean forkSearchEnabled, int searchTimeoutSeconds, boolean autoIngestToH2,
                     int minSourcesForIngest, String nodesUrls) {
        this(enabled, port, bootstrapFromClients, maxRoutingContacts, forkSearchEnabled, searchTimeoutSeconds,
                autoIngestToH2, minSourcesForIngest, nodesUrls, true);
    }

    /** Keeps source compatibility for callers that do not choose the length of the words searched in background. */
    public KadConfig(boolean enabled, int port, boolean bootstrapFromClients, int maxRoutingContacts,
                     boolean forkSearchEnabled, int searchTimeoutSeconds, boolean autoIngestToH2,
                     int minSourcesForIngest, String nodesUrls, boolean forkGlobalSearches) {
        this(enabled, port, bootstrapFromClients, maxRoutingContacts, forkSearchEnabled, searchTimeoutSeconds,
                autoIngestToH2, minSourcesForIngest, nodesUrls, forkGlobalSearches, MIN_WORD_SEARCH_BYTES);
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
        if (wordSearchMinBytes < MIN_WORD_SEARCH_BYTES || wordSearchMinBytes > MAX_WORD_SEARCH_MIN_BYTES) {
            throw new IllegalArgumentException("kadWordSearchMinBytes must be between " + MIN_WORD_SEARCH_BYTES
                    + " (Kad's own rule) and " + MAX_WORD_SEARCH_MIN_BYTES);
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
                props.getProperty("kadNodesUrls", DEFAULT.nodesUrls()),
                Boolean.parseBoolean(props.getProperty("kadForkGlobalSearches", String.valueOf(DEFAULT.forkGlobalSearches()))),
                Integer.parseInt(props.getProperty("kadWordSearchMinBytes",
                        String.valueOf(DEFAULT.wordSearchMinBytes())).trim())
        );
    }
}

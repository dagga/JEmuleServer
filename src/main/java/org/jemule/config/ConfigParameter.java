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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One editable {@code server.properties} parameter: its type, validation range and the scope of its application
 * while the server keeps running.
 */
public enum ConfigParameter {

    PORT("port", Type.INTEGER, Scope.RESTART, 1, 65535, "TCP/UDP port to listen on"),
    MAX_PACKET_SIZE("maxPacketSize", Type.INTEGER, Scope.HOT, 512, 64 * 1024 * 1024,
            "Maximum incoming packet size in bytes"),
    MAX_SEARCH_RESULTS("maxSearchResults", Type.INTEGER, Scope.HOT, 1, 100_000,
            "Maximum search results returned per request"),
    FLOOD_MAX_REQUESTS_PER_SECOND("floodMaxRequestsPerSecond", Type.INTEGER, Scope.HOT, 1, 1_000_000,
            "Flood protection: requests per second allowed per IP"),
    MAX_USERS("maxUsers", Type.INTEGER, Scope.HOT, 1, 1_000_000_000, "Maximum concurrent users"),
    MAX_FILES("maxFiles", Type.INTEGER, Scope.HOT, 1, 2_000_000_000, "Maximum indexed files"),
    MAX_FILES_PER_USER("maxFilesPerUser", Type.INTEGER, Scope.HOT, 1, 10_000_000,
            "Files a single user can publish"),
    MAX_SOURCES_PER_FILE("maxSourcesPerFile", Type.INTEGER, Scope.HOT, 1, 10_000,
            "Sources returned per file request"),
    DATABASE_PATH("databasePath", Type.STRING, Scope.RESTART, 0, 0, "H2 database path"),
    IP_FILTER_PATH("ipFilterPath", Type.STRING, Scope.HOT, 0, 0,
            "IP filter file path (empty disables the filter and its updates)"),
    FAKE_FILE_DETECTION_ENABLED("fakeFileDetectionEnabled", Type.BOOLEAN, Scope.HOT, 0, 0,
            "Fake file detection heuristics"),
    CB_FAILURE_RATE_THRESHOLD("cbFailureRateThreshold", Type.FLOAT, Scope.RESTART, 0, 100,
            "Circuit breaker failure rate threshold (%)"),
    CB_MINIMUM_NUMBER_OF_CALLS("cbMinimumNumberOfCalls", Type.INTEGER, Scope.RESTART, 1, 1_000_000,
            "Circuit breaker: minimum calls before the rate is evaluated"),
    CB_WAIT_DURATION_IN_SECONDS("cbWaitDurationInSeconds", Type.INTEGER, Scope.RESTART, 1, 86_400,
            "Circuit breaker: seconds the breaker stays open"),
    TCP_KEEP_ALIVE_TIMEOUT_IN_SECONDS("tcpKeepAliveTimeoutInSeconds", Type.INTEGER, Scope.HOT, 0, 86_400,
            "Disconnect after this many seconds without data (0 = never)"),
    HEARTBEAT_INTERVAL_SECONDS("heartbeatIntervalSeconds", Type.INTEGER, Scope.HOT, 0, 86_400,
            "Server status heartbeat interval in seconds (0 = disabled)"),
    PUBLIC_IP("publicIp", Type.STRING, Scope.RESTART, 0, 0, "Public IPv4 address (empty = auto-detect)"),
    LOW_ID_CHECK_TIMEOUT_SECONDS("lowIdCheckTimeoutSeconds", Type.INTEGER, Scope.HOT, 0, 300,
            "HighID test timeout in seconds (0 = every client gets LowID)"),
    CHILD_ABUSE_KEYWORDS_PATH("childAbuseKeywordsPath", Type.STRING, Scope.HOT, 0, 0,
            "Child abuse keyword file (empty = embedded list)"),
    ADMIN_PORT("adminPort", Type.INTEGER, Scope.ADMIN, 0, 65535, "Admin console TCP port (0 = disabled)"),
    ADMIN_WEB_PORT("adminWebPort", Type.INTEGER, Scope.ADMIN, 0, 65535,
            "Web admin panel HTTP port (0 = disabled)"),
    ADMIN_BIND_ADDRESS("adminBindAddress", Type.STRING, Scope.ADMIN, 0, 0, "Admin interfaces bind address"),
    ADMIN_ALLOWED_IPS("adminAllowedIps", Type.STRING, Scope.ADMIN, 0, 0, "Admin allowed IPs / CIDR blocks"),
    ADMIN_ALLOWED_MACS("adminAllowedMacs", Type.STRING, Scope.ADMIN, 0, 0, "Admin allowed MAC addresses"),
    SERVER_PEERS("serverPeers", Type.STRING, Scope.RESTART, 0, 0, "eD2K server seeds (host:port,...)"),
    SERVER_MET_URL("serverMetUrl", Type.STRING, Scope.RESTART, 0, 0, "HTTPS server.met source (empty = disabled)"),
    KAD_ENABLED("kadEnabled", Type.BOOLEAN, Scope.RESTART, 0, 0, "Embedded Kademlia node"),
    KAD_PORT("kadPort", Type.INTEGER, Scope.RESTART, 0, 65535, "Kad UDP port (0 = TCP port + 11)"),
    KAD_BOOTSTRAP_FROM_CLIENTS("kadBootstrapFromClients", Type.BOOLEAN, Scope.RESTART, 0, 0,
            "Bootstrap Kad from connected HighID clients"),
    KAD_MAX_ROUTING_CONTACTS("kadMaxRoutingContacts", Type.INTEGER, Scope.RESTART, 1, 100_000,
            "Kad routing table contact limit"),
    KAD_FORK_SEARCH_ENABLED("kadForkSearchEnabled", Type.BOOLEAN, Scope.RESTART, 0, 0,
            "Fork client searches to the Kad network"),
    KAD_SEARCH_TIMEOUT_SECONDS("kadSearchTimeoutSeconds", Type.INTEGER, Scope.RESTART, 1, 300,
            "Kad search timeout in seconds"),
    KAD_AUTO_INGEST_TO_H2("kadAutoIngestToH2", Type.BOOLEAN, Scope.RESTART, 0, 0,
            "Ingest Kad files into the H2 database"),
    KAD_MIN_SOURCES_FOR_INGEST("kadMinSourcesForIngest", Type.INTEGER, Scope.RESTART, 0, 10_000,
            "Minimum sources required to ingest a Kad file"),
    KAD_NODES_URLS("kadNodesUrls", Type.STRING, Scope.RESTART, 0, 0,
            "nodes.dat sources used to bootstrap Kad (comma-separated, empty = disabled)"),
    KAD_FORK_GLOBAL_SEARCHES("kadForkGlobalSearches", Type.BOOLEAN, Scope.RESTART, 0, 0,
            "Search the UDP global searches of other servers' clients on Kad to populate the database"),
    OFFER_FILES_V1_ENABLED("offerFilesV1Enabled", Type.BOOLEAN, Scope.HOT, 0, 0,
            "Answer clients asking for OFFERFILES v1 at login and hold them to its pace (new logins)"),
    OFFER_FILES_BATCH_MAX("offerFilesBatchMax", Type.INTEGER, Scope.HOT, 1, 100_000,
            "OFFERFILES v1: most files in one OP_OFFERFILES (new logins)"),
    OFFER_FILES_MIN_INTERVAL_MS("offerFilesMinIntervalMs", Type.INTEGER, Scope.HOT, 1, 3_600_000,
            "OFFERFILES v1: least time in milliseconds between two full batches (new logins)"),
    OFFER_FILES_GLOBAL_RECORDS_PER_SEC("offerFilesGlobalRecordsPerSec", Type.INTEGER, Scope.HOT, 0, 10_000_000,
            "OFFERFILES v1: files per second accepted from all v1 clients together (0 = no ceiling)"),
    KAD_SOURCE_SEARCH_ENABLED("kadSourceSearchEnabled", Type.BOOLEAN, Scope.HOT, 0, 0,
            "Search Kad for the sources of a file a client asks for, when the server knows few"),
    KAD_SOURCE_SEARCH_MIN_LOCAL("kadSourceSearchMinLocal", Type.INTEGER, Scope.HOT, 1, 255,
            "Kad source search: below this number of local sources, Kad is asked"),
    KAD_SOURCE_SEARCHES_PER_MINUTE("kadSourceSearchesPerMinute", Type.INTEGER, Scope.HOT, 1, 600,
            "Kad source search: new searches per minute for all clients");

    /** Value type of a parameter, with its parsing and formatting rules. */
    public enum Type {
        INTEGER, FLOAT, BOOLEAN, STRING
    }

    /** When a changed value starts to have an effect on the running server. */
    public enum Scope {
        /** Applied as soon as it is saved or reloaded. */
        HOT("hot", "Applied immediately"),
        /** The admin interfaces are rebuilt while the server keeps running. */
        ADMIN("admin", "Applied to admin interfaces"),
        /** Written to the configuration file and applied at the next restart. */
        RESTART("restart", "Applied after restart");

        private final String id;
        private final String label;

        Scope(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }
    }

    private static final List<ConfigParameter> VALUES = List.of(values());

    private final String key;
    private final Type type;
    private final Scope scope;
    private final double min;
    private final double max;
    private final String description;

    ConfigParameter(String key, Type type, Scope scope, double min, double max, String description) {
        this.key = key;
        this.type = type;
        this.scope = scope;
        this.min = min;
        this.max = max;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public Scope scope() {
        return scope;
    }

    public String description() {
        return description;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public static List<ConfigParameter> ordered() {
        return VALUES;
    }

    /** The parameter with that key, or null when the key is not part of the configuration. */
    public static ConfigParameter byKey(String key) {
        if (key == null) return null;
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (ConfigParameter parameter : VALUES) {
            if (parameter.key.toLowerCase(Locale.ROOT).equals(wanted)) return parameter;
        }
        return null;
    }

    /**
     * Validates one raw value.
     *
     * @throws IllegalArgumentException when the value is not valid for this parameter
     */
    public void validate(String raw) {
        if (raw == null) throw new IllegalArgumentException(key + " is missing a value");
        String value = raw.trim();
        switch (type) {
            case INTEGER -> {
                long number;
                try {
                    number = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'");
                }
                if (number < (long) min || number > (long) max) {
                    throw new IllegalArgumentException(key + " must be between " + (long) min + " and "
                            + (long) max + ", got " + number);
                }
            }
            case FLOAT -> {
                float number;
                try {
                    number = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " must be a number, got '" + value + "'");
                }
                if (!Float.isFinite(number) || number < min || number > max) {
                    throw new IllegalArgumentException(key + " must be between " + min + " and " + max
                            + ", got " + value);
                }
            }
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(key + " must be true or false, got '" + value + "'");
                }
            }
            case STRING -> {
            }
        }
        validateFormat(value);
    }

    /** Additional format checks for the string parameters whose content is parsed by the server. */
    private void validateFormat(String value) {
        switch (key) {
            case "adminAllowedIps" -> {
                for (String token : splitList(value)) {
                    if (token.equals("*") || token.equalsIgnoreCase("all")) continue;
                    if (token.contains("/")) {
                        String[] parts = token.split("/", 2);
                        parseAddress(parts[0]);
                        int prefix;
                        try {
                            prefix = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid CIDR block in adminAllowedIps: " + token);
                        }
                        if (prefix < 0 || prefix > 128) {
                            throw new IllegalArgumentException(
                                    "CIDR prefix must be between 0 and 128 in adminAllowedIps: " + token);
                        }
                    } else {
                        parseAddress(token);
                    }
                }
            }
            case "adminAllowedMacs" -> {
                for (String token : splitList(value)) {
                    String normalized = org.jemule.security.AdminAccessFilter.normalizeMac(token);
                    if (normalized == null) {
                        throw new IllegalArgumentException("Invalid MAC address in adminAllowedMacs: " + token);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void parseAddress(String value) {
        try {
            java.net.InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IP address '" + value + "'");
        }
    }

    private static List<String> splitList(String value) {
        return Arrays.stream(value.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Canonical form written back to the configuration file. */
    public String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        return switch (type) {
            case INTEGER -> String.valueOf(Long.parseLong(value));
            case FLOAT -> String.valueOf(Float.parseFloat(value));
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> value;
        };
    }

    /** True when both raw values represent the same value for this parameter. */
    public boolean sameValue(String first, String second) {
        if (first == null || second == null) return first == second;
        try {
            return normalize(first).equals(normalize(second));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The value encoded as a JSON number, boolean or string for the admin API. */
    public Object jsonValue(String raw) {
        if (raw == null) return "";
        return switch (type) {
            case INTEGER -> (Object) Long.parseLong(raw.trim());
            case FLOAT -> (Object) Float.parseFloat(raw.trim());
            case BOOLEAN -> (Object) Boolean.parseBoolean(raw.trim());
            case STRING -> raw;
        };
    }
}

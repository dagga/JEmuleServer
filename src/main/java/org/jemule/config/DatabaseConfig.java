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

import java.util.Locale;
import java.util.Properties;

/**
 * Database the files, bans, statistics and metrics are kept in: the embedded H2 file ({@code databasePath}) or a
 * MySQL server, local or remote.
 *
 * @param type         H2 (default) or MYSQL.
 * @param url          JDBC URL of the MySQL database, {@code jdbc:mysql://host:3306/jemule}; unused with H2.
 * @param user         MySQL user.
 * @param password     MySQL password; when empty, the JEMULE_DATABASE_PASSWORD environment variable is used.
 * @param importFromH2 With MySQL: whether the H2 database at {@code databasePath} is copied into an empty MySQL
 *                     database at the first start.
 */
public record DatabaseConfig(Type type, String url, String user, String password, boolean importFromH2) {

    public enum Type { H2, MYSQL }

    /** Environment variable read when {@code databasePassword} is empty, to keep the password out of the file. */
    public static final String PASSWORD_ENV = "JEMULE_DATABASE_PASSWORD";

    public static final DatabaseConfig DEFAULT = new DatabaseConfig(Type.H2, "", "", "", true);

    /**
     * Connector/J settings added to the URL unless it sets them: batches sent as multi-row statements (one round trip
     * per batch instead of one per row, which matters with a remote server), dead connections detected.
     */
    private static final String[][] URL_DEFAULTS = {
            {"rewriteBatchedStatements", "true"},
            {"tcpKeepAlive", "true"},
            {"connectTimeout", "10000"},
            {"characterEncoding", "UTF-8"},
    };

    public DatabaseConfig {
        if (type == null) type = Type.H2;
        url = url == null ? "" : url.trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        if (type == Type.MYSQL && !url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("databaseUrl must be jdbc:mysql://host:port/database with databaseType=mysql, got '"
                    + url + "'");
        }
    }

    public boolean isMySql() {
        return type == Type.MYSQL;
    }

    /** The URL with the {@link #URL_DEFAULTS} it does not set. */
    public String jdbcUrl() {
        StringBuilder result = new StringBuilder(url);
        String lower = url.toLowerCase(Locale.ROOT);
        boolean hasQuery = url.indexOf('?') >= 0;
        for (String[] setting : URL_DEFAULTS) {
            if (lower.contains(setting[0].toLowerCase(Locale.ROOT) + "=")) continue;
            result.append(hasQuery ? '&' : '?').append(setting[0]).append('=').append(setting[1]);
            hasQuery = true;
        }
        return result.toString();
    }

    /** The password, or the {@link #PASSWORD_ENV} environment variable when it is empty. */
    public String effectivePassword() {
        if (!password.isEmpty()) return password;
        String env = System.getenv(PASSWORD_ENV);
        return env == null ? "" : env;
    }

    /** Parses {@code h2} or {@code mysql}, in any case. */
    public static Type parseType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) return Type.H2;
        try {
            return Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("databaseType must be h2 or mysql, got '" + value + "'");
        }
    }

    /** Creates a DatabaseConfig from a Properties object, using DEFAULT values for missing keys. */
    public static DatabaseConfig fromProperties(Properties props) {
        return new DatabaseConfig(
                parseType(props.getProperty("databaseType", DEFAULT.type().name())),
                props.getProperty("databaseUrl", DEFAULT.url()),
                props.getProperty("databaseUser", DEFAULT.user()),
                props.getProperty("databasePassword", DEFAULT.password()),
                Boolean.parseBoolean(props.getProperty("databaseImportFromH2", String.valueOf(DEFAULT.importFromH2())).trim())
        );
    }

    /** No password in the text: it is logged and shown. */
    @Override
    public String toString() {
        return type == Type.H2 ? "H2" : "MySQL " + url + " (user " + user + ")";
    }
}

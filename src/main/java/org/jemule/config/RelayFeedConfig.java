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
 * The files a relay server sends to the server it relays to (relay mode, {@code relayServer}). On the relay, the files
 * it finds (Kad, other servers, its clients) are posted to {@code http://<relayServer host>:<port>/api/relay/files};
 * on the receiving server, {@code clients} lists the relays allowed to post there. Both sides share {@code token}.
 *
 * @param port    Admin panel HTTP port of the relay server, where the relay posts its files (0: no feed).
 * @param token   Shared secret sent as {@code Authorization: Bearer <token>}; empty disables the feed on both sides.
 *                Read only from server.properties or {@link #TOKEN_ENV}, never shown nor written by the admin panel.
 * @param clients Receiving side: relay servers (IPs or CIDR blocks, comma-separated) allowed to post their files.
 */
public record RelayFeedConfig(int port, String token, String clients) {

    /** Environment variable read when {@code relayToken} is empty, to keep the secret out of the file. */
    public static final String TOKEN_ENV = "JEMULE_RELAY_TOKEN";
    /** Shortest token accepted: a guessable one would let anyone fill the index. */
    public static final int MIN_TOKEN_LENGTH = 16;

    public static final RelayFeedConfig DEFAULT = new RelayFeedConfig(8080, "", "");

    public RelayFeedConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("relayFeedPort must be between 0 (no feed) and 65535");
        }
        token = token == null ? "" : token.trim();
        clients = clients == null ? "" : clients.trim();
        if (!token.isEmpty() && token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("relayToken must hold at least " + MIN_TOKEN_LENGTH + " characters");
        }
    }

    /** The token, or the {@link #TOKEN_ENV} environment variable when it is empty; empty when neither is set. */
    public String effectiveToken() {
        if (!token.isEmpty()) return token;
        String env = System.getenv(TOKEN_ENV);
        return env == null || env.trim().length() < MIN_TOKEN_LENGTH ? "" : env.trim();
    }

    /** Creates a RelayFeedConfig from a Properties object, using DEFAULT values for missing keys. */
    public static RelayFeedConfig fromProperties(Properties props) {
        return new RelayFeedConfig(
                Integer.parseInt(props.getProperty("relayFeedPort", String.valueOf(DEFAULT.port())).trim()),
                props.getProperty("relayToken", DEFAULT.token()),
                props.getProperty("relayClients", DEFAULT.clients())
        );
    }

    /** No token in the text: it is logged and shown. */
    @Override
    public String toString() {
        return "relay feed (port " + port + ", token " + (effectiveToken().isEmpty() ? "none" : "set")
                + ", clients " + (clients.isEmpty() ? "none" : clients) + ")";
    }
}

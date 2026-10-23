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


package org.jemule.network.handler;

import org.jemule.core.ClientState;

import java.util.List;

/**
 * One source of OP_FOUNDSOURCES / OP_FOUNDSOURCES_OBFU, as read by CPartFile::AddSources: an eD2K ID (the IPv4 in
 * eD2K order for a HighID, a LowID of this server otherwise), the TCP port, and for _OBFU the connect options and the
 * user hash.
 *
 * @param userHash null when unknown: then it is not sent, even to a client asking with _OBFU
 */
public record FoundSource(long id, int port, int cryptOptions, byte[] userHash) {

    /** A client of this server. */
    public static FoundSource of(ClientState s) {
        return new FoundSource(s.clientId(), s.listenPort(), SourceHandler.cryptOptions(s), s.userHash());
    }

    public static List<FoundSource> of(List<ClientState> clients) {
        return clients.stream().map(FoundSource::of).toList();
    }

    /** Same ID and port: the same source for the client. */
    public boolean sameEndpoint(FoundSource other) {
        return id == other.id && port == other.port;
    }
}

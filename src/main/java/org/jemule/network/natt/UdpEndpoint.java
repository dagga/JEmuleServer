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


package org.jemule.network.natt;

import java.net.InetAddress;

import static java.util.Objects.requireNonNull;

/** A client's public UDP endpoint as the server saw it: the source of one of its datagrams. */
public record UdpEndpoint(InetAddress address, int port) {

    public UdpEndpoint {
        requireNonNull(address);
        if (port < 1 || port > 0xFFFF) throw new IllegalArgumentException("UDP port out of range: " + port);
    }
}

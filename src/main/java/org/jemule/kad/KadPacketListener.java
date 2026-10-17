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

package org.jemule.kad;

import java.net.InetAddress;

/**
 * Callback listener for incoming Kademlia UDP packets.
 */
@FunctionalInterface
public interface KadPacketListener {
    /**
     * Invoked when a valid Kademlia UDP packet is received.
     *
     * @param packet        The decoded Kademlia packet.
     * @param remoteAddress The sender IP address.
     * @param remotePort    The sender UDP port.
     */
    void onPacket(KadPacket packet, InetAddress remoteAddress, int remotePort);
}

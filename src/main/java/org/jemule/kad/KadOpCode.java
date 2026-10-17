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

/**
 * Kademlia protocol opcodes (primarily Kad2 / protocol 0x3A).
 */
public enum KadOpCode {
    BOOTSTRAP_REQ(0x3A, 0x01),
    BOOTSTRAP_RES(0x3A, 0x02),
    HELLO_REQ(0x3A, 0x11),
    HELLO_RES(0x3A, 0x12),
    REQ(0x3A, 0x21),               // Find Node request
    RES(0x3A, 0x22),               // Find Node response
    SEARCH_KEY_REQ(0x3A, 0x33),    // Keyword search request
    SEARCH_SOURCE_REQ(0x3A, 0x34), // Source search request
    SEARCH_NOTES_REQ(0x3A, 0x35),
    SEARCH_RES(0x3A, 0x3B),        // Search response
    PUBLISH_KEY_REQ(0x3A, 0x41),
    PUBLISH_SOURCE_REQ(0x3A, 0x42),
    PUBLISH_RES(0x3A, 0x4B),
    PING(0x3A, 0x60),
    PONG(0x3A, 0x61);

    public final byte protocol;
    public final byte value;

    KadOpCode(int protocol, int value) {
        this.protocol = (byte) protocol;
        this.value = (byte) value;
    }

    public static KadOpCode fromByte(byte protocol, byte value) {
        for (KadOpCode op : values()) {
            if (op.protocol == protocol && op.value == value) {
                return op;
            }
        }
        return null;
    }
}

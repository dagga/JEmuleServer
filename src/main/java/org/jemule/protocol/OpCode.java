/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
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


package org.jemule.protocol;

public enum OpCode {
    LOGIN_REQUEST((byte) 0x01),
    SERVER_STATUS((byte) 0x34),
    SERVER_MESSAGE((byte) 0x38),
    SERVER_IDENT((byte) 0x41),
    CLIENT_LOGIN((byte) 0x1A), // This might be an old/alternative login? Usually 0x01 is used.
    LOGIN_ACCEPTED((byte) 0x1B),
    SEARCH_REQUEST((byte) 0x16),
    SEARCH_RESULT((byte) 0x64),
    GET_SOURCES((byte) 0x15),
    SOURCES_RESULT((byte) 0x14),
    PUBLISH_FILES((byte) 0x20),
    PUBLISH_ACK((byte) 0x21);

    public final byte value;
    OpCode(byte value) { this.value = value; }

    public static OpCode fromByte(byte b) {
        for (OpCode op : values()) if (op.value == b) return op;
        return null;
    }
}

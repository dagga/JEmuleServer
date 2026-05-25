/*
 * JEmuleServer - An experimental eMule server.
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


package org.jemule.protocol;

public enum OpCode {
    LOGIN_REQUEST((byte) 0x01),
    SERVER_STATUS((byte) 0x34),
    SERVER_MESSAGE((byte) 0x38),
    SERVER_IDENT((byte) 0x41),
    CLIENT_LOGIN((byte) 0x1A), // This might be an old/alternative login? Usually 0x01 is used.
    LOGIN_ACCEPTED((byte) 0x1B),
    ID_CHANGE((byte) 0x40),         // Lugdunum extension: ID Change (confirm HighID)
    SEARCH_REQUEST((byte) 0x16),
    SEARCH_RESULT((byte) 0x33),     // Conformité spec : 0x33 (au lieu de 0x64)
    GET_SOURCES((byte) 0x19),       // Conformité spec : 0x19 (au lieu de 0x15)
    OFFER_FILES((byte) 0x15),       // Ajout depuis la spec
    FOUND_SOURCES((byte) 0x42),     // Conformité spec : OP_FOUNDSOURCES
    GET_SERVER_LIST((byte) 0x14),   // Ajout depuis la spec
    SERVER_LIST((byte) 0x32),       // Conformité spec : 0x32 (au lieu de 0x42)
    DISCONNECT((byte) 0x18),
    PUBLISH_FILES((byte) 0x20),
    QUERY_MORE_RESULT((byte) 0x21), // OP_QUERY_MORE_RESULT
    CALLBACK((byte) 0x1C),

    // eMule Protocol (0xC5) Extensions
    GET_SOURCES_OBFU((byte) 0x23), // Used for obfuscated/extended source requests
    SOURCES_RESULT_OBFU((byte) 0x24), // OP_EXT_SOURCESRES
    EMULE_INFO((byte) 0x01),      // OP_EMULE_INFO
    EMULE_INFO_ACK((byte) 0x02),
    COMPRESSED_PART((byte) 0x28), // OP_COMPRESSEDPART
    ASK_SHARED_FILES((byte) 0x4F); // OP_ASKSHAREDFILES

    public final byte value;

    OpCode(byte value) {
        this.value = value;
    }

    public static OpCode fromByte(byte protocol, byte b) {
        if (protocol == (byte) 0xC5) {
            if (b == (byte) 0x01) return EMULE_INFO;
            if (b == (byte) 0x02) return EMULE_INFO_ACK;
            if (b == (byte) 0x23) return GET_SOURCES_OBFU;
            if (b == (byte) 0x24) return SOURCES_RESULT_OBFU;
            if (b == (byte) 0x28) return COMPRESSED_PART;
            if (b == (byte) 0x4F) return ASK_SHARED_FILES;
        }
        for (OpCode op : values()) {
            // Avoid collision if same byte used in different protocols
            if (protocol == (byte) 0xE3) {
                if (op == EMULE_INFO || op == EMULE_INFO_ACK || op == GET_SOURCES_OBFU || op == ASK_SHARED_FILES) continue;
            }
            if (op.value == b) return op;
        }
        return null;
    }
}

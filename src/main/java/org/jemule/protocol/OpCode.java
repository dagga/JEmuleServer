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


package org.jemule.protocol;

/**
 * ed2k client &lt;-&gt; server opcodes (OP_* in eMule opcodes.h). Every opcode belongs to a protocol:
 * 0xE3 (OP_EDONKEYPROT, also used once a 0xD4 packet has been decompressed) or 0xC5 (OP_EMULEPROT).
 * The same byte value means different things in both protocols, so lookups always take the protocol into account.
 */
public enum OpCode {
    // ---- 0xE3, client -> server
    LOGIN_REQUEST(0xE3, 0x01),        // OP_LOGINREQUEST <hash 16><ID 4><port 2><tags>
    GET_SERVER_LIST(0xE3, 0x14),      // OP_GETSERVERLIST
    OFFER_FILES(0xE3, 0x15),          // OP_OFFERFILES <count 4>(<hash 16><ID 4><port 2><tags>)*
    SEARCH_REQUEST(0xE3, 0x16),       // OP_SEARCHREQUEST <search tree>
    DISCONNECT(0xE3, 0x18),           // OP_DISCONNECT
    GET_SOURCES(0xE3, 0x19),          // OP_GETSOURCES <hash 16><size 4>[<size 8>]
    CLIENT_LOGIN(0xE3, 0x1A),         // OP_SEARCH_USER (not supported)
    CALLBACK_REQUEST(0xE3, 0x1C),     // OP_CALLBACKREQUEST <ID 4>
    PUBLISH_FILES(0xE3, 0x20),        // legacy JEmuleServer variant of OFFER_FILES without <ID><port>
    QUERY_MORE_RESULT(0xE3, 0x21),    // OP_QUERY_MORE_RESULT
    GET_SOURCES_OBFU(0xE3, 0x23),     // OP_GETSOURCES_OBFU <hash 16><size 4>[<size 8>]

    // ---- 0xE3, server -> client
    SERVER_LIST(0xE3, 0x32),          // OP_SERVERLIST <count 1>(<IP 4><port 2>)*
    SEARCH_RESULT(0xE3, 0x33),        // OP_SEARCHRESULT <count 4>(<hash 16><ID 4><port 2><tags>)*[<more 1>]
    SERVER_STATUS(0xE3, 0x34),        // OP_SERVERSTATUS <users 4><files 4>
    CALLBACK_REQUESTED(0xE3, 0x35),   // OP_CALLBACKREQUESTED <IP 4><port 2>[<cryptOptions 1><userhash 16>]
    CALLBACK_FAIL(0xE3, 0x36),        // OP_CALLBACK_FAIL
    SERVER_MESSAGE(0xE3, 0x38),       // OP_SERVERMESSAGE <len 2><text>
    ID_CHANGE(0xE3, 0x40),            // OP_IDCHANGE <ID 4><flags 4><unused 4><IP 4><obf port 4>
    SERVER_IDENT(0xE3, 0x41),         // OP_SERVERIDENT <hash 16><IP 4><port 2><tags>
    FOUND_SOURCES(0xE3, 0x42),        // OP_FOUNDSOURCES <hash 16><count 1>(<ID 4><port 2>)*
    SOURCES_RESULT_OBFU(0xE3, 0x44),  // OP_FOUNDSOURCES_OBFU <hash 16><count 1>(<ID 4><port 2><crypt 1>[<hash 16>])*

    // ---- 0xC5 (eMule extended protocol)
    EMULE_INFO(0xC5, 0x01),           // OP_EMULEINFO
    EMULE_INFO_ACK(0xC5, 0x02),       // OP_EMULEINFOANSWER
    COMPRESSED_PART(0xC5, 0x40);      // OP_COMPRESSEDPART

    public final byte protocol;
    public final byte value;

    OpCode(int protocol, int value) {
        this.protocol = (byte) protocol;
        this.value = (byte) value;
    }

    /**
     * Resolves an opcode received with the given protocol byte. 0xD4 (zlib) packets are decompressed by
     * {@link org.jemule.network.Packet#read} and carry ed2k (0xE3) opcodes.
     *
     * @return the opcode, or null if it is unknown for this protocol
     */
    public static OpCode fromByte(byte protocol, byte b) {
        byte proto = protocol == (byte) 0xD4 ? (byte) 0xE3 : protocol;
        for (OpCode op : values()) {
            if (op.protocol == proto && op.value == b) return op;
        }
        return null;
    }
}

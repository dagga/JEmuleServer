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

/**
 * Opcodes of the server-coordinated NAT traversal, all under 0xE3 (OP_EDONKEYPROT), as listed in the "Wire values"
 * table of aMule master a6d6290be0, docs/NAT_T_Protocol.md.
 */
public final class NatOpcodes {

    /**
     * OP_NAT_CALLBACKREQUESTED, server TCP, server to the target:
     * {@code <requester IP 4><requester UDP port 2><requester ID 4><crypt 1>[<requester user hash 16>]}.
     */
    public static final byte OP_NAT_CALLBACKREQUESTED = (byte) 0x37;

    /**
     * OP_LOWID_HOLEPUNCH_REQUEST, server TCP, client to server: {@code <target ID 4><requester UDP port 2>}
     * (ed2k-server README, "Implementing a compatible client").
     */
    public static final byte OP_LOWID_HOLEPUNCH_REQUEST = (byte) 0x60;

    /**
     * OP_LOWID_HOLEPUNCH_INFO, server TCP, server to both sides:
     * {@code <peer IP 4><peer TCP port 2><peer UDP port 2><peer user hash 16><role 1><peer ID 4><peer crypt 1>}
     * (30 bytes), then {@code <own IP 4><own UDP port 2>} (36 bytes) when the recipient's 0x9F endpoint is known.
     */
    public static final byte OP_LOWID_HOLEPUNCH_INFO = (byte) 0x61;

    /** OP_LOWID_HOLEPUNCH_FAIL, server TCP, server to the requester: {@code <target ID 4><reason 1>}. */
    public static final byte OP_LOWID_HOLEPUNCH_FAIL = (byte) 0x62;

    /**
     * OP_NATT_KEEPALIVE, server UDP, client UDP socket to the server: {@code <user hash 16>}, or the proof form
     * {@code <user hash 16><counter 4><mac 16>}. Its source is the client's public UDP endpoint.
     */
    public static final byte OP_NATT_KEEPALIVE = (byte) 0x9F;

    /**
     * OP_NAT_CALLBACKREQUEST, server UDP, client to server, server-key obfuscated:
     * {@code <target ID 4><requester ID 4>}; requester ID 0 is the cross-server form {@code <target ID 4><0 4><crypt 1>}.
     */
    public static final byte OP_NAT_CALLBACKREQUEST = (byte) 0xA6;

    /** OP_NAT_CALLBACKREQUESTED_UDP, server UDP to the client UDP socket, server-key obfuscated: as 0x37. */
    public static final byte OP_NAT_CALLBACKREQUESTED_UDP = (byte) 0xA7;

    private NatOpcodes() {
    }
}

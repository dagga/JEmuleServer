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

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a remote contact in the Kademlia DHT.
 */
public final class KadContact implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int MAX_FAILED_PINGS = 3;

    private final KadId id;
    private final InetAddress address;
    private final int udpPort;
    private final int tcpPort;
    private final byte kadVersion;
    private final byte[] udpKey;

    private volatile long lastSeen;
    private volatile int failedPings;

    public KadContact(KadId id, InetAddress address, int udpPort, int tcpPort, byte kadVersion, byte[] udpKey) {
        this.id = Objects.requireNonNull(id, "KadId cannot be null");
        this.address = Objects.requireNonNull(address, "address cannot be null");
        if (udpPort <= 0 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP port: " + udpPort);
        }
        this.udpPort = udpPort;
        this.tcpPort = (tcpPort >= 0 && tcpPort <= 65535) ? tcpPort : 0;
        this.kadVersion = kadVersion;
        this.udpKey = udpKey != null ? udpKey.clone() : new byte[0];
        this.lastSeen = System.currentTimeMillis();
        this.failedPings = 0;
    }

    public KadContact(KadId id, InetAddress address, int udpPort, int tcpPort, byte kadVersion) {
        this(id, address, udpPort, tcpPort, kadVersion, null);
    }

    public KadContact(KadId id, InetAddress address, int udpPort) {
        this(id, address, udpPort, 0, (byte) 2, null);
    }

    public KadId id() {
        return id;
    }

    public InetAddress address() {
        return address;
    }

    public int udpPort() {
        return udpPort;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public byte kadVersion() {
        return kadVersion;
    }

    public byte[] udpKey() {
        return udpKey.clone();
    }

    public long lastSeen() {
        return lastSeen;
    }

    public int failedPings() {
        return failedPings;
    }

    public void markAlive() {
        this.lastSeen = System.currentTimeMillis();
        this.failedPings = 0;
    }

    public void recordFailure() {
        this.failedPings++;
    }

    public boolean isDead() {
        return failedPings >= MAX_FAILED_PINGS;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KadContact that = (KadContact) o;
        return udpPort == that.udpPort && id.equals(that.id) && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, address, udpPort);
    }

    @Override
    public String toString() {
        return "KadContact{" +
                "id=" + id.toHexString() +
                ", addr=" + address.getHostAddress() +
                ":" + udpPort +
                ", v=" + (kadVersion & 0xFF) +
                ", failed=" + failedPings +
                '}';
    }
}

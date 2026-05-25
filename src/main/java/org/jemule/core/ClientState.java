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


package org.jemule.core;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

public final class ClientState {
    private final InetAddress address;
    private final int port;
    private final int clientId;
    private final long connectedAt;
    private final AtomicLong lastActivity;
    private final AtomicInteger publishedFilesCount = new AtomicInteger(0);
    private boolean zlibSupported = false;
    private List<FileMetadata> pendingSearchResults;

    public ClientState(InetAddress address, int port, int clientId, long connectedAt, AtomicLong lastActivity) {
        this.address = address;
        this.port = port;
        this.clientId = clientId;
        this.connectedAt = connectedAt;
        this.lastActivity = lastActivity;
    }

    public InetAddress address() { return address; }
    public int port() { return port; }
    public int clientId() { return clientId; }
    public long connectedAt() { return connectedAt; }
    public AtomicLong lastActivity() { return lastActivity; }

    public AtomicInteger publishedFilesCount() { return publishedFilesCount; }

    public boolean isZlibSupported() { return zlibSupported; }
    public void setZlibSupported(boolean zlibSupported) { this.zlibSupported = zlibSupported; }

    public List<FileMetadata> getPendingSearchResults() { return pendingSearchResults; }
    public void setPendingSearchResults(List<FileMetadata> results) { this.pendingSearchResults = results; }

    public static int ipToInt(InetAddress addr) {
        byte[] b = addr.getAddress();
        // If IPv4 (4 bytes) -> convert normally (little-endian expected by protocol)
        if (b.length == 4) {
            return ((b[3] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF);
        }
        // If IPv6 and IPv4-mapped (::ffff:0:0/96), use the last 4 bytes
        if (b.length == 16) {
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) isV4Mapped = false;
            if (isV4Mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                int off = 12;
                return ((b[off + 3] & 0xFF) << 24) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 1] & 0xFF) << 8) | (b[off] & 0xFF);
            }
        }
        // Fallback: return 0 (unspecified) when IPv6 address cannot be encoded in 4 bytes
        return 0;
    }

    public boolean isHighId() {
        return clientId > 0x00FFFFFF;
    }
}

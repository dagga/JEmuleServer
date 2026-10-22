package org.jemule.network;

import org.jemule.protocol.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Downloads and parses an eD2K server.met file without replacing existing server candidates.
 */
final class ServerMetLoader {
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    private static final long MAX_SERVER_COUNT = 100_000;
    private static final int MAX_TAG_COUNT = 50_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private ServerMetLoader() {
    }

    static int load(String source, ServerGossip gossip) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid server.met URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("server.met URL must be an HTTPS URL without user information");
        }

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = org.jemule.core.SharedHttpClient.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("server.met returned HTTP status " + response.statusCode());
            }
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                throw new IOException("server.met redirected to a non-HTTPS URL");
            }
            byte[] bytes = body.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("server.met exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
            }
            return gossip.addServerMetCandidates(parse(bytes));
        }
    }

    static List<ServerGossip.Endpoint> parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 5) {
            throw new IOException("server.met is too short");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = Byte.toUnsignedInt(input.get());
        if (version != 0xE0) {
            throw new IOException("Unsupported server.met version 0x" + Integer.toHexString(version));
        }
        long count = Integer.toUnsignedLong(input.getInt());
        if (count > MAX_SERVER_COUNT) {
            throw new IOException("server.met contains too many server records: " + count);
        }

        LinkedHashSet<ServerGossip.Endpoint> endpoints = new LinkedHashSet<>();
        for (long i = 0; i < count; i++) {
            if (input.remaining() < 10) {
                throw new IOException("Truncated server.met record " + i);
            }
            byte[] addressBytes = new byte[4];
            input.get(addressBytes);
            int port = Short.toUnsignedInt(input.getShort());
            long tagCount = Integer.toUnsignedLong(input.getInt());
            if (tagCount > MAX_TAG_COUNT || tagCount > input.remaining() / 2) {
                throw new IOException("Invalid tag count in server.met record " + i);
            }
            try {
                for (long tag = 0; tag < tagCount; tag++) {
                    Tag.read(input);
                }
                InetAddress address = InetAddress.getByAddress(addressBytes);
                if (port > 0 && port <= 65531 && !address.isAnyLocalAddress()
                        && !address.isMulticastAddress() && !address.isLoopbackAddress()) {
                    endpoints.add(new ServerGossip.Endpoint(address, port));
                }
            } catch (java.net.UnknownHostException | RuntimeException e) {
                throw new IOException("Invalid server.met record " + i, e);
            }
        }
        if (input.hasRemaining()) {
            throw new IOException("Unexpected trailing data in server.met");
        }
        return new ArrayList<>(endpoints);
    }
}

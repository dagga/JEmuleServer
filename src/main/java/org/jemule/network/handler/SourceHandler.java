package org.jemule.network.handler;

import org.jemule.core.ClientState;
import org.jemule.core.event.ClientEvent;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SourceHandler {
    private static final Logger log = LoggerFactory.getLogger(SourceHandler.class);

    public void handleGetSources(ClientContext context, Packet packet, OutputStream out) throws IOException {
        byte[] data = packet.data();
        String hash = null;
        byte[] hashBytes = null;

        if (data.length == 16) {
            hashBytes = data;
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02x", b));
            hash = sb.toString();
        } else if (data.length == 32) {
            hash = new String(data, StandardCharsets.UTF_8).trim();
            if (HandlerUtils.isValidHash(hash)) {
                hashBytes = HandlerUtils.hashToBytes(hash);
            }
        }

        if (hash == null && data.length > 16) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                byte[] potentialHash = new byte[16];
                buf.get(potentialHash);
                hashBytes = potentialHash;
                StringBuilder sb = new StringBuilder();
                for (byte b : potentialHash) sb.append(String.format("%02x", b));
                hash = sb.toString();

                log.debug("Extracted hash from extended GET_SOURCES: {}", HandlerUtils.sanitize(hash));
            } catch (Exception e) {
                log.warn("Failed to extract hash from extended GET_SOURCES: {}", HandlerUtils.sanitize(e.getMessage()));
            }
        }

        if (!HandlerUtils.isValidHash(hash)) {
            log.warn("Invalid hash format/length for GET_SOURCES: {}", data.length);
            return;
        }

        log.info("Client requested sources for hash: {}", HandlerUtils.sanitize(hash));

        var sources = context.getFileIndex().getSources(hash, context.getState(), context.getConfig().maxSourcesPerFile());
        byte[] response = encodeFoundSources(hashBytes, sources);

        byte responseProtocol = Packet.PROTOCOL_ED2K;
        byte responseOpcode = OpCode.FOUND_SOURCES.value;

        if (packet.protocol() == Packet.PROTOCOL_EMULE) {
            responseProtocol = Packet.PROTOCOL_EMULE;
            responseOpcode = OpCode.SOURCES_RESULT_OBFU.value;
            log.debug("Responding to GET_SOURCES_OBFU with SOURCES_RESULT_OBFU (0xC5:0x24)");
        } else {
            log.debug("Responding to GET_SOURCES with FOUND_SOURCES (0xE3:0x42)");
        }

        new Packet(responseProtocol, responseOpcode, response).write(out, context.getState().isZlibSupported());
    }

    /**
     * Keeps only the sources another client can actually contact: their ID (HighID = IP) and the TCP port
     * they listen on, as announced in their OP_LOGINREQUEST. The port of the client's own connection to the
     * server is an ephemeral port and must never be handed out.
     */
    public static List<ClientState> reachableSources(List<ClientState> sources) {
        return sources.stream().filter(s -> s.listenPort() > 0 && s.clientId() != 0).toList();
    }

    /**
     * Encodes one source block <code>&lt;hash 16&gt;&lt;count 1&gt;(&lt;ID 4&gt;&lt;port 2&gt;)[count]</code>, all integers
     * little-endian as read by CPartFile::AddSources. Used by OP_FOUNDSOURCES (TCP) and OP_GLOBFOUNDSOURCES (UDP).
     */
    public static byte[] encodeFoundSources(byte[] hashBytes, List<ClientState> sources) {
        List<ClientState> usable = reachableSources(sources);
        int count = Math.min(usable.size(), 255);
        ByteBuffer buf = ByteBuffer.allocate(16 + 1 + count * 6).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hashBytes);
        buf.put((byte) count);
        for (int i = 0; i < count; i++) {
            ClientState s = usable.get(i);
            buf.putInt((int) s.clientId());
            buf.putShort((short) s.listenPort());
        }
        return buf.array();
    }

    public void handleCallback(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 4) {
            log.warn("Invalid CALLBACK request: data too short");
            return;
        }

        long targetId = Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt());
        log.info("Client {} requested callback to ID: {}", context.getState().clientId(), targetId);

        ClientState targetClient = context.getRegistry().get(targetId);
        if (targetClient == null) {
            log.warn("CALLBACK target not found: {}", targetId);
            return;
        }

        ByteBuffer relayData = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        relayData.putInt((int) ClientState.ipToLong(context.getState().address()));
        relayData.putShort(Short.reverseBytes((short) context.getState().port()));

        Packet callbackPacket = new Packet(Packet.PROTOCOL_ED2K, OpCode.CALLBACK.value, relayData.array());
        context.getRegistry().sendTo(targetId, callbackPacket);

        if (context.getEventManager() != null) {
            context.getEventManager().broadcast(new ClientEvent("CALLBACK_RELAY",
                    String.valueOf(targetId),
                    String.valueOf(context.getState().clientId()),
                    "Relaying callback request"));
        }
    }
}

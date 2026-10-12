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
import java.util.Arrays;
import java.util.List;

public class SourceHandler {
    private static final Logger log = LoggerFactory.getLogger(SourceHandler.class);

    /**
     * Handles OP_GETSOURCES (0x19) and OP_GETSOURCES_OBFU (0x23), both sent in protocol 0xE3 by
     * CDownloadQueue::ProcessLocalRequests: <code>&lt;hash 16&gt;&lt;size 4&gt;</code> or, for files &gt; 4 GB,
     * <code>&lt;hash 16&gt;&lt;0 4&gt;&lt;size 8&gt;</code>. The answer is always sent in 0xE3 (the client ignores any other
     * protocol on its server socket): OP_FOUNDSOURCES_OBFU (0x44) for an _OBFU request, OP_FOUNDSOURCES (0x42) otherwise.
     */
    public void handleGetSources(ClientContext context, Packet packet, OutputStream out) throws IOException {
        byte[] data = packet.data();
        if (data == null || data.length < 16) {
            log.warn("Invalid GET_SOURCES request: {} bytes", data == null ? 0 : data.length);
            return;
        }
        byte[] hashBytes = Arrays.copyOf(data, 16);
        String hash = HandlerUtils.bytesToHashString(hashBytes);
        boolean obfu = packet.opcode() == OpCode.GET_SOURCES_OBFU.value;
        log.debug("Client {} requested sources for {} ({})", context.getState().clientId(), hash, obfu ? "OBFU" : "plain");

        var sources = context.getFileIndex().getSources(hash, context.getState(), context.getConfig().maxSourcesPerFile());
        byte[] response = obfu ? encodeFoundSourcesObfu(hashBytes, sources) : encodeFoundSources(hashBytes, sources);
        byte opcode = obfu ? OpCode.SOURCES_RESULT_OBFU.value : OpCode.FOUND_SOURCES.value;
        new Packet(Packet.PROTOCOL_ED2K, opcode, response).write(out, context.getState().isZlibSupported());
    }

    /** Connect options of a source as understood by CUpDownClient::SetConnectOptions. */
    public static int cryptOptions(ClientState s) {
        int options = 0;
        if (s.supportsCrypt()) options |= 0x01;
        if (s.requestsCrypt()) options |= 0x02;
        if (s.requiresCrypt()) options |= 0x04;
        return options;
    }

    /**
     * Encodes OP_FOUNDSOURCES_OBFU: <code>&lt;hash 16&gt;&lt;count 1&gt;(&lt;ID 4&gt;&lt;port 2&gt;&lt;cryptOptions 1&gt;[&lt;userhash 16&gt;])[count]</code>.
     * The user hash (flag 0x80) is sent for sources supporting the crypt layer: the requester needs it to open an
     * obfuscated connection (CPartFile::AddSources).
     */
    public static byte[] encodeFoundSourcesObfu(byte[] hashBytes, List<ClientState> sources) {
        List<ClientState> usable = reachableSources(sources);
        int count = Math.min(usable.size(), 255);
        ByteBuffer buf = ByteBuffer.allocate(16 + 1 + count * (6 + 1 + 16)).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hashBytes);
        buf.put((byte) count);
        for (int i = 0; i < count; i++) {
            ClientState s = usable.get(i);
            int options = cryptOptions(s);
            boolean withHash = (options & 0x01) != 0;
            buf.putInt((int) s.clientId());
            buf.putShort((short) s.listenPort());
            buf.put((byte) (options | (withHash ? 0x80 : 0)));
            if (withHash) buf.put(s.userHash());
        }
        return Arrays.copyOf(buf.array(), buf.position());
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

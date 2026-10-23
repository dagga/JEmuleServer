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
        List<FoundSource> answer = FoundSource.of(reachableSources(sources));
        // With few sources here, Kad is asked too: its sources come in a second answer
        org.jemule.network.KadSourceService kad = context.getKadSourceService();
        if (kad != null) {
            answer = kad.onGetSources(context.getState(), hashBytes, requestedSize(data), obfu, answer);
        }
        byte[] response = obfu ? encodeObfu(hashBytes, answer) : encodePlain(hashBytes, answer);
        byte opcode = obfu ? OpCode.SOURCES_RESULT_OBFU.value : OpCode.FOUND_SOURCES.value;
        new Packet(Packet.PROTOCOL_ED2K, opcode, response).write(out, context.getState().isZlibSupported());
    }

    /** File size of an OP_GETSOURCES: <code>&lt;size 4&gt;</code>, or <code>&lt;0 4&gt;&lt;size 8&gt;</code> above 4 GB; 0 if absent. */
    static long requestedSize(byte[] data) {
        if (data.length < 20) return 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long size = Integer.toUnsignedLong(buf.getInt(16));
        if (size == 0 && data.length >= 28) size = buf.getLong(20);
        return size;
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
        return encodeObfu(hashBytes, FoundSource.of(reachableSources(sources)));
    }

    /** OP_FOUNDSOURCES_OBFU of {@code sources}, at most 255. */
    public static byte[] encodeObfu(byte[] hashBytes, List<FoundSource> sources) {
        int count = Math.min(sources.size(), 255);
        ByteBuffer buf = ByteBuffer.allocate(16 + 1 + count * (6 + 1 + 16)).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hashBytes);
        buf.put((byte) count);
        for (int i = 0; i < count; i++) {
            FoundSource s = sources.get(i);
            int options = s.cryptOptions() & 0x07;
            boolean withHash = (options & 0x01) != 0 && s.userHash() != null;
            buf.putInt((int) s.id());
            buf.putShort((short) s.port());
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
        return encodePlain(hashBytes, FoundSource.of(reachableSources(sources)));
    }

    /** OP_FOUNDSOURCES of {@code sources}, at most 255. */
    public static byte[] encodePlain(byte[] hashBytes, List<FoundSource> sources) {
        int count = Math.min(sources.size(), 255);
        ByteBuffer buf = ByteBuffer.allocate(16 + 1 + count * 6).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hashBytes);
        buf.put((byte) count);
        for (int i = 0; i < count; i++) {
            FoundSource s = sources.get(i);
            buf.putInt((int) s.id());
            buf.putShort((short) s.port());
        }
        return buf.array();
    }

    /**
     * OP_CALLBACKREQUEST <code>&lt;ID 4&gt;</code> (CUpDownClient::TryToConnect): a client wants to reach a LowID client of
     * this server. The target receives OP_CALLBACKREQUESTED <code>&lt;IP 4&gt;&lt;port 2&gt;&lt;cryptOptions 1&gt;&lt;userhash 16&gt;</code>
     * describing the requester, and connects to it. When both clients have a LowID, the requester also receives
     * the target's endpoint so both clients can initiate TCP connections for NAT hole punching. If the target is
     * not connected the requester gets OP_CALLBACK_FAIL (CServerSocket::ProcessPacket).
     */
    public void handleCallback(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 4) {
            log.warn("Invalid CALLBACK request: data too short");
            return;
        }

        ClientState requester = context.getState();
        long targetId = Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt());
        ClientState target = context.getRegistry().get(targetId);
        long requesterIp = ClientState.ipToLong(requester.address());
        if (target == null || requesterIp == 0 || requester.listenPort() == 0) {
            log.info("Callback from {} to ID {} failed (target {})", requester.clientId(), targetId, target == null ? "not connected" : "found");
            new Packet(Packet.PROTOCOL_ED2K, OpCode.CALLBACK_FAIL.value, new byte[0]).write(out, requester.isZlibSupported());
            return;
        }

        context.getRegistry().sendTo(targetId, callbackRequested(requester));
        log.debug("Relayed callback request from {} to LowID {}", requester.clientId(), targetId);

        if (!requester.isHighId() && !target.isHighId()) {
            long targetIp = ClientState.ipToLong(target.address());
            if (targetIp != 0 && target.listenPort() > 0) {
                callbackRequested(target).write(out, requester.isZlibSupported());
                log.debug("Sent reciprocal callback from LowID {} to LowID {} for TCP hole punching",
                        requester.clientId(), target.clientId());
            }
        }

        if (context.getEventManager() != null) {
            context.getEventManager().broadcast(new ClientEvent("CALLBACK_RELAY",
                    String.valueOf(targetId),
                    String.valueOf(requester.clientId()),
                    "Relaying callback request"));
        }
    }

    private static Packet callbackRequested(ClientState peer) {
        long peerIp = ClientState.ipToLong(peer.address());
        ByteBuffer data = ByteBuffer.allocate(4 + 2 + 1 + 16).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt((int) peerIp);
        data.putShort((short) peer.listenPort());
        data.put((byte) cryptOptions(peer));
        data.put(peer.userHash());
        return new Packet(Packet.PROTOCOL_ED2K, OpCode.CALLBACK_REQUESTED.value, data.array());
    }
}

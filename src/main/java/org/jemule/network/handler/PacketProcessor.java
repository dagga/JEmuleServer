package org.jemule.network.handler;

import org.jemule.Main;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PacketProcessor {
    private static final Logger log = LoggerFactory.getLogger(PacketProcessor.class);

    private final SearchHandler searchHandler = new SearchHandler();
    private final PublishHandler publishHandler = new PublishHandler();
    private final SourceHandler sourceHandler = new SourceHandler();

    public void processPacket(ClientContext context, Packet p, OutputStream out) throws IOException {
        context.getState().lastActivity().set(System.currentTimeMillis());
        OpCode op = OpCode.fromByte(p.protocol(), p.opcode());
        if (op == null) {
            log.info("Unknown opcode received: 0x" + String.format("%02X", p.opcode()) + " (Proto: 0x" + String.format("%02X", p.protocol()) + "), Data length: " + (p.data() != null ? p.data().length : 0));
            return;
        }

        log.info("Processing packet: " + op + " (Proto: 0x" + String.format("%02X", p.protocol()) + ", Data length: " + (p.data() != null ? p.data().length : 0) + ")");

        switch (op) {
            case SEARCH_REQUEST -> searchHandler.handleSearch(context, p.data(), out);
            case QUERY_MORE_RESULT -> searchHandler.handleQueryMoreResult(context, out);
            case PUBLISH_FILES, OFFER_FILES -> publishHandler.handlePublish(context, op, p.data(), out);
            case GET_SOURCES, GET_SOURCES_OBFU -> sourceHandler.handleGetSources(context, p, out);
            case EMULE_INFO -> handleEmuleInfo(context, p.data(), out);
            case CALLBACK -> sourceHandler.handleCallback(context, p.data(), out);
            case COMPRESSED_PART -> handleCompressedPart(context, p.data(), out);
            case FOUND_SOURCES, SOURCES_RESULT_OBFU -> handleSourcesResult(context, p, out);
            case GET_SERVER_LIST -> sendServerList(context, out);
            case DISCONNECT -> handleDisconnect(context);
            default -> log.debug("Unhandled: {} (Proto: 0x{})", op, String.format("%02X", p.protocol()));
        }
    }

    private void handleDisconnect(ClientContext context) throws IOException {
        log.info("Client {} requested disconnect", context.getState().clientId());
        context.disconnect();
    }

    private void handleSourcesResult(ClientContext context, Packet p, OutputStream out) throws IOException {
        byte[] data = p.data();
        int len = data == null ? 0 : data.length;
        log.info("Received SOURCES_RESULT (Proto: 0x{}), length={}", String.format("%02X", p.protocol()), len);
        if (len > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(64, data.length); i++) {
                sb.append(String.format("%02X", data[i])).append(' ');
            }
            log.debug("SOURCES_RESULT payload (first {} bytes): {}", Math.min(64, data.length), sb.toString().trim());
        } else {
            log.debug("SOURCES_RESULT payload is empty (client may be indicating no sources or using it as keepalive)");
        }
    }

    private void handleEmuleInfo(ClientContext context, byte[] data, OutputStream out) throws IOException {
        log.debug("Received EMULE_INFO from {}", context.getSocket().getRemoteSocketAddress() != null ? HandlerUtils.sanitize(context.getSocket().getRemoteSocketAddress().toString()) : "unknown");
        String serverName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String serverVersion = Main.ESERVER_VERSION;
        byte[] nameBytes = serverName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] versionBytes = serverVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + nameBytes.length + 1 + versionBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.put((byte) versionBytes.length);
        buf.put(versionBytes);
        new Packet(Packet.PROTOCOL_EMULE, OpCode.EMULE_INFO_ACK.value, buf.array()).write(out, context.getState().isZlibSupported());
    }

    private void handleCompressedPart(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 1) {
            log.warn("Invalid COMPRESSED_PART request: empty data");
            return;
        }
        log.debug("Received COMPRESSED_PART from client {} (size: {} bytes)", context.getState().clientId(), data.length);
    }

    private void sendServerList(ClientContext context, OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_LIST.value, buf.array()).write(out, context.getState().isZlibSupported());
        log.debug("Sent SERVER_LIST (empty) to client {}", context.getState().clientId());
    }
}

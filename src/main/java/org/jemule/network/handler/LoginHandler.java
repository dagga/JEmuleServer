package org.jemule.network.handler;

import org.jemule.Main;
import org.jemule.core.ClientState;
import org.jemule.core.event.ClientEvent;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LoginHandler {
    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    public void handleLogin(ClientContext context, Packet initialPacket, OutputStream out) throws IOException {
        if (context.getWrappedOut() != null) out = context.getWrappedOut();
        byte[] data = initialPacket.data();
        log.info("Handling login request, data size: {} bytes", data.length);

        int clientId = ByteBuffer.wrap(context.getSocket().getInetAddress().getAddress()).order(ByteOrder.LITTLE_ENDIAN).getInt();

        ClientState state = context.getClientFactory().createClient(context.getSocket().getInetAddress(), context.getSocket().getPort(), clientId);
        context.setState(state);

        if (context.getEventManager() != null) {
            context.getEventManager().broadcast(new ClientEvent(ClientEvent.LOGIN, context.getSocket().getInetAddress().getHostAddress(), "ID:" + clientId, "Client logged in with ID " + clientId));
        }

        if (initialPacket.protocol() == Packet.PROTOCOL_ZLIB) {
            state.setZlibSupported(true);
            log.info("Client supports ZLIB (detected from initial packet)");
        }

        OutputStream finalOut = out;
        context.getRegistry().add(state, (p) -> {
            try {
                p.write(finalOut, state.isZlibSupported());
            } catch (IOException e) {
                log.error("Failed to send relayed packet to {}: {}", state.clientId(), e.getMessage());
            }
        });

        sendServerIdent(context, out);

        ByteBuffer resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        resp.putInt(clientId);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value, resp.array()).write(out, state.isZlibSupported());
        new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_ACCEPTED.value, resp.array()).write(out, state.isZlibSupported());

        log.info("Logged in ID: {} (Sent 0x40 and 0x1B)", clientId);

        sendServerMessage(context, out, "Welcome to " + Main.VERSION + " (JEmuleServer)\n" +
                "Your ID is: " + Integer.toUnsignedString(clientId) + "\n" +
                "Enjoy the extended protocol support!");

        sendServerStatus(context, out);
        sendAskSharedFiles(context, out);

        try {
            Thread.sleep(100);
            sendServerStatus(context, out);
            log.debug("Sent additional SERVER_STATUS to encourage file publishing");

            final OutputStream statusOut = out;
            Thread statusThread = new Thread(() -> {
                try {
                    for (int i = 0; i < 30 && !context.getSocket().isClosed(); i++) {
                        Thread.sleep(10000);
                        if (!context.getSocket().isClosed()) {
                            sendServerStatus(context, statusOut);
                            log.debug("Sent periodic SERVER_STATUS #{} to encourage file publishing", i + 2);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    log.debug("Failed to send periodic status update: {}", e.getMessage());
                }
            });
            statusThread.setDaemon(true);
            statusThread.start();
            log.info("Started periodic status updates thread for client {}", clientId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void sendServerMessage(ClientContext context, OutputStream out, String msg) throws IOException {
        byte[] content = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) content.length);
        buf.put(content);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_MESSAGE.value, buf.array()).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    private void sendServerIdent(ClientContext context, OutputStream out) throws IOException {
        byte[] hash = new byte[16];
        int portInt = context.getConfig().port();

        String serverName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String serverVersion = Main.VERSION + " (JEmuleServer)";
        String desc = "Experimental eMule Server";

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_VERSION, serverVersion));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_EMULE_VERSION, 0x3C));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, 0x01 | 0x04 | 0x08 | 0x10));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_AUX_PORT, portInt));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_USERS, context.getConfig().maxUsers()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_FILES, context.getConfig().maxFiles()));

        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        byte[] addr = context.getSocket().getLocalAddress().getAddress();
        if (addr.length == 4) {
            buf.put(addr);
        } else {
            buf.put((byte) 0x7F);
            buf.put((byte) 0x00);
            buf.put((byte) 0x00);
            buf.put((byte) 0x01);
        }
        buf.put((byte) ((portInt >> 8) & 0xFF));
        buf.put((byte) (portInt & 0xFF));

        Tag.writeList(buf, tags);
        buf.flip();
        byte[] response = new byte[buf.remaining()];
        buf.get(response);

        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, response).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    public static void sendServerStatus(ClientContext context, OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(context.getRegistry().size());
        buf.putInt(context.getFileIndex().fileCount());
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_STATUS.value, buf.array()).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    private void sendAskSharedFiles(ClientContext context, OutputStream out) {
        log.info("Sending ASK_SHARED_FILES to client {}", context.getState().clientId());
        try {
            new Packet(Packet.PROTOCOL_EMULE, OpCode.ASK_SHARED_FILES.value, new byte[0]).write(out, context.getState() != null && context.getState().isZlibSupported());
        } catch (IOException e) {
            log.warn("Failed to send ASK_SHARED_FILES: {}", e.getMessage());
        }
        try {
            new Packet(Packet.PROTOCOL_ED2K, OpCode.ASK_SHARED_FILES.value, new byte[0]).write(out, context.getState() != null && context.getState().isZlibSupported());
            log.debug("Also sent ASK_SHARED_FILES as ED2K for compatibility");
        } catch (Exception e) {
            log.debug("Failed to send fallback ED2K ASK_SHARED_FILES: {}", e.getMessage());
        }
    }
}

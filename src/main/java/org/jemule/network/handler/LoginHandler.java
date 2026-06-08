package org.jemule.network.handler;

import org.jemule.Main;
import org.jemule.core.ClientState;
import org.jemule.core.event.ClientEvent;
import org.jemule.network.Packet;
import org.jemule.network.Server;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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

        long clientId = ClientState.ipToLong(context.getSocket().getInetAddress());

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

        // Send server version FIRST on its own line for eMule auto-detection
        sendServerMessage(context, out, "server version 17.15");

        sendServerIdent(context, out);
        
        // --- Corrected ID_CHANGE packet construction matching eMule's expectations ---
        // eMule's ServerSocket.cpp expects: <NEW_ID 4>[<server_flags 4>]
        // Flags: 0x01=COMPRESSION, 0x08=NEWTAGS, 0x10=UNICODE, 0x80=TYPETAGINTEGER, 0x100=LARGEFILES, 0x400=TCPOBFUSCATION
        int serverFlags = 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400;

        ByteBuffer idChange = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        idChange.putInt((int) clientId);
        idChange.putInt(serverFlags);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value, idChange.array()).write(out, state.isZlibSupported());

        ByteBuffer loginAccepted = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        loginAccepted.putInt((int) clientId);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_ACCEPTED.value, loginAccepted.array()).write(out, state.isZlibSupported());

        log.info("Logged in ID: {} (Sent 0x41, 0x40 and 0x1B)", clientId);

        sendServerStatus(context, out);
        sendAskSharedFiles(context, out);

        log.info("Your ID is: " + Long.toUnsignedString(clientId));
        sendServerMessage(context, out, "Welcome to " + Main.VERSION + " (JEmuleServer)\n" +
                "Your ID is: " + Long.toUnsignedString(clientId) + "\n" +
                "Enjoy the extended protocol support!");

        // Try to parse client's listening UDP/TCP port from the initial login packet and proactively send a UDP GLOBSERVSTATRES
        int clientUdpPort = -1;
        try {
            ByteBuffer inBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (inBuf.remaining() >= 22) { // 16(hash) + 4(id) + 2(port)
                byte[] clientHash = new byte[16];
                inBuf.get(clientHash);
                long idFromPacket = Integer.toUnsignedLong(inBuf.getInt());
                clientUdpPort = Short.toUnsignedInt(inBuf.getShort());
                log.info("Parsed client listening port from login packet: {} (id from packet: {})", clientUdpPort, Long.toUnsignedString(idFromPacket));
            } else {
                log.debug("Login packet too short to extract client listening port (len={})", inBuf.remaining());
            }
        } catch (Exception e) {
            log.debug("Failed to parse client listening port from login packet: {}", e.getMessage());
        }

        if (clientUdpPort > 0) {
            // Send a UDP GLOBSERVSTATRES with challenge == 0 which clients commonly accept when no challenge was issued
            sendUdpStatusToClient(context, context.getSocket().getInetAddress(), clientUdpPort);
        }

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
        InetAddress publicIp = null;
        if (context.getServer() != null) {
            publicIp = context.getServer().getPublicIp();
        } else {
            try {
                publicIp = InetAddress.getLocalHost();
            } catch (Exception e) {
                publicIp = InetAddress.getLoopbackAddress();
            }
        }

        String serverName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String serverVersion = Main.ESERVER_VERSION;
        String desc = "Experimental eMule Server";

        int maxFiles = context.getConfig().maxFiles();
        int maxUsers = context.getConfig().maxUsers();

        // TCP capability flags matching eMule SRV_TCPFLG_ constants
        int tcpFlags = Tag.TCPFLG_COMPRESSION | Tag.TCPFLG_NEWTAGS | Tag.TCPFLG_UNICODE | Tag.TCPFLG_TYPETAGINTEGER | Tag.TCPFLG_LARGEFILES | Tag.UDPFLG_UDPOBFUSCATION | Tag.TCPFLG_TCPOBFUSCATION;

        // UDP capability flags matching eMule SRV_UDPFLG_ constants
        int udpFlags = Tag.UDPFLG_EXT_GETSOURCES | Tag.UDPFLG_NEWTAGS | Tag.UDPFLG_UNICODE | Tag.UDPFLG_LARGEFILES | Tag.UDPFLG_UDPOBFUSCATION | Tag.UDPFLG_TCPOBFUSCATION;

        List<Tag> tags = new ArrayList<>();
        // Important: Standard Lugdunum/eMule servers often send critical stats first
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAXUSERS, maxUsers));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOFTFILES, 1000000));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_HARDFILES, 2000000));
        
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        // ST_VERSION as string for better compatibility
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVER_VERSION, "17"));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, tcpFlags));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_LOWIDUSERS, context.getRegistry().lowIdCount()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPFLAGS, udpFlags));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEY, org.jemule.network.Server.getUdpKey())); // ST_UDPKEY
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEYIP, (int) ClientState.ipToLong(publicIp))); // ST_UDPKEYIP
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCPPORTOBFUSCATION, portInt)); // ST_TCPPORTOBFUSCATION
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPPORTOBFUSCATION, portInt)); // ST_UDPPORTOBFUSCATION

        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        byte[] addr = publicIp.getAddress();
        if (addr.length == 4) {
            buf.put(addr);
        } else {
            buf.put((byte) 0x7F);
            buf.put((byte) 0x00);
            buf.put((byte) 0x00);
            buf.put((byte) 0x01);
        }
        // Port is 2 bytes, written as LITTLE_ENDIAN as per ByteBuffer order
        buf.putShort((short) portInt);

        Tag.writeList(buf, tags);
        buf.flip();
        byte[] response = new byte[buf.remaining()];
        buf.get(response);

        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, response).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    public static void sendServerStatus(ClientContext context, OutputStream out) throws IOException {
        // Corrected: The client expects 2 integers in SERVER_STATUS: Users, Files
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(context.getRegistry().size()); // Users
        buf.putInt(context.getFileIndex().fileCount()); // Files
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

    /**
     * Proactively send a UDP GLOBSERVSTATRES and SERVER_DESC_RES to the given client address/port.
     * Attempts to use the server's bound UDP socket(s) (so source port is 4661/4665) for better NAT compatibility.
     */
    private void sendUdpStatusToClient(ClientContext context, java.net.InetAddress addr, int port) {
        // Build OP_GLOBSERVSTATRES
        try {
            int users = context.getRegistry().size();
            int files = context.getFileIndex().fileCount();
            int maxUsers = context.getConfig().maxUsers();
            int maxFiles = context.getConfig().maxFiles();
            int lowIdUsers = context.getRegistry().lowIdCount();
            int udpPort = context.getConfig().port() + 4;
            int tcpPort = context.getConfig().port();
            int udpKey = Server.getUdpKey();

            // UDP Flags matching eMule's expectations (from server.h)
            int udpFlags = Tag.UDPFLG_EXT_GETSOURCES | Tag.UDPFLG_NEWTAGS | Tag.UDPFLG_UNICODE | Tag.UDPFLG_LARGEFILES | Tag.UDPFLG_UDPOBFUSCATION | Tag.UDPFLG_TCPOBFUSCATION;

            ByteBuffer resp = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
            resp.putInt(0); // challenge == 0 (unsolicited)
            resp.putInt(users);
            resp.putInt(files);
            resp.putInt(maxUsers);
            resp.putInt(1000000); // SoftFiles (Standard Lugdunum value)
            resp.putInt(2000000); // HardFiles (Standard Lugdunum value)
            resp.putInt(udpFlags);
            resp.putInt(lowIdUsers);
            resp.putShort((short) udpPort);
            resp.putShort((short) tcpPort);
            resp.putInt(udpKey);
            resp.flip();
            byte[] globOut = new byte[resp.remaining()];
            resp.get(globOut);

            boolean sent = Server.sendUdpFromBoundPort(context.getConfig().port(), globOut, addr, port);
            if (!sent && context.getConfig().port() <= 0xFFFF - 4) sent = Server.sendUdpFromBoundPort(context.getConfig().port() + 4, globOut, addr, port);
            if (!sent) {
                try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
                    java.net.DatagramPacket dp = new java.net.DatagramPacket(globOut, globOut.length, addr, port);
                    ds.send(dp);
                    log.info("Fallback: Sent UDP GLOBSERVSTATRES from ephemeral socket to {}:{}", addr.getHostAddress(), port);
                } catch (Exception e) {
                    log.debug("Failed to send UDP status to client {}:{} - {}", addr, port, e.getMessage());
                }
            } else {
                log.info("Sent UDP GLOBSERVSTATRES to {}:{} via bound socket", addr.getHostAddress(), port);
            }

            // Build OP_SERVER_DESC_RES using old format <name_len><name><desc_len><desc> which clients accept without challenge
            String sName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
            String sVersion = Main.ESERVER_VERSION;
            String sDesc = "Experimental eMule Server";
            byte[] nameBytes = sName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] descBytes = sDesc.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            ByteBuffer descBuf = ByteBuffer.allocate(2 + 2 + nameBytes.length + 2 + descBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            descBuf.put(Packet.PROTOCOL_ED2K);
            descBuf.put((byte) 0xA3); // OP_SERVER_DESC_RES (old format)
            descBuf.putShort((short) nameBytes.length);
            descBuf.put(nameBytes);
            descBuf.putShort((short) descBytes.length);
            descBuf.put(descBytes);
            descBuf.flip();
            byte[] descOut = new byte[descBuf.remaining()];
            descBuf.get(descOut);

            boolean descSent = Server.sendUdpFromBoundPort(context.getConfig().port(), descOut, addr, port);
            if (!descSent && context.getConfig().port() <= 0xFFFF - 4) descSent = Server.sendUdpFromBoundPort(context.getConfig().port() + 4, descOut, addr, port);
            if (!descSent) {
                try (java.net.DatagramSocket ds2 = new java.net.DatagramSocket()) {
                    ds2.send(new java.net.DatagramPacket(descOut, descOut.length, addr, port));
                    log.info("Fallback: Sent UDP SERVER_DESC_RES (old format) from ephemeral socket to {}:{}", addr.getHostAddress(), port);
                } catch (Exception e) {
                    log.debug("Failed to send UDP server description to {}:{} - {}", addr, port, e.getMessage());
                }
            } else {
                log.info("Sent UDP SERVER_DESC_RES (old format) to {}:{} via bound socket", addr.getHostAddress(), port);
            }
        } catch (Exception e) {
            log.debug("Failed to build/send SERVER_DESC_RES: {}", e.getMessage());
        }
    }
}
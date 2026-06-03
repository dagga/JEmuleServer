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

        // --- Corrected ID_CHANGE packet construction ---
        // Format: <NEW_ID 4><server_flags 4><primary_tcp_port 4 (unused)><client_IP_address 4><obfuscation_port 4 (optional)>
        // Total 20 bytes when obfuscation port is included
        int serverFlags = 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400 | (0x3C << 16); // TCP flags with version in high word

        ByteBuffer idChange = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        idChange.putInt(clientId);
        idChange.putInt(serverFlags);
        idChange.putInt(context.getConfig().port()); // primary_tcp_port (4 bytes)
        idChange.putInt(ClientState.ipToInt(context.getSocket().getLocalAddress())); // client_IP_address (server's IP)
        idChange.putInt(context.getConfig().port()); // obfuscation port (advertised for TCP obfuscation)
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value, idChange.array()).write(out, state.isZlibSupported());

        ByteBuffer loginAccepted = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        loginAccepted.putInt(clientId);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_ACCEPTED.value, loginAccepted.array()).write(out, state.isZlibSupported());

        log.info("Logged in ID: {} (Sent 0x40 and 0x1B)", clientId);

        sendServerMessage(context, out, "Welcome to " + Main.VERSION + " (JEmuleServer)\n" +
                "Your ID is: " + Integer.toUnsignedString(clientId) + "\n" +
                "Enjoy the extended protocol support!");

        sendServerStatus(context, out);
        sendAskSharedFiles(context, out);

        // Try to parse client's listening UDP/TCP port from the initial login packet and proactively send a UDP GLOBSERVSTATRES
        int clientUdpPort = -1;
        try {
            ByteBuffer inBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (inBuf.remaining() >= 22) { // 16(hash) + 4(id) + 2(port)
                byte[] clientHash = new byte[16];
                inBuf.get(clientHash);
                int idFromPacket = inBuf.getInt();
                clientUdpPort = Short.toUnsignedInt(inBuf.getShort());
                log.info("Parsed client listening port from login packet: {} (id from packet: {})", clientUdpPort, Integer.toUnsignedString(idFromPacket));
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

        String serverName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String serverVersion = Main.ESERVER_VERSION;
        String desc = "Experimental eMule Server";

        int maxFiles = context.getConfig().maxFiles();
        int maxUsers = context.getConfig().maxUsers();

        // TCP capability flags matching eMule SRV_TCPFLG_ constants:
        // 0x01 = COMPRESSION, 0x08 = NEWTAGS, 0x10 = UNICODE,
        // 0x80 = TYPETAGINTEGER, 0x100 = LARGEFILES, 0x400 = TCPOBFUSCATION
        int tcpFlags = 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400;

        // UDP capability flags matching eMule SRV_UDPFLG_ constants:
        // 0x01 = EXT_GETSOURCES, 0x08 = NEWTAGS, 0x10 = UNICODE,
        // 0x100 = LARGEFILES, 0x400 = TCPOBFUSCATION
        int udpFlags = 0x01 | 0x08 | 0x10 | 0x100 | 0x400;

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_VERSION, serverVersion)); // CT_VERSION (string)
        // ST_VERSION as uint32: (major<<16) | minor when possible
        int serverVersionInt = 0x003C0000; // fallback (ED2K version in high word)
        try {
            String[] v = serverVersion.split("\\.");
            int maj = Integer.parseInt(v[0]);
            int min = v.length > 1 ? Integer.parseInt(v[1]) : 0;
            serverVersionInt = (maj << 16) | (min & 0xFFFF);
        } catch (Exception ignored) {}
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SERVER_VERSION, serverVersionInt)); // ST_VERSION numeric (major<<16|minor)
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, tcpFlags));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAXUSERS, maxUsers)); // ST_MAXUSERS
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOFTFILES, maxFiles)); // ST_SOFTFILES
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_HARDFILES, maxFiles)); // ST_HARDFILES
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_PREFERENCE, 0));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_LOWIDUSERS, context.getRegistry().lowIdCount())); // ST_LOWIDUSERS
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPFLAGS, udpFlags)); // ST_UDPFLAGS
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEY, org.jemule.network.Server.getUdpKey())); // ST_UDPKEY
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEYIP, ClientState.ipToInt(context.getSocket().getLocalAddress()))); // ST_UDPKEYIP
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCPPORTOBFUSCATION, portInt)); // ST_TCPPORTOBFUSCATION
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPPORTOBFUSCATION, portInt)); // ST_UDPPORTOBFUSCATION

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
            ByteBuffer resp = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
            resp.putInt(0); // challenge == 0 (unsolicited)
            resp.putInt(context.getRegistry().size());
            resp.putInt(context.getFileIndex().fileCount());
            resp.putInt(context.getConfig().maxUsers());
            resp.putInt(context.getConfig().maxFiles());
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

            // Build OP_SERVER_DESC_RES and send it as well
            String sName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
            String sVersion = Main.ESERVER_VERSION;
            String sDesc = "Experimental eMule Server";
            int maxFiles = context.getConfig().maxFiles();
            int maxUsers = context.getConfig().maxUsers();

            java.util.List<org.jemule.protocol.Tag> tags = new java.util.ArrayList<>();
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_SERVERNAME, sName));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_DESCRIPTION, sDesc));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_VERSION, sVersion));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAXUSERS, maxUsers));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAXFILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAX_USERS_V2, maxUsers));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SOFT_FILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_HARD_FILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_PREFERENCE, 0));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_EMULE_VERSION, 0x3C));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_FLAGS, 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SERVER_VERSION, 0x3C));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_LOWID_USERS, context.getRegistry().lowIdCount()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_FLAGS, 0x01 | 0x08 | 0x10 | 0x100 | 0x400));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY, org.jemule.network.Server.getUdpKey()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY_IP, ClientState.ipToInt(context.getSocket().getLocalAddress())));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_OBFUSCATION_PORT, context.getConfig().port()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPPORTOBFUSCATION, context.getConfig().port()));

            ByteBuffer descBuf = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
            descBuf.put(Packet.PROTOCOL_ED2K);
            descBuf.put((byte) 0xA3); // OP_SERVER_DESC_RES
            descBuf.putShort((short) context.getConfig().port());
            descBuf.putInt(ClientState.ipToInt(context.getSocket().getLocalAddress()));
            org.jemule.protocol.Tag.writeList(descBuf, tags);
            descBuf.flip();
            byte[] descOut = new byte[descBuf.remaining()];
            descBuf.get(descOut);

            boolean descSent = Server.sendUdpFromBoundPort(context.getConfig().port(), descOut, addr, port);
            if (!descSent && context.getConfig().port() <= 0xFFFF - 4) descSent = Server.sendUdpFromBoundPort(context.getConfig().port() + 4, descOut, addr, port);
            if (!descSent) {
                try (java.net.DatagramSocket ds2 = new java.net.DatagramSocket()) {
                    ds2.send(new java.net.DatagramPacket(descOut, descOut.length, addr, port));
                    log.info("Fallback: Sent UDP SERVER_DESC_RES from ephemeral socket to {}:{}", addr.getHostAddress(), port);
                } catch (Exception e) {
                    log.debug("Failed to send UDP server description to {}:{} - {}", addr, port, e.getMessage());
                }
            } else {
                log.info("Sent UDP SERVER_DESC_RES to {}:{} via bound socket", addr.getHostAddress(), port);
            }
        } catch (Exception e) {
            log.debug("Failed to build/send SERVER_DESC_RES: {}", e.getMessage());
        }
    }
}
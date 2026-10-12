package org.jemule.network.handler;

import org.jemule.Main;
import org.jemule.core.ClientState;
import org.jemule.core.event.ClientEvent;
import org.jemule.network.Packet;
import org.jemule.network.FirewallChecker;
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

        InetAddress clientIp = context.getSocket().getInetAddress();
        ClientState state = context.getClientFactory().createClient(clientIp, context.getSocket().getPort(), 0);
        context.setState(state);

        parseLoginRequest(state, data);
        if (initialPacket.protocol() == Packet.PROTOCOL_ZLIB || (state.serverCaps() & ClientState.SRVCAP_ZLIB) != 0) {
            state.setZlibSupported(true);
        }

        long clientId = assignClientId(context, state);
        state.setClientId(clientId);

        if (context.getEventManager() != null) {
            context.getEventManager().broadcast(new ClientEvent(ClientEvent.LOGIN, clientIp.getHostAddress(), "ID:" + clientId, "Client logged in with ID " + clientId));
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
        // eMule's ServerSocket.cpp expects: <NEW_ID 4>[<server_flags 4>][<pad 4>][<reported_ip 4>][<obf_tcp_port 4>]
        // Flags: 0x01=COMPRESSION, 0x08=NEWTAGS, 0x10=UNICODE, 0x80=TYPETAGINTEGER, 0x100=LARGEFILES, 0x200=UDPOBFUSCATION, 0x400=TCPOBFUSCATION
        int serverFlags = Tag.SERVER_TCP_FLAGS;

        ByteBuffer idChange = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        idChange.putInt((int) clientId);
        idChange.putInt(serverFlags);
        idChange.putInt(0); // padding/reserved
        idChange.putInt((int) ClientState.ipToLong(clientIp)); // client's IP as seen by the server (used by LowID clients)
        idChange.putInt(context.getConfig().port()); // Obfuscation TCP Port
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value, idChange.array()).write(out, state.isZlibSupported());

        log.info("Client {} logged in with ID {}", HandlerUtils.sanitize(state.nickname()), Long.toUnsignedString(clientId));

        sendServerStatus(context, out);
        sendServerMessage(context, out, "Welcome to JEmuleServer version " + Main.VERSION + "\n" +
                "Your ID is: " + Long.toUnsignedString(clientId));
    }

    /**
     * Chooses the client's ID like eD2K servers do: its IP (HighID) if it accepts incoming connections on
     * the TCP port announced in the login, a LowID (&lt; 2^24) otherwise. An IP whose ID would be below 2^24
     * (x.y.z.0) or that is not IPv4 can only get a LowID.
     */
    static long assignClientId(ClientContext context, ClientState state) {
        long ipId = ClientState.ipToLong(state.address());
        if (ipId > 0x00FFFFFFL && state.listenPort() > 0) {
            Server server = context.getServer();
            byte[] serverHash = server != null ? server.getServerHash() : new byte[16];
            InetAddress serverIp = server != null ? server.getPublicIp() : null;
            FirewallChecker checker = new FirewallChecker(context.getConfig().lowIdCheckTimeoutSeconds() * 1000);
            if (checker.isReachable(state.address(), state.listenPort(), serverHash, serverIp, context.getConfig().port())) {
                return ipId;
            }
        }
        long lowId = context.getRegistry().allocateLowId();
        log.info("Client {} ({}:{}) is not reachable: LowID {}", HandlerUtils.sanitize(state.nickname()),
                state.address().getHostAddress(), state.listenPort(), lowId);
        return lowId;
    }

    /**
     * Parses OP_LOGINREQUEST as sent by CServerConnect::ConnectionEstablished:
     * <pre>&lt;userhash 16&gt;&lt;clientID 4&gt;&lt;listen port 2&gt;&lt;tagcount 4&gt;
     *   CT_NAME (string), CT_VERSION (uint32), CT_SERVER_FLAGS (uint32 SRVCAP_*), CT_EMULE_VERSION (uint32)</pre>
     * A malformed or short packet is tolerated: the corresponding fields keep their defaults.
     */
    public static void parseLoginRequest(ClientState state, byte[] data) {
        if (data == null || data.length < 22) {
            log.debug("Login request too short ({} bytes), no client information available", data == null ? 0 : data.length);
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] userHash = new byte[16];
        buf.get(userHash);
        state.setUserHash(userHash);
        buf.getInt(); // client ID: always 0 for eMule, the server assigns the ID
        state.setListenPort(Short.toUnsignedInt(buf.getShort()));

        for (Tag tag : Tag.readList(buf)) {
            switch (tag.name()) {
                case Tag.NAME_NICK -> state.setNickname(String.valueOf(tag.value()));
                case Tag.NAME_CLIENT_VERSION -> { if (tag.isInt()) state.setClientVersion(tag.unsignedValue()); }
                case Tag.NAME_TCP_FLAGS -> { if (tag.isInt()) state.setServerCaps((int) tag.unsignedValue()); }
                case Tag.NAME_EMULE_VERSION -> { if (tag.isInt()) state.setEmuleVersion(tag.unsignedValue()); }
                default -> log.debug("Ignoring unknown login tag 0x{}", tag.name().length() == 1 ? Integer.toHexString(tag.name().charAt(0)) : tag.name());
            }
        }
        log.info("Login: nick='{}' listenPort={} version=0x{} caps=0x{} emuleVersion=0x{}",
                HandlerUtils.sanitize(state.nickname()), state.listenPort(), Long.toHexString(state.clientVersion()),
                Integer.toHexString(state.serverCaps()), Long.toHexString(state.emuleVersion()));
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
        if (context.getServer() != null) {
            System.arraycopy(context.getServer().getServerHash(), 0, hash, 0, 16);
        }
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

        String serverName = Main.SERVER_NAME;
        String serverVersion = Main.ESERVER_VERSION;
        String desc = Main.SERVER_DESCRIPTION;

        int maxFiles = context.getConfig().maxFiles();
        int maxUsers = context.getConfig().maxUsers();

        // TCP capability flags matching eMule SRV_TCPFLG_ constants
        // Note: We include UDPOBFUSCATION (0x200) and TCPOBFUSCATION (0x400)
        int tcpFlags = Tag.SERVER_TCP_FLAGS;

        // UDP capability flags matching eMule SRV_UDPFLG_ constants
        // Note: We include TCPOBFUSCATION (0x400) in UDP flags as well, as eMule checks it there too
        int udpFlags = Tag.SERVER_UDP_FLAGS;

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_HASH, "\u0011", hash)); // Send hash as a tag too for extra visibility
        // Important: Standard Lugdunum/eMule servers often send critical stats first
        // Note: Using TYPE_STRING for limits as some eMule clients expect strings for display
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAXUSERS, maxUsers));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOFTFILES, 1000000));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_HARDFILES, 2000000));
        
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        // ST_VERSION as integer for standard compliance
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SERVER_VERSION, org.jemule.Main.ESERVER_VERSION_INT));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_LOWIDUSERS, context.getRegistry().lowIdCount()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPFLAGS, udpFlags));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEY, org.jemule.security.UdpObfuscation.serverKeyFor(context.getSocket().getInetAddress()))); // ST_UDPKEY
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPKEYIP, (int) ClientState.ipToLong(context.getSocket().getInetAddress()))); // ST_UDPKEYIP: IP the key is valid for
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCPPORTOBFUSCATION, portInt)); // ST_TCPPORTOBFUSCATION
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDPPORTOBFUSCATION, portInt + 12)); // ST_UDPPORTOBFUSCATION (port + 12)

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
}

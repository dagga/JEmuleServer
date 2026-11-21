package org.jemule.network.handler;

import org.jemule.Main;
import org.jemule.config.OfferFilesConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.OfferFilesSession;
import org.jemule.core.event.ClientEvent;
import org.jemule.network.Packet;
import org.jemule.network.FirewallChecker;
import org.jemule.network.Server;
import org.jemule.network.natt.KeepaliveToken;
import org.jemule.network.natt.NatCallbackService;
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
        log.debug("Handling login request, data size: {} bytes", data.length);

        InetAddress clientIp = context.getSocket().getInetAddress();
        ClientState state = context.getClientFactory().createClient(clientIp, context.getSocket().getPort(), 0);
        context.setState(state);

        parseLoginRequest(state, data);

        // maxUsers: refused before the HighID test, which opens a connection to the client
        int maxUsers = context.getConfig().maxUsers();
        if (context.getRegistry().size() >= maxUsers) {
            log.warn("Server full ({} users): login refused", maxUsers);
            sendServerMessage(context, out, "ERROR : This server is full (" + maxUsers + " users).");
            context.disconnect();
            return;
        }
        if (initialPacket.protocol() == Packet.PROTOCOL_ZLIB || (state.serverCaps() & ClientState.SRVCAP_ZLIB) != 0) {
            state.setZlibSupported(true);
        }

        // Lugdunum NAT callback: decided once at login, a later change of natCallback applies to the next logins
        boolean natCallback = context.getConfig().natTraversal().natCallback();
        boolean natOptIn = natCallback && NatCallbackService.optsIn(state.serverCaps());
        // ed2k-server's LowID hole punch (natHolePunch, needs natCallback): also decided once at login
        boolean natHolePunch = context.getConfig().natTraversal().holePunch();
        long clientId = assignClientId(context, state, !natCallback ? ClientRegistry.LowIdParity.ANY
                : natOptIn ? ClientRegistry.LowIdParity.ODD : ClientRegistry.LowIdParity.EVEN);
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
        NatCallbackService natCallbackService = context.getNatCallbackService();
        if (natCallbackService != null) {
            if (natOptIn) {
                natCallbackService.register(state);
            } else if (natHolePunch && NatCallbackService.clientUdpPort(state) != 0) {
                // A hole-punch target only: its 0xF9 UDP port and its 0x9F keepalives are where peers punch to
                natCallbackService.register(state, false);
            }
        }

        // Send server version FIRST on its own line for eMule auto-detection
        sendServerMessage(context, out, "server version " + Main.ESERVER_VERSION);

        negotiateOfferFiles(context, state);
        context.getIpv6SourceService().onLogin(context);
        sendServerIdent(context, out);
        
        // --- Corrected ID_CHANGE packet construction matching eMule's expectations ---
        // eMule's ServerSocket.cpp expects: <NEW_ID 4>[<server_flags 4>][<server_tcp_port 4>][<reported_ip 4>][<obf_tcp_port 4>]
        // Flags: 0x01=COMPRESSION, 0x08=NEWTAGS, 0x10=UNICODE, 0x80=TYPETAGINTEGER, 0x100=LARGEFILES, 0x200=UDPOBFUSCATION, 0x400=TCPOBFUSCATION
        // 0x1000 (SRV_TCPFLG_NATTRAVERSAL): the Lugdunum NAT callback, for every client while it is enabled
        // 0x20000 (SRV_TCPFLG_LOWID_HOLEPUNCH) and 0x100000 (SRV_TCPFLG_HOLEPUNCH_EXTENSION): ed2k-server's hole punch
        int serverFlags = Tag.SERVER_TCP_FLAGS | (natCallback ? Tag.TCPFLG_NATT : 0)
                | (natHolePunch ? Tag.TCPFLG_LOWID_HOLEPUNCH | Tag.TCPFLG_HOLEPUNCH_EXTENSION : 0)
                | org.jemule.network.ipv6.Ipv6SourceService.tcpFlags(context.getConfig());
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value,
                idChange(state, serverFlags, clientIp, context.getConfig().port(), natOptIn, context.getSocket().getPort()))
                .write(out, state.isZlibSupported());

        log.info("Client {} logged in with ID {}", HandlerUtils.sanitize(state.nickname()), Long.toUnsignedString(clientId));

        sendServerStatus(context, out);
        // Shown in the client's server info; read from the running configuration (welcomeMessage, loginMessage)
        sendServerMessage(context, out, welcomeMessage(context.getConfig(), clientId));
        String loginMessage = loginMessage(context.getConfig(), clientId);
        if (loginMessage != null) sendServerMessage(context, out, loginMessage);
        // Relay mode: the client stays here, and learns the relay server that its requests are played on
        org.jemule.network.ServerRelay relay = context.getServerRelay();
        if (relay != null && !relay.isRelayServer(clientIp)) sendRelayServerList(context, out, relay.announce());

        // FR-KAD-02: HighID clients are used to bootstrap the embedded Kademlia node
        if (context.getServer() != null) {
            context.getServer().onClientLoggedIn(state);
        }
        context.getIpv6SourceService().afterLogin(context);
    }

    /**
     * OFFERFILES v1 (doc/offerfiles-v1.md): only a client that asked for it at login, on a server where it is enabled,
     * gets the terms in OP_SERVERIDENT and is held to them for the whole connection. Every other client publishes as on
     * a legacy server.
     */
    static void negotiateOfferFiles(ClientContext context, ClientState state) {
        if (state.offerFilesRequested() < Tag.OFFERFILES_VERSION) return;
        context.getOfferFilesV1().loginAsked();
        OfferFilesConfig offerFiles = context.getConfig().offerFiles();
        if (!offerFiles.enabled()) return;
        OfferFilesSession session = OfferFilesSession.of(context.getConfig().maxFilesPerUser(), PublishHandler.HARD_FILES, offerFiles);
        if (!session.isValid()) {
            log.error("OFFERFILES v1 not advertised: invalid terms {}", session);
            return;
        }
        state.setOfferFilesSession(session);
        context.getOfferFilesV1().sessionAdvertised();
    }

    /**
     * OP_IDCHANGE as read by CServerSocket::ProcessPacket:
     * <code>&lt;ID 4&gt;&lt;TCP flags 4&gt;&lt;server TCP port 4&gt;&lt;reported IP 4&gt;&lt;obfuscation TCP port 4&gt;</code>,
     * 20 bytes. A client that opted in to the Lugdunum NAT callback gets Lugdunum's 24-byte form, which appends
     * <code>&lt;your port 4&gt;</code>: its TCP port as the server sees it, the source port of its connection for a
     * LowID (after any NAT), its listen port for a HighID (aMule master a6d6290be0, docs/NAT_T_Protocol.md, "Lugdunum
     * NAT callback"; ed2k-server README, "Lugdunum's NAT callback"). aMule takes the callback as active only when the
     * 24-byte form comes with the TCP flag 0x1000.
     */
    static byte[] idChange(ClientState state, int serverFlags, InetAddress clientIp, int serverPort, boolean natOptIn,
                           int connectionPort) {
        ByteBuffer idChange = ByteBuffer.allocate(natOptIn ? 24 : 20).order(ByteOrder.LITTLE_ENDIAN);
        idChange.putInt((int) state.clientId());
        idChange.putInt(serverFlags);
        idChange.putInt(serverPort); // server TCP port (eMule and aMule skip it)
        idChange.putInt((int) ClientState.ipToLong(clientIp)); // client's IP as seen by the server (used by LowID clients)
        idChange.putInt(serverPort); // obfuscation TCP port: the same port, obfuscation is detected on it
        if (natOptIn) idChange.putInt(state.isHighId() ? state.listenPort() : connectionPort);
        return idChange.array();
    }

    /**
     * Chooses the client's ID like eD2K servers do: its IP (HighID) if it accepts incoming connections on
     * the TCP port announced in the login (the answer must carry its user hash), a LowID (&lt; 2^24) of the given
     * parity otherwise. An IP whose ID would be below 2^24 (x.y.z.0) or that is not IPv4 can only get a LowID.
     */
    static long assignClientId(ClientContext context, ClientState state, ClientRegistry.LowIdParity parity) {
        long ipId = ClientState.ipToLong(state.address());
        if (ipId > 0x00FFFFFFL && state.listenPort() > 0) {
            Server server = context.getServer();
            byte[] serverHash = server != null ? server.getServerHash() : new byte[16];
            InetAddress serverIp = server != null ? server.getPublicIp() : null;
            FirewallChecker checker = new FirewallChecker(context.getConfig().lowIdCheckTimeoutSeconds() * 1000,
                    context.getConfig().obfuscatedFirewallCheck());
            if (checker.isReachable(state.address(), state.listenPort(), state.userHash(), state.serverCaps(), serverHash, serverIp,
                    context.getConfig().port())) {
                return ipId;
            }
        }
        long lowId = context.getRegistry().allocateLowId(parity);
        log.info("Client {} ({}:{}) is not reachable: LowID {}", HandlerUtils.sanitize(state.nickname()),
                state.address().getHostAddress(), state.listenPort(), lowId);
        return lowId;
    }

    /**
     * Parses OP_LOGINREQUEST as sent by CServerConnect::ConnectionEstablished:
     * <pre>&lt;userhash 16&gt;&lt;clientID 4&gt;&lt;listen port 2&gt;&lt;tagcount 4&gt;
     *   CT_NAME (string), CT_VERSION (uint32), CT_SERVER_FLAGS (uint32 SRVCAP_*), CT_EMULE_VERSION (uint32)
     *   [offerfiles_v (string-named uint32, OFFERFILES v1 request)] [CT_MOD_IP_V6 0xAE (hash: IPv6 16, doc/ipv6.md)]</pre>
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
                case Tag.NAME_EMULE_UDPPORTS -> { if (tag.isInt()) state.setEmuleUdpPorts((int) tag.unsignedValue()); }
                // Highest OFFERFILES version the client implements; any other type than uint32 means a legacy client
                case Tag.NAME_OFFERFILES_VERSION -> { if (tag.type() == Tag.TYPE_INTEGER) state.setOfferFilesRequested(tag.unsignedValue()); }
                case Tag.NAME_IPV6 -> { if (tag.type() == Tag.TYPE_HASH) state.ipv6().setLoginTag((byte[]) tag.value()); }
                default -> log.debug("Ignoring unknown login tag 0x{}", tag.name().length() == 1 ? Integer.toHexString(tag.name().charAt(0)) : tag.name());
            }
        }
        log.info("Login: nick='{}' listenPort={} version=0x{} caps=0x{} emuleVersion=0x{}",
                HandlerUtils.sanitize(state.nickname()), state.listenPort(), Long.toHexString(state.clientVersion()),
                Integer.toHexString(state.serverCaps()), Long.toHexString(state.emuleVersion()));
    }

    /** OP_SERVERLIST {@code <count 1>(<IP 4><port 2>)} with the relay server only. */
    static void sendRelayServerList(ClientContext context, OutputStream out, byte[] entry) throws IOException {
        if (entry.length != 6) return;
        byte[] list = new byte[7];
        list[0] = 1;
        System.arraycopy(entry, 0, list, 1, 6);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_LIST.value, list).write(out, context.getState().isZlibSupported());
    }

    /** The welcome message for {@code clientId}: {@code welcomeMessage}, or the default one when it is empty. */
    public static String welcomeMessage(ServerConfig config, long clientId) {
        String template = config.welcomeMessage().isEmpty() ? ServerConfig.DEFAULT_WELCOME_MESSAGE : config.welcomeMessage();
        return renderMessage(template, clientId);
    }

    /** The further login message for {@code clientId}, or null when {@code loginMessage} is empty. */
    public static String loginMessage(ServerConfig config, long clientId) {
        return config.loginMessage().isEmpty() ? null : renderMessage(config.loginMessage(), clientId);
    }

    /** {@code {version}} and {@code {id}} replaced; {@code \n} (as typed in the web panel) starts a new line. */
    static String renderMessage(String template, long clientId) {
        return template.replace("\r\n", "\n").replace("\\n", "\n")
                .replace("{version}", Main.VERSION)
                .replace("{id}", Long.toUnsignedString(clientId));
    }

    public static void sendServerMessage(ClientContext context, OutputStream out, String msg) throws IOException {
        byte[] content = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) content.length);
        buf.put(content);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_MESSAGE.value, buf.array()).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    private static void sendServerIdent(ClientContext context, OutputStream out) throws IOException {
        serverIdent(context, true).write(out, context.getState() != null && context.getState().isZlibSupported());
    }

    /**
     * OP_SERVERIDENT for the client of the context: at login, and again with each IPv6 verdict (doc/ipv6.md). Sent
     * again, it carries the session's NAT callback token unchanged.
     */
    public static Packet serverIdent(ClientContext context) {
        return serverIdent(context, false);
    }

    private static Packet serverIdent(ClientContext context, boolean login) {
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

        String serverName = context.getConfig().serverName();
        String desc = context.getConfig().serverDescription();

        int maxFiles = context.getConfig().maxFiles();
        int maxUsers = context.getConfig().maxUsers();

        // TCP capability flags matching eMule SRV_TCPFLG_ constants
        // Note: We include UDPOBFUSCATION (0x200) and TCPOBFUSCATION (0x400)
        int tcpFlags = Tag.SERVER_TCP_FLAGS;

        // UDP capability flags matching eMule SRV_UDPFLG_ constants
        // Note: We include TCPOBFUSCATION (0x400) in UDP flags as well, as eMule checks it there too
        // SRV_UDPFLG_FOREIGN_NAT_CALLBACK while the cross-server NAT callback is taken
        int udpFlags = context.getServer() != null ? context.getServer().udpFlags() : Tag.SERVER_UDP_FLAGS;

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_HASH, "\u0011", hash)); // Send hash as a tag too for extra visibility
        // Important: Standard Lugdunum/eMule servers often send critical stats first
        // Note: Using TYPE_STRING for limits as some eMule clients expect strings for display
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAXUSERS, maxUsers));
        // ST_SOFTFILES: distinct files indexed per connection; ST_HARDFILES: an OP_OFFERFILES must declare fewer files
        OfferFilesSession offerFiles = context.getState() != null ? context.getState().offerFilesSession() : null;
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOFTFILES, offerFiles != null ? offerFiles.softFiles() : context.getConfig().maxFilesPerUser()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_HARDFILES, offerFiles != null ? offerFiles.hardFiles() : PublishHandler.HARD_FILES));

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
        if (offerFiles != null) {
            // All or nothing, each once, only to a client that asked: the connection is held to exactly these terms
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_OFFERFILES_VERSION, Tag.OFFERFILES_VERSION));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_OFFERFILES_BATCH_MAX, offerFiles.batchMax()));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_OFFERFILES_MIN_INTERVAL_MS, offerFiles.minIntervalMs()));
        }
        byte[] nattToken = natCallbackToken(context, login);
        if (nattToken != null) {
            // Over TCP only: a 0x9F proven with it counts from any address (NatCallbackService.acceptKeepalive)
            tags.add(new Tag(Tag.TYPE_HASH, Tag.NAME_NATT_TOKEN, nattToken));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_NATT_PROOF, KeepaliveToken.PROOF_VERSION));
        }
        Tag ipv6Status = context.getIpv6SourceService().statusTag(context);
        if (ipv6Status != null) tags.add(ipv6Status);

        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        // ed2k addresses are IPv4: without an IPv4 public address, announce the one the client reached, if any
        InetAddress local = context.getSocket().getLocalAddress();
        if (publicIp instanceof java.net.Inet4Address) {
            buf.put(publicIp.getAddress());
        } else if (local instanceof java.net.Inet4Address && !local.isAnyLocalAddress()) {
            buf.put(local.getAddress());
        } else {
            buf.putInt(0); // unknown
        }
        // Port is 2 bytes, written as LITTLE_ENDIAN as per ByteBuffer order
        buf.putShort((short) portInt);

        Tag.writeList(buf, tags);
        buf.flip();
        byte[] response = new byte[buf.remaining()];
        buf.get(response);
        return new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, response);
    }

    /**
     * The NAT callback session token of this login (ST_NATT_TOKEN, ed2k-server 0.9.88), new at every login: only for
     * a client that may send 0x9F ({@link NatCallbackService#mayKeepalive}), while natCallback and natCallbackToken
     * are on. Null for any other client, which gets no unsolicited tag. After the login, the same token again.
     */
    static byte[] natCallbackToken(ClientContext context, boolean login) {
        NatCallbackService natCallbackService = context.getNatCallbackService();
        if (natCallbackService == null || !context.getConfig().natTraversal().natCallback()
                || !context.getConfig().natTraversal().natCallbackToken()) {
            return null;
        }
        return login ? natCallbackService.issueToken(context.getState())
                : natCallbackService.sessionToken(context.getState());
    }

    public static void sendServerStatus(ClientContext context, OutputStream out) throws IOException {
        // Corrected: The client expects 2 integers in SERVER_STATUS: Users, Files
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(context.getRegistry().size()); // Users
        buf.putInt(context.announcedFileCount()); // Files: the relay server's index in relay mode
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_STATUS.value, buf.array()).write(out, context.getState() != null && context.getState().isZlibSupported());
    }
}

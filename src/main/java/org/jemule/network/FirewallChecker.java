package org.jemule.network;

import org.jemule.core.ClientState;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Checks whether a logging-in client accepts incoming connections on the TCP port it announced, the way
 * eD2K servers decide between a HighID and a LowID: the server connects to the client, sends an OP_HELLO
 * and waits for the OP_HELLOANSWER (CClientReqSocket::ProcessPacket / CUpDownClient::SendHelloAnswer).
 * The same test dials an IPv6 address back before it is published.
 * <p>
 * A client that announces the crypt layer in its login (CT_SERVER_FLAGS, SRVCAP_SUPPORTCRYPT / SRVCAP_REQUIRECRYPT)
 * is dialled obfuscated first ({@link ClientObfuscation}), as ed2k-server and eNode-go do. A client that requires
 * the layer accepts a plain connection only as the test of the server it is logging in to, recognised by the
 * server's IPv4 address (CServerConnect::AwaitingTestFromIP): never over IPv6, and never after the login. eMuleQt's
 * hidden cryptLayerRequiredStrict option (off by default) refuses even that one; aMule and eMule have no such mode.
 * The plain dial is the fallback when the obfuscated handshake fails on a client that does not require the layer;
 * a refused or timed out connection is not dialled again. With {@code obfuscatedFirewallCheck=false}, every client is
 * dialled plain only, as before.
 * <p>
 * The answer must come from the client that is logging in: behind a NAT, the announced port may be forwarded
 * to another client sharing the same public IP, which would answer in its place. The user hash of the
 * OP_HELLOANSWER is therefore compared with the one of the login request.
 */
public class FirewallChecker {
    private static final Logger log = LoggerFactory.getLogger(FirewallChecker.class);
    private static final byte OP_HELLO = 0x01;
    private static final byte OP_HELLOANSWER = 0x4C;

    private enum Outcome { REACHED, NOT_REACHED, NO_HANDSHAKE }

    private final int timeoutMs;
    private final boolean obfuscatedFirst;

    /** Dials obfuscated first a client announcing the crypt layer. */
    public FirewallChecker(int timeoutMs) {
        this(timeoutMs, true);
    }

    /** @param obfuscatedFirst {@code obfuscatedFirewallCheck}: false dials every client plain only */
    public FirewallChecker(int timeoutMs, boolean obfuscatedFirst) {
        this.timeoutMs = timeoutMs;
        this.obfuscatedFirst = obfuscatedFirst;
    }

    /**
     * @param userHash   user hash sent by the client in its login request
     * @param clientCaps CT_SERVER_FLAGS of the login request (SRVCAP_*): its crypt layer options
     * @return true if the client with this user hash answered our OP_HELLO on ip:port within the timeout
     */
    public boolean isReachable(InetAddress ip, int port, byte[] userHash, int clientCaps,
                               byte[] serverHash, InetAddress serverIp, int serverPort) {
        if (port <= 0 || timeoutMs <= 0) return false;
        boolean requires = (clientCaps & ClientState.SRVCAP_REQUIRECRYPT) != 0;
        boolean supports = requires || (clientCaps & ClientState.SRVCAP_SUPPORTCRYPT) != 0;
        byte[] hello = helloPacket(serverHash, serverIp, serverPort);
        if (supports && obfuscatedFirst) {
            Outcome obfuscated = dial(ip, port, userHash, hello, true);
            if (obfuscated != Outcome.NO_HANDSHAKE || requires) return obfuscated == Outcome.REACHED;
        }
        return dial(ip, port, userHash, hello, false) == Outcome.REACHED;
    }

    private Outcome dial(InetAddress ip, int port, byte[] userHash, byte[] hello, boolean obfuscated) {
        String mode = obfuscated ? "obfuscated" : "plain";
        try (Socket s = new Socket()) {
            try {
                s.connect(new InetSocketAddress(ip, port), timeoutMs);
            } catch (IOException e) {
                log.debug("Firewall check of {}:{} failed: {}", ip.getHostAddress(), port, e.getMessage());
                return Outcome.NOT_REACHED;
            }
            s.setSoTimeout(timeoutMs);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            if (obfuscated) {
                try {
                    ClientObfuscation.Streams streams = ClientObfuscation.negotiate(in, out, userHash);
                    in = streams.in();
                    out = streams.out();
                } catch (ClientObfuscation.HandshakeException e) {
                    log.debug("Obfuscated firewall check of {}:{}: {}", ip.getHostAddress(), port, e.getMessage());
                    return Outcome.NO_HANDSHAKE;
                }
            }
            new Packet(Packet.PROTOCOL_ED2K, OP_HELLO, hello).write(out, false);

            long deadline = System.currentTimeMillis() + timeoutMs;
            // The client may first send OP_EMULEINFO (0xC5) before OP_HELLOANSWER
            for (int i = 0; i < 8 && System.currentTimeMillis() < deadline; i++) {
                Packet p = Packet.read(in, 64 * 1024);
                if ((p.protocol() == Packet.PROTOCOL_ED2K || p.protocol() == Packet.PROTOCOL_ZLIB) && p.opcode() == OP_HELLOANSWER) {
                    // OP_HELLOANSWER: <user hash 16><ID 4><port 2><tags>...
                    byte[] data = p.data();
                    if (data.length >= 16 && Arrays.equals(data, 0, 16, userHash, 0, 16)) {
                        log.debug("Firewall check ({}) of {}:{} reached", mode, ip.getHostAddress(), port);
                        return Outcome.REACHED;
                    }
                    log.info("Firewall check ({}) of {}:{} answered by another client (user hash mismatch)", mode,
                            ip.getHostAddress(), port);
                    return Outcome.NOT_REACHED;
                }
            }
        } catch (IOException e) {
            log.debug("Firewall check ({}) of {}:{} failed: {}", mode, ip.getHostAddress(), port, e.getMessage());
        }
        return Outcome.NOT_REACHED;
    }

    /** OP_HELLO: <hashsize 1 = 16><hash 16><ID 4><port 2><tagcount 4><tags><server IP 4><server port 2>. */
    static byte[] helloPacket(byte[] serverHash, InetAddress serverIp, int serverPort) {
        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        int ip = serverIp == null ? 0 : (int) org.jemule.core.ClientState.ipToLong(serverIp);
        buf.put((byte) 16);
        buf.put(serverHash);
        buf.putInt(ip);
        buf.putShort((short) serverPort);
        Tag.writeList(buf, List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_NICK, "JEmuleServer"),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_CLIENT_VERSION, 0x3C)));
        buf.putInt(ip);
        buf.putShort((short) serverPort);
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        return out;
    }
}

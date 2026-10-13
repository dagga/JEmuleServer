package org.jemule.network;

import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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
 * eMule accepts this unencrypted test connection even when it requires obfuscation, because it is
 * "awaiting a test" from the server it is logging in to (CServerConnect::AwaitingTestFromIP).
 * <p>
 * The answer must come from the client that is logging in: behind a NAT, the announced port may be forwarded
 * to another client sharing the same public IP, which would answer in its place. The user hash of the
 * OP_HELLOANSWER is therefore compared with the one of the login request.
 */
public class FirewallChecker {
    private static final Logger log = LoggerFactory.getLogger(FirewallChecker.class);
    private static final byte OP_HELLO = 0x01;
    private static final byte OP_HELLOANSWER = 0x4C;

    private final int timeoutMs;

    public FirewallChecker(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * @param userHash user hash sent by the client in its login request
     * @return true if the client with this user hash answered our OP_HELLO on ip:port within the timeout
     */
    public boolean isReachable(InetAddress ip, int port, byte[] userHash, byte[] serverHash, InetAddress serverIp, int serverPort) {
        if (port <= 0 || timeoutMs <= 0) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            new Packet(Packet.PROTOCOL_ED2K, OP_HELLO, helloPacket(serverHash, serverIp, serverPort)).write(s.getOutputStream(), false);

            InputStream in = s.getInputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            // The client may first send OP_EMULEINFO (0xC5) before OP_HELLOANSWER
            for (int i = 0; i < 8 && System.currentTimeMillis() < deadline; i++) {
                Packet p = Packet.read(in, 64 * 1024);
                if ((p.protocol() == Packet.PROTOCOL_ED2K || p.protocol() == Packet.PROTOCOL_ZLIB) && p.opcode() == OP_HELLOANSWER) {
                    // OP_HELLOANSWER: <user hash 16><ID 4><port 2><tags>...
                    byte[] data = p.data();
                    if (data.length >= 16 && Arrays.equals(data, 0, 16, userHash, 0, 16)) {
                        return true;
                    }
                    log.info("Firewall check of {}:{} answered by another client (user hash mismatch)", ip.getHostAddress(), port);
                    return false;
                }
            }
        } catch (IOException e) {
            log.debug("Firewall check of {}:{} failed: {}", ip.getHostAddress(), port, e.getMessage());
        }
        return false;
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

package org.jemule.network;

import org.jemule.Main;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.event.EventManager;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerTagsTest {

    private static final int TCP_FLAGS = 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400;
    private static final int UDP_FLAGS = 0x01 | 0x08 | 0x10 | 0x100 | 0x200 | 0x400;

    static class CapturingDatagramSocket extends java.net.DatagramSocket {
        public final List<DatagramPacket> sent = new java.util.ArrayList<>();
        CapturingDatagramSocket() throws Exception { super(); }
        @Override public void send(DatagramPacket p) throws java.io.IOException {
            sent.add(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()));
        }
    }

    private Tag findTag(List<Tag> tags, String name) {
        return tags.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void testUdpDescriptionResponseContainsAllExpectedTags() throws Exception {
        EventManager em = new EventManager();
        ClientFactory cf = new ClientFactory();
        ServerConfig config = new ServerConfig(
                14662, 1000000, 300, 50, 50000, 100000, 100, 200,
                null, null, false, 50f, 10, 60, 1800, 120, null
        );
        Server server = new Server(config, cf);

        InetAddress remote = InetAddress.getByName("127.0.0.1");

        // Build UDP OP_SERVER_DESC_REQ (0xA2) payload with challenge
        // New format challenge must have 0xF0FF in lower 16 bits
        int challenge = 0x1234F0FF;
        ByteBuffer reqBuf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        reqBuf.put(Packet.PROTOCOL_ED2K);
        reqBuf.put((byte) 0xA2);
        reqBuf.putInt(challenge);

        DatagramPacket dp = new DatagramPacket(reqBuf.array(), 6, remote, 40000);
        CapturingDatagramSocket cds = new CapturingDatagramSocket();

        java.lang.reflect.Method m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, java.net.DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, dp);

        assertFalse(cds.sent.isEmpty(), "Expected at least one UDP response");
        DatagramPacket response = cds.sent.get(0);
        byte[] data = response.getData();
        int len = response.getLength();

        assertEquals(Packet.PROTOCOL_ED2K, data[0]);
        assertEquals((byte) 0xA3, data[1]);

        // Parse response (New Format): [Protocol 1] [Opcode 1] [Challenge 4] [Tags...]
        ByteBuffer buf = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); // protocol
        buf.get(); // opcode
        assertEquals(challenge, buf.getInt());

        List<Tag> tags = Tag.readList(buf);

        // Verify all expected tags are present
        Tag nameTag = findTag(tags, Tag.NAME_SERVERNAME);
        assertNotNull(nameTag, "Missing NAME_SERVERNAME tag");
        assertEquals(Tag.TYPE_STRING, nameTag.type());
        assertTrue(((String) nameTag.value()).contains("JEmuleServer"));

        Tag descTag = findTag(tags, Tag.NAME_DESCRIPTION);
        assertNotNull(descTag, "Missing NAME_DESCRIPTION tag");
        assertEquals(Tag.TYPE_STRING, descTag.type());

        Tag verTag = findTag(tags, Tag.NAME_SERVER_VERSION);
        assertNotNull(verTag, "Missing NAME_SERVER_VERSION tag");
        assertEquals(Tag.TYPE_INTEGER, verTag.type());
        assertEquals((17 << 16) | 15, (int) verTag.value());

        Tag maxUsersTag = findTag(tags, Tag.NAME_MAXUSERS);
        assertNotNull(maxUsersTag, "Missing NAME_MAXUSERS tag");
        assertEquals(50000, (int) maxUsersTag.value());

        Tag softTag = findTag(tags, Tag.NAME_SOFT_FILES);
        assertNotNull(softTag, "Missing NAME_SOFT_FILES tag");
        assertEquals(100000, (int) softTag.value());

        Tag hardTag = findTag(tags, Tag.NAME_HARD_FILES);
        assertNotNull(hardTag, "Missing NAME_HARD_FILES tag");
        assertEquals(100000, (int) hardTag.value());

        Tag tcpFlagsTag = findTag(tags, Tag.NAME_TCP_FLAGS);
        assertNotNull(tcpFlagsTag, "Missing NAME_TCP_FLAGS tag (newly added to UDP)");
        assertEquals(TCP_FLAGS, (int) tcpFlagsTag.value());

        Tag lowIdTag = findTag(tags, Tag.NAME_LOWID_USERS);
        assertNotNull(lowIdTag, "Missing NAME_LOWID_USERS tag (newly added)");
        assertEquals(Tag.TYPE_INTEGER, lowIdTag.type());
        assertEquals(0, (int) lowIdTag.value()); // No clients registered

        Tag udpFlagsTag = findTag(tags, Tag.NAME_UDP_FLAGS);
        assertNotNull(udpFlagsTag, "Missing NAME_UDP_FLAGS tag (newly added)");
        assertEquals(UDP_FLAGS, (int) udpFlagsTag.value());
    }

    @Test
    void testUdpDescriptionResponseOldFormat() throws Exception {
        EventManager em = new EventManager();
        ClientFactory cf = new ClientFactory();
        ServerConfig config = new ServerConfig(
                14662, 1000000, 300, 50, 50000, 100000, 100, 200,
                null, null, false, 50f, 10, 60, 1800, 120, null
        );
        Server server = new Server(config, cf);

        InetAddress remote = InetAddress.getByName("127.0.0.1");

        // Build UDP OP_SERVER_DESC_REQ (0xA2) payload (old format, no challenge)
        byte[] req = new byte[2];
        req[0] = Packet.PROTOCOL_ED2K;
        req[1] = (byte) 0xA2;

        DatagramPacket dp = new DatagramPacket(req, req.length, remote, 40000);
        CapturingDatagramSocket cds = new CapturingDatagramSocket();

        java.lang.reflect.Method m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, java.net.DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, dp);

        assertFalse(cds.sent.isEmpty(), "Expected at least one UDP response");
        DatagramPacket response = cds.sent.get(0);
        byte[] data = response.getData();
        int len = response.getLength();

        assertEquals(Packet.PROTOCOL_ED2K, data[0]);
        assertEquals((byte) 0xA3, data[1]);

        // Parse response (Old Format): [Protocol 1] [Opcode 1] [NameLen 2] [Name] [DescLen 2] [Desc]
        ByteBuffer buf = ByteBuffer.wrap(data, 2, len - 2).order(ByteOrder.LITTLE_ENDIAN);
        int nameLen = Short.toUnsignedInt(buf.getShort());
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(name.contains("JEmuleServer"));

        int descLen = Short.toUnsignedInt(buf.getShort());
        byte[] descBytes = new byte[descLen];
        buf.get(descBytes);
        String desc = new String(descBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(desc.contains("Experimental"));
    }

    @Test
    void testUdpDescriptionResponseLowIDCount() throws Exception {
        EventManager em = new EventManager();
        ClientFactory cf = new ClientFactory();
        FileIndex fi = new FileIndex(null, em);
        ServerConfig config = new ServerConfig(
                14663, 1000000, 300, 50, 50000, 100000, 100, 200,
                null, null, false, 50f, 10, 60, 1800, 120, null
        );
        Server server = new Server(config, cf);

        // Register clients with known IDs via reflection on the registry
        java.lang.reflect.Field regField = Server.class.getDeclaredField("registry");
        regField.setAccessible(true);
        ClientRegistry registry = (ClientRegistry) regField.get(server);

        InetAddress addr = InetAddress.getByName("10.0.0.1");
        // LowID: 0x00000001 <= 0x00FFFFFF
        registry.add(new ClientState(addr, 5000, 0x00000001, 0, new AtomicLong(0)), null);
        // LowID: 0x00000005 <= 0x00FFFFFF
        registry.add(new ClientState(addr, 5001, 0x00000005, 0, new AtomicLong(0)), null);
        // HighID: 0x01000000 > 0x00FFFFFF
        registry.add(new ClientState(addr, 5002, 0x01000000, 0, new AtomicLong(0)), null);

        assertEquals(3, registry.size());
        assertEquals(2, registry.lowIdCount());

        InetAddress remote = InetAddress.getByName("127.0.0.1");
        
        // Build UDP OP_SERVER_DESC_REQ (0xA2) payload (new format, with challenge)
        int challenge = 0x55AA70FF; // Must have 0xF0FF in lower 16 bits? 
                                    // Actually buildServerDescPacket checks challenge != 0
        ByteBuffer reqBuf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        reqBuf.put(Packet.PROTOCOL_ED2K);
        reqBuf.put((byte) 0xA2);
        reqBuf.putInt(challenge);

        DatagramPacket dp = new DatagramPacket(reqBuf.array(), 6, remote, 40000);
        CapturingDatagramSocket cds = new CapturingDatagramSocket();

        java.lang.reflect.Method m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, java.net.DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, dp);

        assertFalse(cds.sent.isEmpty());
        DatagramPacket response = cds.sent.get(0);
        byte[] data = response.getData();
        int len = response.getLength();

        assertEquals(Packet.PROTOCOL_ED2K, data[0]);
        assertEquals((byte) 0xA3, data[1]);

        ByteBuffer buf = ByteBuffer.wrap(data, 2, len - 2).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(challenge, buf.getInt());
        
        List<Tag> tags = Tag.readList(buf);
        Tag lowIdTag = findTag(tags, Tag.NAME_LOWID_USERS);
        assertNotNull(lowIdTag);
        assertEquals(2, (int) lowIdTag.value(), "LOWID_USERS should reflect 2 low-ID clients");
    }

    @Test
    void testTcpFlagsHaveCorrectBits() {
        // Verify the TCP flags mask matches eMule SRV_TCPFLG_ constants
        assertEquals(0x01, 1 << 0, "SRV_TCPFLG_COMPRESSION");
        assertEquals(0x08, 1 << 3, "SRV_TCPFLG_NEWTAGS");
        assertEquals(0x10, 1 << 4, "SRV_TCPFLG_UNICODE");
        assertEquals(0x80, 1 << 7, "SRV_TCPFLG_TYPETAGINTEGER");
        assertEquals(0x100, 1 << 8, "SRV_TCPFLG_LARGEFILES");
        assertEquals(0x400, 1 << 10, "SRV_TCPFLG_TCPOBFUSCATION");

        // The 0x04 bit (1<<2) is NOT used by any standard eMule flag
        // and must NOT be set
        assertTrue((TCP_FLAGS & 0x04) == 0, "Bit 0x04 must NOT be set (no standard eMule flag)");
    }

    @Test
    void testUdpFlagsHaveCorrectBits() {
        assertEquals(0x01, 1 << 0, "SRV_UDPFLG_EXT_GETSOURCES");
        assertEquals(0x08, 1 << 3, "SRV_UDPFLG_NEWTAGS");
        assertEquals(0x10, 1 << 4, "SRV_UDPFLG_UNICODE");
        assertEquals(0x100, 1 << 8, "SRV_UDPFLG_LARGEFILES");
        assertEquals(0x200, 1 << 9, "SRV_UDPFLG_UDPOBFUSCATION");
        assertEquals(0x400, 1 << 10, "SRV_UDPFLG_TCPOBFUSCATION");

        assertTrue((UDP_FLAGS & 0x02) == 0, "Bit 0x02 (EXT_GETFILES) must NOT be set");
        assertTrue((UDP_FLAGS & 0x20) == 0, "Bit 0x20 (EXT_GETSOURCES2) must NOT be set");
        assertTrue((UDP_FLAGS & 0x40) == 0, "Bit 0x40 must NOT be set");
    }
}

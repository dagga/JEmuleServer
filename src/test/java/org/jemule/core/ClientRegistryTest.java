package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClientRegistryTest {

    private ClientState client(int id) throws Exception {
        return new ClientState(InetAddress.getByName("10.0.0.1"), 5000, id, 0, new AtomicLong(0));
    }

    @Test
    void testEmptyRegistry() {
        ClientRegistry reg = new ClientRegistry();
        assertEquals(0, reg.size());
        assertEquals(0, reg.lowIdCount());
    }

    @Test
    void testLowIdCountWithHighIds() throws Exception {
        ClientRegistry reg = new ClientRegistry();
        reg.add(client(0x01000000), null); // HighID ( > 0x00FFFFFF)
        reg.add(client(0x01000001), null); // HighID
        assertEquals(2, reg.size());
        assertEquals(0, reg.lowIdCount(), "All clients are HighID");
    }

    @Test
    void testLowIdCountWithLowIds() throws Exception {
        ClientRegistry reg = new ClientRegistry();
        reg.add(client(0x00000001), null); // LowID ( <= 0x00FFFFFF)
        reg.add(client(0x00000005), null); // LowID
        assertEquals(2, reg.size());
        assertEquals(2, reg.lowIdCount(), "All clients are LowID");
    }

    @Test
    void testLowIdCountMixed() throws Exception {
        ClientRegistry reg = new ClientRegistry();
        reg.add(client(0x00000001), null); // LowID
        reg.add(client(0x00000005), null); // LowID
        reg.add(client(0x01000000), null); // HighID
        reg.add(client(0x02000000), null); // HighID
        reg.add(client(0x03000000), null); // HighID
        assertEquals(5, reg.size());
        assertEquals(2, reg.lowIdCount(), "Should count only LowID clients");
    }

    @Test
    void testLowIdBoundary() throws Exception {
        ClientRegistry reg = new ClientRegistry();
        reg.add(client(0x00FFFFFF), null); // Max LowID (0x00FFFFFF)
        reg.add(client(0x01000000), null); // Min HighID (0x01000000)
        assertEquals(2, reg.size());
        assertEquals(1, reg.lowIdCount(), "0x00FFFFFF is LowID, 0x01000000 is HighID");
    }

    @Test
    void testLowIdCountAfterRemoval() throws Exception {
        ClientRegistry reg = new ClientRegistry();
        ClientState low = client(0x00000001);
        ClientState high = client(0x01000000);
        reg.add(low, null);
        reg.add(high, null);
        assertEquals(1, reg.lowIdCount(), "Only LowID among the two");
        reg.remove(low);
        assertEquals(0, reg.lowIdCount(), "After removing LowID, no LowID left");
        reg.add(client(0x00000002), null);
        assertEquals(1, reg.lowIdCount(), "After adding another LowID");
        reg.remove(high);
        assertEquals(1, reg.lowIdCount(), "Removing HighID does not affect LowID count");
    }
}

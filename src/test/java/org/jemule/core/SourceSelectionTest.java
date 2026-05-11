package org.jemule.core;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SourceSelectionTest {

    @Test
    void testSourceSelectionDiversityAndProximity() throws Exception {
        FileIndex index = new FileIndex(null);
        String hash = "1234567890ABCDEF1234567890ABCDEF";
        FileMetadata meta = new FileMetadata(hash, "test.iso", 1000000, "ISO");
        
        // Add 10 sources with different IPs
        for (int i = 1; i <= 10; i++) {
            InetAddress addr = InetAddress.getByName("192.168.1." + i);
            ClientState s = new ClientState(addr, 4662, i, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
            meta.sources().put(String.valueOf(i), s);
        }
        
        // Add one source on a very different IP
        InetAddress farAddr = InetAddress.getByName("10.0.0.1");
        ClientState farClient = new ClientState(farAddr, 4662, 11, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        meta.sources().put("11", farClient);
        
        index.addFile(meta);
        
        // Requester is also in 192.168.1.x
        ClientState requester = new ClientState(InetAddress.getByName("192.168.1.100"), 4662, 100, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        
        // Get only 5 sources
        List<ClientState> sources = index.getSources(hash, requester, 5);
        
        assertEquals(5, sources.size());
        
        // Check that they are mostly from 192.168.1.x because of proximity (higher score)
        // Since we have 10 sources in 192.168.1.x, the top 5 should all be from that subnet
        for (ClientState s : sources) {
            assertTrue(s.address().getHostAddress().startsWith("192.168.1."), "Source " + s.address().getHostAddress() + " should be close to requester");
        }
    }

    @Test
    void testSourceLimit() throws Exception {
        FileIndex index = new FileIndex(null);
        String hash = "HASH123";
        FileMetadata meta = new FileMetadata(hash, "test.zip", 500, "ZIP");
        
        for (int i = 0; i < 10; i++) {
            ClientState s = new ClientState(InetAddress.getByName("1.1.1." + i), 4662, i, 0, new AtomicLong(0));
            meta.sources().put(String.valueOf(i), s);
        }
        index.addFile(meta);
        
        List<ClientState> result = index.getSources(hash, null, 3);
        assertEquals(3, result.size());
    }
}

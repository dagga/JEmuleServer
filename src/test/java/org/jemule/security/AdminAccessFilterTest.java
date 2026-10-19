package org.jemule.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccessFilterTest {

    @Test
    void defaultAllowsLoopbackOnly() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("", "");
        assertTrue(filter.isAllowed(InetAddress.getByName("127.0.0.1")));
        assertTrue(filter.isAllowed(InetAddress.getByName("127.0.0.2")));
        assertTrue(filter.isAllowed(InetAddress.getByName("::1")));
        assertFalse(filter.isAllowed(InetAddress.getByName("192.168.1.50")));
        assertFalse(filter.isAllowed(InetAddress.getByName("10.0.0.1")));
    }

    @Test
    void allowsSpecificIpsAndCidr() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("192.168.1.50, 10.0.0.0/24, 127.0.0.1", "");
        assertTrue(filter.isAllowed(InetAddress.getByName("127.0.0.1")));
        assertTrue(filter.isAllowed(InetAddress.getByName("192.168.1.50")));
        assertFalse(filter.isAllowed(InetAddress.getByName("192.168.1.51")));
        assertTrue(filter.isAllowed(InetAddress.getByName("10.0.0.1")));
        assertTrue(filter.isAllowed(InetAddress.getByName("10.0.0.254")));
        assertFalse(filter.isAllowed(InetAddress.getByName("10.0.1.1")));
        assertFalse(filter.isAllowed(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void wildcardAllowsAllIps() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("*", "");
        assertTrue(filter.isAllowed(InetAddress.getByName("127.0.0.1")));
        assertTrue(filter.isAllowed(InetAddress.getByName("192.168.1.50")));
        assertTrue(filter.isAllowed(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void macFilterMatching() throws Exception {
        InetAddress clientIp = InetAddress.getByName("192.168.1.50");
        AdminAccessFilter filter = new AdminAccessFilter("192.168.1.0/24", "AA:BB:CC:DD:EE:FF, 11-22-33-44-55-66",
                addr -> "aa:bb:cc:dd:ee:ff");

        assertTrue(filter.isAllowed(clientIp));

        AdminAccessFilter rejectingFilter = new AdminAccessFilter("192.168.1.0/24", "AA:BB:CC:DD:EE:FF",
                addr -> "99:88:77:66:55:44");

        assertFalse(rejectingFilter.isAllowed(clientIp));

        AdminAccessFilter unresolvableFilter = new AdminAccessFilter("192.168.1.0/24", "AA:BB:CC:DD:EE:FF",
                addr -> null);

        assertFalse(unresolvableFilter.isAllowed(clientIp));
    }

    @Test
    void macNormalization() {
        assertEquals("AABBCCDDEEFF", AdminAccessFilter.normalizeMac("aa:bb:cc:dd:ee:ff"));
        assertEquals("AABBCCDDEEFF", AdminAccessFilter.normalizeMac("AA-BB-CC-DD-EE-FF"));
        assertEquals("AABBCCDDEEFF", AdminAccessFilter.normalizeMac("aabb.ccdd.eeff"));
        assertEquals("AABBCCDDEEFF", AdminAccessFilter.normalizeMac("AABBCCDDEEFF"));
        assertNull(AdminAccessFilter.normalizeMac("invalid-mac"));
        assertNull(AdminAccessFilter.normalizeMac(null));
    }
}

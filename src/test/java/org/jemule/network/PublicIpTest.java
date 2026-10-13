package org.jemule.network;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** ed2k announces 4-byte addresses: the public IP is always an IPv4 address. */
class PublicIpTest {

    @Test
    void configuredIpv4IsKept() throws Exception {
        assertEquals(InetAddress.getByName("203.0.113.7"), Server.resolvePublicIp("203.0.113.7"));
    }

    @Test
    void configuredIpv6FallsBackToAnIpv4Address() {
        assertInstanceOf(Inet4Address.class, Server.resolvePublicIp("2001:db8::1"));
    }

    @Test
    void autoDetectionReturnsAnIpv4Address() {
        assertInstanceOf(Inet4Address.class, Server.resolvePublicIp(null));
    }
}

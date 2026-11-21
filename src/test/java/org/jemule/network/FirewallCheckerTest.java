package org.jemule.network;

import org.jemule.config.ConfigParameter;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientState;
import org.jemule.network.FakeEmuleClient.Crypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The HighID test and the IPv6 dial-back: obfuscated first for a client that announces the crypt layer (#124). */
class FirewallCheckerTest {
    private static final byte[] HASH = HexFormat.of().parseHex("00112233445566778899AABBCCDDEEFF");
    private static final byte[] OTHER_HASH = HexFormat.of().parseHex("FFEEDDCCBBAA99887766554433221100");
    private static final byte[] SERVER_HASH = new byte[16];
    private static final int SUPPORTS = ClientState.SRVCAP_SUPPORTCRYPT;
    private static final int REQUIRES = ClientState.SRVCAP_SUPPORTCRYPT | ClientState.SRVCAP_REQUESTCRYPT
            | ClientState.SRVCAP_REQUIRECRYPT;

    private static final InetAddress IPV4 = InetAddress.getLoopbackAddress();

    private static boolean check(FakeEmuleClient client, InetAddress ip, int caps) {
        return new FirewallChecker(2000).isReachable(ip, client.port(), HASH, caps, SERVER_HASH, IPV4, 4661);
    }

    private static InetAddress ipv6Loopback() throws IOException {
        InetAddress loopback = InetAddress.getByName("::1");
        try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
            return loopback;
        } catch (IOException e) {
            assumeTrue(false, "no IPv6 loopback: " + e.getMessage());
            return null;
        }
    }

    @Test
    @Timeout(10)
    void aClientRequiringTheCryptLayerIsReachedObfuscatedOverIpv4() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.REQUIRED)) {
            assertTrue(check(client, IPV4, REQUIRES));
            assertEquals(List.of("obfuscated"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void aClientRequiringTheCryptLayerIsReachedObfuscatedOverIpv6() throws Exception {
        InetAddress ipv6 = ipv6Loopback();
        try (FakeEmuleClient client = new FakeEmuleClient(ipv6, HASH, Crypt.REQUIRED)) {
            assertTrue(check(client, ipv6, REQUIRES));
            assertEquals(List.of("obfuscated"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void aClientSupportingTheCryptLayerIsDialledObfuscated() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.SUPPORTED)) {
            assertTrue(check(client, IPV4, SUPPORTS));
            assertEquals(List.of("obfuscated"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void aClientWithoutTheCryptLayerIsDialledPlain() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.NONE)) {
            assertTrue(check(client, IPV4, 0));
            assertEquals(List.of("plain"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void aFailedHandshakeFallsBackToPlainWhenTheLayerIsNotRequired() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.NONE)) {
            assertTrue(check(client, IPV4, SUPPORTS));
            assertEquals(List.of("refused", "plain"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void noPlainFallbackForAClientThatRequiresTheLayer() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.NONE)) {
            assertFalse(check(client, IPV4, REQUIRES));
            assertEquals(List.of("refused"), client.connections());
        }
    }

    @Test
    @Timeout(10)
    void anotherClientBehindThePortFailsTheHandshakeAndTheUserHashCheck() throws Exception {
        // Keyed with the dialled client's hash: another client cannot decrypt the request
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, OTHER_HASH, Crypt.SUPPORTED)) {
            assertFalse(check(client, IPV4, REQUIRES));
            assertEquals(List.of("refused"), client.connections());
        }
        // Then the plain fallback: the OP_HELLOANSWER carries another user hash
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, OTHER_HASH, Crypt.SUPPORTED)) {
            assertFalse(check(client, IPV4, SUPPORTS));
            assertEquals(List.of("refused", "plain"), client.connections());
        }
    }

    // ---- obfuscatedFirewallCheck=false: plain only, as before

    @Test
    @Timeout(10)
    void switchedOffEveryClientIsDialledPlain() throws Exception {
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.SUPPORTED)) {
            assertTrue(new FirewallChecker(2000, false).isReachable(IPV4, client.port(), HASH, REQUIRES, SERVER_HASH,
                    IPV4, 4661));
            assertEquals(List.of("plain"), client.connections());
        }
        try (FakeEmuleClient client = new FakeEmuleClient(IPV4, HASH, Crypt.REQUIRED)) {
            assertFalse(new FirewallChecker(2000, false).isReachable(IPV4, client.port(), HASH, REQUIRES, SERVER_HASH,
                    IPV4, 4661), "strict mode refuses the plain test");
            assertEquals(List.of("refused"), client.connections());
        }
    }

    @Test
    void theSwitchIsOnByDefaultAndKeptByTheCopies() {
        assertTrue(ServerConfig.DEFAULT.obfuscatedFirewallCheck());
        assertTrue(ServerConfig.fromProperties(new Properties()).obfuscatedFirewallCheck());
        assertEquals("true", ServerConfig.DEFAULT.toProperties().get("obfuscatedFirewallCheck"));
        Properties off = new Properties();
        off.setProperty("obfuscatedFirewallCheck", "false");
        ServerConfig config = ServerConfig.fromProperties(off);
        assertFalse(config.obfuscatedFirewallCheck());
        assertFalse(config.withPort(4662).withIpv6Sources(true).obfuscatedFirewallCheck(), "kept by the with* copies");
        assertTrue(config.withObfuscatedFirewallCheck(true).obfuscatedFirewallCheck());
        assertEquals(ConfigParameter.Scope.HOT, ConfigParameter.OBFUSCATED_FIREWALL_CHECK.scope());
    }
}

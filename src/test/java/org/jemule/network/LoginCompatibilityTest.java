package org.jemule.network;

import org.jemule.core.ClientState;
import org.jemule.network.handler.LoginHandler;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** OP_LOGINREQUEST as sent by eMule 0.50a (sockets.cpp, CServerConnect::ConnectionEstablished). */
class LoginCompatibilityTest {

    private static ClientState newState() throws Exception {
        return new ClientState(InetAddress.getByName("10.0.0.1"), 51234, 0x0100000AL, System.currentTimeMillis(), new AtomicLong());
    }

    @Test
    void loginTagsAreParsed() throws Exception {
        byte[] userHash = hash("00112233445566778899aabbccddeeff");
        ClientState st = newState();
        LoginHandler.parseLoginRequest(st, loginRequest(userHash, 4662, "[eMule] ünïcødé", EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_REQUESTCRYPT));

        assertArrayEquals(userHash, st.userHash());
        assertEquals(4662, st.listenPort());
        assertEquals("[eMule] ünïcødé", st.nickname());
        assertEquals(0x3C, st.clientVersion());
        assertEquals((50 << 10) | (1 << 7), st.emuleVersion());
        assertTrue(st.supportsCrypt());
        assertTrue(st.requestsCrypt());
        assertFalse(st.requiresCrypt());
        assertEquals(51234, st.port(), "socket port is kept separately from the listen port");
    }

    @Test
    void truncatedLoginIsTolerated() throws Exception {
        ClientState st = newState();
        LoginHandler.parseLoginRequest(st, new byte[10]);
        assertEquals(0, st.listenPort());
        assertEquals(0, st.serverCaps());
    }
}

package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** maxUsers: a login beyond the limit gets a "server full" message and is disconnected, without any ID. */
class MaxUsersTest {

    @Test
    void loginIsRefusedWhenTheServerIsFull() throws Exception {
        ServerConfig config = new ServerConfig(4661, 2 * 1024 * 1024, 300, 50, 1, 1000, 5, 200, null, null, true,
                50f, 10, 60, 300, 120, null, 0);
        ClientRegistry registry = new ClientRegistry();
        FileIndex index = new FileIndex(null);
        EmuleTestSupport.loggedInContext("10.0.0.1", 50000, config, registry, index); // the only allowed user

        boolean[] disconnected = {false};
        var ctx = new EmuleTestSupport.FakeContext(new EmuleTestSupport.DummySocket(InetAddress.getByName("10.0.0.2"), 50001),
                config, registry, index) {
            @Override public void disconnect() { disconnected[0] = true; }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] login = EmuleTestSupport.loginRequest(EmuleTestSupport.hash("00112233445566778899aabbccddeeff"), 4662,
                "late", EmuleTestSupport.EMULE_DEFAULT_SRVCAPS);
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, login), out);

        assertTrue(disconnected[0]);
        assertEquals(1, registry.size());
        Packet answer = readPacket(out.toByteArray());
        assertEquals(OpCode.SERVER_MESSAGE.value, answer.opcode(), "no OP_IDCHANGE, only the message");
        assertTrue(new String(answer.data(), StandardCharsets.UTF_8).contains("server is full"));
    }

    private static Packet readPacket(byte[] bytes) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        Packet p = Packet.read(in, 1 << 20);
        assertEquals(0, in.available(), "a single packet is sent");
        return p;
    }
}

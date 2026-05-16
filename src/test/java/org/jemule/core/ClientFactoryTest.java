/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.core;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class ClientFactoryTest {

    @Test
    void testCreateClient() throws Exception {
        ClientFactory factory = new ClientFactory();
        InetAddress addr = InetAddress.getLoopbackAddress();
        int port = 4662;
        int clientId = 12345;

        ClientState state = factory.createClient(addr, port, clientId);

        assertNotNull(state);
        assertEquals(addr, state.address());
        assertEquals(port, state.port());
        assertEquals(clientId, state.clientId());
        assertTrue(state.connectedAt() <= System.currentTimeMillis());
        assertNotNull(state.lastActivity());
    }

    @Test
    void testCreateClientInvalidArgs() {
        ClientFactory factory = new ClientFactory();
        
        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient(null, 4662, 12345)
        );

        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient(InetAddress.getLoopbackAddress(), 0, 12345)
        );

        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient(InetAddress.getLoopbackAddress(), 70000, 12345)
        );
    }
}

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

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link ClientState} instances.
 * Centralizes client creation and validation.
 */
public class ClientFactory {
    private static final Logger log = LoggerFactory.getLogger(ClientFactory.class);

    public ClientState createClient(InetAddress address, int port, int clientId) {
        if (address == null) {
            throw new IllegalArgumentException("Client address cannot be null");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid client port: " + port);
        }

        log.debug("Creating new ClientState for {}:{} with ID {}", address, port, clientId);
        
        long now = System.currentTimeMillis();
        return new ClientState(
            address, 
            port, 
            clientId, 
            now, 
            new AtomicLong(now)
        );
    }
}

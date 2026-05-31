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

import org.jemule.network.Packet;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ClientRegistry {
    private final ConcurrentHashMap<Integer, ClientState> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Consumer<Packet>> messengers = new ConcurrentHashMap<>();

    public void add(ClientState s, Consumer<Packet> messenger) {
        clients.put(s.clientId(), s);
        if (messenger != null) messengers.put(s.clientId(), messenger);
    }

    public void remove(ClientState s) {
        clients.remove(s.clientId());
        messengers.remove(s.clientId());
    }

    public ClientState get(int id) {
        return clients.get(id);
    }

    public void sendTo(int targetId, Packet p) {
        Consumer<Packet> messenger = messengers.get(targetId);
        if (messenger != null) {
            messenger.accept(p);
        }
    }

    public int size() {
        return clients.size();
    }

    public int lowIdCount() {
        return (int) clients.values().stream().filter(c -> !c.isHighId()).count();
    }

    public Iterable<ClientState> getAllClients() {
        return clients.values();
    }
}

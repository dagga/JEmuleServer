package org.jemule.core;

import org.jemule.network.Packet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ClientRegistry {
    private final ConcurrentHashMap<Integer, ClientState> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Consumer<Packet>> messengers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<Integer>> clientIdIndex = new ConcurrentHashMap<>();

    public void add(ClientState s, Consumer<Packet> messenger) {
        connections.put(s.connectionId(), s);
        if (messenger != null) messengers.put(s.connectionId(), messenger);
        clientIdIndex.computeIfAbsent(s.clientId(), k -> ConcurrentHashMap.newKeySet()).add(s.connectionId());
    }

    public void remove(ClientState s) {
        connections.remove(s.connectionId());
        messengers.remove(s.connectionId());
        Set<Integer> set = clientIdIndex.get(s.clientId());
        if (set != null) {
            set.remove(s.connectionId());
            if (set.isEmpty()) clientIdIndex.remove(s.clientId());
        }
    }

    public ClientState get(long clientId) {
        Set<Integer> set = clientIdIndex.get(clientId);
        if (set == null || set.isEmpty()) return null;
        for (Integer connId : set) {
            ClientState cs = connections.get(connId);
            if (cs != null) return cs;
        }
        return null;
    }

    public void sendTo(long targetId, Packet p) {
        Set<Integer> connIds = clientIdIndex.get(targetId);
        if (connIds == null) return;
        for (Integer connId : connIds) {
            Consumer<Packet> messenger = messengers.get(connId);
            if (messenger != null) messenger.accept(p);
        }
    }

    public int size() {
        return connections.size();
    }

    public int lowIdCount() {
        return (int) connections.values().stream().filter(c -> !c.isHighId()).count();
    }

    public Iterable<ClientState> getAllClients() {
        return connections.values();
    }
}

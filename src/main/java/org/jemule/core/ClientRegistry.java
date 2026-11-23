package org.jemule.core;

import org.jemule.network.Packet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ClientRegistry {
    private final ConcurrentHashMap<Integer, ClientState> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Consumer<Packet>> messengers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<Integer>> clientIdIndex = new ConcurrentHashMap<>();
    /** The clients refused by nickname or for publishing made-up hashes (not the IP filter). */
    private final ClientBans clientBans = new ClientBans();

    /** The clients refused by nickname or for publishing made-up hashes. */
    public ClientBans clientBans() {
        return clientBans;
    }

    /** Highest LowID: IDs below 2^24 are LowIDs for eMule (IsLowID). */
    static final long MAX_LOW_ID = 0x00FFFFFFL;
    private final java.util.concurrent.atomic.AtomicLong nextLowId = new java.util.concurrent.atomic.AtomicLong(1);

    /**
     * Allocates a LowID (1 .. 2^24-1) that is not used by a connected client. LowIDs are unique on this server;
     * other clients reach a LowID client through OP_CALLBACKREQUEST.
     */
    public long allocateLowId() {
        return allocateLowId(LowIdParity.ANY);
    }

    /**
     * LowID parity: with the Lugdunum NAT callback on, a client that opted in gets an odd LowID and every other client
     * an even one, so that a peer knows from a source's ID alone whether a callback is worth asking for (aMule master
     * a6d6290be0, docs/NAT_T_Protocol.md, "Lugdunum NAT callback").
     */
    public enum LowIdParity {
        ANY, ODD, EVEN;

        boolean accepts(long id) {
            return this == ANY || ((id & 1) == 1) == (this == ODD);
        }
    }

    /** Same as {@link #allocateLowId()}, with a LowID of the given parity. */
    public long allocateLowId(LowIdParity parity) {
        for (long tries = 0; tries < MAX_LOW_ID; tries++) {
            long id = nextLowId.getAndUpdate(v -> v >= MAX_LOW_ID ? 1 : v + 1);
            if (parity.accepts(id) && !clientIdIndex.containsKey(id)) return id;
        }
        throw new IllegalStateException("No LowID available");
    }

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

    /** Sends {@code p} to one connection, if it is still open. @return false if it is closed. */
    public boolean sendToConnection(int connectionId, Packet p) {
        Consumer<Packet> messenger = messengers.get(connectionId);
        if (messenger == null) return false;
        messenger.accept(p);
        return true;
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

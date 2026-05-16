package org.jemule.core.event;

import org.jemule.core.*;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EventSystemTest {

    @Test
    public void testEventManagerBroadcasting() {
        EventManager em = new EventManager();
        List<Event> received = new ArrayList<>();
        
        em.subscribeAll(received::add);
        
        ClientEvent ce = new ClientEvent(ClientEvent.CONNECTED, InetAddress.getLoopbackAddress().getHostAddress(), "test", "Welcome");
        em.broadcast(ce);
        
        assertEquals(1, received.size());
        assertEquals(ClientEvent.CONNECTED, received.getFirst().getType());
        assertEquals(InetAddress.getLoopbackAddress().getHostAddress(), ((ClientEvent)received.getFirst()).getClientIp());
    }

    @Test
    public void testSpecificSubscription() {
        EventManager em = new EventManager();
        List<Event> clientEvents = new ArrayList<>();
        List<Event> fileEvents = new ArrayList<>();
        
        em.subscribe(ClientEvent.CONNECTED, clientEvents::add);
        em.subscribe(FileEvent.PUBLISHED, fileEvents::add);
        
        em.broadcast(new ClientEvent(ClientEvent.CONNECTED, "1.1.1.1", "user", "hi"));
        em.broadcast(new FileEvent(FileEvent.PUBLISHED, "movie.avi", "HASH123", "new file"));
        em.broadcast(new ClientEvent(ClientEvent.DISCONNECTED, "1.1.1.1", "user", "bye"));
        
        assertEquals(1, clientEvents.size());
        assertEquals(1, fileEvents.size());
        assertEquals(ClientEvent.CONNECTED, clientEvents.getFirst().getType());
        assertEquals(FileEvent.PUBLISHED, fileEvents.getFirst().getType());
    }

    @Test
    public void testFileIndexIntegration() {
        EventManager em = new EventManager();
        List<Event> events = new ArrayList<>();
        em.subscribeAll(events::add);
        
        FileIndex index = new FileIndex(null, em);
        FileMetadata meta = new FileMetadata("HASH", "test.txt", 100, "txt");
        
        index.addFile(meta);
        
        assertEquals(1, events.size());
        assertInstanceOf(FileEvent.class, events.getFirst());
        assertEquals(FileEvent.PUBLISHED, events.getFirst().getType());
        
        index.search("test", 10);
        assertEquals(2, events.size());
        assertEquals(FileEvent.SEARCHED, events.get(1).getType());
    }
}

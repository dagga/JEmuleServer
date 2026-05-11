package org.jemule.core.event;

import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.network.ClientHandler;
import org.jemule.security.FloodProtector;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class EventSystemTest {

    @Test
    public void testEventManagerBroadcasting() {
        EventManager em = new EventManager();
        List<Event> received = new ArrayList<>();
        
        em.subscribeAll(received::add);
        
        ClientEvent ce = new ClientEvent(ClientEvent.CONNECTED, "127.0.0.1", "test", "Welcome");
        em.broadcast(ce);
        
        assertEquals(1, received.size());
        assertEquals(ClientEvent.CONNECTED, received.get(0).getType());
        assertEquals("127.0.0.1", ((ClientEvent)received.get(0)).getClientIp());
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
        assertEquals(ClientEvent.CONNECTED, clientEvents.get(0).getType());
        assertEquals(FileEvent.PUBLISHED, fileEvents.get(0).getType());
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
        assertTrue(events.get(0) instanceof FileEvent);
        assertEquals(FileEvent.PUBLISHED, events.get(0).getType());
        
        index.search("test", 10);
        assertEquals(2, events.size());
        assertEquals(FileEvent.SEARCHED, events.get(1).getType());
    }
}

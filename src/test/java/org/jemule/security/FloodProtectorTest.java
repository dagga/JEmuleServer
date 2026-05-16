package org.jemule.security;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FloodProtectorTest {

    @Test
    void testFloodProtection() throws Exception {
        FloodProtector protector = new FloodProtector(10);
        InetAddress ip = InetAddress.getLoopbackAddress();

        // Autoriser 10 requêtes
        for (int i = 0; i < 10; i++) {
            assertTrue(protector.allow(ip), "Request " + i + " should be allowed");
        }

        // La 11ème doit être refusée
        assertFalse(protector.allow(ip), "Request 11 should be denied");
    }

    @Test
    void testRefill() throws Exception {
        FloodProtector protector = new FloodProtector(5);
        InetAddress ip = InetAddress.getLoopbackAddress();

        for (int i = 0; i < 5; i++) assertTrue(protector.allow(ip));
        assertFalse(protector.allow(ip));

        // Attendre 1.1 seconde pour le refill
        Thread.sleep(1100);

        assertTrue(protector.allow(ip), "Should be allowed after refill");
    }

    @Test
    void testConcurrency() throws Exception {
        int threads = 10;
        int limit = 100;
        FloodProtector protector = new FloodProtector(limit);
        InetAddress ip = InetAddress.getByName("192.168.1.1");
        
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < 200; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    if (protector.allow(ip)) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown(); // Start all threads
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(limit, allowedCount.get(), "Should allow exactly the limit even with concurrent requests");
    }
}

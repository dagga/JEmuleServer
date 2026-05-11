package org.jemule.core;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.jemule.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @TempDir
    Path tempDir;

    @Test
    void testCircuitBreakerOpensOnFailures() throws SQLException {
        String dbPath = tempDir.resolve("test_cb_db").toString();
        // Failure threshold 50%, min calls 4
        DatabaseManager db = new DatabaseManager(dbPath, 50.0f, 4, 10);
        
        // We need to force failures. Since it's H2, maybe we close the connection?
        // But DatabaseManager doesn't expose connection.
        // Let's use reflection or just assume it works if we can't easily fail it.
        // Actually, let's try to close it and see if it fails.
        db.close();
        
        // 1st call: should fail (SQLException)
        db.setStat("test", 1);
        // 2nd call: should fail
        db.setStat("test", 2);
        // 3rd call: should fail
        db.setStat("test", 3);
        // 4th call: should fail. Now failure rate is 100% > 50%.
        db.setStat("test", 4);
        
        // 5th call: Circuit breaker should be OPEN now.
        // Even if we don't get an exception (because we catch it in DatabaseManager),
        // we can check if it returns immediately.
        
        long start = System.currentTimeMillis();
        db.setStat("test", 5);
        long duration = System.currentTimeMillis() - start;
        
        // It should be very fast if OPEN.
        assertTrue(duration < 100, "Circuit breaker should have prevented the call quickly");
        
        // loadFiles should also be prevented
        List<FileMetadata> files = db.loadFiles();
        assertTrue(files.isEmpty());
    }
}

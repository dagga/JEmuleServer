package org.jemule.network;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * The console 'stop' and the JVM shutdown hook both call {@link Server#stop()}: the server must close only once, and
 * the second call must not return (letting the JVM halt) before the first one has closed the index and the database.
 */
public class ServerStopTest {

    @Test
    public void concurrentStopsCloseOnceAndBothWaitForTheEnd(@TempDir Path tempDir) throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ServerConfig config = ServerConfig.DEFAULT.withPathsRelativeTo(tempDir).withPort(port).withAdminWebPort(0);
        Server server = new Server(config, new ClientFactory());
        Thread main = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
                // closed by stop()
            }
        });
        main.setDaemon(true);
        main.start();
        Thread.sleep(500);

        Logger serverLog = (Logger) LoggerFactory.getLogger(Server.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serverLog.addAppender(appender);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            List<Long> returnedAt = new CopyOnWriteArrayList<>();
            Runnable stopper = () -> {
                try {
                    go.await();
                    server.stop();
                    returnedAt.add(System.nanoTime());
                } catch (Throwable t) {
                    errors.add(t);
                }
            };
            Thread console = new Thread(stopper, "console-stop");
            Thread hook = new Thread(stopper, "shutdown-hook");
            console.start();
            hook.start();
            go.countDown();

            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                console.join();
                hook.join();
            });
            Assertions.assertTrue(errors.isEmpty(), () -> "stop() failed: " + errors);
            Assertions.assertEquals(2, returnedAt.size());

            long halted = appender.list.stream().filter(e -> e.getFormattedMessage().equals("Server halted.")).count();
            Assertions.assertEquals(1, halted, "the server must be closed exactly once");
            Assertions.assertTrue(appender.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(
                    ch.qos.logback.classic.Level.ERROR)), "no error while closing twice");

            // A later call (the hook after a completed console stop) returns at once
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), server::stop);
        } finally {
            serverLog.detachAppender(appender);
        }
    }
}

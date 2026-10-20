/*
 * JEmuleServer - An eMule server.
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

import org.jemule.core.ServerThreads.Tier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerThreadsTest {

    @Test
    void tiersAreOrderedAdminClientsSearchIngestionMaintenance() {
        assertTrue(Tier.ADMIN.priority > Tier.CLIENT.priority);
        assertTrue(Tier.CLIENT.priority > Tier.SEARCH.priority);
        assertTrue(Tier.SEARCH.priority > Tier.INGESTION.priority);
        assertTrue(Tier.INGESTION.priority > Tier.MAINTENANCE.priority);
    }

    @Test
    void platformTiersGetTheirPriorityAndTheClientTierVirtualThreads() throws Exception {
        for (Tier tier : Tier.values()) {
            CompletableFuture<Thread> running = new CompletableFuture<>();
            Thread thread = ServerThreads.start(tier, "test-" + tier, () -> running.complete(Thread.currentThread()));
            assertSame(thread, running.get(5, TimeUnit.SECONDS));
            assertEquals("test-" + tier, thread.getName());
            if (tier == Tier.CLIENT) {
                assertTrue(thread.isVirtual(), "clients are served by virtual threads");
            } else {
                assertFalse(thread.isVirtual(), tier + " must not share the carriers of the client threads");
                assertTrue(thread.isDaemon());
                assertEquals(tier.priority, thread.getPriority());
            }
        }
    }

    @Test
    void executorsRunTheirTasksInTheirTier() throws Exception {
        ExecutorService admin = ServerThreads.adminExecutor("admin-test");
        ScheduledExecutorService maintenance = ServerThreads.maintenanceScheduler("maintenance-test");
        ExecutorService clients = ServerThreads.clientExecutor();
        try {
            assertEquals(Tier.ADMIN.priority, admin.submit(() -> Thread.currentThread().getPriority()).get());
            assertEquals(Tier.MAINTENANCE.priority,
                    maintenance.submit(() -> Thread.currentThread().getPriority()).get());
            assertTrue(maintenance.submit(() -> Thread.currentThread().getName()).get().startsWith("maintenance-test-"));
            assertTrue(clients.submit(() -> Thread.currentThread().isVirtual()).get());
        } finally {
            admin.shutdownNow();
            maintenance.shutdownNow();
            clients.shutdownNow();
        }
    }
}

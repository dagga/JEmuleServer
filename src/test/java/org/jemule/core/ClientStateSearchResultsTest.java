package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClientStateSearchResultsTest {
    @Test
    void remoteCompletionAppendsToInitializedResultsAndKeepsMoreAvailableWhileLoading() throws Exception {
        ClientState state = new ClientState(InetAddress.getLoopbackAddress(), 4662, 1234,
                System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        FileMetadata local = file("00000000000000000000000000000001");
        FileMetadata remote = file("00000000000000000000000000000002");

        long generation = state.beginPendingSearch();
        state.initializePendingSearch(generation, List.of(local), true);
        ClientState.PendingSearchBatch first = state.takePendingSearchBatch(1);
        assertNotNull(first);
        assertEquals(List.of(local), first.results());
        assertTrue(first.moreAvailable());

        state.completePendingSearch(generation, List.of(remote));
        ClientState.PendingSearchBatch second = state.takePendingSearchBatch(10);
        assertNotNull(second);
        assertEquals(List.of(remote), second.results());
        assertFalse(second.moreAvailable());
    }

    @Test
    void staleRemoteCompletionCannotModifyAReplacementSearchQueue() throws Exception {
        ClientState state = new ClientState(InetAddress.getLoopbackAddress(), 4662, 1234,
                System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        FileMetadata previous = file("00000000000000000000000000000001");
        FileMetadata current = file("00000000000000000000000000000002");

        long previousGeneration = state.beginPendingSearch();
        state.initializePendingSearch(previousGeneration, List.of(previous), true);
        long currentGeneration = state.beginPendingSearch();
        state.initializePendingSearch(currentGeneration, List.of(current), false);
        state.completePendingSearch(previousGeneration, List.of(previous));

        ClientState.PendingSearchBatch currentBatch = state.takePendingSearchBatch(10);
        assertNotNull(currentBatch);
        assertEquals(List.of(current), currentBatch.results());
        assertFalse(currentBatch.moreAvailable());
        assertNull(state.takePendingSearchBatch(10));
    }

    private static FileMetadata file(String hash) {
        return new FileMetadata(hash, "file-" + hash, 1024, "bin");
    }
}

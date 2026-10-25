package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostingsTest {

    private static List<Integer> ids(Postings postings) {
        List<Integer> ids = new ArrayList<>();
        postings.forEach(ids::add);
        return ids;
    }

    @Test
    void aSingleNumberAndIncreasingOnes() {
        Postings postings = new Postings(0);
        assertEquals(List.of(0), ids(postings));
        postings.add(1);
        postings.add(200);
        postings.add(70_000);
        postings.add(Integer.MAX_VALUE);
        assertEquals(List.of(0, 1, 200, 70_000, Integer.MAX_VALUE), ids(postings));
        assertEquals(5, postings.size());
    }

    @Test
    void reusedNumbersAreInsertedInOrder() {
        Postings postings = new Postings(10);
        postings.add(20);
        postings.add(5);
        postings.add(15);
        assertEquals(List.of(5, 10, 15, 20), ids(postings));
        postings.add(30);
        assertEquals(List.of(5, 10, 15, 20, 30), ids(postings));
    }

    @Test
    void removalsDownToNothingAndBack() {
        Postings postings = new Postings(3);
        postings.add(8);
        postings.add(9);
        postings.remove(4); // absent
        postings.remove(100); // absent, above the highest
        postings.remove(8);
        assertEquals(List.of(3, 9), ids(postings));
        postings.remove(3);
        assertEquals(List.of(9), ids(postings));
        postings.add(2);
        assertEquals(List.of(2, 9), ids(postings));
        postings.remove(2);
        postings.remove(9);
        assertEquals(0, postings.size());
        assertEquals(List.of(), ids(postings));
        postings.add(7);
        assertEquals(List.of(7), ids(postings));
    }

    @Test
    void randomOperationsMatchASortedSet() {
        Random random = new Random(1);
        TreeSet<Integer> expected = new TreeSet<>();
        Postings postings = new Postings(random.nextInt(1000));
        expected.add(ids(postings).getFirst());
        for (int i = 0; i < 20_000; i++) {
            int id = random.nextInt(i % 3 == 0 ? 1 << 20 : 2000);
            if (random.nextInt(4) == 0) {
                expected.remove(id);
                postings.remove(id);
            } else if (expected.add(id)) {
                postings.add(id);
            }
            if (i % 5000 == 0) postings.trim();
        }
        assertEquals(new ArrayList<>(expected), ids(postings));
        assertEquals(expected.size(), postings.size());
    }
}

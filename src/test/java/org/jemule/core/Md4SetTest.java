package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Md4SetTest {

    @Test
    void hashesAreFoundWhateverTheirCase() {
        Md4Set set = new Md4Set();
        assertTrue(set.add("aabbccddeeff00112233445566778899"));
        assertFalse(set.add("AABBCCDDEEFF00112233445566778899"), "same hash");
        assertTrue(set.contains("AaBbCcDdEeFf00112233445566778899"));
        assertFalse(set.contains("aabbccddeeff00112233445566778898"));
        assertEquals(1, set.size());
    }

    @Test
    void otherStringsAndTheZeroHashAreKeptToo() {
        Md4Set set = new Md4Set();
        assertTrue(set.add("not a hash"));
        assertTrue(set.contains("NOT A HASH"));
        assertTrue(set.add("00000000000000000000000000000000"));
        assertTrue(set.contains("00000000000000000000000000000000"));
        assertFalse(set.contains(null));
        assertEquals(2, set.size());
        assertTrue(set.remove("00000000000000000000000000000000"));
        assertTrue(set.remove("Not A Hash"));
        assertEquals(0, set.size());
    }

    @Test
    void behavesLikeASetOfStringsUnderRandomOperations() {
        Random random = new Random(42);
        Md4Set set = new Md4Set();
        Set<String> expected = new HashSet<>();
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            // Few distinct high halves, so that runs of collisions are frequent
            pool.add(String.format("%016X%016X", random.nextInt(4), random.nextLong()));
        }
        for (int op = 0; op < 200_000; op++) {
            String hash = pool.get(random.nextInt(pool.size()));
            String variant = random.nextBoolean() ? hash : hash.toLowerCase(Locale.ROOT);
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(expected.add(hash), set.add(variant));
                case 1 -> assertEquals(expected.remove(hash), set.remove(variant));
                default -> assertEquals(expected.contains(hash), set.contains(variant));
            }
        }
        assertEquals(expected.size(), set.size());
        for (String hash : pool) assertEquals(expected.contains(hash), set.contains(hash));
        set.clear();
        assertEquals(0, set.size());
    }
}

package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileTableTest {

    private static FileMetadata file(int n, String name) {
        return FileMetadata.withoutSources(String.format("%032x", n), name, 1000 + n, "Video", List.of());
    }

    @Test
    void namesOfEveryKindAreGivenBack() {
        FileTable table = new FileTable();
        List<String> names = List.of("plain.avi", "été à la plage.mp3", "日本語のファイル.mkv", "emoji 🎬.mp4",
                "", "x".repeat(1024), "Ÿ œ € ÿ.txt");
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) ids.add(table.add(file(i, names.get(i))));
        int noName = table.add(file(99, null));
        for (int i = 0; i < names.size(); i++) assertEquals(names.get(i), table.name(ids.get(i)));
        assertNull(table.name(noName));
        assertEquals(names.get(2), table.view(ids.get(2)).name());
    }

    @Test
    void renamedAndRemovedFilesKeepTheOthersIntact() {
        Random random = new Random(7);
        FileTable table = new FileTable();
        Map<Integer, String> expected = new HashMap<>();
        Map<Integer, Integer> numbers = new HashMap<>();
        for (int round = 0; round < 50_000; round++) {
            int n = random.nextInt(3000);
            String name = "file " + n + " v" + random.nextInt(5) + (random.nextInt(10) == 0 ? " ñ日" : "") + ".avi";
            Integer id = numbers.get(n);
            int action = random.nextInt(4);
            if (id == null) {
                id = table.add(file(n, name));
                numbers.put(n, id);
                expected.put(id, name);
            } else if (action == 0) {
                table.remove(id);
                numbers.remove(n);
                expected.remove(id);
            } else {
                // a change of the source count (same name) or a new name
                String newName = action == 1 ? expected.get(id) : name;
                table.set(id, file(n, newName));
                expected.put(id, newName);
            }
            if (round % 10_000 == 0) table.trim();
        }
        table.trim();
        assertEquals(expected.size(), table.count());
        expected.forEach((id, name) -> assertEquals(name, table.name(id)));
    }
}

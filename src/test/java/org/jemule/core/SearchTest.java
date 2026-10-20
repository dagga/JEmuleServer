package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchTest {

    @Test
    public void testSimpleSearch() {
        FileMetadata meta = new FileMetadata("HASH1", "Test File.txt", 1000, "Text");
        
        // Leaf String Tag2 (type 0x01)
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x01);
        byte[] term = "test".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term.length);
        buf.put(term);
        buf.flip();

        SearchQuery query = SearchQuery.parse(buf);
        assertTrue(query.test(meta));
        assertFalse(query.test(new FileMetadata("HASH2", "Other.bin", 2000, "Binary")));
    }

    @Test
    public void fileTypeFilterUsesTheExtensionOfFilesWithoutType() {
        SearchQuery video = new SearchQuery.TermQuery("Video", SearchQuery.ID_FILETYPE);
        SearchQuery program = new SearchQuery.TermQuery("Pro", SearchQuery.ID_FILETYPE);

        // Many servers send no FT_FILETYPE in their search results: eMule derives it from the extension
        assertTrue(video.test(new FileMetadata("HASH1", "The Matrix (1999).MKV", 2_000_000_000L, null)));
        assertTrue(video.test(new FileMetadata("HASH2", "matrix.avi", 700_000_000L, "")));
        // Archives and CD images are searched as "Pro"
        assertTrue(program.test(new FileMetadata("HASH3", "ubuntu.iso", 4_000_000_000L, "")));
        assertTrue(program.test(new FileMetadata("HASH4", "sources.tar.gz", 1_000L, "")));
        assertFalse(video.test(new FileMetadata("HASH5", "matrix", 700_000_000L, "")), "no extension, no type");
        // An announced type wins over the extension
        assertFalse(video.test(new FileMetadata("HASH6", "matrix.avi", 700_000_000L, "Audio")));
    }

    @Test
    public void testAndSearch() {
        FileMetadata meta = new FileMetadata("HASH1", "Test File.txt", 1000, "Text");

        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        // AND operator
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);
        
        // Left: "test"
        buf.put((byte) 0x01);
        byte[] term1 = "test".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term1.length);
        buf.put(term1);

        // Right: "file"
        buf.put((byte) 0x01);
        byte[] term2 = "file".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term2.length);
        buf.put(term2);
        
        buf.flip();

        SearchQuery query = SearchQuery.parse(buf);
        assertTrue(query.test(meta));
        assertFalse(query.test(new FileMetadata("HASH2", "Test.bin", 1000, "Binary")));
    }

    @Test
    public void testSizeFilter() {
        FileMetadata small = new FileMetadata("H1", "Small.txt", 100, "Text");
        FileMetadata large = new FileMetadata("H2", "Large.txt", 10000, "Text");

        // MIN SIZE 500
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x03); // Numeric Tag with ID
        buf.putInt(500);
        buf.put((byte) 0x01); // ED2K_SEARCH_OP_GREATER
        buf.putShort((short) 1); // Name len
        buf.put((byte) 0x02); // ID_FILESIZE
        buf.flip();

        SearchQuery query = SearchQuery.parse(buf);
        assertFalse(query.test(small));
        assertTrue(query.test(large));
    }
    
    @Test
    public void testFileTypeFilter() {
        FileMetadata movie = new FileMetadata("H1", "Movie.avi", 1000000, "Video");
        
        // FILETYPE "Video"
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x02); // String Tag with ID
        byte[] term = "Video".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term.length);
        buf.put(term);
        buf.putShort((short) 1); // Name len
        buf.put((byte) 0x03); // ID_FILETYPE
        buf.flip();
        
        SearchQuery query = SearchQuery.parse(buf);
        assertTrue(query.test(movie));
        assertFalse(query.test(new FileMetadata("H2", "Song.mp3", 5000, "Audio")));
    }

    @Test
    public void testNotSearch() {
        FileMetadata meta = new FileMetadata("HASH1", "Test File.txt", 1000, "Text");

        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        // NOT operator (AND NOT)
        buf.put((byte) 0x00);
        buf.put((byte) 0x02);
        
        // Left: "test"
        buf.put((byte) 0x01);
        byte[] term1 = "test".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term1.length);
        buf.put(term1);

        // Right: "other"
        buf.put((byte) 0x01);
        byte[] term2 = "other".getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) term2.length);
        buf.put(term2);
        
        buf.flip();

        SearchQuery query = SearchQuery.parse(buf);
        assertTrue(query.test(meta));
        assertFalse(query.test(new FileMetadata("HASH2", "Test Other.txt", 1000, "Text")));
    }
}

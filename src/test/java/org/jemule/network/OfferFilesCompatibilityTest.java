package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.network.handler.PublishHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OP_OFFERFILES as produced by CSharedFileList::SendListToServer / CreateOfferedFilePacket (eMule 0.50a).
 */
class OfferFilesCompatibilityTest {

    private FileIndex index;
    private FakeContext ctx;

    @BeforeEach
    void setup() throws Exception {
        index = new FileIndex(null);
        ctx = loggedInContext("10.1.2.3", 50000, ServerConfig.DEFAULT, new ClientRegistry(), index);
    }

    private void offer(byte[] payload) throws Exception {
        new PublishHandler().handlePublish(ctx, OpCode.OFFER_FILES, payload, new ByteArrayOutputStream());
    }

    private FileMetadata file(String hexHash) {
        return index.search("", 1000).stream().filter(m -> m.hash().equals(hexHash)).findFirst().orElse(null);
    }

    @Test
    void sizesAreReadAsUnsignedIntegers() throws Exception {
        offer(offerFiles(
                completeFile(hash("11111111111111111111111111111111"), "three-gigabytes.avi", 3_000_000_000L),
                completeFile(hash("22222222222222222222222222222222"), "uint16-size.txt", 40_000L),
                completeFile(hash("33333333333333333333333333333333"), "uint8-size.txt", 200L),
                completeFile(hash("44444444444444444444444444444444"), "large-file.mkv", 5_000_000_000L)));

        assertEquals(3_000_000_000L, file("11111111111111111111111111111111").size());
        assertEquals(40_000L, file("22222222222222222222222222222222").size());
        assertEquals(200L, file("33333333333333333333333333333333").size());
        assertEquals(5_000_000_000L, file("44444444444444444444444444444444").size());
    }

    @Test
    void offersWith32To127FilesAreNotMistakenForText() throws Exception {
        // The first payload byte is the low byte of the file count: 64 = 0x40 = '@'
        byte[][] files = new byte[64][];
        for (int i = 0; i < files.length; i++) {
            files[i] = completeFile(hash(String.format("%032x", i + 1)), "shared file " + i + ".avi", 700_000_000L + i);
        }
        offer(offerFiles(files));
        assertEquals(64, index.fileCount());
    }

    @Test
    void integerFileTypeIsStoredAsTheSearchTerm() throws Exception {
        byte[] video = completeFile(hash("55555555555555555555555555555555"), "video.avi", 1000); // FT_FILETYPE = 2 (UINT8)
        byte[] archive = offeredFile(hash("66666666666666666666666666666666"), 0xFBFBFBFB, 0xFBFB,
                new NewTags().str(0x01, "archive.zip").num(0x02, 1000).num(0x03, 6)); // ED2KFT_ARCHIVE
        byte[] collection = offeredFile(hash("77777777777777777777777777777777"), 0xFBFBFBFB, 0xFBFB,
                new NewTags().str(0x01, "my.emulecollection").num(0x02, 1000).str(0x03, "EmuleCollection"));
        offer(offerFiles(video, archive, collection));

        assertEquals("Video", file("55555555555555555555555555555555").type());
        assertEquals("Pro", file("66666666666666666666666666666666").type());
        assertEquals("EmuleCollection", file("77777777777777777777777777777777").type());
    }
}

package com.github.luben.zstd;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the one-shot API after the semi-mite migration.
 */
class ZstdOneShotTest {

    private static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void compressAndDecompressRoundTrip() {
        for (int level : new int[]{1, 3, 9, 19}) {
            byte[] source = randomBytes(64 * 1024, level);
            byte[] compressed = Zstd.compress(source, level);
            assertTrue(compressed.length > 0);
            assertArrayEquals(source, Zstd.decompress(compressed, source.length),
                    "round trip at level " + level);
        }
    }

    @Test
    void emptyInputProducesValidFrame() {
        byte[] compressed = Zstd.compress(new byte[0]);
        byte[] restored = Zstd.decompress(compressed, 0);
        assertEquals(0, restored.length);
    }

    @Test
    void wholeBufferApiReportsSizeAndErrors() {
        byte[] source = randomBytes(8 * 1024, 42);
        byte[] dst = new byte[(int) Zstd.compressBound(source.length)];
        long written = Zstd.compress(dst, source, 3);
        assertFalse(Zstd.isError(written));

        byte[] restored = new byte[source.length];
        long read = Zstd.decompress(restored, Arrays.copyOf(dst, (int) written));
        assertFalse(Zstd.isError(read));
        assertEquals(source.length, read);
        assertArrayEquals(source, restored);

        // too small a destination surfaces a ZstdException carrying the zstd code
        ZstdException failure = assertThrows(ZstdException.class,
                () -> Zstd.compress(new byte[1], source, 3));
        assertEquals(Zstd.errDstSizeTooSmall(), failure.getErrorCode());
        assertNotNull(Zstd.getErrorName(-Zstd.errDstSizeTooSmall()));
    }

    @Test
    void byteArrayWithOffsets() {
        byte[] source = randomBytes(10_000, 1);
        byte[] compressed = new byte[(int) Zstd.compressBound(source.length) + 17];
        long written = Zstd.compressByteArray(compressed, 7, compressed.length - 7,
                source, 0, source.length, 3);
        assertFalse(Zstd.isError(written));

        byte[] restored = new byte[source.length + 11];
        long read = Zstd.decompressByteArray(restored, 5, source.length, compressed, 7, (int) written);
        assertFalse(Zstd.isError(read));
        assertArrayEquals(source, Arrays.copyOfRange(restored, 5, 5 + source.length));
    }

    @Test
    void directByteBufferRoundTrip() {
        byte[] source = randomBytes(20_000, 9);
        ByteBuffer src = ByteBuffer.allocateDirect(source.length);
        src.put(source).flip();

        ByteBuffer dst = ByteBuffer.allocateDirect((int) Zstd.compressBound(source.length));
        int compressedSize = Zstd.compress(dst, src, 4);
        assertEquals(source.length, src.position());
        assertEquals(compressedSize, dst.position());

        dst.flip();
        ByteBuffer restored = ByteBuffer.allocateDirect(source.length);
        int decompressedSize = Zstd.decompress(restored, dst);
        assertEquals(source.length, decompressedSize);
        restored.flip();
        byte[] actual = new byte[decompressedSize];
        restored.get(actual);
        assertArrayEquals(source, actual);

        // the convenience variant allocates and returns a ready-to-read buffer
        ByteBuffer src2 = ByteBuffer.allocateDirect(source.length);
        src2.put(source).flip();
        ByteBuffer compressedBuffer = Zstd.compress(src2, 3);
        assertNotNull(compressedBuffer);
        ByteBuffer restoredBuffer = Zstd.decompress(compressedBuffer, source.length);
        byte[] restoredBytes = new byte[restoredBuffer.remaining()];
        restoredBuffer.get(restoredBytes);
        assertArrayEquals(source, restoredBytes);
    }

    @Test
    void frameIntrospection() {
        byte[] source = randomBytes(4096, 3);
        byte[] compressed = Zstd.compress(source, 3);

        assertEquals(compressed.length, Zstd.findFrameCompressedSize(compressed));
        assertEquals(compressed.length, Zstd.findFrameCompressedSize(compressed, 0, compressed.length));
        assertEquals(source.length, Zstd.getFrameContentSize(compressed));
        assertEquals(source.length, Zstd.getFrameContentSize(compressed, 0, compressed.length));
        assertEquals(source.length, Zstd.decompressedSize(compressed));
        assertEquals(Zstd.magicNumber(), 0xFD2FB528);

        ByteBuffer direct = ByteBuffer.allocateDirect(compressed.length);
        direct.put(compressed).flip();
        assertEquals(compressed.length, Zstd.findFrameCompressedSize(direct));
        assertEquals(source.length, Zstd.getFrameContentSize(direct));
    }

    @Test
    void multipleFramesAreDecompressed() {
        byte[] first = "first frame".getBytes();
        byte[] second = "second frame, a bit longer".getBytes();
        byte[] c1 = Zstd.compress(first, 3);
        byte[] c2 = Zstd.compress(second, 3);
        byte[] combined = new byte[c1.length + c2.length];
        System.arraycopy(c1, 0, combined, 0, c1.length);
        System.arraycopy(c2, 0, combined, c1.length, c2.length);

        byte[] restored = Zstd.decompress(combined);
        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, restored);
    }

    @Test
    void decompressFrameUsesTheFrameHeader() {
        byte[] source = randomBytes(5000, 31);
        byte[] compressed = Zstd.compress(source, 3);
        assertArrayEquals(source, Zstd.decompressFrame(compressed));
        assertArrayEquals(source, Zstd.decompressFrame(compressed, 0));
        assertEquals(compressed.length, Zstd.findFrameCompressedSize(compressed));
    }

    @Test
    void truncatedFrameThrows() {
        byte[] compressed = Zstd.compress(randomBytes(1000, 4), 3);
        assertThrows(ZstdException.class,
                () -> Zstd.decompressFrame(compressed, 0, compressed.length / 2, 1000));
    }

    @Test
    void errorConstantsMatchBundledLibrary() {
        assertEquals(0, Zstd.errNoError());
        assertEquals(1, Zstd.errGeneric());
        assertEquals(10, Zstd.errPrefixUnknown());
        assertEquals(20, Zstd.errCorruptionDetected());
        assertEquals(64, Zstd.errMemoryAllocation());
        assertEquals(70, Zstd.errDstSizeTooSmall());
        assertTrue(Zstd.isError(-Zstd.errGeneric()));
        assertFalse(Zstd.isError(Zstd.errNoError()));
    }

    @Test
    void staticConstantsAreSane() {
        assertTrue(Zstd.windowLogMin() <= Zstd.windowLogMax());
        assertTrue(Zstd.chainLogMin() <= Zstd.chainLogMax());
        assertTrue(Zstd.hashLogMin() <= Zstd.hashLogMax());
        assertTrue(Zstd.searchLogMin() <= Zstd.searchLogMax());
        assertTrue(Zstd.searchLengthMin() <= Zstd.searchLengthMax());
        assertTrue(Zstd.blockSizeMax() > 0);
        assertTrue(Zstd.minCompressionLevel() <= Zstd.defaultCompressionLevel());
        assertTrue(Zstd.maxCompressionLevel() >= Zstd.defaultCompressionLevel());
    }

    @Test
    void precompiledResourceFramesStillDecompress() throws Exception {
        byte[] compressed;
        try (var in = getClass().getResourceAsStream("/xml-1.zst")) {
            assertNotNull(in);
            compressed = in.readAllBytes();
        }
        byte[] expected;
        try (var in = getClass().getResourceAsStream("/xml")) {
            assertNotNull(in);
            expected = in.readAllBytes();
        }
        byte[] actual = Zstd.decompress(compressed, expected.length);
        assertArrayEquals(expected, actual);
    }

    @Test
    void streamOfResourceFramesWorks() throws Exception {
        byte[] compressed;
        try (var in = getClass().getResourceAsStream("/xml-1x2.zst")) {
            assertNotNull(in);
            compressed = in.readAllBytes();
        }
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            byte[] restored = in.readAllBytes();
            assertEquals(2 * 5_345_280, restored.length);
        }
    }
}

package com.github.luben.zstd;

import org.junit.jupiter.api.Test;

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
 * Context configuration, frame progression and error-path tests. These exercise
 * the parameter setters that used to be individual JNI entry points and are now
 * ordinary semi-mite client calls.
 */
class ZstdContextTest {

    private static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void checksumAndContentSizeFlags() {
        byte[] source = randomBytes(50_000, 41);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(3).setChecksum(true).setContentSize(true);
            byte[] compressed = ctx.compress(source);

            // flip a byte at the very end: only the checksum can catch it
            compressed[compressed.length - 2] ^= 0x55;
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                assertThrows(ZstdException.class, () -> dctx.decompress(compressed, source.length));
            }
        }
    }

    @Test
    void contentSizeCanBeOmitted() {
        byte[] source = randomBytes(10_000, 42);
        byte[] compressed;
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setContentSize(false);
            compressed = ctx.compress(source);
        }
        assertEquals(-1, Zstd.getFrameContentSize(compressed));
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            assertArrayEquals(source, dctx.decompress(compressed, source.length));
        }
    }

    @Test
    void pledgedSourceSizeIsWrittenToTheFrame() {
        byte[] source = randomBytes(7_000, 43);
        byte[] compressed;
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setPledgedSrcSize(source.length);
            byte[] dst = new byte[(int) Zstd.compressBound(source.length)];
            int size = ctx.compress(dst, source);
            compressed = Arrays.copyOf(dst, size);
        }
        assertEquals(source.length, Zstd.getFrameContentSize(compressed));
    }

    @Test
    void frameProgressionTracksInput() {
        byte[] source = randomBytes(30_000, 44);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(1).setPledgedSrcSize(source.length);
            byte[] dst = new byte[(int) Zstd.compressBound(source.length)];
            ctx.compress(dst, source);

            ZstdFrameProgression progression = ctx.getFrameProgression();
            assertEquals(source.length, progression.getIngested());
            assertTrue(progression.getProduced() > 0);
            assertEquals(0, progression.getCurrentJobID());
            assertEquals(0, progression.getNbActiveWorkers());
        }
    }

    @Test
    void dictionaryIdCanBeSuppressed() {
        Random random = new Random(45);
        byte[][] samples = new byte[128][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new byte[256];
            for (int j = 0; j < samples[i].length; j++) {
                samples[i][j] = (byte) ('a' + random.nextInt(6));
            }
        }
        byte[] dictBuffer = new byte[4096];
        long trainedSize = Zstd.trainFromBuffer(samples, dictBuffer);
        assertFalse(Zstd.isError(trainedSize));
        byte[] trained = Arrays.copyOf(dictBuffer, (int) trainedSize);
        long id = Zstd.getDictIdFromDict(trained);
        assertTrue(id > 0);

        byte[] source = randomBytes(1000, 46);
        // The context holds a shared lock on the dictionary, so the dictionary
        // must be closed *after* the context (declaration order in a
        // try-with-resources block is closed in reverse).
        try (ZstdDictCompress dictCompress = new ZstdDictCompress(trained, 3);
             ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.loadDict(dictCompress).setLevel(3).setDictID(false);
            byte[] compressed = ctx.compress(source);
            assertEquals(0, Zstd.getDictIdFromFrame(compressed));
        }
    }

    @Test
    void longDistanceMatchingAndWorkerSettings() {
        byte[] source = randomBytes(200_000, 47);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(3).setWindowLog(20).setLong(20)
                    .setWorkers(2).setJobSize(64 * 1024)
                    .setOverlapLog(6).setTargetLength(16)
                    .setMinMatch(4).setSearchLog(4)
                    .setChainLog(10).setHashLog(14)
                    .setStrategy(2).setValidateSequences(Zstd.ParamSwitch.ENABLE)
                    .setEnableLongDistanceMatching(Zstd.ParamSwitch.ENABLE);
            byte[] compressed = ctx.compress(source);
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                dctx.setLongMax(27);
                assertArrayEquals(source, dctx.decompress(compressed, source.length));
            }

            // disabling LDM again must not fail
            ctx.setLong(0);
        }
    }

    @Test
    void magiclessFrames() {
        byte[] source = randomBytes(15_000, 48);
        byte[] compressed;
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            compressed = ctx.setMagicless(true).compress(source);
        }
        assertEquals(source.length,
                Zstd.getFrameContentSize(compressed, 0, compressed.length, true));
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            byte[] restored = dctx.setMagicless(true).decompress(compressed, source.length);
            assertArrayEquals(source, restored);
        }
    }

    @Test
    void resetClearsSessionAndParameters() {
        byte[] source = randomBytes(5_000, 49);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(9).setChecksum(true);
            byte[] first = ctx.compress(source);
            assertNotNull(first);

            ctx.reset();
            ctx.setLevel(1);
            byte[] second = ctx.compress(source);
            assertNotNull(second);

            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                assertArrayEquals(source, dctx.decompress(first, source.length));
                assertArrayEquals(source, dctx.decompress(second, source.length));
            }
        }
    }

    @Test
    void closedContextRejectsCalls() {
        ZstdCompressCtx ctx = new ZstdCompressCtx();
        ctx.close();
        assertThrows(IllegalStateException.class, () -> ctx.setLevel(3));

        ZstdDecompressCtx dctx = new ZstdDecompressCtx();
        dctx.close();
        assertThrows(IllegalStateException.class, () -> dctx.setMagicless(true));
    }

    @Test
    void loadingANullDictionaryRemovesIt() {
        byte[] dict = new byte[4096];
        new Random(52).nextBytes(dict);
        byte[] source = randomBytes(3000, 53);

        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.loadDict(dict).setLevel(3);
            byte[] withDict = ctx.compress(source);

            ctx.loadDict((byte[]) null);
            byte[] withoutDict = ctx.compress(source);

            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                assertArrayEquals(source, dctx.decompress(withDict, source.length));
                assertArrayEquals(source, dctx.decompress(withoutDict, source.length));
            }
        }
    }

    @Test
    void sequenceProducerCanBeCleared() {
        byte[] source = randomBytes(4000, 54);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            // A null producer unregisters the external producer: the C bridge
            // passes (state = 0, function = 0) to ZSTD_registerSequenceProducer.
            ctx.registerSequenceProducer(null);
            ctx.setSequenceProducerFallback(true);
            byte[] compressed = ctx.compress(source);
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                assertArrayEquals(source, dctx.decompress(compressed, source.length));
            }
        }
    }

    @Test
    void byteBufferCompressionWithOffsets() {
        byte[] source = randomBytes(9_000, 50);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ByteBuffer dst = ByteBuffer.allocateDirect(32 * 1024);
            ByteBuffer src = ByteBuffer.allocateDirect(source.length);
            src.put(source).flip();

            int written = ctx.compressDirectByteBuffer(dst, 11, dst.capacity() - 11,
                    src, 0, src.remaining());
            assertTrue(written > 0);
            // low-level method: the caller owns the positions
            assertEquals(0, src.position());
            assertEquals(0, dst.position());
        }
    }
}

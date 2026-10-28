package com.github.luben.zstd;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dictionary tests: prepared dictionaries, training, dictionary ids and the
 * by-reference entry points (which are copy-based after the semi-mite
 * migration, with unchanged observable behaviour).
 */
class ZstdDictionaryTest {

    private static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sampleDictionary(int length) {
        byte[] dict = new byte[length];
        Random random = new Random(51);
        for (int i = 0; i < length; i++) {
            dict[i] = (byte) ('a' + random.nextInt(8));
        }
        return dict;
    }

    @Test
    void preparedDictionaryRoundTrip() {
        byte[] dict = sampleDictionary(16 * 1024);
        byte[] source = randomBytes(8_000, 21);

        try (ZstdDictCompress compressDict = new ZstdDictCompress(dict, 4)) {
            byte[] compressed = Zstd.compress(source, compressDict);
            try (ZstdDictDecompress decompressDict = new ZstdDictDecompress(dict)) {
                byte[] restored = Zstd.decompress(compressed, decompressDict, source.length);
                assertArrayEquals(source, restored);
            }
        }
    }

    @Test
    void dictionaryWithOffsetAndLength() {
        byte[] padded = new byte[4096 + 32];
        byte[] dict = sampleDictionary(4096);
        System.arraycopy(dict, 0, padded, 16, dict.length);
        byte[] source = randomBytes(4_000, 22);

        try (ZstdDictCompress compressDict = new ZstdDictCompress(padded, 16, 4096, 3)) {
            byte[] compressed = Zstd.compress(source, compressDict);
            try (ZstdDictDecompress decompressDict = new ZstdDictDecompress(padded, 16, 4096)) {
                assertArrayEquals(source,
                        Zstd.decompress(compressed, decompressDict, source.length));
            }
        }
    }

    @Test
    void directBufferDictionaryIsCopied() {
        byte[] dict = sampleDictionary(8 * 1024);
        ByteBuffer dictBuffer = ByteBuffer.allocateDirect(dict.length);
        dictBuffer.put(dict).flip();
        byte[] source = randomBytes(3_000, 23);

        try (ZstdDictCompress compressDict = new ZstdDictCompress(dictBuffer, 3, true);
             ZstdDictDecompress decompressDict = new ZstdDictDecompress(dictBuffer, true)) {
            assertNotNull(compressDict.getByReferenceBuffer());
            byte[] compressed = Zstd.compress(source, compressDict);
            assertArrayEquals(source,
                    Zstd.decompress(compressed, decompressDict, source.length));
        }
    }

    @Test
    void contextDictionaryLoading() {
        byte[] dict = sampleDictionary(8 * 1024);
        byte[] source = randomBytes(5_000, 24);

        try (ZstdDictCompress compressDict = new ZstdDictCompress(dict, 3);
             ZstdCompressCtx compressCtx = new ZstdCompressCtx()) {
            compressCtx.setLevel(3).loadDict(compressDict);
            byte[] compressed = compressCtx.compress(source);

            try (ZstdDictDecompress decompressDict = new ZstdDictDecompress(dict);
                 ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx()) {
                decompressCtx.loadDict(decompressDict);
                assertArrayEquals(source, decompressCtx.decompress(compressed, source.length));
            }

            // byte[] dictionaries go through ZSTD_*Ctx_loadDictionary
            try (ZstdCompressCtx rawCtx = new ZstdCompressCtx()) {
                rawCtx.loadDict(dict).setLevel(3);
                byte[] compressedRaw = rawCtx.compress(source);
                try (ZstdDecompressCtx rawDec = new ZstdDecompressCtx()) {
                    rawDec.loadDict(dict);
                    assertArrayEquals(source, rawDec.decompress(compressedRaw, source.length));
                }
            }
        }
    }

    @Test
    void dictionaryIdIsExposed() {
        byte[] dict = sampleDictionary(1024);
        long id = Zstd.getDictIdFromDict(dict);
        // A raw content dictionary has no id; a trained one does.
        assertTrue(id >= 0);

        byte[] trained = trainDictionary();
        long trainedId = Zstd.getDictIdFromDict(trained);
        assertTrue(trainedId > 0, "trained dictionary should carry an id");

        byte[] source = randomBytes(2_000, 25);
        byte[] compressed = Zstd.compressUsingDict(source, trained, 3);
        assertEquals(trainedId, Zstd.getDictIdFromFrame(compressed));
    }

    @Test
    void trainerFromSampleArrays() {
        byte[][] samples = trainingSamples();
        byte[] dictBuffer = new byte[4096];
        long size = Zstd.trainFromBuffer(samples, dictBuffer);
        assertFalse(Zstd.isError(size));
        assertTrue(size > 0);
        byte[] dict = Arrays.copyOf(dictBuffer, (int) size);
        byte[] source = randomBytes(2_000, 26);
        assertArrayEquals(source, Zstd.decompress(
                Zstd.compressUsingDict(source, dict, 3), dict, source.length));
    }

    @Test
    void trainerLegacyAlgorithm() {
        byte[][] samples = trainingSamples();
        byte[] dictBuffer = new byte[4096];
        long size = Zstd.trainFromBuffer(samples, dictBuffer, true, 3);
        assertFalse(Zstd.isError(size));
        assertTrue(size > 0);
    }

    @Test
    void trainerDirectBufferApi() {
        byte[][] samples = trainingSamples();
        int total = 0;
        for (byte[] sample : samples) {
            total += sample.length;
        }
        ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(total);
        int[] sizes = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            sizes[i] = samples[i].length;
            sampleBuffer.put(samples[i]);
        }
        sampleBuffer.flip();

        ByteBuffer dictBuffer = ByteBuffer.allocateDirect(4096);
        long size = Zstd.trainFromBufferDirect(sampleBuffer, sizes, dictBuffer, false);
        assertFalse(Zstd.isError(size));
        assertTrue(size > 0);
    }

    @Test
    void zstdDictTrainerFacade() {
        ZstdDictTrainer trainer = new ZstdDictTrainer(512 * 1024, 8192);
        for (byte[] sample : trainingSamples()) {
            assertTrue(trainer.addSample(sample));
        }
        byte[] dict = trainer.trainSamples();
        assertTrue(dict.length > 0);
        byte[] source = randomBytes(1_500, 27);
        byte[] compressed = Zstd.compressUsingDict(source, dict, 3);
        assertArrayEquals(source, Zstd.decompress(compressed, dict, source.length));
    }

    @Test
    void streamDictionarySupport() throws Exception {
        byte[] dict = sampleDictionary(8 * 1024);
        byte[] source = randomBytes(20_000, 28);

        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(sink, 3)) {
            out.setDict(dict);
            out.write(source);
        }
        try (ZstdInputStream in = new ZstdInputStream(
                new java.io.ByteArrayInputStream(sink.toByteArray()))) {
            in.setDict(dict);
            assertArrayEquals(source, in.readAllBytes());
        }
    }

    @Test
    void dictionaryIdFromDirectBuffer() {
        byte[] trained = trainDictionary();
        ByteBuffer buffer = ByteBuffer.allocateDirect(trained.length);
        buffer.put(trained).flip();
        assertEquals(Zstd.getDictIdFromDict(trained), Zstd.getDictIdFromDictDirect(buffer));

        byte[] compressed = Zstd.compressUsingDict(randomBytes(1000, 55), trained, 3);
        ByteBuffer frame = ByteBuffer.allocateDirect(compressed.length);
        frame.put(compressed).flip();
        assertEquals(Zstd.getDictIdFromFrame(compressed), Zstd.getDictIdFromFrameBuffer(frame));
    }

    @Test
    void advanceDictionaryApiRejectsNull() {
        assertEquals(-Zstd.errDictionaryWrong(), Zstd.loadDictCompress(0, null, 0));
        assertEquals(-Zstd.errDictionaryWrong(), Zstd.loadDictDecompress(0, null, 0));
        assertEquals(-Zstd.errDictionaryWrong(), Zstd.loadFastDictCompress(0, null));
        assertEquals(-Zstd.errDictionaryWrong(), Zstd.loadFastDictDecompress(0, null));
    }

    private static byte[][] trainingSamples() {
        Random random = new Random(31);
        // The legacy trainer needs a reasonable amount of sample material.
        byte[][] samples = new byte[1000][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new byte[256];
            for (int j = 0; j < samples[i].length; j++) {
                samples[i][j] = (byte) ('a' + random.nextInt(6));
            }
        }
        return samples;
    }

    private static byte[] trainDictionary() {
        byte[] buffer = new byte[8192];
        long size = Zstd.trainFromBuffer(trainingSamples(), buffer);
        assertFalse(Zstd.isError(size));
        return Arrays.copyOf(buffer, (int) size);
    }
}

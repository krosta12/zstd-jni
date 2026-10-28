package com.github.luben.zstd;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * semi-mite creates one confined arena per call, so concurrent use of the
 * shared proxy must be safe. The zstd contexts themselves are per-thread, as
 * documented by the public API.
 */
class ZstdConcurrencyTest {

    @Test
    void concurrentCompressionAndDecompression() throws Exception {
        byte[] source = new byte[40_000];
        new Random(71).nextBytes(source);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                final int id = worker;
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 20; iteration++) {
                        byte[] compressed = Zstd.compress(source, 1 + (id % 5));
                        assertArrayEquals(source, Zstd.decompress(compressed, source.length));
                        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                            ctx.setLevel(3);
                            byte[] buffer = new byte[(int) Zstd.compressBound(source.length)];
                            int written = ctx.compress(buffer, source);
                            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                                byte[] restored = new byte[source.length];
                                dctx.decompress(restored, java.util.Arrays.copyOf(buffer, written));
                                assertArrayEquals(source, restored);
                            }
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}

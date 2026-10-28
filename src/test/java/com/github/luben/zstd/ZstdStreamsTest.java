package com.github.luben.zstd;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming tests: IO streams, buffer streams, direct-buffer streams and the
 * pull/push ByteBuffer API on the contexts.
 */
class ZstdStreamsTest {

    private static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void outputStreamToInputStreamRoundTrip() throws Exception {
        byte[] source = randomBytes(200_000, 11);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(compressed, 6)) {
            out.write(source, 0, 70_000);
            out.flush();
            out.write(source, 70_000, source.length - 70_000);
        }

        try (ZstdInputStream in = new ZstdInputStream(
                new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(source, in.readAllBytes());
        }
    }

    @Test
    void outputStreamCloseFrameOnFlush() throws Exception {
        byte[] source = randomBytes(50_000, 5);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(compressed, 3)) {
            out.setCloseFrameOnFlush(true);
            out.write(source);
            out.flush();
            out.write(source);
        }
        try (ZstdInputStream in = new ZstdInputStream(
                new ByteArrayInputStream(compressed.toByteArray()))) {
            byte[] restored = in.readAllBytes();
            assertEquals(2 * source.length, restored.length);
            assertArrayEquals(source, Arrays.copyOfRange(restored, 0, source.length));
            assertArrayEquals(source, Arrays.copyOfRange(restored, source.length, restored.length));
        }
    }

    @Test
    void emptyOutputStreamProducesValidFrame() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(compressed)) {
            // no writes at all
        }
        try (ZstdInputStream in = new ZstdInputStream(
                new ByteArrayInputStream(compressed.toByteArray()))) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void inputStreamResetSessionReusesContext() throws Exception {
        byte[] first = randomBytes(20_000, 1);
        byte[] second = randomBytes(30_000, 2);
        byte[] c1 = Zstd.compress(first, 3);
        byte[] c2 = Zstd.compress(second, 3);

        try (ZstdInputStreamNoFinalizer in = new ZstdInputStreamNoFinalizer(
                new ByteArrayInputStream(c1))) {
            assertArrayEquals(first, in.readAllBytes());
            in.resetSession(new ByteArrayInputStream(c2));
            assertArrayEquals(second, in.readAllBytes());
        }
    }

    @Test
    void heapBufferDecompressingStream() throws Exception {
        byte[] source = randomBytes(120_000, 7);
        byte[] compressed = Zstd.compress(source, 4);

        try (ZstdBufferDecompressingStream in =
                     new ZstdBufferDecompressingStream(ByteBuffer.wrap(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer target = ByteBuffer.allocate(
                    ZstdBufferDecompressingStream.recommendedTargetBufferSize());
            while (in.hasRemaining()) {
                target.clear();
                int read = in.read(target);
                target.flip();
                byte[] chunk = new byte[read];
                target.get(chunk);
                out.write(chunk);
            }
            assertArrayEquals(source, out.toByteArray());
        }
    }

    @Test
    void directBufferStreams() throws Exception {
        byte[] source = randomBytes(150_000, 8);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ByteBuffer target = ByteBuffer.allocateDirect(
                ZstdDirectBufferCompressingStream.recommendedOutputBufferSize());
        ZstdDirectBufferCompressingStream out =
                new ZstdDirectBufferCompressingStream(target, 4) {
                    @Override
                    protected ByteBuffer flushBuffer(ByteBuffer toFlush) throws IOException {
                        toFlush.flip();
                        byte[] chunk = new byte[toFlush.remaining()];
                        toFlush.get(chunk);
                        sink.write(chunk);
                        toFlush.clear();
                        return toFlush;
                    }
                };
        ByteBuffer directSource = ByteBuffer.allocateDirect(source.length);
        directSource.put(source).flip();
        out.compress(directSource);
        out.close();

        byte[] compressed = sink.toByteArray();
        ByteBuffer compressedDirect = ByteBuffer.allocateDirect(compressed.length);
        compressedDirect.put(compressed).flip();

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        ByteBuffer decompressed = ByteBuffer.allocateDirect(
                ZstdDirectBufferDecompressingStream.recommendedTargetBufferSize());
        try (ZstdDirectBufferDecompressingStream in =
                     new ZstdDirectBufferDecompressingStream(compressedDirect)) {
            while (true) {
                decompressed.clear();
                int read = in.read(decompressed);
                if (read == 0) {
                    break;
                }
                decompressed.flip();
                byte[] chunk = new byte[read];
                decompressed.get(chunk);
                restored.write(chunk);
                if (!in.hasRemaining()) {
                    break;
                }
            }
        }
        assertArrayEquals(source, restored.toByteArray());
    }

    @Test
    void contextByteBufferStream() {
        byte[] source = randomBytes(300_000, 12);
        byte[] restored;

        try (ZstdCompressCtx compressCtx = new ZstdCompressCtx();
             ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx()) {
            compressCtx.setLevel(5);
            compressCtx.setChecksum(true);

            ByteBuffer input = ByteBuffer.allocateDirect(source.length);
            input.put(source).flip();
            ByteBuffer compressed = ByteBuffer.allocateDirect(
                    (int) Zstd.compressBound(source.length));

            boolean finished = false;
            while (!finished) {
                finished = compressCtx.compressByteBufferStream(compressed, input, EndDirective.END);
            }
            assertEquals(input.limit(), input.position());
            compressed.flip();

            ByteBuffer output = ByteBuffer.allocateDirect(source.length);
            boolean done = false;
            while (!done) {
                done = decompressCtx.decompressByteBufferStream(output, compressed);
            }
            output.flip();
            restored = new byte[output.remaining()];
            output.get(restored);
        }
        assertArrayEquals(source, restored);
    }

    @Test
    void contextByteBufferStreamWithHeapBuffers() {
        byte[] source = randomBytes(80_000, 13);
        try (ZstdCompressCtx compressCtx = new ZstdCompressCtx();
             ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx()) {
            ByteBuffer input = ByteBuffer.wrap(source);
            ByteBuffer compressed = ByteBuffer.allocate((int) Zstd.compressBound(source.length));
            boolean finished = false;
            while (!finished) {
                finished = compressCtx.compressByteBufferStream(compressed, input, EndDirective.END);
            }
            compressed.flip();

            ByteBuffer output = ByteBuffer.allocate(source.length);
            boolean done = false;
            while (!done) {
                done = decompressCtx.decompressByteBufferStream(output, compressed);
            }
            output.flip();
            byte[] restored = new byte[output.remaining()];
            output.get(restored);
            assertArrayEquals(source, restored);
        }
    }

    @Test
    void contextCompressByteArrayAndBufferConvenienceMethods() {
        byte[] source = randomBytes(10_000, 14);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(2);
            byte[] compressed = ctx.compress(source);
            ByteBuffer sourceBuffer = ByteBuffer.allocateDirect(source.length);
            sourceBuffer.put(source).flip();
            ByteBuffer compressedBuffer = ctx.compress(sourceBuffer);
            assertNotNull(compressedBuffer);
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                ByteBuffer restoredBuffer = dctx.decompress(compressedBuffer, source.length);
                byte[] restored = new byte[restoredBuffer.remaining()];
                restoredBuffer.get(restored);
                assertArrayEquals(source, restored);
            }
        }
    }

    @Test
    void directBufferStreamWithDictionary() throws Exception {
        byte[] dict = new byte[4096];
        Random random = new Random(81);
        for (int i = 0; i < dict.length; i++) {
            dict[i] = (byte) ('a' + random.nextInt(8));
        }
        byte[] source = randomBytes(20_000, 82);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ByteBuffer target = ByteBuffer.allocateDirect(
                ZstdDirectBufferCompressingStream.recommendedOutputBufferSize());
        ZstdDirectBufferCompressingStream out =
                new ZstdDirectBufferCompressingStream(target, 3) {
                    @Override
                    protected ByteBuffer flushBuffer(ByteBuffer toFlush) throws IOException {
                        toFlush.flip();
                        byte[] chunk = new byte[toFlush.remaining()];
                        toFlush.get(chunk);
                        sink.write(chunk);
                        toFlush.clear();
                        return toFlush;
                    }
                };
        out.setDict(dict);
        ByteBuffer directSource = ByteBuffer.allocateDirect(source.length);
        directSource.put(source).flip();
        out.compress(directSource);
        out.close();

        assertArrayEquals(source,
                Zstd.decompress(sink.toByteArray(), dict, source.length));
    }

    @Test
    void directBufferStreamWithPreparedDictionary() throws Exception {
        byte[] dict = new byte[4096];
        Random random = new Random(83);
        for (int i = 0; i < dict.length; i++) {
            dict[i] = (byte) ('a' + random.nextInt(8));
        }
        byte[] source = randomBytes(20_000, 84);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ZstdDictCompress prepared = new ZstdDictCompress(dict, 3);
        try {
            ByteBuffer target = ByteBuffer.allocateDirect(
                    ZstdDirectBufferCompressingStream.recommendedOutputBufferSize());
            ZstdDirectBufferCompressingStream out =
                    new ZstdDirectBufferCompressingStream(target, 3) {
                        @Override
                        protected ByteBuffer flushBuffer(ByteBuffer toFlush) throws IOException {
                            toFlush.flip();
                            byte[] chunk = new byte[toFlush.remaining()];
                            toFlush.get(chunk);
                            sink.write(chunk);
                            toFlush.clear();
                            return toFlush;
                        }
                    };
            out.setDict(prepared);
            ByteBuffer directSource = ByteBuffer.allocateDirect(source.length);
            directSource.put(source).flip();
            out.compress(directSource);
            out.close();
        } finally {
            prepared.close();
        }

        assertArrayEquals(source,
                Zstd.decompress(sink.toByteArray(), dict, source.length));
    }

    @Test
    void streamReportsZstdErrorOnCorruptInput() throws Exception {
        byte[] source = randomBytes(1000, 15);
        byte[] compressed;
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setChecksum(true);
            compressed = ctx.compress(source);
        }
        compressed[compressed.length / 2] ^= 0x7F;

        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            in.readAllBytes();
            org.junit.jupiter.api.Assertions.fail("corrupt frame was not detected");
        } catch (IOException expected) {
            // corrupt frames surface as ZstdIOException
        }
    }
}

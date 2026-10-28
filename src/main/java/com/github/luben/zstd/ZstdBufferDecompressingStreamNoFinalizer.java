package com.github.luben.zstd;

import com.github.luben.zstd.mite.MiteIoPos;
import com.github.luben.zstd.mite.ZstdMite;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ZstdBufferDecompressingStreamNoFinalizer extends BaseZstdBufferDecompressingStreamNoFinalizer {
    static {
        ZstdMite.ensureLoaded();
    }

    public ZstdBufferDecompressingStreamNoFinalizer(@NotNull ByteBuffer source) {
        super(source);
        if (source.isDirect()) {
            throw new IllegalArgumentException("Source buffer should be a non-direct buffer");
        }
        stream = createDStream();
        initDStream(stream);
    }

    @Override
    public int read(@NotNull ByteBuffer target) throws IOException {
        if (target.isDirect()) {
            throw new IllegalArgumentException("Target buffer should be a non-direct buffer");
        }
        return readInternal(target, false);
    }

    @Override
    long createDStream() {
        return createDStreamNative();
    }

    @Override
    long freeDStream(long stream) {
        return freeDStreamNative(stream);
    }

    @Override
    long initDStream(long stream) {
        return initDStreamNative(stream);
    }

    @Override
    long decompressStream(long stream, @NotNull ByteBuffer dst, int dstBufPos, int dstSize, @NotNull ByteBuffer src, int srcBufPos, int srcSize) {
        if (!src.hasArray()) {
            throw new IllegalArgumentException("provided source ByteBuffer lacks array");
        }
        if (!dst.hasArray()) {
            throw new IllegalArgumentException("provided destination ByteBuffer lacks array");
        }
        byte[] targetArr = dst.array();
        byte[] sourceArr = src.array();

        // We are interested in array data corresponding to the pos represented by the ByteBuffer view.
        // A ByteBuffer may share an underlying array with other ByteBuffers. In such scenario, we need to adjust the
        // index of the array by adding an offset using arrayOffset().
        return decompressStreamNative(stream, targetArr, dstBufPos + dst.arrayOffset(), dstSize, sourceArr, srcBufPos + src.arrayOffset(), srcSize);
    }

    public static int recommendedTargetBufferSize() {
        return (int) recommendedDOutSizeNative();
    }

    private long createDStreamNative() {
        return ZstdMite.client().zm_createDStream();
    }

    private long freeDStreamNative(long stream) {
        return ZstdMite.client().zm_freeDStream(stream);
    }

    private long initDStreamNative(long stream) {
        return ZstdMite.client().zm_initDStream(stream);
    }

    private long decompressStreamNative(long stream, byte @NotNull [] dst, int dstOffset, int dstSize, byte @NotNull [] src, int srcOffset, int srcSize) {
        MiteIoPos positions = new MiteIoPos();
        long result = ZstdMite.client().zm_dStreamDecompress(stream, dst, dstOffset, dstSize,
                src, srcOffset, srcSize, positions);
        this.consumed = positions.srcPos;
        this.produced = positions.dstPos;
        return result;
    }

    private static long recommendedDOutSizeNative() {
        return ZstdMite.client().zm_DStreamOutSize();
    }
}

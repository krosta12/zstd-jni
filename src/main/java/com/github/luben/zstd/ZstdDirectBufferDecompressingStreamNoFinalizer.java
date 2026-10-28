package com.github.luben.zstd;

import com.github.luben.zstd.mite.ByteBuffers;
import com.github.luben.zstd.mite.MiteIoPos;
import com.github.luben.zstd.mite.ZstdMite;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ZstdDirectBufferDecompressingStreamNoFinalizer extends BaseZstdBufferDecompressingStreamNoFinalizer {
    static {
        ZstdMite.ensureLoaded();
    }

    public ZstdDirectBufferDecompressingStreamNoFinalizer(@NotNull ByteBuffer source) {
        super(source);
        if (!source.isDirect()) {
            throw new IllegalArgumentException("Source buffer should be a direct buffer");
        }
        this.source = source;
        stream = createDStream();
        initDStream(stream);
    }

    @Override
    public int read(@NotNull ByteBuffer target) throws IOException {
        if (!target.isDirect()) {
            throw new IllegalArgumentException("Target buffer should be a direct buffer");
        }
        return readInternal(target, true);
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
    long decompressStream(long stream, @NotNull ByteBuffer dst, int dstOffset, int dstSize, @NotNull ByteBuffer src, int srcOffset, int srcSize) {
        return decompressStreamNative(stream, dst, dstOffset, dstSize, src, srcOffset, srcSize);
    }

    public static int recommendedTargetBufferSize() {
        return (int) recommendedDOutSizeNative();
    }

    private static long createDStreamNative() {
        return ZstdMite.client().zm_createDStream();
    }

    private static long freeDStreamNative(long stream) {
        return ZstdMite.client().zm_freeDStream(stream);
    }

    private long initDStreamNative(long stream) {
        return ZstdMite.client().zm_initDStream(stream);
    }

    private long decompressStreamNative(long stream, @NotNull ByteBuffer dst, int dstOffset, int dstSize, @NotNull ByteBuffer src, int srcOffset, int srcSize) {
        MiteIoPos positions = new MiteIoPos();
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        byte[] srcCopy = ByteBuffers.read(src, srcOffset, srcSize);
        long result = ZstdMite.client().zm_dStreamDecompress(stream, dstCopy, 0, dstSize,
                srcCopy, 0, srcSize, positions);
        this.consumed = positions.srcPos;
        this.produced = positions.dstPos;
        if (!Zstd.isError(result) && positions.dstPos > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, positions.dstPos);
        }
        return result;
    }

    private static long recommendedDOutSizeNative() {
        return ZstdMite.client().zm_DStreamOutSize();
    }
}

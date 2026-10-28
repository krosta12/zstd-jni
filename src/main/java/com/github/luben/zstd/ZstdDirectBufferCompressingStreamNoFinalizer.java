package com.github.luben.zstd;

import com.github.luben.zstd.mite.ByteBuffers;
import com.github.luben.zstd.mite.MiteIoPos;
import com.github.luben.zstd.mite.ZstdMite;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ZstdDirectBufferCompressingStreamNoFinalizer implements Closeable, Flushable {

    static {
        ZstdMite.ensureLoaded();
    }

    private @Nullable ByteBuffer target;
    private final long stream;

    /**
     * This method should flush the buffer and either return the same buffer (but cleared) or a new buffer
     * that should be used from then on.
     * @param toFlush buffer that has to be flushed (or most cases, you want to call {@link ByteBuffer#flip()} first)
     * @return the new buffer to use, for most cases the same as the one passed in, after a call to {@link ByteBuffer#clear()}.
     */
    protected @NotNull ByteBuffer flushBuffer(@NotNull ByteBuffer toFlush) throws IOException {
        return toFlush;
    }

    public ZstdDirectBufferCompressingStreamNoFinalizer(@NotNull ByteBuffer target, int level) throws IOException {
        if (!target.isDirect()) {
            throw new IllegalArgumentException("Target buffer should be a direct buffer");
        }
        this.target = target;
        this.level = level;
        stream = createCStream();
    }

    public static int recommendedOutputBufferSize() { return (int)recommendedCOutSize(); }

    private int consumed = 0;
    private int produced = 0;
    private boolean closed = false;
    private boolean initialized = false;
    private int level = Zstd.defaultCompressionLevel();
    private byte @Nullable [] dict = null;
    private @Nullable ZstdDictCompress fastDict = null;

    /* semi-mite bridge methods */
    private static long recommendedCOutSize() {
        return ZstdMite.client().zm_CStreamOutSize();
    }

    private static long createCStream() {
        return ZstdMite.client().zm_createCStream();
    }

    private static long freeCStream(long ctx) {
        return ZstdMite.client().zm_freeCStream(ctx);
    }

    private long initCStream(long ctx, int level) {
        return ZstdMite.client().zm_initCStream(ctx, level);
    }

    private long initCStreamWithDict(long ctx, byte @NotNull [] dict, int dict_size, int level) {
        return ZstdMite.client().zm_initCStreamWithDict(ctx, dict, dict_size, level);
    }

    private long initCStreamWithFastDict(long ctx, @NotNull ZstdDictCompress dict) {
        return ZstdMite.client().zm_initCStreamWithFastDict(ctx, dict.nativePtr());
    }

    private long compressDirectByteBuffer(long ctx, @NotNull ByteBuffer dst, int dstOffset, int dstSize, @NotNull ByteBuffer src, int srcOffset, int srcSize) {
        MiteIoPos positions = new MiteIoPos();
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        byte[] srcCopy = ByteBuffers.read(src, srcOffset, srcSize);
        long result = ZstdMite.client().zm_cStreamCompress(ctx, dstCopy, 0, dstSize,
                srcCopy, 0, srcSize, positions);
        this.consumed = positions.srcPos;
        this.produced = positions.dstPos;
        if (!Zstd.isError(result) && positions.dstPos > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, positions.dstPos);
        }
        return result;
    }

    private long flushStream(long ctx, @NotNull ByteBuffer dst, int dstOffset, int dstSize) {
        MiteIoPos positions = new MiteIoPos();
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        long result = ZstdMite.client().zm_cStreamFlush(ctx, dstCopy, 0, dstSize, positions);
        this.produced = positions.dstPos;
        if (!Zstd.isError(result) && positions.dstPos > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, positions.dstPos);
        }
        return result;
    }

    private long endStream(long ctx, @NotNull ByteBuffer dst, int dstOffset, int dstSize) {
        MiteIoPos positions = new MiteIoPos();
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        long result = ZstdMite.client().zm_cStreamEnd(ctx, dstCopy, 0, dstSize, positions);
        this.produced = positions.dstPos;
        if (!Zstd.isError(result) && positions.dstPos > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, positions.dstPos);
        }
        return result;
    }

    public @NotNull ZstdDirectBufferCompressingStreamNoFinalizer setDict(byte @NotNull [] dict) {
        if (initialized) {
            throw new IllegalStateException("Change of parameter on initialized stream");
        }
        this.dict = dict;
        this.fastDict = null;
        return this;
    }

    public @NotNull ZstdDirectBufferCompressingStreamNoFinalizer setDict(@NotNull ZstdDictCompress dict) {
        if (initialized) {
            throw new IllegalStateException("Change of parameter on initialized stream");
        }
        this.dict = null;
        dict.acquireSharedLock();
        if (this.fastDict != null) {
            this.fastDict.releaseSharedLock();
        }
        this.fastDict = dict;
        return this;
    }

    public void compress(@NotNull ByteBuffer source) throws IOException {
        if (!source.isDirect()) {
            throw new IllegalArgumentException("Source buffer should be a direct buffer");
        }
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (!initialized) {
            long result = 0;
            ZstdDictCompress fastDict = this.fastDict;
            if (fastDict != null) {
                result = initCStreamWithFastDict(stream, fastDict);
            } else if (dict != null) {
                result = initCStreamWithDict(stream, dict, dict.length, level);
            } else {
                result = initCStream(stream, level);
            }
            if (Zstd.isError(result)) {
                throw new ZstdIOException(result);
            }
            initialized = true;
        }
        while (source.hasRemaining()) {
            ByteBuffer target = this.target;
            if (target == null) {
                throw new IOException("Stream closed");
            }
            if (!target.hasRemaining()) {
                target = flushBuffer(target);
                this.target = target;
                if (!target.isDirect()) {
                    throw new IllegalArgumentException("Target buffer should be a direct buffer");
                }
                if (!target.hasRemaining()) {
                    throw new IOException("The target buffer has no more space, even after flushing, and there are still bytes to compress");
                }
            }
            long result = compressDirectByteBuffer(stream, target, target.position(), target.remaining(), source, source.position(), source.remaining());
            if (Zstd.isError(result)) {
                throw new ZstdIOException(result);
            }
            target.position(target.position() + produced);
            source.position(source.position() + consumed);
        }
    }

    @Override
    public void flush() throws IOException {
        if (closed) {
            throw new IOException("Already closed");
        }
        if (initialized) {
            long needed;
            do {
                ByteBuffer target = this.target;
                if (target == null) {
                    throw new IOException("Stream closed");
                }
                needed = flushStream(stream, target, target.position(), target.remaining());
                if (Zstd.isError(needed)) {
                    throw new ZstdIOException(needed);
                }
                target.position(target.position() + produced);
                target = flushBuffer(target);
                this.target = target;
                if (!target.isDirect()) {
                    throw new IllegalArgumentException("Target buffer should be a direct buffer");
                }
                if (needed > 0 && !target.hasRemaining()) {
                    // don't check on the first iteration of the loop
                    throw new IOException("The target buffer has no more space, even after flushing, and there are still bytes to compress");
                }
            }
            while (needed > 0);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            try {
                if (initialized) {
                    long needed;
                    do {
                        ByteBuffer target = this.target;
                        if (target == null) {
                            throw new IOException("Stream closed");
                        }
                        needed = endStream(stream, target, target.position(), target.remaining());
                        if (Zstd.isError(needed)) {
                            throw new ZstdIOException(needed);
                        }
                        target.position(target.position() + produced);
                        target = flushBuffer(target);
                        this.target = target;
                        if (!target.isDirect()) {
                            throw new IllegalArgumentException("Target buffer should be a direct buffer");
                        }
                        if (needed > 0 && !target.hasRemaining()) {
                            throw new IOException("The target buffer has no more space, even after flushing, and there are still bytes to compress");
                        }
                    } while (needed > 0);
                }
            }
            finally {
                freeCStream(stream);
                closed = true;
                initialized = false;
                target = null; // help GC with realizing the buffer can be released
                if (fastDict != null) {
                    fastDict.releaseSharedLock();
                }
                fastDict = null;
                dict = null;
            }
        }
    }
}

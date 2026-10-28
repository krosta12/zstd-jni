package com.github.luben.zstd;

import com.github.luben.zstd.mite.ByteBuffers;
import com.github.luben.zstd.mite.ZstdMite;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ZstdDecompressCtx extends AutoCloseBase {

    static {
        ZstdMite.ensureLoaded();
    }

    private long nativePtr = 0;
    // Note: keeps a reference to the dictionary so it's not garbage collected
    @Nullable
    private ZstdDictDecompress decompression_dict = null;

    private static long init() {
        return ZstdMite.client().zm_createDCtx();
    }

    private static void free(long nativePtr) {
        ZstdMite.client().zm_freeDCtx(nativePtr);
    }

    /**
     * Create a context for faster compress operations
     * One such context is required for each thread - put this in a ThreadLocal.
     */
    public ZstdDecompressCtx() {
        nativePtr = init();
        if (0 == nativePtr) {
            throw new IllegalStateException("ZSTD_createDeCompressCtx failed");
        }
        storeFence();
    }

    void doClose() {
        if (nativePtr != 0) {
            free(nativePtr);
            nativePtr = 0;
        }
        if (decompression_dict != null) {
            decompression_dict.releaseSharedLock();
            decompression_dict = null;
        }
    }

    /**
     * Enable or disable magicless frames
     * @param magiclessFlag A 32-bits checksum of content is written at end of frame, default: false
     */
    @NotNull
    public ZstdDecompressCtx setMagicless(boolean magiclessFlag) {
        ensureOpen();
        acquireSharedLock();
        Zstd.setDecompressionMagicless(nativePtr, magiclessFlag);
        releaseSharedLock();
        return this;
    }

    /**
     * Set the maximum window size the decompressor accepts, as a power of 2.
     * Frames that need a larger window are rejected instead of decoded, which
     * bounds the memory a frame header can make the decompressor allocate.
     * Cleared by {@link #reset()}.
     *
     * @param windowLogMax Maximum window size, expressed as power of 2,
     *                     default: 27 (ZSTD_WINDOWLOG_LIMIT_DEFAULT)
     */
    @NotNull
    public ZstdDecompressCtx setLongMax(int windowLogMax) {
        ensureOpen();
        acquireSharedLock();
        try {
            long result = Zstd.setDecompressionLongMax(nativePtr, windowLogMax);
            if (Zstd.isError(result)) {
                throw new ZstdException(result);
            }
        } finally {
            releaseSharedLock();
        }
        return this;
    }

    /**
     * Load decompression dictionary
     *
     * @param dict the dictionary
     */
    @NotNull
    public ZstdDecompressCtx loadDict(@Nullable ZstdDictDecompress dict) {
        ensureOpen();
        acquireSharedLock();
        if (dict != null) {
            dict.acquireSharedLock();
        }
        try {
            long result = loadDDictFast0(nativePtr, dict);
            if (Zstd.isError(result)) {
                throw new ZstdException(result);
            }
            if (decompression_dict != null) {
                decompression_dict.releaseSharedLock();
            }
            // keep a reference to the dictionary so it's not garbage collected
            decompression_dict = dict;
        } finally {
            releaseSharedLock();
        }
        return this;
    }

    private static long loadDDictFast0(long nativePtr, @Nullable ZstdDictDecompress dict) {
        return ZstdMite.client().zm_refDDict(nativePtr, dict == null ? 0 : dict.nativePtr());
    }

    /**
     * Load decompression dictionary.
     *
     * @param dict the dictionary or `null` to remove loaded dictionary
     */
    @NotNull
    public ZstdDecompressCtx loadDict(byte @Nullable [] dict) {
        ensureOpen();
        acquireSharedLock();
        try {
            long result = loadDDict0(nativePtr, dict);
            if (Zstd.isError(result)) {
                throw new ZstdException(result);
            }
            if (decompression_dict != null) {
                decompression_dict.releaseSharedLock();
                decompression_dict = null;
            }
        } finally {
            releaseSharedLock();
        }
        return this;
    }

    private static long loadDDict0(long nativePtr, byte @Nullable [] dict) {
        return ZstdMite.client().zm_loadDDict(nativePtr, dict, dict == null ? 0 : dict.length);
    }

    /**
     * Clear all state and parameters from the decompression context. This leaves the object in a
     * state identical to a newly created decompression context.
     */
    public void reset() {
        ensureOpen();
        acquireSharedLock();
        try {
            long result = reset0(nativePtr);
            if (Zstd.isError(result)) {
                throw new ZstdException(result);
            }
            if (decompression_dict != null) {
                decompression_dict.releaseSharedLock();
                decompression_dict = null;
            }
        } finally {
            releaseSharedLock();
        }

    }

    private static long reset0(long nativePtr) {
        return ZstdMite.client().zm_resetDCtx(nativePtr);
    }

    private void ensureOpen() {
        if (nativePtr == 0) {
            throw new IllegalStateException("Decompression context is closed");
        }
    }

    /**
     * Decompress as much of the <code>src</code> {@link ByteBuffer} into the <code>dst</code> {@link
     * ByteBuffer} as possible. Both buffers may be direct or array-backed heap buffers. The
     * destination buffer must be writable.
     *
     * @param dst destination of uncompressed data
     * @param src buffer to decompress
     * @return true if all state has been flushed from internal buffers
     * @throws IllegalArgumentException if either buffer is unsupported or the destination is read-only
     */
    public boolean decompressByteBufferStream(@NotNull ByteBuffer dst, @NotNull ByteBuffer src) {
        ensureOpen();
        if (dst.isReadOnly()) {
            throw new IllegalArgumentException("dst must be writable");
        }
        if (!dst.isDirect() && !dst.hasArray()) {
            throw new IllegalArgumentException("dst must be a direct or array-backed buffer");
        }
        if (!src.isDirect() && !src.hasArray()) {
            throw new IllegalArgumentException("src must be a direct or array-backed buffer");
        }

        acquireSharedLock();
        try {
            return decompressStreamImpl(dst, src);
        } finally {
            releaseSharedLock();
        }
    }

    /**
     * Shared implementation of the buffer-stream entry points. Both buffers may
     * be direct or array-backed: the affected regions are copied into arrays,
     * handed to semi-mite, and the produced bytes and positions are written
     * back afterwards.
     */
    private boolean decompressStreamImpl(@NotNull ByteBuffer dst, @NotNull ByteBuffer src) {
        int dstStart = dst.position();
        int dstLength = dst.limit() - dstStart;
        int srcStart = src.position();
        int srcLength = src.limit() - srcStart;

        byte[] dstCopy = ByteBuffers.read(dst, dstStart, dstLength);
        byte[] srcCopy = ByteBuffers.read(src, srcStart, srcLength);

        long result = ZstdMite.client().zm_decompressStream(nativePtr,
                dstCopy, 0, dstLength, srcCopy, 0, srcLength);
        if ((result & 0x80000000L) != 0) {
            long code = -(result & 0xFF);
            throw new ZstdException(code, Zstd.getErrorName(code));
        }

        int produced = (int) (result >>> 32) & 0x7FFFFFFF;
        int consumed = (int) result & 0x7FFFFFFF;
        ByteBuffers.write(dst, dstStart, dstCopy, 0, produced);
        dst.position(dstStart + produced);
        src.position(srcStart + consumed);
        return (result >>> 63) == 1;
    }

    /**
     * Decompress as much of the <code>src</code> {@link ByteBuffer} into the <code>dst</code> {@link
     * ByteBuffer} as possible.
     *
     * @param dst destination of uncompressed data
     * @param src buffer to decompress
     * @return true if all state has been flushed from internal buffers
     */
    public boolean decompressDirectByteBufferStream(@NotNull ByteBuffer dst, @NotNull ByteBuffer src) {
        ensureOpen();
        acquireSharedLock();
        try {
            return decompressStreamImpl(dst, src);
        } finally {
            releaseSharedLock();
        }
    }

    /**
     * Decompresses buffer 'srcBuff' into buffer 'dstBuff' using this ZstdDecompressCtx.
     * <p>
     * Destination buffer should be sized to be larger of equal to the originalSize.
     * This is a low-level function that does not take into account or affect the `limit`
     * or `position` of source or destination buffers.
     *
     * @param dstBuff   the destination buffer - must be direct
     * @param dstOffset the start offset of 'dstBuff'
     * @param dstSize   the size of 'dstBuff'
     * @param srcBuff   the source buffer - must be direct
     * @param srcOffset the start offset of 'srcBuff'
     * @param srcSize   the size of 'srcBuff'
     * @return the number of bytes decompressed into destination buffer (originalSize)
     */
    public int decompressDirectByteBuffer(@NotNull ByteBuffer dstBuff, int dstOffset, int dstSize, @NotNull ByteBuffer srcBuff, int srcOffset, int srcSize) {
        ensureOpen();
        if (!srcBuff.isDirect()) {
            throw new IllegalArgumentException("srcBuff must be a direct buffer");
        }
        if (!dstBuff.isDirect()) {
            throw new IllegalArgumentException("dstBuff must be a direct buffer");
        }
        Objects.checkFromIndexSize(srcOffset, srcSize, srcBuff.limit());
        Objects.checkFromIndexSize(dstOffset, dstSize, dstBuff.limit());

        acquireSharedLock();

        try {
            long size = decompressDirectByteBuffer0(nativePtr, dstBuff, dstOffset, dstSize, srcBuff, srcOffset, srcSize);
            if (Zstd.isError(size)) {
                throw new ZstdException(size);
            }
            if (size > Integer.MAX_VALUE) {
                throw new ZstdException(Zstd.errGeneric(), "Output size is greater than MAX_INT");
            }
            return (int) size;
        } finally {
            releaseSharedLock();
        }
    }

    private static long decompressDirectByteBuffer0(long nativePtr, @NotNull ByteBuffer dst, int dstOffset, int dstSize, @NotNull ByteBuffer src, int srcOffset, int srcSize) {
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        byte[] srcCopy = ByteBuffers.read(src, srcOffset, srcSize);
        long size = ZstdMite.client().zm_decompressDCtx(nativePtr, dstCopy, 0, dstSize, srcCopy, 0, srcSize);
        if (!Zstd.isError(size) && size > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, (int) size);
        }
        return size;
    }

    /**
     * Decompresses byte array 'srcBuff' into byte array 'dstBuff' using this ZstdDecompressCtx.
     * <p>
     * Destination buffer should be sized to be larger of equal to the originalSize.
     *
     * @param dstBuff   the destination buffer
     * @param dstOffset the start offset of 'dstBuff'
     * @param dstSize   the size of 'dstBuff'
     * @param srcBuff   the source buffer
     * @param srcOffset the start offset of 'srcBuff'
     * @param srcSize   the size of 'srcBuff'
     * @return the number of bytes decompressed into destination buffer (originalSize)
     */
    public int decompressByteArray(byte @NotNull [] dstBuff, int dstOffset, int dstSize, byte @NotNull [] srcBuff, int srcOffset, int srcSize) {
        Objects.checkFromIndexSize(srcOffset, srcSize, srcBuff.length);
        Objects.checkFromIndexSize(dstOffset, dstSize, dstBuff.length);

        ensureOpen();
        acquireSharedLock();

        try {
            long size = decompressByteArray0(nativePtr, dstBuff, dstOffset, dstSize, srcBuff, srcOffset, srcSize);
            if (Zstd.isError(size)) {
                throw new ZstdException(size);
            }
            if (size > Integer.MAX_VALUE) {
                throw new ZstdException(Zstd.errGeneric(), "Output size is greater than MAX_INT");
            }
            return (int) size;
        } finally {
            releaseSharedLock();
        }
    }

    private static long decompressByteArray0(long nativePtr, byte @NotNull [] dst, int dstOffset, int dstSize, byte @NotNull [] src, int srcOffset, int srcSize) {
        return ZstdMite.client().zm_decompressDCtx(nativePtr, dst, dstOffset, dstSize, src, srcOffset, srcSize);
    }

    public int decompressByteArrayToDirectByteBuffer(@NotNull ByteBuffer dstBuff, int dstOffset, int dstSize, byte @NotNull [] srcBuff, int srcOffset, int srcSize) {
        if (!dstBuff.isDirect()) {
            throw new IllegalArgumentException("dstBuff must be a direct buffer");
        }

        Objects.checkFromIndexSize(srcOffset, srcSize, srcBuff.length);
        Objects.checkFromIndexSize(dstOffset, dstSize, dstBuff.limit());

        ensureOpen();
        acquireSharedLock();

        try {
            long size = decompressByteArrayToDirectByteBuffer0(nativePtr, dstBuff, dstOffset, dstSize, srcBuff, srcOffset, srcSize);
            if (Zstd.isError(size)) {
                throw new ZstdException(size);
            }
            if (size > Integer.MAX_VALUE) {
                throw new ZstdException(Zstd.errGeneric(), "Output size is greater than MAX_INT");
            }
            return (int) size;
        } finally {
            releaseSharedLock();
        }
    }

    private static long decompressByteArrayToDirectByteBuffer0(long nativePtr, @NotNull ByteBuffer dst, int dstOffset, int dstSize, byte @NotNull [] src, int srcOffset, int srcSize) {
        byte[] dstCopy = ByteBuffers.read(dst, dstOffset, dstSize);
        long size = ZstdMite.client().zm_decompressDCtx(nativePtr, dstCopy, 0, dstSize, src, srcOffset, srcSize);
        if (!Zstd.isError(size) && size > 0) {
            ByteBuffers.write(dst, dstOffset, dstCopy, 0, (int) size);
        }
        return size;
    }

    public int decompressDirectByteBufferToByteArray(byte @NotNull [] dstBuff, int dstOffset, int dstSize, @NotNull ByteBuffer srcBuff, int srcOffset, int srcSize) {
        if (!srcBuff.isDirect()) {
            throw new IllegalArgumentException("srcBuff must be a direct buffer");
        }

        Objects.checkFromIndexSize(srcOffset, srcSize, srcBuff.limit());
        Objects.checkFromIndexSize(dstOffset, dstSize, dstBuff.length);

        ensureOpen();
        acquireSharedLock();

        try {
            long size = decompressDirectByteBufferToByteArray0(nativePtr, dstBuff, dstOffset, dstSize, srcBuff, srcOffset, srcSize);
            if (Zstd.isError(size)) {
                throw new ZstdException(size);
            }
            if (size > Integer.MAX_VALUE) {
                throw new ZstdException(Zstd.errGeneric(), "Output size is greater than MAX_INT");
            }
            return (int) size;
        } finally {
            releaseSharedLock();
        }
    }

    private static long decompressDirectByteBufferToByteArray0(long nativePtr, byte @NotNull [] dst, int dstOffset, int dstSize, @NotNull ByteBuffer src, int srcOffset, int srcSize) {
        byte[] srcCopy = ByteBuffers.read(src, srcOffset, srcSize);
        return ZstdMite.client().zm_decompressDCtx(nativePtr, dst, dstOffset, dstSize, srcCopy, 0, srcSize);
    }

    /* Covenience methods */

    /**
     * Decompresses buffer 'srcBuff' into buffer 'dstBuff' using this ZstdDecompressCtx.
     * <p>
     * Destination buffer should be sized to be larger of equal to the originalSize.
     *
     * @param dstBuf the destination buffer - must be direct. It is assumed that the `position()` of this buffer marks the offset
     *               at which the decompressed data are to be written, and that the `limit()` of this buffer is the maximum
     *               decompressed data size to allow.
     *               <p>
     *               When this method returns successfully, its `position()` will be set to its current `position()` plus the
     *               decompressed size of the data.
     *               </p>
     * @param srcBuf the source buffer - must be direct. It is assumed that the `position()` of this buffer marks the beginning of the
     *               compressed data to be decompressed, and that the `limit()` of this buffer marks its end.
     *               <p>
     *               When this method returns successfully, its `position()` will be set to the initial `limit()`.
     *               </p>
     * @return the size of the decompressed data.
     */
    public int decompress(@NotNull ByteBuffer dstBuf, @NotNull ByteBuffer srcBuf) throws ZstdException {
        int size = decompressDirectByteBuffer(dstBuf,  // decompress into dstBuf
                dstBuf.position(),                      // write decompressed data at offset position()
                dstBuf.limit() - dstBuf.position(),     // write no more than limit() - position()
                srcBuf,                                 // read compressed data from srcBuf
                srcBuf.position(),                      // read starting at offset position()
                srcBuf.limit() - srcBuf.position());    // read no more than limit() - position()
        srcBuf.position(srcBuf.limit());
        dstBuf.position(dstBuf.position() + size);
        return size;
    }

    public int decompress(@NotNull ByteBuffer dstBuf, byte @NotNull [] src) throws ZstdException {
        int size = decompressByteArrayToDirectByteBuffer(dstBuf,  // decompress into dstBuf
                dstBuf.position(),                      // write decompressed data at offset position()
                dstBuf.limit() - dstBuf.position(),     // write no more than limit() - position()
                src,                                 // read compressed data from src
                0,
                src.length);
        dstBuf.position(dstBuf.position() + size);
        return size;
    }

    public int decompress(byte @NotNull [] dst, @NotNull ByteBuffer srcBuf) throws ZstdException {
        int size = decompressDirectByteBufferToByteArray(dst,  // decompress into dst
                0,
                dst.length,
                srcBuf,                                 // read compressed data from srcBuf
                srcBuf.position(),                      // read starting at offset position()
                srcBuf.limit() - srcBuf.position());    // read no more than limit() - position()
        srcBuf.position(srcBuf.limit());
        return size;
    }

    @NotNull
    public ByteBuffer decompress(@NotNull ByteBuffer srcBuf, int originalSize) throws ZstdException {
        ByteBuffer dstBuf = ByteBuffer.allocateDirect(originalSize);
        int size = decompressDirectByteBuffer(dstBuf, 0, originalSize, srcBuf, srcBuf.position(), srcBuf.limit() - srcBuf.position());
        srcBuf.position(srcBuf.limit());
        // Since we allocated the buffer ourselves, we know it cannot be used to hold any further decompressed data,
        // so leave the position at zero where the caller surely wants it, ready to read
        return dstBuf;
    }

    public int decompress(byte @NotNull [] dst, byte @NotNull [] src) {
        return decompressByteArray(dst, 0, dst.length, src, 0, src.length);
    }

    /**
     * Decompress data
     *
     * @param src          the source buffer
     * @param originalSize the maximum size of the uncompressed data.
     *                     If originalSize is greater than the actual uncompressed size, additional memory copy going to happen.
     *                     If originalSize is smaller than the uncompressed size, {@link ZstdException} will be thrown.
     * @return byte array with the decompressed data
     */
    public byte @NotNull [] decompress(byte @NotNull [] src, int originalSize) throws ZstdException {
        return decompress(src, 0, src.length, originalSize);
    }

    /**
     * Decompress data
     *
     * @param src          the source buffer
     * @param srcOffset    the start offset of 'src'
     * @param srcSize      the size of 'src'
     * @param originalSize the maximum size of the uncompressed data.
     *                     If originalSize is greater than the actual uncompressed size, additional memory copy going to happen.
     *                     If originalSize is smaller than the uncompressed size, {@link ZstdException} will be thrown.
     * @return byte array with the decompressed data
     */
    public byte @NotNull [] decompress(byte @NotNull [] src, int srcOffset, int srcSize, int originalSize) throws ZstdException {
        if (originalSize < 0) {
            throw new ZstdException(Zstd.errGeneric(), "Original size should not be negative");
        }
        byte[] dst = new byte[originalSize];
        int size = decompressByteArray(dst, 0, dst.length, src, srcOffset, srcSize);
        if (size != originalSize) {
            return Arrays.copyOfRange(dst, 0, size);
        } else {
            return dst;
        }
    }
}

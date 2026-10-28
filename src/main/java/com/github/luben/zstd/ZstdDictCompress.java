package com.github.luben.zstd;

import com.github.luben.zstd.mite.ByteBuffers;
import com.github.luben.zstd.mite.ZstdMite;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

public class ZstdDictCompress extends SharedDictBase {

    static {
        ZstdMite.ensureLoaded();
    }

    private long nativePtr = 0;

    private ByteBuffer sharedDict = null;

    private int level = Zstd.defaultCompressionLevel();

    private void init(byte @NotNull [] dict, int dict_offset, int dict_size, int level) {
        nativePtr = ZstdMite.client().zm_createCDict(dict, dict_offset, dict_size, level);
    }

    /**
     * The buffer is copied into a native CDict even when {@code byReference} is
     * requested: semi-mite's array marshalling cannot hand ownership of a
     * direct-buffer address to native code. The observable contract (the
     * dictionary content is frozen when this constructor returns) is unchanged
     * and safer than borrowing caller memory.
     */
    private void initDirect(@NotNull ByteBuffer dict, int dict_offset, int dict_size, int level, int byReference) {
        byte[] copy = ByteBuffers.read(dict, dict_offset, dict_size);
        nativePtr = ZstdMite.client().zm_createCDict(copy, 0, dict_size, level);
    }

    private void free() {
        ZstdMite.client().zm_freeCDict(nativePtr);
    }

    /**
     * Get the byte buffer that backs this dict, if any, or null if not backed by a byte buffer.
     */
    public @Nullable ByteBuffer getByReferenceBuffer() {
	return sharedDict;
    }

    /**
     * Convenience constructor to create a new dictionary for use with fast compress
     *
     * @param dict  buffer containing dictionary to load/parse with exact length
     * @param level compression level
     */
    public ZstdDictCompress(byte @NotNull [] dict, int level) {
        this(dict, 0, dict.length, level);
    }

    /**
     * Create a new dictionary for use with fast compress
     *
     * @param dict   buffer containing dictionary
     * @param offset the offset into the buffer to read from
     * @param length number of bytes to use from the buffer
     * @param level  compression level
     */
    public ZstdDictCompress(byte @NotNull [] dict, int offset, int length, int level) {
        this.level = level;
        if (dict.length - offset < 0) {
            throw new IllegalArgumentException("Dictionary buffer is too short");
        }
        if (offset < 0 || length < 0 || length > dict.length - offset) {
            throw new IllegalArgumentException("Invalid offset/length for dictionary buffer");
        }
        init(dict, offset, length, level);

        if (0 == nativePtr) {
            throw new IllegalStateException("ZSTD_createCDict failed");
        }
        // Ensures that even if ZstdDictCompress is created and published through a race, no thread could observe
        // nativePtr == 0.
        storeFence();
    }

    /**
     * Create a new dictionary for use with fast compress. The provided bytebuffer is available for reuse when the method returns.
     *
     * @param dict   Direct ByteBuffer containing dictionary using position and limit to define range in buffer.
     * @param level  compression level
     */
    public ZstdDictCompress(@NotNull ByteBuffer dict, int level) {
	this(dict, level, false);
    }

    /**
     * Create a new dictionary for use with fast compress.
     * If byReference is true, then the native code does not copy the data but keeps a reference to the byte buffer, which must then not be modified before this context has been closed.
     *
     * @param dict   Direct ByteBuffer containing dictionary using position and limit to define range in buffer.
     * @param level  compression level
     * @param byReference tell the native part to use the byte buffer directly and not copy the data when true.
     */
    public ZstdDictCompress(@NotNull ByteBuffer dict, int level, boolean byReference) {
	this.level = level;
	int length = dict.limit() - dict.position();
        if (!dict.isDirect()) {
            throw new IllegalArgumentException("dict must be a direct buffer");
        }
        if (length < 0) {
            throw new IllegalArgumentException("dict cannot be empty.");
        }
	initDirect(dict, dict.position(), length, level, byReference ? 1 : 0);

        if (nativePtr == 0L) {
           throw new IllegalStateException("ZSTD_createCDict failed");
        }
	if (byReference) {
	    sharedDict = dict; // ensures the dict is not garbage collected while this object remains.
	}
        // Ensures that even if ZstdDictCompress is created and published through a race, no thread could observe
        // nativePtr == 0.
        storeFence();
    }


    int level() {
        return level;
    }

    /**
     * The raw {@code ZSTD_CDict*} handle. Package-private: it is passed to the
     * semi-mite bridge for {@code ZSTD_CCtx_refCDict} calls.
     */
    long nativePtr() {
        return nativePtr;
    }

    @Override
    void  doClose() {
        if (nativePtr != 0) {
            free();
            nativePtr = 0;
            sharedDict = null;
        }
    }
}

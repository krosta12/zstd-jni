package com.github.luben.zstd.mite;

import io.github.krosta12.semimite.client.MiteClient;

/**
 * The single native boundary of zstd-jni.
 * <p>
 * Every method declared here is bound by semi-mite to a function exported by the
 * {@code zstd-mite} shared library (see {@code src/main/native/zstd_mite.c}).
 * There is no JNI glue and no handwritten FFM descriptor: semi-mite derives the
 * {@code FunctionDescriptor} from the Java signatures below and creates the
 * {@code MethodHandle} when the library is loaded.
 * <p>
 * Conventions:
 * <ul>
 *     <li>opaque native pointers are carried as {@code long} values (the C side
 *     declares them as {@code long long} so the ABI is identical on every
 *     platform, including Windows where C {@code long} is 32-bit);</li>
 *     <li>{@code byte[]} arguments become {@code char*} pointers to a temporary
 *     native copy, and are copied back to the Java array when the call returns,
 *     which is what makes out-parameters such as destination buffers work;</li>
 *     <li>the C functions use Java-friendly flat signatures instead of the
 *     struct-based zstd API, so the proxy layer never has to know about
 *     {@code ZSTD_inBuffer}/{@code ZSTD_outBuffer}.</li>
 * </ul>
 * All methods are stateless from the proxy's point of view: zstd state lives in
 * the native contexts identified by the {@code long} handles.
 */
@MiteClient
public interface ZstdNativeClient {

    // ------------------------------------------------------------------
    // One-shot compression / decompression
    // ------------------------------------------------------------------

    /** One-shot compression from raw addresses ({@code Zstd.compressUnsafe}). */
    long zm_compressUnsafe(long dst, long dstSize, long src, long srcSize, int level, boolean checksum);

    /** One-shot decompression from raw addresses ({@code Zstd.decompressUnsafe}). */
    long zm_decompressUnsafe(long dst, long dstSize, long src, long srcSize);

    /** {@code ZSTD_CCtx_reset(session_only)} + {@code ZSTD_compress2}. */
    long zm_compress2(long cctx, byte[] dst, int dstOffset, int dstSize,
                      byte[] src, int srcOffset, int srcSize);

    /** {@code ZSTD_DCtx_reset(session_only)} + {@code ZSTD_decompressDCtx}. */
    long zm_decompressDCtx(long dctx, byte[] dst, int dstOffset, int dstSize,
                           byte[] src, int srcOffset, int srcSize);

    // ------------------------------------------------------------------
    // Frame introspection and error handling
    // ------------------------------------------------------------------

    long zm_findFrameCompressedSize(byte[] src, int srcPosition, int srcSize);

    long zm_getFrameContentSize(byte[] src, int srcPosition, int srcSize, boolean magicless);

    long zm_decompressedSize(byte[] src, int srcPosition, int srcSize, boolean magicless);

    long zm_compressBound(long srcSize);

    boolean zm_isError(long code);

    /** Returns a static, null-terminated error name owned by libzstd. */
    String zm_getErrorName(long code);

    long zm_getErrorCode(long code);

    /**
     * Fills {@code out} with the stable {@code ZSTD_error_*} constants, in the
     * order used by {@link com.github.luben.zstd.Zstd}'s {@code err*} methods.
     */
    void zm_errorConstants(int[] out);

    // ------------------------------------------------------------------
    // Dictionary ids
    // ------------------------------------------------------------------

    long zm_getDictIdFromFrame(byte[] src, int srcSize);

    long zm_getDictIdFromDict(byte[] dict, int dictSize);

    // ------------------------------------------------------------------
    // Compile-time constants of the bundled libzstd
    // ------------------------------------------------------------------

    int zm_magicNumber();

    int zm_windowLogMin();

    int zm_windowLogMax();

    int zm_chainLogMin();

    int zm_chainLogMax();

    int zm_hashLogMin();

    int zm_hashLogMax();

    int zm_searchLogMin();

    int zm_searchLogMax();

    int zm_searchLengthMin();

    int zm_searchLengthMax();

    int zm_blockSizeMax();

    int zm_defaultCompressionLevel();

    int zm_minCompressionLevel();

    int zm_maxCompressionLevel();

    // ------------------------------------------------------------------
    // Context lifecycle
    // ------------------------------------------------------------------

    long zm_createCCtx();

    void zm_freeCCtx(long cctx);

    long zm_createDCtx();

    void zm_freeDCtx(long dctx);

    // ------------------------------------------------------------------
    // ZSTD_CCtx parameters and state
    // ------------------------------------------------------------------

    void zm_setLevel(long cctx, int level);

    void zm_setChecksum(long cctx, boolean checksum);

    void zm_setContentSize(long cctx, boolean contentSize);

    void zm_setDictID(long cctx, boolean dictId);

    int zm_setCompressionChecksums(long cctx, boolean useChecksums);

    int zm_setCompressionMagicless(long cctx, boolean useMagicless);

    int zm_setCompressionLevel(long cctx, int level);

    int zm_setCompressionLong(long cctx, int windowLog);

    int zm_setCompressionWorkers(long cctx, int workers);

    int zm_setCompressionOverlapLog(long cctx, int overlapLog);

    int zm_setCompressionJobSize(long cctx, int jobSize);

    int zm_setCompressionTargetLength(long cctx, int targetLength);

    int zm_setCompressionMinMatch(long cctx, int minMatch);

    int zm_setCompressionSearchLog(long cctx, int searchLog);

    int zm_setCompressionChainLog(long cctx, int chainLog);

    int zm_setCompressionHashLog(long cctx, int hashLog);

    int zm_setCompressionWindowLog(long cctx, int windowLog);

    int zm_setCompressionStrategy(long cctx, int strategy);

    int zm_setEnableLongDistanceMatching(long cctx, int enableLdm);

    int zm_setValidateSequences(long cctx, int validateSequences);

    int zm_setSequenceProducerFallback(long cctx, boolean fallback);

    int zm_setSearchForExternalRepcodes(long cctx, int searchRepcodes);

    void zm_registerSequenceProducer(long cctx, long state, long function);

    /** Loads a dictionary from a byte array; a {@code null} array removes it. */
    long zm_loadCDict(long cctx, byte[] dict, int dictSize);

    /** References (or, for {@code cdict == 0}, removes) a prepared CDict. */
    long zm_refCDict(long cctx, long cdict);

    long zm_resetCCtx(long cctx);

    long zm_setPledgedSrcSize(long cctx, long srcSize);

    /** Writes the current frame progression into the {@code @MiteStruct} out-parameter. */
    void zm_getFrameProgression(long cctx, FrameProgressionRaw out);

    /**
     * Streaming compression. Returns the same packed value the JNI implementation
     * used to return (see {@code ZstdCompressCtx#updateStreamPositions}).
     */
    long zm_compressStream(long cctx, byte[] dst, int dstOffset, int dstSize,
                           byte[] src, int srcOffset, int srcSize, int endOp);

    // ------------------------------------------------------------------
    // ZSTD_DCtx parameters and state
    // ------------------------------------------------------------------

    int zm_setDecompressionLongMax(long dctx, int windowLogMax);

    int zm_setDecompressionMagicless(long dctx, boolean useMagicless);

    int zm_setRefMultipleDDicts(long dctx, boolean useMultiple);

    /** Loads a dictionary from a byte array; a {@code null} array removes it. */
    long zm_loadDDict(long dctx, byte[] dict, int dictSize);

    /** References (or, for {@code ddict == 0}, removes) a prepared DDict. */
    long zm_refDDict(long dctx, long ddict);

    long zm_resetDCtx(long dctx);

    /**
     * Streaming decompression. Returns the same packed value the JNI
     * implementation used to return.
     */
    long zm_decompressStream(long dctx, byte[] dst, int dstOffset, int dstSize,
                             byte[] src, int srcOffset, int srcSize);

    // ------------------------------------------------------------------
    // Prepared dictionaries
    // ------------------------------------------------------------------

    long zm_createCDict(byte[] dict, int offset, int size, int level);

    void zm_freeCDict(long cdict);

    long zm_createDDict(byte[] dict, int offset, int size);

    void zm_freeDDict(long ddict);

    // ------------------------------------------------------------------
    // Dictionary training
    // ------------------------------------------------------------------

    /**
     * @param samples      all samples concatenated into one array
     * @param samplesLength number of valid bytes in {@code samples}
     * @param sampleSizes  the size of each sample in {@code samples}
     * @param sampleCount  number of entries in {@code sampleSizes}
     * @param dict         destination dictionary buffer
     * @param dictSize     capacity of {@code dict}
     * @param legacy       use {@code ZDICT_trainFromBuffer_legacy}
     * @param level        compression level used by the legacy trainer
     */
    long zm_trainFromBuffer(byte[] samples, int samplesLength, int[] sampleSizes, int sampleCount,
                            byte[] dict, int dictSize, boolean legacy, int level);

    // ------------------------------------------------------------------
    // Stream objects shared by the stream wrappers
    // ------------------------------------------------------------------

    long zm_DStreamInSize();

    long zm_DStreamOutSize();

    long zm_CStreamOutSize();

    long zm_createDStream();

    long zm_freeDStream(long dstream);

    long zm_initDStream(long dstream);

    long zm_resetDStream(long dstream);

    long zm_createCStream();

    long zm_freeCStream(long cstream);

    long zm_initCStream(long cstream, int level);

    long zm_initCStreamWithDict(long cstream, byte[] dict, int dictSize, int level);

    long zm_initCStreamWithFastDict(long cstream, long cdict);

    long zm_resetCStream(long cstream);

    /**
     * Compresses a chunk into {@code dst} starting at {@code dstOffset}. The
     * advanced positions are written back through {@code positions}.
     */
    long zm_cStreamCompress(long cstream, byte[] dst, int dstOffset, int dstSize,
                            byte[] src, int srcOffset, int srcSize, MiteIoPos positions);

    long zm_cStreamFlush(long cstream, byte[] dst, int dstOffset, int dstSize, MiteIoPos positions);

    long zm_cStreamEnd(long cstream, byte[] dst, int dstOffset, int dstSize, MiteIoPos positions);

    /**
     * Decompresses a chunk from {@code src} starting at {@code srcOffset}. The
     * advanced positions are written back through {@code positions}.
     */
    long zm_dStreamDecompress(long dstream, byte[] dst, int dstOffset, int dstSize,
                              byte[] src, int srcOffset, int srcSize, MiteIoPos positions);

    /** Streaming compression used by {@code ZstdOutputStreamNoFinalizer}. */
    long zm_outputStreamCompress(long cstream, byte[] dst, int dstSize,
                                 byte[] src, int srcSize, MiteIoPos positions);

    long zm_outputStreamFlush(long cstream, byte[] dst, int dstSize, MiteIoPos positions);

    long zm_outputStreamEnd(long cstream, byte[] dst, int dstSize, MiteIoPos positions);
}

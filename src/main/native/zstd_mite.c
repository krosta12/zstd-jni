/*
 * zstd-mite: the flat C ABI used by the semi-mite client.
 *
 * This file replaces the whole JNI layer of zstd-jni (jni_zstd.c,
 * jni_fast_zstd.c, jni_inputstream_zstd.c, jni_outputstream_zstd.c,
 * jni_bufferdecompress_zstd.c, jni_directbuffercompress_zstd.c,
 * jni_directbufferdecompress_zstd.c, jni_zdict.c and the Panama shim
 * ffm_zstd.c).
 *
 * There is no JNIEnv, no jclass, no array marshalling and no exception
 * throwing here: semi-mite owns the Java <-> native transition, and every
 * function below has a plain C signature built only from primitives and
 * pointers.  Opaque zstd objects are passed as long long handles so the ABI is
 * identical on Windows, Linux and macOS.
 */

#ifndef ZSTD_STATIC_LINKING_ONLY
#define ZSTD_STATIC_LINKING_ONLY
#endif
#ifndef ZDICT_STATIC_LINKING_ONLY
#define ZDICT_STATIC_LINKING_ONLY
#endif

#include <zstd.h>
#include <zstd_errors.h>
#include <zdict.h>

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#  define ZM_EXPORT __declspec(dllexport)
#else
#  define ZM_EXPORT __attribute__((visibility("default")))
#endif

/* ------------------------------------------------------------------------- */
/* Handle helpers                                                            */
/* ------------------------------------------------------------------------- */

static ZSTD_CCtx *zm_cctx(long long handle) {
    return (ZSTD_CCtx *)(intptr_t)handle;
}

static ZSTD_DCtx *zm_dctx(long long handle) {
    return (ZSTD_DCtx *)(intptr_t)handle;
}

static ZSTD_CStream *zm_cstream(long long handle) {
    return (ZSTD_CStream *)(intptr_t)handle;
}

static ZSTD_DStream *zm_dstream(long long handle) {
    return (ZSTD_DStream *)(intptr_t)handle;
}

static ZSTD_CDict *zm_cdict(long long handle) {
    return (ZSTD_CDict *)(intptr_t)handle;
}

static ZSTD_DDict *zm_ddict(long long handle) {
    return (ZSTD_DDict *)(intptr_t)handle;
}

static long long zm_size(size_t value) {
    /*
     * Go through intptr_t so that zstd's negative size_t error codes keep
     * their sign when the Java side widens them to long. On 64-bit targets
     * this is a bit-preserving cast; on 32-bit targets it sign-extends the
     * error value instead of turning it into a large positive number.
     */
    return (long long)(intptr_t)value;
}

static long long zm_error(ZSTD_ErrorCode code) {
    return -(long long)code;
}

/* ------------------------------------------------------------------------- */
/* One-shot compression / decompression                                      */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_compressUnsafe(long long dst, long long dstSize,
                                      long long src, long long srcSize,
                                      int level, bool checksum) {
    ZSTD_CCtx *cctx = ZSTD_createCCtx();
    if (cctx == NULL) {
        return zm_error(ZSTD_error_memory_allocation);
    }
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, level);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag, checksum ? 1 : 0);
    size_t result = ZSTD_compress2(cctx,
                                   (void *)(intptr_t)dst, (size_t)dstSize,
                                   (const void *)(intptr_t)src, (size_t)srcSize);
    ZSTD_freeCCtx(cctx);
    return zm_size(result);
}

ZM_EXPORT long long zm_decompressUnsafe(long long dst, long long dstSize,
                                        long long src, long long srcSize) {
    return zm_size(ZSTD_decompress((void *)(intptr_t)dst, (size_t)dstSize,
                                   (const void *)(intptr_t)src, (size_t)srcSize));
}

ZM_EXPORT long long zm_compress2(long long cctxHandle,
                                 char *dst, int dstOffset, int dstSize,
                                 const char *src, int srcOffset, int srcSize) {
    if (dstOffset < 0 || dstSize < 0) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (srcOffset < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);

    ZSTD_CCtx *cctx = zm_cctx(cctxHandle);
    ZSTD_CCtx_reset(cctx, ZSTD_reset_session_only);
    return zm_size(ZSTD_compress2(cctx,
                                  dst + dstOffset, (size_t)dstSize,
                                  src + srcOffset, (size_t)srcSize));
}

ZM_EXPORT long long zm_decompressDCtx(long long dctxHandle,
                                      char *dst, int dstOffset, int dstSize,
                                      const char *src, int srcOffset, int srcSize) {
    if (dstOffset < 0 || dstSize < 0) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (srcOffset < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);

    ZSTD_DCtx *dctx = zm_dctx(dctxHandle);
    ZSTD_DCtx_reset(dctx, ZSTD_reset_session_only);
    return zm_size(ZSTD_decompressDCtx(dctx,
                                       dst + dstOffset, (size_t)dstSize,
                                       src + srcOffset, (size_t)srcSize));
}

/* ------------------------------------------------------------------------- */
/* Frame introspection and errors                                            */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_findFrameCompressedSize(const char *src, int srcPosition, int srcSize) {
    if (srcPosition < 0) return zm_error(ZSTD_error_srcSize_wrong);
    if (srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);
    return zm_size(ZSTD_findFrameCompressedSize(src + srcPosition, (size_t)srcSize));
}

/*
 * Shared with the magicless-aware paths: a magicless frame cannot be parsed by
 * ZSTD_getFrameContentSize, so it is decoded with ZSTD_getFrameHeader_advanced.
 */
static size_t zm_frame_content_size(const void *buffer, size_t size, bool magicless) {
    if (magicless) {
        ZSTD_frameHeader header;
        if (ZSTD_getFrameHeader_advanced(&header, buffer, size, ZSTD_f_zstd1_magicless) != 0) {
            return 0;
        }
        /* skippable frames always carry a magic number, so they cannot occur here */
        return header.frameContentSize;
    }
    return ZSTD_getFrameContentSize(buffer, size);
}

ZM_EXPORT long long zm_getFrameContentSize(const char *src, int srcPosition, int srcSize,
                                           bool magicless) {
    if (srcPosition < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);
    return zm_size(zm_frame_content_size(src + srcPosition, (size_t)srcSize, magicless));
}

ZM_EXPORT long long zm_decompressedSize(const char *src, int srcPosition, int srcSize,
                                        bool magicless) {
    if (srcPosition < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);
    size_t size = zm_frame_content_size(src + srcPosition, (size_t)srcSize, magicless);
    /* historic behaviour: unknown/negative content sizes are reported as 0 */
    return size > 0 ? zm_size(size) : 0;
}

ZM_EXPORT long long zm_compressBound(long long srcSize) {
    return zm_size(ZSTD_compressBound((size_t)srcSize));
}

ZM_EXPORT bool zm_isError(long long code) {
    return ZSTD_isError((size_t)code) != 0;
}

ZM_EXPORT const char *zm_getErrorName(long long code) {
    return ZSTD_getErrorName((size_t)code);
}

ZM_EXPORT long long zm_getErrorCode(long long code) {
    return (long long)ZSTD_getErrorCode((size_t)code);
}

/*
 * The stable zstd_errors.h constants. Keeping them in C means a new bundled
 * libzstd cannot silently change the values the Java API exposes.
 */
ZM_EXPORT void zm_errorConstants(int *out) {
    if (out == NULL) return;
    out[0]  = ZSTD_error_no_error;
    out[1]  = ZSTD_error_GENERIC;
    out[2]  = ZSTD_error_prefix_unknown;
    out[3]  = ZSTD_error_version_unsupported;
    out[4]  = ZSTD_error_frameParameter_unsupported;
    out[5]  = ZSTD_error_frameParameter_windowTooLarge;
    out[6]  = ZSTD_error_corruption_detected;
    out[7]  = ZSTD_error_checksum_wrong;
    out[8]  = ZSTD_error_dictionary_corrupted;
    out[9]  = ZSTD_error_dictionary_wrong;
    out[10] = ZSTD_error_dictionaryCreation_failed;
    out[11] = ZSTD_error_parameter_unsupported;
    out[12] = ZSTD_error_parameter_outOfBound;
    out[13] = ZSTD_error_tableLog_tooLarge;
    out[14] = ZSTD_error_maxSymbolValue_tooLarge;
    out[15] = ZSTD_error_maxSymbolValue_tooSmall;
    out[16] = ZSTD_error_stage_wrong;
    out[17] = ZSTD_error_init_missing;
    out[18] = ZSTD_error_memory_allocation;
    out[19] = ZSTD_error_workSpace_tooSmall;
    out[20] = ZSTD_error_dstSize_tooSmall;
    out[21] = ZSTD_error_srcSize_wrong;
    out[22] = ZSTD_error_dstBuffer_null;
}

/* ------------------------------------------------------------------------- */
/* Dictionary ids                                                            */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_getDictIdFromFrame(const char *src, int srcSize) {
    if (src == NULL || srcSize < 0) return 0;
    return (long long)ZSTD_getDictID_fromFrame(src, (size_t)srcSize);
}

ZM_EXPORT long long zm_getDictIdFromDict(const char *dict, int dictSize) {
    if (dict == NULL || dictSize < 0) return 0;
    return (long long)ZSTD_getDictID_fromDict(dict, (size_t)dictSize);
}

/* ------------------------------------------------------------------------- */
/* libzstd constants                                                         */
/* ------------------------------------------------------------------------- */

ZM_EXPORT int zm_magicNumber(void)              { return (int)ZSTD_MAGICNUMBER; }
ZM_EXPORT int zm_windowLogMin(void)             { return ZSTD_WINDOWLOG_MIN; }
ZM_EXPORT int zm_windowLogMax(void)             { return ZSTD_WINDOWLOG_MAX; }
ZM_EXPORT int zm_chainLogMin(void)              { return ZSTD_CHAINLOG_MIN; }
ZM_EXPORT int zm_chainLogMax(void)              { return ZSTD_CHAINLOG_MAX; }
ZM_EXPORT int zm_hashLogMin(void)               { return ZSTD_HASHLOG_MIN; }
ZM_EXPORT int zm_hashLogMax(void)               { return ZSTD_HASHLOG_MAX; }
ZM_EXPORT int zm_searchLogMin(void)             { return ZSTD_SEARCHLOG_MIN; }
ZM_EXPORT int zm_searchLogMax(void)             { return ZSTD_SEARCHLOG_MAX; }
ZM_EXPORT int zm_searchLengthMin(void)          { return ZSTD_MINMATCH_MIN; }
ZM_EXPORT int zm_searchLengthMax(void)          { return ZSTD_MINMATCH_MAX; }
ZM_EXPORT int zm_blockSizeMax(void)             { return ZSTD_BLOCKSIZE_MAX; }
ZM_EXPORT int zm_defaultCompressionLevel(void)  { return ZSTD_CLEVEL_DEFAULT; }
ZM_EXPORT int zm_minCompressionLevel(void)      { return ZSTD_minCLevel(); }
ZM_EXPORT int zm_maxCompressionLevel(void)      { return ZSTD_maxCLevel(); }

/* ------------------------------------------------------------------------- */
/* Context lifecycle                                                         */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_createCCtx(void) {
    return (long long)(intptr_t)ZSTD_createCCtx();
}

ZM_EXPORT void zm_freeCCtx(long long cctx) {
    ZSTD_freeCCtx(zm_cctx(cctx));
}

ZM_EXPORT long long zm_createDCtx(void) {
    return (long long)(intptr_t)ZSTD_createDCtx();
}

ZM_EXPORT void zm_freeDCtx(long long dctx) {
    ZSTD_freeDCtx(zm_dctx(dctx));
}

/* ------------------------------------------------------------------------- */
/* Compression parameters                                                    */
/* ------------------------------------------------------------------------- */

ZM_EXPORT void zm_setLevel(long long cctx, int level) {
    ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_compressionLevel, level);
}

ZM_EXPORT void zm_setChecksum(long long cctx, bool checksum) {
    ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_checksumFlag, checksum ? 1 : 0);
}

ZM_EXPORT void zm_setContentSize(long long cctx, bool contentSize) {
    ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_contentSizeFlag, contentSize ? 1 : 0);
}

ZM_EXPORT void zm_setDictID(long long cctx, bool dictId) {
    ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_dictIDFlag, dictId ? 1 : 0);
}

ZM_EXPORT int zm_setCompressionChecksums(long long cctx, bool useChecksums) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_checksumFlag,
                                       useChecksums ? 1 : 0);
}

ZM_EXPORT int zm_setCompressionMagicless(long long cctx, bool useMagicless) {
    ZSTD_format_e format = useMagicless ? ZSTD_f_zstd1_magicless : ZSTD_f_zstd1;
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_format, format);
}

ZM_EXPORT int zm_setCompressionLevel(long long cctx, int level) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_compressionLevel, level);
}

/*
 * windowLog outside the legal range disables long-distance matching and
 * resets the window size, matching the behaviour of the previous JNI layer.
 */
ZM_EXPORT int zm_setCompressionLong(long long cctxHandle, int windowLog) {
    ZSTD_CCtx *cctx = zm_cctx(cctxHandle);
    if (windowLog < ZSTD_WINDOWLOG_MIN || windowLog > ZSTD_WINDOWLOG_LIMIT_DEFAULT) {
        size_t result = ZSTD_CCtx_setParameter(cctx, ZSTD_c_enableLongDistanceMatching,
                                               ZSTD_ps_disable);
        if (ZSTD_isError(result)) {
            return (int)result;
        }
        return (int)ZSTD_CCtx_setParameter(cctx, ZSTD_c_windowLog, 0);
    }
    {
        size_t result = ZSTD_CCtx_setParameter(cctx, ZSTD_c_enableLongDistanceMatching,
                                               ZSTD_ps_enable);
        if (ZSTD_isError(result)) {
            return (int)result;
        }
        return (int)ZSTD_CCtx_setParameter(cctx, ZSTD_c_windowLog, windowLog);
    }
}

ZM_EXPORT int zm_setCompressionWorkers(long long cctx, int workers) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_nbWorkers, workers);
}

ZM_EXPORT int zm_setCompressionOverlapLog(long long cctx, int overlapLog) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_overlapLog, overlapLog);
}

ZM_EXPORT int zm_setCompressionJobSize(long long cctx, int jobSize) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_jobSize, jobSize);
}

ZM_EXPORT int zm_setCompressionTargetLength(long long cctx, int targetLength) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_targetLength, targetLength);
}

ZM_EXPORT int zm_setCompressionMinMatch(long long cctx, int minMatch) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_minMatch, minMatch);
}

ZM_EXPORT int zm_setCompressionSearchLog(long long cctx, int searchLog) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_searchLog, searchLog);
}

ZM_EXPORT int zm_setCompressionChainLog(long long cctx, int chainLog) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_chainLog, chainLog);
}

ZM_EXPORT int zm_setCompressionHashLog(long long cctx, int hashLog) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_hashLog, hashLog);
}

ZM_EXPORT int zm_setCompressionWindowLog(long long cctx, int windowLog) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_windowLog, windowLog);
}

ZM_EXPORT int zm_setCompressionStrategy(long long cctx, int strategy) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_strategy, strategy);
}

ZM_EXPORT int zm_setEnableLongDistanceMatching(long long cctx, int enableLdm) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_enableLongDistanceMatching,
                                       enableLdm);
}

ZM_EXPORT int zm_setValidateSequences(long long cctx, int validateSequences) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_validateSequences,
                                       validateSequences);
}

ZM_EXPORT int zm_setSequenceProducerFallback(long long cctx, bool fallback) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_enableSeqProducerFallback,
                                       fallback ? 1 : 0);
}

ZM_EXPORT int zm_setSearchForExternalRepcodes(long long cctx, int searchRepcodes) {
    return (int)ZSTD_CCtx_setParameter(zm_cctx(cctx), ZSTD_c_searchForExternalRepcodes,
                                       searchRepcodes);
}

ZM_EXPORT void zm_registerSequenceProducer(long long cctx, long long state,
                                           long long function) {
    ZSTD_registerSequenceProducer(zm_cctx(cctx),
                                  (void *)(intptr_t)state,
                                  (ZSTD_sequenceProducer_F)(intptr_t)function);
}

ZM_EXPORT long long zm_loadCDict(long long cctx, const char *dict, int dictSize) {
    if (dict == NULL) {
        return zm_size(ZSTD_CCtx_loadDictionary(zm_cctx(cctx), NULL, 0));
    }
    return zm_size(ZSTD_CCtx_loadDictionary(zm_cctx(cctx), dict, (size_t)dictSize));
}

ZM_EXPORT long long zm_refCDict(long long cctx, long long cdict) {
    return zm_size(ZSTD_CCtx_refCDict(zm_cctx(cctx),
                                      cdict == 0 ? NULL : zm_cdict(cdict)));
}

ZM_EXPORT long long zm_resetCCtx(long long cctx) {
    return zm_size(ZSTD_CCtx_reset(zm_cctx(cctx), ZSTD_reset_session_and_parameters));
}

ZM_EXPORT long long zm_setPledgedSrcSize(long long cctx, long long srcSize) {
    if (srcSize < 0) {
        return zm_error(ZSTD_error_srcSize_wrong);
    }
    return zm_size(ZSTD_CCtx_setPledgedSrcSize(zm_cctx(cctx),
                                               (unsigned long long)srcSize));
}

/* Mirror of the Java @MiteStruct layout. */
typedef struct {
    long long ingested;
    long long consumed;
    long long produced;
    long long flushed;
    int currentJobID;
    int nbActiveWorkers;
} ZM_FrameProgression;

ZM_EXPORT void zm_getFrameProgression(long long cctx, ZM_FrameProgression *out) {
    if (out == NULL) return;
    ZSTD_frameProgression progression = ZSTD_getFrameProgression(zm_cctx(cctx));
    out->ingested = (long long)progression.ingested;
    out->consumed = (long long)progression.consumed;
    out->produced = (long long)progression.produced;
    out->flushed = (long long)progression.flushed;
    out->currentJobID = progression.currentJobID;
    out->nbActiveWorkers = progression.nbActiveWorkers;
}

/*
 * The Java side receives the same packed encoding the JNI implementation used:
 *   bit 63      : frame fully flushed
 *   bit 31      : error flag, low byte = zstd error code
 *   bits 32..62 : destination position
 *   bits 0..30  : source position
 */
static long long zm_encode_stream_result(size_t result, long long dstPos, long long srcPos) {
    if (ZSTD_isError(result)) {
        return (1LL << 31) | (long long)ZSTD_getErrorCode(result);
    }
    {
        long long encoded = (dstPos << 32) | (srcPos & 0x7FFFFFFF);
        if (result == 0) {
            encoded |= (1LL << 63);
        }
        return encoded;
    }
}

ZM_EXPORT long long zm_compressStream(long long cctxHandle,
                                      char *dst, int dstOffset, int dstSize,
                                      const char *src, int srcOffset, int srcSize,
                                      int endOp) {
    if (dst == NULL) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (src == NULL) return zm_error(ZSTD_error_srcSize_wrong);
    if (dstOffset < 0 || dstSize < 0) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (srcOffset < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);

    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { src + srcOffset, (size_t)srcSize, 0 };
    size_t result = ZSTD_compressStream2(zm_cctx(cctxHandle), &output, &input,
                                         (ZSTD_EndDirective)endOp);
    return zm_encode_stream_result(result, (long long)output.pos, (long long)input.pos);
}

/* ------------------------------------------------------------------------- */
/* Decompression parameters                                                  */
/* ------------------------------------------------------------------------- */

ZM_EXPORT int zm_setDecompressionLongMax(long long dctx, int windowLogMax) {
    return (int)ZSTD_DCtx_setParameter(zm_dctx(dctx), ZSTD_d_windowLogMax, windowLogMax);
}

ZM_EXPORT int zm_setDecompressionMagicless(long long dctx, bool useMagicless) {
    ZSTD_format_e format = useMagicless ? ZSTD_f_zstd1_magicless : ZSTD_f_zstd1;
    return (int)ZSTD_DCtx_setParameter(zm_dctx(dctx), ZSTD_d_format, format);
}

ZM_EXPORT int zm_setRefMultipleDDicts(long long dctx, bool useMultiple) {
    ZSTD_refMultipleDDicts_e value = useMultiple
            ? ZSTD_rmd_refMultipleDDicts
            : ZSTD_rmd_refSingleDDict;
    return (int)ZSTD_DCtx_setParameter(zm_dctx(dctx), ZSTD_d_refMultipleDDicts, value);
}

ZM_EXPORT long long zm_loadDDict(long long dctx, const char *dict, int dictSize) {
    if (dict == NULL) {
        return zm_size(ZSTD_DCtx_loadDictionary(zm_dctx(dctx), NULL, 0));
    }
    return zm_size(ZSTD_DCtx_loadDictionary(zm_dctx(dctx), dict, (size_t)dictSize));
}

ZM_EXPORT long long zm_refDDict(long long dctx, long long ddict) {
    return zm_size(ZSTD_DCtx_refDDict(zm_dctx(dctx),
                                      ddict == 0 ? NULL : zm_ddict(ddict)));
}

ZM_EXPORT long long zm_resetDCtx(long long dctx) {
    return zm_size(ZSTD_DCtx_reset(zm_dctx(dctx), ZSTD_reset_session_and_parameters));
}

ZM_EXPORT long long zm_decompressStream(long long dctxHandle,
                                        char *dst, int dstOffset, int dstSize,
                                        const char *src, int srcOffset, int srcSize) {
    if (dst == NULL) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (src == NULL) return zm_error(ZSTD_error_srcSize_wrong);
    if (dstOffset < 0 || dstSize < 0) return zm_error(ZSTD_error_dstSize_tooSmall);
    if (srcOffset < 0 || srcSize < 0) return zm_error(ZSTD_error_srcSize_wrong);

    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { src + srcOffset, (size_t)srcSize, 0 };
    size_t result = ZSTD_decompressStream(zm_dctx(dctxHandle), &output, &input);
    return zm_encode_stream_result(result, (long long)output.pos, (long long)input.pos);
}

/* ------------------------------------------------------------------------- */
/* Prepared dictionaries                                                     */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_createCDict(const char *dict, int offset, int size, int level) {
    if (dict == NULL) return 0;
    if (offset < 0 || size < 0) return 0;
    return (long long)(intptr_t)ZSTD_createCDict(dict + offset, (size_t)size, level);
}

ZM_EXPORT void zm_freeCDict(long long cdict) {
    ZSTD_freeCDict(zm_cdict(cdict));
}

ZM_EXPORT long long zm_createDDict(const char *dict, int offset, int size) {
    if (dict == NULL) return 0;
    if (offset < 0 || size < 0) return 0;
    return (long long)(intptr_t)ZSTD_createDDict(dict + offset, (size_t)size);
}

ZM_EXPORT void zm_freeDDict(long long ddict) {
    ZSTD_freeDDict(zm_ddict(ddict));
}

/* ------------------------------------------------------------------------- */
/* Dictionary training                                                       */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_trainFromBuffer(const char *samples, int samplesLength,
                                       const int *sampleSizes, int sampleCount,
                                       char *dict, int dictCapacity,
                                       bool legacy, int level) {
    size_t *sizes;
    size_t total = 0;
    size_t result;
    int i;

    if (dict == NULL || dictCapacity < 0) {
        return zm_error(ZSTD_error_dictionary_wrong);
    }
    if (sampleCount < 0 || (samples == NULL && sampleCount > 0)) {
        return zm_error(ZSTD_error_GENERIC);
    }

    sizes = (size_t *)malloc(sizeof(size_t) * (size_t)(sampleCount > 0 ? sampleCount : 1));
    if (sizes == NULL) {
        return zm_error(ZSTD_error_memory_allocation);
    }
    for (i = 0; i < sampleCount; i++) {
        if (sampleSizes[i] < 0) {
            free(sizes);
            return zm_error(ZSTD_error_GENERIC);
        }
        sizes[i] = (size_t)sampleSizes[i];
        total += sizes[i];
    }
    if (total > (size_t)samplesLength) {
        free(sizes);
        return zm_error(ZSTD_error_GENERIC);
    }

    if (legacy) {
        ZDICT_legacy_params_t params;
        memset(&params, 0, sizeof(params));
        params.zParams.compressionLevel = level;
        result = ZDICT_trainFromBuffer_legacy(dict, (size_t)dictCapacity,
                                              samples, sizes, (unsigned)sampleCount,
                                              params);
    } else {
        result = ZDICT_trainFromBuffer(dict, (size_t)dictCapacity,
                                       samples, sizes, (unsigned)sampleCount, level);
    }
    free(sizes);
    return zm_size(result);
}

/* ------------------------------------------------------------------------- */
/* Streaming objects                                                         */
/* ------------------------------------------------------------------------- */

ZM_EXPORT long long zm_DStreamInSize(void)  { return zm_size(ZSTD_DStreamInSize()); }
ZM_EXPORT long long zm_DStreamOutSize(void) { return zm_size(ZSTD_DStreamOutSize()); }
ZM_EXPORT long long zm_CStreamOutSize(void) { return zm_size(ZSTD_CStreamOutSize()); }

ZM_EXPORT long long zm_createDStream(void) {
    return (long long)(intptr_t)ZSTD_createDStream();
}

ZM_EXPORT long long zm_freeDStream(long long stream) {
    return zm_size(ZSTD_freeDStream(zm_dstream(stream)));
}

ZM_EXPORT long long zm_initDStream(long long stream) {
    return zm_size(ZSTD_initDStream(zm_dstream(stream)));
}

ZM_EXPORT long long zm_resetDStream(long long stream) {
    return zm_size(ZSTD_DCtx_reset(zm_dctx(stream), ZSTD_reset_session_only));
}

ZM_EXPORT long long zm_createCStream(void) {
    return (long long)(intptr_t)ZSTD_createCStream();
}

ZM_EXPORT long long zm_freeCStream(long long stream) {
    return zm_size(ZSTD_freeCStream(zm_cstream(stream)));
}

ZM_EXPORT long long zm_initCStream(long long stream, int level) {
    return zm_size(ZSTD_initCStream(zm_cstream(stream), level));
}

ZM_EXPORT long long zm_initCStreamWithDict(long long stream, const char *dict,
                                           int dictSize, int level) {
    if (dict == NULL) {
        return zm_error(ZSTD_error_dictionary_wrong);
    }
    ZSTD_CCtx_reset(zm_cctx(stream), ZSTD_reset_session_only);
    ZSTD_CCtx_setParameter(zm_cctx(stream), ZSTD_c_compressionLevel, level);
    return zm_size(ZSTD_CCtx_loadDictionary(zm_cctx(stream), dict, (size_t)dictSize));
}

ZM_EXPORT long long zm_initCStreamWithFastDict(long long stream, long long cdict) {
    if (cdict == 0) {
        return zm_error(ZSTD_error_dictionary_wrong);
    }
    ZSTD_CCtx_reset(zm_cctx(stream), ZSTD_reset_session_only);
    return zm_size(ZSTD_CCtx_refCDict(zm_cctx(stream), zm_cdict(cdict)));
}

ZM_EXPORT long long zm_resetCStream(long long stream) {
    return zm_size(ZSTD_CCtx_reset(zm_cctx(stream), ZSTD_reset_session_only));
}

/* Mirror of the Java @MiteStruct layout. */
typedef struct {
    int srcPos;
    int dstPos;
} ZM_IoPos;

ZM_EXPORT long long zm_cStreamCompress(long long streamHandle,
                                       char *dst, int dstOffset, int dstSize,
                                       const char *src, int srcOffset, int srcSize,
                                       ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { src + srcOffset, (size_t)srcSize, 0 };
    size_t result = ZSTD_compressStream(zm_cstream(streamHandle), &output, &input);
    if (positions != NULL) {
        positions->srcPos = (int)input.pos;
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

ZM_EXPORT long long zm_cStreamFlush(long long streamHandle,
                                    char *dst, int dstOffset, int dstSize,
                                    ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    size_t result = ZSTD_flushStream(zm_cstream(streamHandle), &output);
    if (positions != NULL) {
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

ZM_EXPORT long long zm_cStreamEnd(long long streamHandle,
                                  char *dst, int dstOffset, int dstSize,
                                  ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    size_t result = ZSTD_endStream(zm_cstream(streamHandle), &output);
    if (positions != NULL) {
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

ZM_EXPORT long long zm_dStreamDecompress(long long streamHandle,
                                         char *dst, int dstOffset, int dstSize,
                                         const char *src, int srcOffset, int srcSize,
                                         ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst + dstOffset, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { src + srcOffset, (size_t)srcSize, 0 };
    size_t result = ZSTD_decompressStream(zm_dstream(streamHandle), &output, &input);
    if (positions != NULL) {
        positions->srcPos = (int)input.pos;
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

/*
 * Byte-array stream helpers: ZSTD_outBuffer always starts at 0 because the
 * Java side passes the destination array slice directly.
 */
ZM_EXPORT long long zm_outputStreamCompress(long long streamHandle,
                                            char *dst, int dstSize,
                                            const char *src, int srcSize,
                                            ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { src, (size_t)srcSize,
                            positions == NULL ? 0 : (size_t)positions->srcPos };
    size_t result = ZSTD_compressStream2(zm_cstream(streamHandle), &output, &input,
                                         ZSTD_e_continue);
    if (positions != NULL) {
        positions->srcPos = (int)input.pos;
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

ZM_EXPORT long long zm_outputStreamFlush(long long streamHandle,
                                         char *dst, int dstSize,
                                         ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { NULL, 0, 0 };
    size_t result = ZSTD_compressStream2(zm_cstream(streamHandle), &output, &input,
                                         ZSTD_e_flush);
    if (positions != NULL) {
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

ZM_EXPORT long long zm_outputStreamEnd(long long streamHandle,
                                       char *dst, int dstSize,
                                       ZM_IoPos *positions) {
    ZSTD_outBuffer output = { dst, (size_t)dstSize, 0 };
    ZSTD_inBuffer input = { NULL, 0, 0 };
    size_t result = ZSTD_compressStream2(zm_cstream(streamHandle), &output, &input,
                                         ZSTD_e_end);
    if (positions != NULL) {
        positions->dstPos = (int)output.pos;
    }
    return zm_size(result);
}

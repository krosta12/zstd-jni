package com.github.luben.zstd.mite;

import io.github.krosta12.semimite.annotation.MiteStruct;

/**
 * Mutable out-parameter used by the streaming bridge functions to report how
 * many bytes were consumed from the source buffer and produced into the
 * destination buffer.
 * <p>
 * semi-mite marshals this object into a native {@code struct { int src_pos;
 * int dst_pos; }} before the call and copies the fields back afterwards, which
 * is the semi-mite replacement for the {@code SetIntField} calls the old JNI
 * code used to advance the Java-side positions.
 */
@MiteStruct
public final class MiteIoPos {

    /** Number of bytes consumed from the source buffer. */
    public int srcPos;

    /** Number of bytes produced into the destination buffer. */
    public int dstPos;
}

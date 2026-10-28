package com.github.luben.zstd.mite;

import io.github.krosta12.semimite.annotation.MiteStruct;

/**
 * Native out-parameter mirroring {@code ZSTD_frameProgression}.
 * <p>
 * The C struct has the same field order and natural alignment on every
 * supported 64-bit platform ({@code unsigned long long} x4 followed by
 * {@code int} x2), which is exactly what semi-mite's {@code @MiteStruct}
 * layout produces for the Java fields below.
 */
@MiteStruct
public final class FrameProgressionRaw {

    public long ingested;
    public long consumed;
    public long produced;
    public long flushed;
    public int currentJobID;
    public int nbActiveWorkers;
}

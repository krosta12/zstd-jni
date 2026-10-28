package com.github.luben.zstd.mite;

import java.nio.ByteBuffer;

/**
 * Small helpers for moving a region of a {@link ByteBuffer} in and out of the
 * {@code byte[]} arguments used by the semi-mite client.
 * <p>
 * zstd-jni exposes direct-buffer APIs for zero-copy operation. semi-mite's
 * marshalling layer works with Java arrays, so the direct-buffer entry points
 * copy the affected region into a temporary array for the call and copy the
 * produced bytes back afterwards. Semantics (positions, limits, error
 * reporting) are unchanged; only the copy elision of the old JNI
 * {@code GetDirectBufferAddress} path is lost.
 */
public final class ByteBuffers {

    private ByteBuffers() {
    }

    /**
     * Copies {@code [offset, offset + length)} out of {@code buffer}.
     *
     * @param buffer the source buffer (direct or array-backed)
     * @param offset absolute index in the buffer
     * @param length number of bytes to copy
     * @return a new array with a snapshot of the region
     */
    public static byte[] read(ByteBuffer buffer, int offset, int length) {
        byte[] copy = new byte[length];
        ByteBuffer view = buffer.duplicate();
        view.position(offset);
        view.get(copy, 0, length);
        return copy;
    }

    /**
     * Copies {@code length} bytes from {@code source} into {@code buffer} at
     * {@code offset}.
     *
     * @param buffer     the destination buffer
     * @param offset     absolute index in the buffer
     * @param source     the bytes to write
     * @param sourceOffset first byte of {@code source} to copy
     * @param length     number of bytes to copy
     */
    public static void write(ByteBuffer buffer, int offset, byte[] source, int sourceOffset, int length) {
        if (length == 0) {
            return;
        }
        ByteBuffer view = buffer.duplicate();
        view.position(offset);
        view.put(source, sourceOffset, length);
    }
}

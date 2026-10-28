package com.github.luben.zstd.mite;

import com.github.luben.zstd.util.Native;
import io.github.krosta12.semimite.core.Mite;
import io.github.krosta12.semimite.core.MiteContext;

import java.nio.file.Path;

/**
 * Owns the semi-mite runtime used by zstd-jni.
 * <p>
 * On first use the native {@code zstd-mite} library is located (or extracted
 * from the jar) by {@link Native}, loaded through {@link Mite#use(String, Path)},
 * and the single {@link ZstdNativeClient} proxy is created. Every call in the
 * public zstd-jni API goes through that proxy; there is no {@code System.load},
 * no JNI method and no direct {@code java.lang.foreign} usage anywhere in
 * zstd-jni itself.
 * <p>
 * The context is deliberately kept open for the lifetime of the JVM: the
 * native library backs contexts and dictionaries that the caller may keep
 * until process exit, and unloading a live library is not safe.
 */
public final class ZstdMite {

    private static final ZstdNativeClient CLIENT;
    private static final MiteContext CONTEXT;
    private static final Path LIBRARY_PATH;

    static {
        try {
            LIBRARY_PATH = Native.nativeLibraryPath();
            CONTEXT = Mite.use("com.github.luben.zstd.mite", LIBRARY_PATH).start();
            ZstdNativeClient client = CONTEXT.get(ZstdNativeClient.class);
            if (client == null) {
                throw new IllegalStateException(
                        "semi-mite did not create a ZstdNativeClient proxy");
            }
            CLIENT = client;
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private ZstdMite() {
    }

    /**
     * Forces initialization of the semi-mite runtime. Calling this from the
     * static initializer of a public class keeps failures close to the first
     * use and preserves the old {@code Native.load()} behaviour.
     */
    public static void ensureLoaded() {
        if (CLIENT == null) {
            throw new IllegalStateException("zstd native library is not loaded");
        }
    }

    /**
     * Returns the semi-mite proxy implementing the complete native surface.
     *
     * @return the native client proxy
     */
    public static ZstdNativeClient client() {
        return CLIENT;
    }

    /**
     * Returns the semi-mite context that owns the native library.
     *
     * @return the active context
     */
    public static MiteContext context() {
        return CONTEXT;
    }

    /**
     * Returns the path of the native library that was loaded.
     *
     * @return absolute path of the shared library
     */
    public static Path libraryPath() {
        return LIBRARY_PATH;
    }
}

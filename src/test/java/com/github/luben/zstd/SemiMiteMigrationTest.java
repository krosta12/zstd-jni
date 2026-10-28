package com.github.luben.zstd;

import com.github.luben.zstd.mite.ZstdMite;
import com.github.luben.zstd.mite.ZstdNativeClient;
import io.github.krosta12.semimite.client.MiteClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the actual point of the migration: the public API must contain no JNI
 * entry points, and every native call must be routed through the semi-mite
 * client.
 */
class SemiMiteMigrationTest {

    private static final String[] PUBLIC_CLASSES = {
            "com.github.luben.zstd.Zstd",
            "com.github.luben.zstd.ZstdCompressCtx",
            "com.github.luben.zstd.ZstdDecompressCtx",
            "com.github.luben.zstd.ZstdDictCompress",
            "com.github.luben.zstd.ZstdDictDecompress",
            "com.github.luben.zstd.ZstdInputStream",
            "com.github.luben.zstd.ZstdInputStreamNoFinalizer",
            "com.github.luben.zstd.ZstdOutputStream",
            "com.github.luben.zstd.ZstdOutputStreamNoFinalizer",
            "com.github.luben.zstd.ZstdBufferDecompressingStream",
            "com.github.luben.zstd.ZstdBufferDecompressingStreamNoFinalizer",
            "com.github.luben.zstd.ZstdDirectBufferCompressingStream",
            "com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer",
            "com.github.luben.zstd.ZstdDirectBufferDecompressingStream",
            "com.github.luben.zstd.ZstdDirectBufferDecompressingStreamNoFinalizer",
            "com.github.luben.zstd.ZstdDictTrainer",
    };

    @Test
    void noNativeMethodsRemainInThePublicApi() throws Exception {
        for (String className : PUBLIC_CLASSES) {
            Class<?> type = Class.forName(className);
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(Modifier.isNative(method.getModifiers()),
                        "JNI method still present: " + className + "#" + method.getName());
            }
        }
    }

    @Test
    void nativeBoundaryIsASemiMiteClient() {
        assertTrue(ZstdNativeClient.class.isAnnotationPresent(MiteClient.class),
                "the native surface must be a @MiteClient interface");
        assertNotNull(ZstdMite.client(), "semi-mite proxy was not created");
        assertFalse(ZstdMite.client().getClass().getName().equals(ZstdNativeClient.class.getName()),
                "the client must be a proxy, not a plain implementation");
    }

    @Test
    void nativeLibraryIsLoadedFromAnExistingPath() {
        assertNotNull(ZstdMite.libraryPath());
        assertTrue(Files.isRegularFile(ZstdMite.libraryPath()),
                "semi-mite needs the library on disk to resolve symbols");
        assertTrue(com.github.luben.zstd.util.Native.isLoaded());
        assertFalse(ZstdMite.context().isClosed());
    }

    @Test
    void clientExposesTheWholeBoundary() {
        long declared = java.util.Arrays.stream(ZstdNativeClient.class.getDeclaredMethods())
                .filter(m -> !m.isDefault() && !Modifier.isStatic(m.getModifiers()))
                .count();
        assertTrue(declared >= 90, "unexpectedly small native boundary: " + declared);
        for (Method method : ZstdNativeClient.class.getDeclaredMethods()) {
            assertTrue(method.getName().startsWith("zm_"),
                    "bridge methods must share the zm_ namespace: " + method.getName());
        }
    }
}

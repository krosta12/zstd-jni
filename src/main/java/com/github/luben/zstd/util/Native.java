package com.github.luben.zstd.util;

import com.github.luben.zstd.mite.ZstdMite;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Locates the native {@code zstd-mite} shared library for semi-mite.
 * <p>
 * Unlike the JNI version of zstd-jni this class no longer calls
 * {@code System.loadLibrary} or {@code System.load}: semi-mite loads the
 * library itself through {@link java.lang.foreign.SymbolLookup#libraryLookup}.
 * This class only has to produce a filesystem path, extracting the bundled
 * binary from the jar when necessary.
 * <p>
 * The lookup order is:
 * <ol>
 *     <li>{@code -DZstdNativePath=/absolute/path/to/library} - explicit override;</li>
 *     <li>after {@link #assumeLoaded()}, a library found on {@code java.library.path};</li>
 *     <li>the platform binary packaged in the jar under
 *     {@code /<os>/<arch>/libzstd-mite.<ext>}, extracted to a temporary file.</li>
 * </ol>
 */
public enum Native {
    ;

    private static final @NotNull String nativePathOverride = "ZstdNativePath";
    private static final @NotNull String tempFolderOverride = "ZstdTempFolder";
    private static final @NotNull String libnameShort = "zstd-mite";
    private static final @NotNull String libname = "lib" + libnameShort;

    private static final @NotNull AtomicBoolean loaded = new AtomicBoolean(false);

    private static volatile boolean assumedLoaded = false;
    private static volatile @Nullable File tempFolder;
    private static volatile @Nullable Path resolvedLibrary;

    private static @NotNull String osName() {
        String osProperty = System.getProperty("os.name");
        String os = (osProperty == null ? "" : osProperty).toLowerCase().replace(' ', '_');
        if (os.startsWith("win")) {
            return "win";
        } else if (os.startsWith("mac")) {
            return "darwin";
        } else {
            return os;
        }
    }

    private static @NotNull String libExtension() {
        if (osName().contains("os_x") || osName().contains("darwin")) {
            return "dylib";
        } else if (osName().contains("win")) {
            return "dll";
        } else {
            return "so";
        }
    }

    private static @NotNull String resourceName() {
        String os = osName();
        String arch = System.getProperty("os.arch");
        if (os.equals("darwin") && "amd64".equals(arch)) {
            arch = "x86_64";
        }
        return "/" + os + "/" + arch + "/" + libname + "." + libExtension();
    }

    private static @NotNull String errorMessage() {
        return "Unsupported OS/arch, cannot find " + resourceName()
                + " and no library was supplied. Build the native library "
                + "(gradle nativeLibrary) or point -D" + nativePathOverride
                + " at lib" + libnameShort + "." + libExtension() + ".";
    }

    /**
     * Declares that the native library has already been provided by the host
     * application. semi-mite still needs a path to load symbols from, so the
     * library is looked up on {@code java.library.path} (or supplied through
     * {@code -DZstdNativePath}).
     */
    public static synchronized void assumeLoaded() {
        assumedLoaded = true;
        loaded.set(true);
    }

    /**
     * Returns whether {@link #load()} has completed.
     *
     * @return {@code true} once the semi-mite runtime owns the library
     */
    public static synchronized boolean isLoaded() {
        return loaded.get();
    }

    /**
     * Starts the semi-mite runtime, which loads the native library and creates
     * the client proxy. Kept for source compatibility with the JNI version.
     */
    public static synchronized void load() {
        ZstdMite.ensureLoaded();
        loaded.set(true);
    }

    /**
     * Starts the runtime using an explicit temporary directory for the
     * extracted library.
     *
     * @param folder directory used to unpack the bundled binary, or {@code null}
     *               to use the default temporary directory
     */
    public static synchronized void load(final @Nullable File folder) {
        tempFolder = folder;
        load();
    }

    /**
     * Returns the absolute path of the native library that semi-mite should
     * load, extracting it from the classpath resources when needed.
     *
     * @return path to an existing shared library
     * @throws UnsatisfiedLinkError if the platform binary cannot be found
     */
    public static synchronized @NotNull Path nativeLibraryPath() {
        Path resolved = resolvedLibrary;
        if (resolved != null) {
            return resolved;
        }

        String overridePath = System.getProperty(nativePathOverride);
        if (overridePath != null && !overridePath.isBlank()) {
            Path path = Path.of(overridePath).toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                throw new UnsatisfiedLinkError(
                        "-D" + nativePathOverride + " points at a missing file: " + path);
            }
            resolvedLibrary = path;
            loaded.set(true);
            return path;
        }

        if (assumedLoaded) {
            Path found = findOnLibraryPath();
            if (found == null) {
                throw new UnsatisfiedLinkError(
                        "assumeLoaded() requires the library on java.library.path "
                                + "or an explicit -D" + nativePathOverride + "; " + errorMessage());
            }
            resolvedLibrary = found;
            loaded.set(true);
            return found;
        }

        String resource = resourceName();
        try (InputStream is = Native.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new UnsatisfiedLinkError("Failed to open resource " + resource + ": " + errorMessage());
            }
            File tempLib = File.createTempFile(libname + "-", "." + libExtension(), tempFolder);
            tempLib.deleteOnExit();
            Files.copy(is, tempLib.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Path path = tempLib.toPath().toAbsolutePath();
            resolvedLibrary = path;
            loaded.set(true);
            return path;
        } catch (IOException e) {
            ExceptionInInitializerError err = new ExceptionInInitializerError(
                    "Cannot unpack " + libname + ": " + e.getMessage());
            err.initCause(e);
            throw err;
        }
    }

    /**
     * Searches {@code java.library.path} for the {@code zstd-mite} library.
     *
     * @return the first match, or {@code null}
     */
    private static @Nullable Path findOnLibraryPath() {
        String libraryPath = System.getProperty("java.library.path", "");
        String fileName = libname + "." + libExtension();
        for (String entry : libraryPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry, fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }
}

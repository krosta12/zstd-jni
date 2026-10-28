# zstd-jni — semi-mite edition

Java bindings for the **Zstandard** compression library, with the native layer
completely migrated from JNI (and from hand-written Project Panama code) to
[semi-mite](https://github.com/krosta12/semi-mite).

This tree contains **no JNI code and no `java.lang.foreign` usage**:

* the `jni/` directory, every `jni_*.c` shim, the Panama shim `ffm_zstd.c` and
  the whole `src/main/java22` FFM implementation are gone;
* all native calls go through one `@MiteClient` interface
  (`com.github.luben.zstd.mite.ZstdNativeClient`);
* semi-mite, the framework the project now depends on, is the only component
  that touches the Foreign Function & Memory API.

The public `com.github.luben.zstd` API is unchanged: the same classes, method
signatures and behaviour as the JNI/Panama releases.

## How it works

```
Java public API (Zstd, ZstdCompressCtx, streams, dictionaries, …)
        │
        ▼
ZstdNativeClient  ← @MiteClient interface, one flat method per native operation
        │
        ▼
semi-mite proxy  ← builds FunctionDescriptors, looks up symbols, marshals
        │
        ▼
libzstd-mite (C) ← src/main/native/zstd_mite.c + the vendored libzstd sources
        │
        ▼
Zstandard
```

* `ZstdNativeClient` declares the complete native boundary with primitives,
  `byte[]` buffers and `long` handles. semi-mite derives the FFM descriptors
  from those Java signatures when the pre-built library is loaded
  (`Mite.use(...)`), so there is no generated binding code to maintain.
* Opaque zstd objects (`ZSTD_CCtx*`, `ZSTD_DCtx*`, `ZSTD_CDict*`, `ZSTD_DDict*`,
  stream objects) are carried as `long` handles. On the C side they are
  `long long`, which keeps the ABI identical on Windows (where C `long` is
  32-bit) and on 64-bit Unix.
* `src/main/native/zstd_mite.c` is a thin, JNI-free facade over the zstd
  advanced API. It replaces the eight former JNI files one function at a time;
  the Java-side logic (offset validation, error handling, stream position
  bookkeeping) is preserved.
* `ZstdMite` locates the native library (or extracts the bundled binary from
  the jar), starts the semi-mite context and holds the single client proxy for
  the lifetime of the JVM. `com.github.luben.zstd.util.Native` is reduced to
  path resolution; it no longer calls `System.loadLibrary`.

## Requirements

* **JDK 22+** — the Foreign Function & Memory API used by semi-mite is final
  there. The previous JNI build supported older JDKs and Android; that is no
  longer possible with this backend.
* A **64-bit JVM** — semi-mite's pointer-size validation requires 8-byte
  pointers.
* A C compiler for the native library: `gcc` or `clang` (`cl` also works with
  the provided CMake project).
* semi-mite **0.2.0 or newer**. The published semi-mite 0.1.5 maps Java `long`
  to C `long`, which is 4 bytes on Windows and therefore cannot carry native
  handles; 0.2.0 maps `long` to `long long` as the bridge requires.

The Gradle build expects the semi-mite project in the sibling directory
`../Fufa` (this is the workspace layout) and uses it through a Gradle composite
build. The path can be overridden with `-PsemiMiteProject=/path/to/semi-mite`.
If semi-mite 0.2.0 is published to Maven Local or Maven Central instead, the
`includeBuild` in `settings.gradle` can simply be removed; for the sibling
checkout, the equivalent local publication is:

```bash
cd ../Fufa && ./gradlew publishToMavenLocal
```

Gradle resolves a JDK 22 toolchain (auto-provisioning it through the Foojay
resolver when no local JDK 22 is installed).

## Building

```bash
./gradlew test      # builds libzstd-mite for the host platform and runs the suite
./gradlew jar       # packages classes + the host platform's native library
```

The native library is compiled by the `nativeLibrary` Gradle task from
`src/main/native/*.c` (the vendored zstd sources plus `zstd_mite.c`). It is
placed under `build/native/<os>/<arch>/` and copied into the jar at
`/<os>/<arch>/libzstd-mite.<ext>`, which is where `Native` looks for it at
runtime.

If you prefer CMake:

```bash
cmake -S . -B build/cmake
cmake --build build/cmake
```

Cross-platform jars are not produced by a single build; build the native
library once per target platform and place the results under the corresponding
`/<os>/<arch>/` resource path.

## Using the library

The Maven coordinates and the API remain the same as upstream zstd-jni:

```java
byte[] compressed = Zstd.compress(input, 3);
byte[] restored   = Zstd.decompress(compressed, input.length);
```

Streams, contexts and dictionaries work exactly as documented upstream:

```java
try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
    ctx.setLevel(6).setChecksum(true);
    byte[] compressed = ctx.compress(input);
}

try (ZstdOutputStream out = new ZstdOutputStream(fileOut, 3)) {
    out.write(input);
}
```

At runtime semi-mite needs the native library on disk. It is normally extracted
from the jar into the system temporary directory. The following system
properties are supported:

| Property          | Meaning                                                              |
| ----------------- | -------------------------------------------------------------------- |
| `ZstdNativePath`  | Absolute path to an already-built `libzstd-mite` library.             |
| `ZstdTempFolder`  | Directory used when extracting the bundled library from the jar.      |

Because semi-mite uses FFM, run the JVM with `--enable-native-access=ALL-UNNAMED`
(or add the equivalent `Enable-Native-Access` manifest attribute) to avoid the
restricted-method warning on recent JDKs.

## What changed in the migration

| Area                | Before                                     | After                                              |
| ------------------- | ------------------------------------------ | -------------------------------------------------- |
| Native boundary     | `native` methods in eight JNI classes       | one `@MiteClient` interface, `zm_*` functions       |
| C layer             | JNI shims (`JNIEnv`, `jbyteArray`, fields)  | flat C functions (`zstd_mite.c`)                    |
| JDK 22+ path        | `src/main/java22` + `ZstdBinding` on FFM    | deleted; one implementation for all supported JDKs  |
| Library loading     | `System.load` / `System.loadLibrary`        | `Mite.use(path)` → `SymbolLookup.libraryLookup`     |
| Array handing       | `GetPrimitiveArrayCritical` (zero-copy)     | semi-mite copies into the per-call arena            |
| Direct `ByteBuffer` | `GetDirectBufferAddress` (zero-copy)        | region copied in/out at the Java boundary           |
| Dict `byReference`  | CDict/DDict borrowed the direct buffer      | dictionary content is copied (semantics preserved)  |
| Minimum JDK         | 8 (JNI) / 22 (FFM)                          | 22                                                  |
| Android             | JNI build supported                         | not supported (FFM is unavailable on Android)       |

### Behavioural notes

* The performance characteristics differ: the common `byte[]` and direct-buffer
  calls now make one extra copy at the Java/native boundary. The API contract
  (positions, limits, returned sizes, error codes) is unchanged, and the
  streaming classes still reuse their buffers. For throughput-critical code,
  reuse `ZstdCompressCtx`/`ZstdDecompressCtx`, which is also what the upstream
  documentation recommends.
* `ZstdDictCompress(ByteBuffer, …, byReference)` and
  `ZstdDictDecompress(ByteBuffer, byReference)` always copy the dictionary
  content. This is safer than borrowing caller memory, and the documented
  contract (“the buffer may be reused after the call”) still holds for the
  default constructors. A buffer passed with `byReference = true` is no longer
  read by native code afterwards, so mutating it cannot corrupt the dictionary.
* `Native.assumeLoaded()` is kept for source compatibility, but semi-mite still
  needs a path to resolve symbols. The library is then searched on
  `java.library.path`, or `ZstdNativePath` must be set.
* The semi-mite context is intentionally never closed by zstd-jni: contexts and
  dictionaries may live until process exit and unloading a live library is not
  safe. A JVM shutdown reclaims it.
* The native library is named `libzstd-mite` rather than `libzstd-jni`; the
  public Java package names are untouched.
* Do not relocate/shade `com.github.luben.zstd.mite`: semi-mite discovers the
  `@MiteClient` interface by scanning that package and binds its methods to the
  exported C symbols by name.
* No `--add-opens`, no JNI headers, no `jni.h` and no generated glue code are
  involved.

## Testing

`./gradlew test` runs 54 tests, including:

* byte-array and `ByteBuffer` round trips at several compression levels;
* streaming through `ZstdInputStream`/`ZstdOutputStream`, the buffer streams,
  the direct-buffer streams and the `Zstd*Ctx` ByteBuffer API;
* dictionaries (prepared, trained, offset/length, direct buffers, streams) and
  the dictionary trainer (cover and legacy algorithms);
* frame introspection, magicless frames, checksums, content-size control,
  pledged sizes, frame progression and the zstd error constants;
* decompression of pre-compressed frames from `src/test/resources`;
* a reflection test asserting that no public class has `native` methods and
  that the boundary is a semi-mite `@MiteClient`;
* concurrent use of the shared semi-mite proxy.

## Zstd

**Zstd**, short for Zstandard, is a lossless compression algorithm that
provides a good ratio *and* speed. It is developed by Yann Collet and the
source is available at <https://github.com/facebook/zstd>.

This tree vendors the zstd sources under `src/main/native` (see the upstream
README and licenses there). The zstd-jni version is the zstd base version plus
the binding revision, e.g. `1.5.7-25`.

## License

The Java bindings remain under the 2-clause BSD license; the native zstd
library is licensed under the 3-clause BSD license or GPL2. See `LICENSE` and
the license files under `src/main/native` for the full conditions.

The original zstd-jni project is at <https://github.com/luben/zstd-jni>.

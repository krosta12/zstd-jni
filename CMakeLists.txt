cmake_minimum_required(VERSION 3.16)
project(zstd-mite C ASM)

# The bridge is plain C: semi-mite performs the Java <-> native transition, so
# no JNI headers or JNI sources are part of this build any more.

file(GLOB_RECURSE ZSTD_C_SOURCES CONFIGURE_DEPENDS "src/main/native/*.c")
set(ZSTD_SOURCES ${ZSTD_C_SOURCES})

# huf_decompress_amd64.S is GNU/ELF assembly; keep the portable C fallback on
# Windows toolchains.
if (NOT WIN32)
    file(GLOB_RECURSE ZSTD_ASM_SOURCES CONFIGURE_DEPENDS "src/main/native/*.S")
    list(APPEND ZSTD_SOURCES ${ZSTD_ASM_SOURCES})
endif ()

add_library(zstd-mite SHARED ${ZSTD_SOURCES})

target_include_directories(zstd-mite PRIVATE
        src/main/native
        src/main/native/common
        src/main/native/compress
        src/main/native/decompress
        src/main/native/dictBuilder)

target_compile_definitions(zstd-mite PRIVATE ZSTD_MULTITHREAD=1)
if (WIN32)
    target_compile_definitions(zstd-mite PRIVATE ZSTD_DISABLE_ASM=1)
endif ()

if (MSVC)
    target_compile_options(zstd-mite PRIVATE /O2)
else ()
    target_compile_options(zstd-mite PRIVATE -O3 -fPIC)
endif ()

set_target_properties(zstd-mite PROPERTIES
        PREFIX "lib"
        OUTPUT_NAME "zstd-mite")

find_package(Threads REQUIRED)
target_link_libraries(zstd-mite PRIVATE Threads::Threads)

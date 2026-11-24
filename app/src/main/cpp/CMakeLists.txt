# Third Eye on-phone AI: one native library (libthirdeye_ai.so) holding
#   - whisper.cpp  (offline speech recognition, step 5)
#   - llama.cpp + libmtmd (SmolVLM2 image description, step 6)
# on top of a single ggml. whisper.cpp and llama.cpp each ship their own copy of ggml;
# linking both copies gives duplicate-symbol errors, so ggml is added exactly once here:
# llama.cpp's copy when llama.cpp is present, otherwise whisper.cpp's.
#
# Built only when the Gradle property thirdeyeNativeAi=true (see app/build.gradle).
# Sources are not committed; clone them into third_party/ (see docs/OFFLINE_AI.md).
# Either source tree may be missing; the matching Java feature then reports "not available".

cmake_minimum_required(VERSION 3.18.1)
project(thirdeye_ai C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRDEYE_WHISPER_DIR "" CACHE PATH "whisper.cpp checkout")
set(THIRDEYE_LLAMA_DIR "" CACHE PATH "llama.cpp checkout")

# Everything static: one .so for the app, no extra libggml*.so / libllama.so to package.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)        # cross-compiling for the phone, not this PC
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)        # ggml's own thread pool; no libomp to ship
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(HAVE_WHISPER OFF)
if (THIRDEYE_WHISPER_DIR AND EXISTS "${THIRDEYE_WHISPER_DIR}/src/whisper.cpp")
    set(HAVE_WHISPER ON)
endif()
set(HAVE_LLAMA OFF)
if (THIRDEYE_LLAMA_DIR AND EXISTS "${THIRDEYE_LLAMA_DIR}/tools/mtmd/mtmd.cpp")
    set(HAVE_LLAMA ON)
endif()

if (NOT HAVE_WHISPER AND NOT HAVE_LLAMA)
    message(FATAL_ERROR "thirdeyeNativeAi=true but neither whisper.cpp ('${THIRDEYE_WHISPER_DIR}') "
            "nor llama.cpp ('${THIRDEYE_LLAMA_DIR}') was found. Clone them (docs/OFFLINE_AI.md) "
            "or turn the property off.")
endif()
message(STATUS "Third Eye native AI: whisper=${HAVE_WHISPER} llama=${HAVE_LLAMA}")

set(JNI_SOURCES)
set(JNI_LIBS)
set(JNI_DEFS)

# ---- llama.cpp first: its root CMakeLists adds ggml (the one and only copy) -----------
if (HAVE_LLAMA)
    add_subdirectory("${THIRDEYE_LLAMA_DIR}" llama.cpp)

    # libmtmd (multimodal), compiled here so llama.cpp's command-line tools aren't needed.
    file(GLOB MTMD_SOURCES "${THIRDEYE_LLAMA_DIR}/tools/mtmd/*.cpp"
                           "${THIRDEYE_LLAMA_DIR}/tools/mtmd/models/*.cpp")
    list(FILTER MTMD_SOURCES EXCLUDE REGEX ".*(-cli|deprecation-warning|test).*\\.cpp$")
    add_library(mtmd STATIC ${MTMD_SOURCES})
    target_include_directories(mtmd PUBLIC "${THIRDEYE_LLAMA_DIR}/tools/mtmd"
                                    PRIVATE "${THIRDEYE_LLAMA_DIR}/vendor")
    target_link_libraries(mtmd PUBLIC llama ggml)

    list(APPEND JNI_SOURCES llama_jni.cpp)
    list(APPEND JNI_LIBS mtmd llama)
    list(APPEND JNI_DEFS THIRDEYE_HAS_LLAMA=1)
elseif (HAVE_WHISPER AND NOT TARGET ggml)
    add_subdirectory("${THIRDEYE_WHISPER_DIR}/ggml" ggml)
endif()

# ---- whisper.cpp, compiled directly against that single ggml --------------------------
if (HAVE_WHISPER)
    add_library(whisper STATIC "${THIRDEYE_WHISPER_DIR}/src/whisper.cpp")
    target_include_directories(whisper PUBLIC "${THIRDEYE_WHISPER_DIR}/include"
                                       PRIVATE "${THIRDEYE_WHISPER_DIR}/src")
    target_compile_definitions(whisper PRIVATE WHISPER_VERSION="thirdeye")
    target_link_libraries(whisper PUBLIC ggml)

    list(APPEND JNI_SOURCES whisper_jni.cpp)
    list(APPEND JNI_LIBS whisper)
    list(APPEND JNI_DEFS THIRDEYE_HAS_WHISPER=1)
endif()

# ---- the JNI library -------------------------------------------------------------------
add_library(thirdeye_ai SHARED ${JNI_SOURCES})
target_compile_definitions(thirdeye_ai PRIVATE ${JNI_DEFS})
target_link_libraries(thirdeye_ai PRIVATE ${JNI_LIBS} ggml log android)

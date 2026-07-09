cmake_minimum_required(VERSION 3.18)
project(chunkup_cuda LANGUAGES C CXX CUDA)

set(CHUNKUP_COMMON_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../common)

# ── CUDA toolkit discovery (Linux + Windows) ───────────────────────
# On Windows: nvcc is typically in PATH via CUDA installer
# On Linux (RHEL/Debian): nvcc at /usr/local/cuda/bin/nvcc or /opt/cuda/bin/nvcc
#   build-engine.sh passes -DCUDAToolkit_ROOT=... and -DCMAKE_CUDA_COMPILER=...
if(NOT DEFINED CMAKE_CUDA_COMPILER)
    # Try find_package first (CMake 3.17+)
    find_package(CUDAToolkit QUIET)
    if(CUDAToolkit_FOUND)
        set(CMAKE_CUDA_COMPILER "${CUDAToolkit_BIN_DIR}/nvcc")
        message(STATUS "CUDA found via CUDAToolkit: ${CMAKE_CUDA_COMPILER}")
    else()
        # Fallback: check common paths
        foreach(cuda_path /usr/local/cuda /opt/cuda)
            if(EXISTS "${cuda_path}/bin/nvcc")
                set(CMAKE_CUDA_COMPILER "${cuda_path}/bin/nvcc")
                set(CUDAToolkit_ROOT "${cuda_path}")
                message(STATUS "CUDA found at: ${cuda_path}")
                break()
            endif()
        endforeach()
    endif()
endif()

if(NOT CMAKE_CUDA_COMPILER)
    message(FATAL_ERROR "CUDA compiler (nvcc) not found. "
        "Install cuda-toolkit or set -DCMAKE_CUDA_COMPILER=/path/to/nvcc")
endif()

message(STATUS "CUDA compiler: ${CMAKE_CUDA_COMPILER}")

# ── architecture flags ─────────────────────────────────────────────
include(CheckLanguage)
check_language(CUDA)
if(CMAKE_CUDA_COMPILER)
    enable_language(CUDA)
endif()

# Detect CUDA compute capability
set(CMAKE_CUDA_ARCHITECTURES "61;75;86;89" CACHE STRING "CUDA architectures to target")
if(NOT MSVC)
    set(CMAKE_CUDA_FLAGS "${CMAKE_CUDA_FLAGS} -Xcompiler -fPIC")
endif()

add_library(chunkup_cuda SHARED
    src/chunkup_cuda.cu
    src/chunkup_cuda_host.c
    ${CHUNKUP_COMMON_DIR}/chunkup_kernel_host.c
    ${CHUNKUP_COMMON_DIR}/chunkup_noise_state.c
    ${CHUNKUP_COMMON_DIR}/chunkup_sl_log.c
)

target_include_directories(chunkup_cuda PUBLIC include)
target_include_directories(chunkup_cuda PRIVATE ${CHUNKUP_COMMON_DIR})

target_compile_definitions(chunkup_cuda PRIVATE CHUNKUP_EXPORT_BUILD)

if(MSVC)
    target_compile_options(chunkup_cuda PRIVATE /utf-8)
endif()

set_target_properties(chunkup_cuda PROPERTIES
    CUDA_SEPARABLE_COMPILATION ON
    POSITION_INDEPENDENT_CODE ON
)

# Link CUDA runtime
if(CUDAToolkit_FOUND)
    target_link_libraries(chunkup_cuda PRIVATE CUDA::cudart)
else()
    find_library(CUDA_RT_LIB cudart
        PATHS /usr/local/cuda/lib64 /opt/cuda/lib64 /usr/lib64
        NO_DEFAULT_PATH
    )
    if(CUDA_RT_LIB)
        target_link_libraries(chunkup_cuda PRIVATE ${CUDA_RT_LIB})
    endif()
endif()

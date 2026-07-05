cmake_minimum_required(VERSION 3.18)
project(chunkup_opencl LANGUAGES C CXX)

# ── OpenCL discovery (Linux + Windows) ─────────────────────────────
# CMake 4.4+ FindOpenCL 的 CheckSymbolExists 在部分 Windows 环境会失败；
# Windows 上优先手动解析 CUDA SDK 自带的 OpenCL 头文件与库。
if(WIN32)
    if(DEFINED ENV{CUDA_PATH})
        set(_chunkup_cuda_root "$ENV{CUDA_PATH}")
    else()
        set(_chunkup_cuda_root "C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v11.8")
    endif()
    if(NOT OpenCL_INCLUDE_DIR)
        find_path(OpenCL_INCLUDE_DIR CL/cl.h
            PATHS "${_chunkup_cuda_root}/include"
            NO_DEFAULT_PATH
        )
    endif()
    if(NOT OpenCL_LIBRARY)
        find_library(OpenCL_LIBRARY NAMES OpenCL
            PATHS "${_chunkup_cuda_root}/lib/x64"
            NO_DEFAULT_PATH
        )
    endif()
    if(OpenCL_INCLUDE_DIR AND OpenCL_LIBRARY)
        set(OpenCL_FOUND TRUE)
        set(OpenCL_INCLUDE_DIRS ${OpenCL_INCLUDE_DIR})
        set(OpenCL_LIBRARIES ${OpenCL_LIBRARY})
        message(STATUS "OpenCL found via CUDA SDK: ${OpenCL_INCLUDE_DIR}")
    endif()
endif()

if(NOT OpenCL_FOUND)
    find_package(OpenCL QUIET)
endif()
if(NOT OpenCL_FOUND)
    # Manual fallback on Linux — headers are usually in /usr/include/CL
    find_path(OPENCL_INCLUDE_DIR CL/cl.h
        PATHS /usr/include /usr/local/include /opt/opencl/include
        PATH_SUFFIXES CL OpenCL
    )
    find_library(OPENCL_LIBRARY OpenCL
        PATHS /usr/lib /usr/lib64 /usr/local/lib
        PATH_SUFFIXES x86_64-linux-gnu
    )
    if(OPENCL_INCLUDE_DIR AND OPENCL_LIBRARY)
        set(OpenCL_FOUND TRUE)
        set(OpenCL_INCLUDE_DIRS ${OPENCL_INCLUDE_DIR})
        set(OpenCL_LIBRARIES ${OPENCL_LIBRARY})
        message(STATUS "OpenCL found manually: ${OPENCL_INCLUDE_DIR}")
    endif()
endif()

if(NOT OpenCL_FOUND)
    message(FATAL_ERROR "OpenCL development headers not found. Install with:\n"
        "  RHEL/Fedora:  dnf install ocl-icd-devel\n"
        "  Debian/Ubuntu: apt install ocl-icd-opencl-dev\n"
        "  Arch/Manjaro:  pacman -S opencl-headers ocl-icd\n"
        "  Alpine:        apk add opencl-headers opencl-icd-loader-dev")
endif()

set(CHUNKUP_COMMON_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../common)

add_library(chunkup_opencl SHARED
    src/chunkup_opencl.cpp
    ${CHUNKUP_COMMON_DIR}/chunkup_kernel_host.c
    ${CHUNKUP_COMMON_DIR}/chunkup_noise_state.c
)

target_include_directories(chunkup_opencl PUBLIC include)
target_include_directories(chunkup_opencl PRIVATE ${CHUNKUP_COMMON_DIR})
target_include_directories(chunkup_opencl PRIVATE ${OpenCL_INCLUDE_DIRS})

target_compile_definitions(chunkup_opencl PRIVATE
    CHUNKUP_EXPORT_BUILD
    CHUNKUP_OPENCL_KERNEL_PATH="${CMAKE_CURRENT_SOURCE_DIR}/kernels/chunkup_kernel.cl"
)

target_link_libraries(chunkup_opencl PRIVATE ${OpenCL_LIBRARIES})

if(MSVC)
    target_compile_options(chunkup_opencl PRIVATE /utf-8)
endif()

set_target_properties(chunkup_opencl PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

cmake_minimum_required(VERSION 3.18)
project(chunkup_opencl LANGUAGES C CXX)

find_package(OpenCL REQUIRED)

set(CHUNKUP_COMMON_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../common)

add_library(chunkup_opencl SHARED
    src/chunkup_opencl.cpp
    ${CHUNKUP_COMMON_DIR}/chunkup_kernel_host.c
    ${CHUNKUP_COMMON_DIR}/chunkup_noise_state.c
)

target_include_directories(chunkup_opencl PUBLIC include)
target_include_directories(chunkup_opencl PRIVATE ${CHUNKUP_COMMON_DIR})

target_compile_definitions(chunkup_opencl PRIVATE
    CHUNKUP_OPENCL_KERNEL_PATH="${CMAKE_CURRENT_SOURCE_DIR}/kernels/chunkup_kernel.cl"
)

target_link_libraries(chunkup_opencl PRIVATE OpenCL::OpenCL)

if(MSVC)
    target_compile_options(chunkup_opencl PRIVATE /utf-8)
endif()

set_target_properties(chunkup_opencl PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

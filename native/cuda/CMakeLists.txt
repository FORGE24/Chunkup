cmake_minimum_required(VERSION 3.18)
project(chunkup_cuda LANGUAGES C CXX CUDA)

set(CHUNKUP_COMMON_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../common)

add_library(chunkup_cuda SHARED
    src/chunkup_cuda.cu
    ${CHUNKUP_COMMON_DIR}/chunkup_kernel_host.c
    ${CHUNKUP_COMMON_DIR}/chunkup_noise_state.c
)

target_include_directories(chunkup_cuda PUBLIC include)
target_include_directories(chunkup_cuda PRIVATE ${CHUNKUP_COMMON_DIR})

if(MSVC)
    target_compile_options(chunkup_cuda PRIVATE /utf-8)
endif()

set_target_properties(chunkup_cuda PROPERTIES
    CUDA_SEPARABLE_COMPILATION ON
    POSITION_INDEPENDENT_CODE ON
)

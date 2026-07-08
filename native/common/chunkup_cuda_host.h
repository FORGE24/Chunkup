#pragma once

#include <stddef.h>
#include <stdint.h>

#include "chunkup_export.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ChunkupCudaPinnedChunkBuffers {
    float* host_density;
    float* device_density;
    uint8_t* host_fluid;
    uint8_t* device_fluid;
    size_t block_count;
    int ready;
} ChunkupCudaPinnedChunkBuffers;

/** 确保 pinned + device 缓冲至少容纳 block_count 方块。 */
CHUNKUP_API int chunkup_cuda_pinned_ensure(ChunkupCudaPinnedChunkBuffers* buffers, size_t block_count);

CHUNKUP_API void chunkup_cuda_pinned_release(ChunkupCudaPinnedChunkBuffers* buffers);

/** D→H 到 pinned host（比 pageable 更快）。 */
CHUNKUP_API int chunkup_cuda_pinned_copy_density_d2h(
    ChunkupCudaPinnedChunkBuffers* buffers,
    size_t block_count
);

CHUNKUP_API int chunkup_cuda_pinned_copy_fluid_d2h(
    ChunkupCudaPinnedChunkBuffers* buffers,
    size_t block_count
);

#ifdef __cplusplus
}
#endif

#pragma once

#include "../common/chunkup_export.h"
#include "../common/chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

CHUNKUP_API int chunkup_cuda_is_available(void);

CHUNKUP_API int chunkup_cuda_kernel_dispatch(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
);

/** 批量 SKYLIGHT + FACE_CULL；host 缓冲按 chunk 顺序拼接。 */
CHUNKUP_API int chunkup_cuda_kernel_dispatch_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
);

/** 批量 NOISE_FILL：一次 launch 生成多 chunk 密度 + Aquifer 流体。 */
CHUNKUP_API int chunkup_cuda_density_fill_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const int32_t* chunk_xs,
    const int32_t* chunk_zs,
    float* host_density,
    uint8_t* host_fluid,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
);

#ifdef __cplusplus
}
#endif

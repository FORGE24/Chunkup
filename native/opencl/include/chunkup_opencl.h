#pragma once

#include "../common/chunkup_export.h"
#include "../common/chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

CHUNKUP_API int chunkup_opencl_is_available(void);

CHUNKUP_API int chunkup_opencl_kernel_dispatch(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
);

CHUNKUP_API int chunkup_opencl_kernel_dispatch_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
);

/** NOISE_FILL 攒批：当前走 CPU cell-fill（与单 chunk dispatch 一致），符号供回退链探测。 */
CHUNKUP_API int chunkup_opencl_density_fill_batch(
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

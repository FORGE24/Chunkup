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

#ifdef __cplusplus
}
#endif

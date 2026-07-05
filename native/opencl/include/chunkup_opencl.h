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

#ifdef __cplusplus
}
#endif

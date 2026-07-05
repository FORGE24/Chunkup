#pragma once

#include "chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 批量区块加载：host 侧 density / skylight / face_mask 按 chunk 顺序拼接。
 * template_job 提供 op_mask / min_y / height / seed；chunk_x/chunk_z 可忽略。
 */
int chunkup_kernel_dispatch_cpu_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
);

#ifdef __cplusplus
}
#endif

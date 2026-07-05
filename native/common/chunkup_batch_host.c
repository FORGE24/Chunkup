#include "chunkup_batch.h"
#include "chunkup_kernel.h"

#include <string.h>

int chunkup_kernel_dispatch_cpu_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!template_job || batch_count <= 0 || !host_density || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    for (int i = 0; i < batch_count; ++i) {
        ChunkupKernelJob job = *template_job;
        job.chunk_x = i;
        job.chunk_z = 0;

        ChunkupKernelBuffers buffers = {0};
        buffers.density = host_density + (size_t)i * blocks_per_chunk;
        buffers.skylight = host_skylight ? host_skylight + (size_t)i * blocks_per_chunk : NULL;
        buffers.face_mask = host_face_mask ? host_face_mask + (size_t)i * blocks_per_chunk : NULL;
        buffers.stride_y = CHUNKUP_BLOCKS_PER_SECTION;

        ChunkupKernelResult chunk_result = {0, 0};
        const int rc = chunkup_kernel_dispatch_cpu(&job, &buffers, &chunk_result);
        if (rc != 0) {
            return rc;
        }
        result->ops_completed |= chunk_result.ops_completed;
    }

    return 0;
}

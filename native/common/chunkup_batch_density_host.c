#include "chunkup_batch.h"
#include "chunkup_kernel.h"
#include "chunkup_noise_state.h"
#include "chunkup_kernel_algo.h"

int chunkup_kernel_dispatch_density_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const int32_t* chunk_xs,
    const int32_t* chunk_zs,
    float* host_density,
    uint8_t* host_fluid,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!template_job || batch_count <= 0 || !chunk_xs || !chunk_zs || !host_density || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    chunkup_noise_prepare(template_job->seed);

    for (int i = 0; i < batch_count; ++i) {
        const int base_x = chunk_xs[i] * (int)CHUNKUP_CHUNK_SIZE;
        const int base_z = chunk_zs[i] * (int)CHUNKUP_CHUNK_SIZE;
        float* chunk_density = host_density + (size_t)i * blocks_per_chunk;
        uint8_t* chunk_fluid = host_fluid ? host_fluid + (size_t)i * blocks_per_chunk : NULL;

        chunkup_cell_fill_chunk(
            &chunkup_active_bundle,
            base_x,
            base_z,
            template_job->min_y,
            template_job->height,
            chunk_density,
            chunk_fluid,
            CHUNKUP_BLOCKS_PER_SECTION
        );
    }

    result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    return 0;
}

#include "chunkup_kernel.h"
#include "chunkup_kernel_algo.h"

#include <string.h>

uint32_t chunkup_kernel_ops_for_stage(uint32_t stage) {
    switch (stage) {
        case CHUNKUP_STAGE_NOISE_FILL:
            return CHUNKUP_OP_NOISE_FILL;
        case CHUNKUP_STAGE_GENERATED:
            return CHUNKUP_OP_SKYLIGHT | CHUNKUP_OP_FACE_CULL;
        case CHUNKUP_STAGE_LOADED:
            return 0u;
        default:
            return 0u;
    }
}

uint32_t chunkup_kernel_density_bytes(uint32_t height) {
    return CHUNKUP_BLOCKS_PER_SECTION * height * (uint32_t)sizeof(float);
}

uint32_t chunkup_kernel_light_bytes(uint32_t height) {
    return CHUNKUP_BLOCKS_PER_SECTION * height;
}

uint32_t chunkup_kernel_face_mask_bytes(uint32_t height) {
    return CHUNKUP_BLOCKS_PER_SECTION * height;
}

static void chunkup_op_noise_fill(const ChunkupKernelJob* job, ChunkupKernelBuffers* buffers) {
    const int base_x = job->chunk_x * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = job->chunk_z * (int)CHUNKUP_CHUNK_SIZE;

    for (int ly = 0; ly < job->height; ++ly) {
        const int wy = job->min_y + ly;
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, buffers->stride_y);
                buffers->density[idx] = chunkup_density_at(base_x + lx, wy, base_z + lz, job->seed);
            }
        }
    }
}

static void chunkup_op_skylight(const ChunkupKernelJob* job, ChunkupKernelBuffers* buffers) {
    for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            int light = 15;
            for (int ly = job->height - 1; ly >= 0; --ly) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, buffers->stride_y);
                if (chunkup_is_solid(buffers->density[idx])) {
                    light = 0;
                }
                buffers->skylight[idx] = (uint8_t)light;
                if (light > 0) {
                    light -= 1;
                }
            }
        }
    }
    (void)job;
}

static void chunkup_op_blocklight(const ChunkupKernelJob* job, ChunkupKernelBuffers* buffers) {
    const uint32_t count = buffers->stride_y * (uint32_t)job->height;
    memset(buffers->blocklight, 0, count);
}

static void chunkup_op_face_cull(const ChunkupKernelJob* job, ChunkupKernelBuffers* buffers) {
    for (int ly = 0; ly < job->height; ++ly) {
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, buffers->stride_y);
                if (!chunkup_is_solid(buffers->density[idx])) {
                    buffers->face_mask[idx] = 0u;
                    continue;
                }

                uint8_t mask = 0u;
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx, ly + 1, lz, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 0;
                }
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx, ly - 1, lz, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 1;
                }
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx, ly, lz - 1, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 2;
                }
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx, ly, lz + 1, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 3;
                }
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx - 1, ly, lz, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 4;
                }
                if (!chunkup_is_solid(chunkup_density_sample(buffers->density, lx + 1, ly, lz, (uint32_t)job->height, buffers->stride_y))) {
                    mask |= 1u << 5;
                }
                buffers->face_mask[idx] = mask;
            }
        }
    }
}

int chunkup_kernel_dispatch_cpu(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
) {
    if (!job || !buffers || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            return -2;
        }
        chunkup_op_noise_fill(job, buffers);
        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            return -3;
        }
        chunkup_op_skylight(job, buffers);
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        if (!buffers->blocklight) {
            return -4;
        }
        chunkup_op_blocklight(job, buffers);
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        if (!buffers->density || !buffers->face_mask) {
            return -5;
        }
        chunkup_op_face_cull(job, buffers);
        result->ops_completed |= CHUNKUP_OP_FACE_CULL;
    }

    return 0;
}

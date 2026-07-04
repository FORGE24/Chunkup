#include "../common/chunkup_kernel_algo.h"

#include <cuda_runtime.h>

__global__ void chunkup_kernel_noise_fill(
    float* density,
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint32_t seed,
    uint32_t stride_y
) {
    const int lx = (int)(blockIdx.x * blockDim.x + threadIdx.x);
    const int lz = (int)(blockIdx.y * blockDim.y + threadIdx.y);
    const int ly = (int)(blockIdx.z * blockDim.z + threadIdx.z);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const int wy = min_y + ly;
    const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
    density[idx] = chunkup_density_at(base_x + lx, wy, base_z + lz, seed);
}

__global__ void chunkup_kernel_skylight(
    const float* density,
    uint8_t* skylight,
    int height,
    uint32_t stride_y
) {
    const int lx = (int)(blockIdx.x * blockDim.x + threadIdx.x);
    const int lz = (int)(blockIdx.y * blockDim.y + threadIdx.y);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
        if (chunkup_is_solid(density[idx])) {
            light = 0;
        }
        skylight[idx] = (uint8_t)light;
        if (light > 0) {
            light -= 1;
        }
    }
}

#include "chunkup_cuda.h"

extern "C" int chunkup_cuda_is_available(void) {
    int device_count = 0;
    cudaError_t err = cudaGetDeviceCount(&device_count);
    return (err == cudaSuccess && device_count > 0) ? 1 : 0;
}

extern "C" int chunkup_cuda_kernel_dispatch(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
) {
    if (!job || !buffers || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const int base_x = job->chunk_x * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = job->chunk_z * (int)CHUNKUP_CHUNK_SIZE;
    const dim3 block(4, 4, 4);
    const dim3 grid(
        (CHUNKUP_CHUNK_SIZE + block.x - 1) / block.x,
        (CHUNKUP_CHUNK_SIZE + block.y - 1) / block.y,
        ((uint32_t)job->height + block.z - 1) / block.z
    );

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            return -2;
        }
        chunkup_kernel_noise_fill<<<grid, block>>>(
            buffers->density,
            base_x,
            base_z,
            job->min_y,
            job->height,
            job->seed,
            buffers->stride_y
        );
        cudaDeviceSynchronize();
        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            return -3;
        }
        const dim3 light_block(4, 4, 1);
        const dim3 light_grid(
            (CHUNKUP_CHUNK_SIZE + light_block.x - 1) / light_block.x,
            (CHUNKUP_CHUNK_SIZE + light_block.y - 1) / light_block.y,
            1
        );
        chunkup_kernel_skylight<<<light_grid, light_block>>>(
            buffers->density,
            buffers->skylight,
            job->height,
            buffers->stride_y
        );
        cudaDeviceSynchronize();
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        const uint32_t count = buffers->stride_y * (uint32_t)job->height;
        cudaMemset(buffers->blocklight, 0, count);
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        ChunkupKernelResult cpu_result = {0, 0};
        const int rc = chunkup_kernel_dispatch_cpu(job, buffers, &cpu_result);
        result->ops_completed |= cpu_result.ops_completed;
        if (rc != 0) {
            return rc;
        }
    }

    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}

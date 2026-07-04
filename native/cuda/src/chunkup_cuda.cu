#include "../common/chunkup_kernel_algo.h"
#include "../common/chunkup_cell_fill.h"

#include <cuda_runtime.h>

__global__ void chunkup_kernel_cell_fill(
    float* density,
    uint8_t* fluid,
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint32_t stride_y
) {
    if (blockIdx.x != 0 || blockIdx.y != 0 || blockIdx.z != 0) {
        return;
    }
    if (threadIdx.x != 0 || threadIdx.y != 0 || threadIdx.z != 0) {
        return;
    }

    chunkup_cell_fill_chunk(
        &chunkup_device_bundle,
        base_x,
        base_z,
        min_y,
        height,
        density,
        fluid,
        stride_y
    );
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

static int chunkup_cuda_check(cudaError_t err) {
    return err == cudaSuccess ? 0 : -10;
}

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

    if (job->op_mask & (CHUNKUP_OP_NOISE_FILL | CHUNKUP_OP_SKYLIGHT | CHUNKUP_OP_FACE_CULL)) {
        ChunkupNoiseBundle bundle;
        chunkup_noise_init_bundle(&bundle, job->seed);
        if (chunkup_cuda_check(cudaMemcpyToSymbol(
                chunkup_device_bundle,
                &bundle,
                sizeof(ChunkupNoiseBundle),
                0,
                cudaMemcpyHostToDevice)) != 0) {
            return -11;
        }
    }

    const int base_x = job->chunk_x * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = job->chunk_z * (int)CHUNKUP_CHUNK_SIZE;
    const uint32_t block_count = buffers->stride_y * (uint32_t)job->height;
    const size_t density_bytes = (size_t)block_count * sizeof(float);
    const size_t fluid_bytes = (size_t)block_count;
    const size_t light_bytes = (size_t)block_count;

    float* d_density = NULL;
    uint8_t* d_fluid = NULL;
    uint8_t* d_skylight = NULL;
    uint8_t* d_blocklight = NULL;

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            return -2;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
            return -10;
        }
        if (buffers->fluid) {
            if (chunkup_cuda_check(cudaMalloc(&d_fluid, fluid_bytes)) != 0) {
                cudaFree(d_density);
                return -10;
            }
        }
        chunkup_kernel_cell_fill<<<dim3(1, 1, 1), dim3(1, 1, 1)>>>(
            d_density,
            d_fluid,
            base_x,
            base_z,
            job->min_y,
            job->height,
            buffers->stride_y
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->density, d_density, density_bytes, cudaMemcpyDeviceToHost)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            return -10;
        }
        if (buffers->fluid && d_fluid) {
            if (chunkup_cuda_check(cudaMemcpy(buffers->fluid, d_fluid, fluid_bytes, cudaMemcpyDeviceToHost)) != 0) {
                cudaFree(d_density);
                cudaFree(d_fluid);
                return -10;
            }
        }
        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            return -3;
        }
        if (!d_density) {
            if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
                return -10;
            }
            if (chunkup_cuda_check(cudaMemcpy(d_density, buffers->density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
                cudaFree(d_density);
                return -10;
            }
        }
        if (chunkup_cuda_check(cudaMalloc(&d_skylight, light_bytes)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            return -10;
        }
        const dim3 light_block(4, 4, 1);
        const dim3 light_grid(
            (CHUNKUP_CHUNK_SIZE + light_block.x - 1) / light_block.x,
            (CHUNKUP_CHUNK_SIZE + light_block.y - 1) / light_block.y,
            1
        );
        chunkup_kernel_skylight<<<light_grid, light_block>>>(
            d_density,
            d_skylight,
            job->height,
            buffers->stride_y
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->skylight, d_skylight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        if (!buffers->blocklight) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            return -4;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_blocklight, light_bytes)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemset(d_blocklight, 0, light_bytes)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            cudaFree(d_blocklight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->blocklight, d_blocklight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            cudaFree(d_density);
            cudaFree(d_fluid);
            cudaFree(d_skylight);
            cudaFree(d_blocklight);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        ChunkupKernelResult cpu_result = {0, 0};
        const int rc = chunkup_kernel_dispatch_cpu(job, buffers, &cpu_result);
        result->ops_completed |= cpu_result.ops_completed;
        cudaFree(d_density);
        cudaFree(d_fluid);
        cudaFree(d_skylight);
        cudaFree(d_blocklight);
        if (rc != 0) {
            return rc;
        }
        return cudaGetLastError() == cudaSuccess ? 0 : -10;
    }

    cudaFree(d_density);
    cudaFree(d_fluid);
    cudaFree(d_skylight);
    cudaFree(d_blocklight);
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}

#include "../common/chunkup_kernel_algo.h"

#include <cuda_runtime.h>

#ifdef __CUDACC__
__device__ __forceinline__ uint32_t chunkup_cuda_block_index(int lx, int ly, int lz, uint32_t stride_y) {
    return (uint32_t)ly * stride_y + (uint32_t)lz * CHUNKUP_CHUNK_SIZE + (uint32_t)lx;
}

/**
 * 密度 + Aquifer fluid：每 (lx,lz) 列一线程，共享 5×5 router cell 缓存。
 * Grid <<<1, (16,16)>>> — 单 chunk。
 */
__global__ void chunkup_kernel_density_fill(
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint32_t stride_y,
    float* density,
    uint8_t* fluid
) {
    __shared__ ChunkupCellCache2D cell_cache;

    const int tid = (int)(threadIdx.y * blockDim.x + threadIdx.x);
    if (tid < 25) {
        const int ci = tid / 5;
        const int cj = tid % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        cell_cache.samples[ci][cj] = chunkup_router_sample_2d(&chunkup_device_bundle, wx, wz);
    }
    __syncthreads();

    const int lx = (int)threadIdx.x;
    const int lz = (int)threadIdx.y;
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    for (int ly = 0; ly < height; ++ly) {
        const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
        const float wx = (float)(base_x + lx);
        const float wy = (float)(min_y + ly);
        const float wz = (float)(base_z + lz);

        const float d = chunkup_cell_interpolated_density(
            &chunkup_device_bundle,
            &cell_cache,
            base_x,
            base_z,
            min_y,
            lx,
            ly,
            lz
        );
        density[idx] = d;
        if (fluid) {
            fluid[idx] = chunkup_router_aquifer_fluid(&chunkup_device_bundle, wx, wy, wz, d);
        }
    }
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
        const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
        if (chunkup_is_solid(density[idx])) {
            light = 0;
        }
        skylight[idx] = (uint8_t)light;
        if (light > 0) {
            light -= 1;
        }
    }
}
#endif

#include "chunkup_cuda.h"

static int chunkup_cuda_check(cudaError_t err) {
    return err == cudaSuccess ? 0 : -10;
}

static void chunkup_cuda_free_buffers(float* d_density, uint8_t* d_fluid, uint8_t* d_skylight, uint8_t* d_blocklight) {
    if (d_density) {
        cudaFree(d_density);
    }
    if (d_fluid) {
        cudaFree(d_fluid);
    }
    if (d_skylight) {
        cudaFree(d_skylight);
    }
    if (d_blocklight) {
        cudaFree(d_blocklight);
    }
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

    const int base_x = job->chunk_x * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = job->chunk_z * (int)CHUNKUP_CHUNK_SIZE;
    const uint32_t block_count = buffers->stride_y * (uint32_t)job->height;
    const size_t density_bytes = (size_t)block_count * sizeof(float);
    const size_t light_bytes = (size_t)block_count;

    float* d_density = NULL;
    uint8_t* d_fluid = NULL;
    uint8_t* d_skylight = NULL;
    uint8_t* d_blocklight = NULL;

    const int needs_density_dev =
        (job->op_mask & (CHUNKUP_OP_NOISE_FILL | CHUNKUP_OP_SKYLIGHT)) != 0;

    if (needs_density_dev) {
        if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
            return -10;
        }
    }

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -2;
        }

        if (buffers->fluid) {
            if (chunkup_cuda_check(cudaMalloc(&d_fluid, light_bytes)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
                return -10;
            }
        }

        ChunkupNoiseBundle host_bundle;
        chunkup_noise_init_bundle(&host_bundle, job->seed);
        if (chunkup_cuda_check(cudaMemcpyToSymbol(chunkup_device_bundle, &host_bundle, sizeof(host_bundle))) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }

        chunkup_kernel_density_fill<<<1, dim3(16, 16, 1)>>>(
            base_x,
            base_z,
            job->min_y,
            job->height,
            buffers->stride_y,
            d_density,
            d_fluid
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }

        if (chunkup_cuda_check(cudaMemcpy(buffers->density, d_density, density_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        if (buffers->fluid && d_fluid) {
            if (chunkup_cuda_check(cudaMemcpy(buffers->fluid, d_fluid, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
                return -10;
            }
        }

        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -3;
        }
        if (!(job->op_mask & CHUNKUP_OP_NOISE_FILL)) {
            if (!d_density) {
                if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
                    chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
                    return -10;
                }
            }
            if (chunkup_cuda_check(cudaMemcpy(d_density, buffers->density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
                return -10;
            }
        }
        if (chunkup_cuda_check(cudaMalloc(&d_skylight, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
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
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->skylight, d_skylight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        if (!buffers->blocklight) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -4;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_blocklight, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemset(d_blocklight, 0, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->blocklight, d_blocklight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        ChunkupKernelResult cpu_result = {0, 0};
        const int rc = chunkup_kernel_dispatch_cpu(job, buffers, &cpu_result);
        result->ops_completed |= cpu_result.ops_completed;
        chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
        if (rc != 0) {
            return rc;
        }
        return cudaGetLastError() == cudaSuccess ? 0 : -10;
    }

    chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight);
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}
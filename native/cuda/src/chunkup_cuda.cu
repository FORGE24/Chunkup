//遵守Apache License 2.0
/*  *
 * Copyright 2026 FORGE24
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
/**
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

/**
 * 批量 NOISE_FILL：grid.z = batch_count，每 chunk 一块 (16×16) 线程。
 */
__global__ void chunkup_kernel_density_fill_batch(
    const int32_t* chunk_xs,
    const int32_t* chunk_zs,
    int min_y,
    int height,
    uint32_t stride_y,
    uint32_t blocks_per_chunk,
    float* density,
    uint8_t* fluid,
    int batch_count
) {
    const int chunk_idx = (int)blockIdx.z;
    if (chunk_idx >= batch_count) {
        return;
    }

    __shared__ ChunkupCellCache2D cell_cache;

    const int base_x = chunk_xs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = chunk_zs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    float* chunk_density = density + (size_t)chunk_idx * blocks_per_chunk;
    uint8_t* chunk_fluid = fluid ? fluid + (size_t)chunk_idx * blocks_per_chunk : NULL;

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
        chunk_density[idx] = d;
        if (chunk_fluid) {
            chunk_fluid[idx] = chunkup_router_aquifer_fluid(&chunkup_device_bundle, wx, wy, wz, d);
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
        const float sample = density[idx];
        if (chunkup_skylight_opacity(sample) >= 15) {
            light = 0;
        }
        skylight[idx] = (uint8_t)light;
        light = chunkup_skylight_propagate(light, sample);
    }
}

__global__ void chunkup_kernel_face_cull(
    const float* density,
    uint8_t* face_mask,
    int height,
    uint32_t stride_y
) {
    const int lx = (int)(blockIdx.x * blockDim.x + threadIdx.x);
    const int ly = (int)(blockIdx.y * blockDim.y + threadIdx.y);
    const int lz = (int)(blockIdx.z * blockDim.z + threadIdx.z);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
    if (!chunkup_is_solid(density[idx])) {
        face_mask[idx] = 0u;
        return;
    }

    uint8_t mask = 0u;
    if (!chunkup_is_solid(chunkup_density_sample(density, lx, ly + 1, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 0;
    }
    if (!chunkup_is_solid(chunkup_density_sample(density, lx, ly - 1, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 1;
    }
    if (!chunkup_is_solid(chunkup_density_sample(density, lx, ly, lz - 1, (uint32_t)height, stride_y))) {
        mask |= 1u << 2;
    }
    if (!chunkup_is_solid(chunkup_density_sample(density, lx, ly, lz + 1, (uint32_t)height, stride_y))) {
        mask |= 1u << 3;
    }
    if (!chunkup_is_solid(chunkup_density_sample(density, lx - 1, ly, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 4;
    }
    if (!chunkup_is_solid(chunkup_density_sample(density, lx + 1, ly, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 5;
    }
    face_mask[idx] = mask;
}

__global__ void chunkup_kernel_skylight_batch(
    const float* density,
    uint8_t* skylight,
    int height,
    uint32_t stride_y,
    uint32_t blocks_per_chunk,
    int batch_count
) {
    const int chunk_idx = (int)blockIdx.z;
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)(blockIdx.x * blockDim.x + threadIdx.x);
    const int lz = (int)(blockIdx.y * blockDim.y + threadIdx.y);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    const float* chunk_density = density + (size_t)chunk_idx * blocks_per_chunk;
    uint8_t* chunk_light = skylight + (size_t)chunk_idx * blocks_per_chunk;

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
        const float sample = chunk_density[idx];
        if (chunkup_skylight_opacity(sample) >= 15) {
            light = 0;
        }
        chunk_light[idx] = (uint8_t)light;
        light = chunkup_skylight_propagate(light, sample);
    }
}

__global__ void chunkup_kernel_face_cull_batch(
    const float* density,
    uint8_t* face_mask,
    int height,
    uint32_t stride_y,
    uint32_t blocks_per_chunk,
    int batch_count
) {
    const int flat_z = (int)blockIdx.z;
    const int chunk_idx = flat_z / height;
    const int ly = flat_z % height;
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)(blockIdx.x * blockDim.x + threadIdx.x);
    const int lz = (int)(blockIdx.y * blockDim.y + threadIdx.y);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const float* chunk_density = density + (size_t)chunk_idx * blocks_per_chunk;
    uint8_t* chunk_faces = face_mask + (size_t)chunk_idx * blocks_per_chunk;

    const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
    if (!chunkup_is_solid(chunk_density[idx])) {
        chunk_faces[idx] = 0u;
        return;
    }

    uint8_t mask = 0u;
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx, ly + 1, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 0;
    }
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx, ly - 1, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 1;
    }
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx, ly, lz - 1, (uint32_t)height, stride_y))) {
        mask |= 1u << 2;
    }
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx, ly, lz + 1, (uint32_t)height, stride_y))) {
        mask |= 1u << 3;
    }
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx - 1, ly, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 4;
    }
    if (!chunkup_is_solid(chunkup_density_sample(chunk_density, lx + 1, ly, lz, (uint32_t)height, stride_y))) {
        mask |= 1u << 5;
    }
    chunk_faces[idx] = mask;
}
#endif

#include "chunkup_cuda.h"

static int chunkup_cuda_check(cudaError_t err) {
    return err == cudaSuccess ? 0 : -10;
}

static void chunkup_cuda_free_buffers(
    float* d_density,
    uint8_t* d_fluid,
    uint8_t* d_skylight,
    uint8_t* d_blocklight,
    uint8_t* d_face_mask
) {
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
    if (d_face_mask) {
        cudaFree(d_face_mask);
    }
}

extern "C" CHUNKUP_API int chunkup_cuda_is_available(void) {
    int device_count = 0;
    cudaError_t err = cudaGetDeviceCount(&device_count);
    return (err == cudaSuccess && device_count > 0) ? 1 : 0;
}

extern "C" CHUNKUP_API int chunkup_cuda_kernel_dispatch(
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
    uint8_t* d_face_mask = NULL;

    const int needs_density_dev =
        (job->op_mask & (CHUNKUP_OP_NOISE_FILL | CHUNKUP_OP_SKYLIGHT | CHUNKUP_OP_FACE_CULL)) != 0;

    if (needs_density_dev) {
        if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
            return -10;
        }
    }

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -2;
        }

        if (buffers->fluid) {
            if (chunkup_cuda_check(cudaMalloc(&d_fluid, light_bytes)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                return -10;
            }
        }

        ChunkupNoiseBundle host_bundle;
        chunkup_noise_init_bundle(&host_bundle, job->seed);
        if (chunkup_cuda_check(cudaMemcpyToSymbol(chunkup_device_bundle, &host_bundle, sizeof(host_bundle))) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
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
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }

        if (chunkup_cuda_check(cudaMemcpy(buffers->density, d_density, density_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        if (buffers->fluid && d_fluid) {
            if (chunkup_cuda_check(cudaMemcpy(buffers->fluid, d_fluid, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                return -10;
            }
        }

        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -3;
        }
        if (!(job->op_mask & CHUNKUP_OP_NOISE_FILL)) {
            if (!d_density) {
                if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
                    chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                    return -10;
                }
            }
            if (chunkup_cuda_check(cudaMemcpy(d_density, buffers->density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                return -10;
            }
        }
        if (chunkup_cuda_check(cudaMalloc(&d_skylight, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
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
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->skylight, d_skylight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        if (!buffers->blocklight) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -4;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_blocklight, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemset(d_blocklight, 0, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->blocklight, d_blocklight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        if (!buffers->density || !buffers->face_mask) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -5;
        }
        if (!d_density) {
            if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                return -10;
            }
            if (chunkup_cuda_check(cudaMemcpy(d_density, buffers->density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
                return -10;
            }
        }
        if (chunkup_cuda_check(cudaMalloc(&d_face_mask, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }

        const dim3 cull_block(4, 4, 4);
        const dim3 cull_grid(
            (CHUNKUP_CHUNK_SIZE + cull_block.x - 1) / cull_block.x,
            ((uint32_t)job->height + cull_block.y - 1) / cull_block.y,
            (CHUNKUP_CHUNK_SIZE + cull_block.z - 1) / cull_block.z
        );
        chunkup_kernel_face_cull<<<cull_grid, cull_block>>>(
            d_density,
            d_face_mask,
            job->height,
            buffers->stride_y
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->face_mask, d_face_mask, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_FACE_CULL;
    }

    chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}

extern "C" CHUNKUP_API int chunkup_cuda_kernel_dispatch_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!template_job || batch_count <= 0 || !host_density || !result || blocks_per_chunk == 0u) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const size_t density_bytes = (size_t)batch_count * blocks_per_chunk * sizeof(float);
    const size_t light_bytes = (size_t)batch_count * blocks_per_chunk;

    float* d_density = NULL;
    uint8_t* d_skylight = NULL;
    uint8_t* d_face_mask = NULL;

    if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
        return -10;
    }
    if (chunkup_cuda_check(cudaMemcpy(d_density, host_density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
        chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
        return -10;
    }

    const dim3 light_block(4, 4, 1);
    const dim3 light_grid(
        (CHUNKUP_CHUNK_SIZE + light_block.x - 1) / light_block.x,
        (CHUNKUP_CHUNK_SIZE + light_block.y - 1) / light_block.y,
        (unsigned int)batch_count
    );

    if (template_job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!host_skylight) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -3;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_skylight, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }

        chunkup_kernel_skylight_batch<<<light_grid, light_block>>>(
            d_density,
            d_skylight,
            template_job->height,
            CHUNKUP_BLOCKS_PER_SECTION,
            blocks_per_chunk,
            batch_count
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(host_skylight, d_skylight, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (template_job->op_mask & CHUNKUP_OP_FACE_CULL) {
        if (!host_face_mask) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -5;
        }
        if (chunkup_cuda_check(cudaMalloc(&d_face_mask, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }

        const dim3 cull_block(4, 4, 1);
        const dim3 cull_grid(
            (CHUNKUP_CHUNK_SIZE + cull_block.x - 1) / cull_block.x,
            (CHUNKUP_CHUNK_SIZE + cull_block.y - 1) / cull_block.y,
            (unsigned int)batch_count * (unsigned int)template_job->height
        );
        chunkup_kernel_face_cull_batch<<<cull_grid, cull_block>>>(
            d_density,
            d_face_mask,
            template_job->height,
            CHUNKUP_BLOCKS_PER_SECTION,
            blocks_per_chunk,
            batch_count
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(host_face_mask, d_face_mask, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
            return -10;
        }
        result->ops_completed |= CHUNKUP_OP_FACE_CULL;
    }

    chunkup_cuda_free_buffers(d_density, d_skylight, NULL, NULL, d_face_mask);
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}

extern "C" CHUNKUP_API int chunkup_cuda_density_fill_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const int32_t* chunk_xs,
    const int32_t* chunk_zs,
    float* host_density,
    uint8_t* host_fluid,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!template_job || batch_count <= 0 || !chunk_xs || !chunk_zs || !host_density || !result || blocks_per_chunk == 0u) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const size_t density_bytes = (size_t)batch_count * blocks_per_chunk * sizeof(float);
    const size_t light_bytes = (size_t)batch_count * blocks_per_chunk;

    float* d_density = NULL;
    uint8_t* d_fluid = NULL;
    int32_t* d_chunk_xs = NULL;
    int32_t* d_chunk_zs = NULL;

    if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
        return -10;
    }
    if (host_fluid) {
        if (chunkup_cuda_check(cudaMalloc(&d_fluid, light_bytes)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
            return -10;
        }
    }
    if (chunkup_cuda_check(cudaMalloc(&d_chunk_xs, (size_t)batch_count * sizeof(int32_t))) != 0 ||
        chunkup_cuda_check(cudaMalloc(&d_chunk_zs, (size_t)batch_count * sizeof(int32_t))) != 0) {
        chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
        if (d_chunk_xs) cudaFree(d_chunk_xs);
        if (d_chunk_zs) cudaFree(d_chunk_zs);
        return -10;
    }

    if (chunkup_cuda_check(cudaMemcpy(d_chunk_xs, chunk_xs, (size_t)batch_count * sizeof(int32_t), cudaMemcpyHostToDevice)) != 0 ||
        chunkup_cuda_check(cudaMemcpy(d_chunk_zs, chunk_zs, (size_t)batch_count * sizeof(int32_t), cudaMemcpyHostToDevice)) != 0) {
        chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
        cudaFree(d_chunk_xs);
        cudaFree(d_chunk_zs);
        return -10;
    }

    ChunkupNoiseBundle host_bundle;
    chunkup_noise_init_bundle(&host_bundle, template_job->seed);
    if (chunkup_cuda_check(cudaMemcpyToSymbol(chunkup_device_bundle, &host_bundle, sizeof(host_bundle))) != 0) {
        chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
        cudaFree(d_chunk_xs);
        cudaFree(d_chunk_zs);
        return -10;
    }

    const dim3 block(16, 16, 1);
    const dim3 grid(1, 1, (unsigned int)batch_count);
    chunkup_kernel_density_fill_batch<<<grid, block>>>(
        d_chunk_xs,
        d_chunk_zs,
        template_job->min_y,
        template_job->height,
        CHUNKUP_BLOCKS_PER_SECTION,
        blocks_per_chunk,
        d_density,
        d_fluid,
        batch_count
    );
    if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
        chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
        chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
        cudaFree(d_chunk_xs);
        cudaFree(d_chunk_zs);
        return -10;
    }

    if (chunkup_cuda_check(cudaMemcpy(host_density, d_density, density_bytes, cudaMemcpyDeviceToHost)) != 0) {
        chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
        cudaFree(d_chunk_xs);
        cudaFree(d_chunk_zs);
        return -10;
    }
    if (host_fluid && d_fluid) {
        if (chunkup_cuda_check(cudaMemcpy(host_fluid, d_fluid, light_bytes, cudaMemcpyDeviceToHost)) != 0) {
            chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
            cudaFree(d_chunk_xs);
            cudaFree(d_chunk_zs);
            return -10;
        }
    }

    cudaFree(d_chunk_xs);
    cudaFree(d_chunk_zs);
    chunkup_cuda_free_buffers(d_density, d_fluid, NULL, NULL, NULL);
    result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}
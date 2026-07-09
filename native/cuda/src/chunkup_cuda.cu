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
#include "../common/chunkup_cuda_host.h"
#include "../common/chunkup_surface.h"
#include "../common/chunkup_sl_log.h"

#include <cuda_runtime.h>
#include <stdio.h>

static ChunkupCudaPinnedChunkBuffers g_chunkup_pinned_single = {0};
static ChunkupCudaPinnedChunkBuffers g_chunkup_pinned_batch = {0};
static int32_t* g_chunkup_d_chunk_xs = NULL;
static int32_t* g_chunkup_d_chunk_zs = NULL;
static int g_chunkup_d_chunk_coords_cap = 0;

static int chunkup_cuda_ensure_chunk_coords(int batch_count) {
    if (batch_count <= g_chunkup_d_chunk_coords_cap) {
        return 0;
    }
    if (g_chunkup_d_chunk_xs) {
        cudaFree(g_chunkup_d_chunk_xs);
    }
    if (g_chunkup_d_chunk_zs) {
        cudaFree(g_chunkup_d_chunk_zs);
    }
    g_chunkup_d_chunk_xs = NULL;
    g_chunkup_d_chunk_zs = NULL;
    g_chunkup_d_chunk_coords_cap = 0;

    if (chunkup_cuda_check(cudaMalloc(&g_chunkup_d_chunk_xs, (size_t)batch_count * sizeof(int32_t))) != 0 ||
        chunkup_cuda_check(cudaMalloc(&g_chunkup_d_chunk_zs, (size_t)batch_count * sizeof(int32_t))) != 0) {
        if (g_chunkup_d_chunk_xs) {
            cudaFree(g_chunkup_d_chunk_xs);
        }
        if (g_chunkup_d_chunk_zs) {
            cudaFree(g_chunkup_d_chunk_zs);
        }
        g_chunkup_d_chunk_xs = NULL;
        g_chunkup_d_chunk_zs = NULL;
        return -10;
    }
    g_chunkup_d_chunk_coords_cap = batch_count;
    return 0;
}

#define CHUNKUP_CUDA_Y_TILE 4u

#ifdef __CUDACC__
__device__ __forceinline__ uint32_t chunkup_cuda_block_index(int lx, int ly, int lz, uint32_t stride_y) {
    return (uint32_t)ly * stride_y + (uint32_t)lz * CHUNKUP_CHUNK_SIZE + (uint32_t)lx;
}

/**
 * 密度 + Aquifer fluid：Y 维并行（block.z=4），提高 SM 占用。
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

    const int lx = (int)threadIdx.x;
    const int lz = (int)threadIdx.y;
    const int ly = (int)blockIdx.z * (int)blockDim.z + (int)threadIdx.z;

    const int tid = (int)(threadIdx.z * blockDim.y * blockDim.x + threadIdx.y * blockDim.x + threadIdx.x);
    if (tid < 25) {
        const int ci = tid / 5;
        const int cj = tid % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        cell_cache.samples[ci][cj] = chunkup_router_sample_2d(&chunkup_device_bundle, wx, wz);
    }
    __syncthreads();

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

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

/**
 * 批量 NOISE_FILL：grid.z = z_slices * batch_count，Y 维并行。
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
    const int z_slices = (height + (int)CHUNKUP_CUDA_Y_TILE - 1) / (int)CHUNKUP_CUDA_Y_TILE;
    const int chunk_idx = (int)blockIdx.z / z_slices;
    if (chunk_idx >= batch_count) {
        return;
    }

    __shared__ ChunkupCellCache2D cell_cache;

    const int base_x = chunk_xs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = chunk_zs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    float* chunk_density = density + (size_t)chunk_idx * blocks_per_chunk;
    uint8_t* chunk_fluid = fluid ? fluid + (size_t)chunk_idx * blocks_per_chunk : NULL;

    const int lx = (int)threadIdx.x;
    const int lz = (int)threadIdx.y;
    const int ly = (int)((blockIdx.z % (unsigned int)z_slices) * blockDim.z + threadIdx.z);

    const int tid = (int)(threadIdx.z * blockDim.y * blockDim.x + threadIdx.y * blockDim.x + threadIdx.x);
    if (tid < 25) {
        const int ci = tid / 5;
        const int cj = tid % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        cell_cache.samples[ci][cj] = chunkup_router_sample_2d(&chunkup_device_bundle, wx, wz);
    }
    __syncthreads();

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

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

__global__ void chunkup_kernel_surface_thin(
    const float* density,
    const uint8_t* biome_kind,
    int height,
    uint32_t stride_y,
    uint8_t* surface_layers
) {
    const int lx = (int)threadIdx.x;
    const int lz = (int)threadIdx.y;
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    const int col = lz * (int)CHUNKUP_CHUNK_SIZE + lx;
    const uint32_t base = (uint32_t)col * CHUNKUP_SURFACE_LAYERS;
    surface_layers[base + 0] = CHUNKUP_SURFACE_SKIP;
    surface_layers[base + 1] = CHUNKUP_SURFACE_SKIP;
    surface_layers[base + 2] = CHUNKUP_SURFACE_SKIP;
    surface_layers[base + 3] = CHUNKUP_SURFACE_SKIP;

    int top_ly = -1;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint32_t idx = chunkup_cuda_block_index(lx, ly, lz, stride_y);
        if (density[idx] > 0.0f) {
            top_ly = ly;
            break;
        }
    }
    if (top_ly < 0) {
        return;
    }
    (void)top_ly;

    const uint8_t kind = biome_kind ? biome_kind[col] : CHUNKUP_BIOME_DEFAULT;
    uint8_t top = CHUNKUP_SURFACE_GRASS;
    uint8_t mid = CHUNKUP_SURFACE_DIRT;
    uint8_t deep = CHUNKUP_SURFACE_DIRT;
    uint8_t bottom = CHUNKUP_SURFACE_STONE;

    if (kind == CHUNKUP_BIOME_DESERT || kind == CHUNKUP_BIOME_BADLANDS) {
        top = CHUNKUP_SURFACE_SAND;
        mid = CHUNKUP_SURFACE_SAND;
        deep = CHUNKUP_SURFACE_SAND;
    } else if (kind == CHUNKUP_BIOME_SNOW) {
        top = CHUNKUP_SURFACE_SNOW;
        mid = CHUNKUP_SURFACE_DIRT;
    } else if (kind == CHUNKUP_BIOME_BEACH) {
        top = CHUNKUP_SURFACE_SAND;
        mid = CHUNKUP_SURFACE_SAND;
        deep = CHUNKUP_SURFACE_GRAVEL;
    }

    surface_layers[base + 0] = top;
    surface_layers[base + 1] = mid;
    surface_layers[base + 2] = deep;
    surface_layers[base + 3] = bottom;
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

#include <string.h>

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
    if (err != cudaSuccess || device_count <= 0) {
        return 0;
    }
    cudaDeviceProp prop{};
    if (cudaGetDeviceProperties(&prop, 0) == cudaSuccess) {
        char params[256];
        snprintf(
            params,
            sizeof(params),
            "DeviceCount=%d,DeviceName=%s,SMCount=%d,MaxThreadsPerBlock=%d",
            device_count,
            prop.name,
            prop.multiProcessorCount,
            prop.maxThreadsPerBlock
        );
        CHUNKUP_SL_INFO_INIT("CUDA Probe Module", "CUDA device probe succeeded", params);
    }
    return 1;
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
        (job->op_mask & (CHUNKUP_OP_NOISE_FILL | CHUNKUP_OP_SKYLIGHT | CHUNKUP_OP_FACE_CULL | CHUNKUP_OP_SURFACE_THIN)) != 0;

    const int use_pinned_noise = (job->op_mask & CHUNKUP_OP_NOISE_FILL) != 0;
    if (use_pinned_noise) {
        if (chunkup_cuda_pinned_ensure(&g_chunkup_pinned_single, block_count) != 0) {
            return -10;
        }
        d_density = g_chunkup_pinned_single.device_density;
        d_fluid = g_chunkup_pinned_single.device_fluid;
    } else if (needs_density_dev) {
        if (chunkup_cuda_check(cudaMalloc(&d_density, density_bytes)) != 0) {
            return -10;
        }
    }

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            if (!use_pinned_noise) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            }
            return -2;
        }

        ChunkupNoiseBundle host_bundle;
        chunkup_noise_init_bundle(&host_bundle, job->seed);
        if (chunkup_cuda_check(cudaMemcpyToSymbol(chunkup_device_bundle, &host_bundle, sizeof(host_bundle))) != 0) {
            if (!use_pinned_noise) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            }
            return -10;
        }

        const dim3 fill_block(16, 16, CHUNKUP_CUDA_Y_TILE);
        const dim3 fill_grid(
            1,
            1,
            (unsigned int)((job->height + (int)CHUNKUP_CUDA_Y_TILE - 1) / (int)CHUNKUP_CUDA_Y_TILE)
        );
        chunkup_kernel_density_fill<<<fill_grid, fill_block>>>(
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
            if (!use_pinned_noise) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            }
            return -10;
        }

        if (chunkup_cuda_pinned_copy_density_d2h(&g_chunkup_pinned_single, block_count) != 0) {
            return -10;
        }
        if (buffers->fluid && chunkup_cuda_pinned_copy_fluid_d2h(&g_chunkup_pinned_single, block_count) != 0) {
            return -10;
        }
        memcpy(buffers->density, g_chunkup_pinned_single.host_density, density_bytes);
        if (buffers->fluid) {
            memcpy(buffers->fluid, g_chunkup_pinned_single.host_fluid, light_bytes);
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

    if (job->op_mask & CHUNKUP_OP_SURFACE_THIN) {
        if (!buffers->density || !buffers->surface_layers) {
            if (!use_pinned_noise && d_density) {
                chunkup_cuda_free_buffers(d_density, d_fluid, d_skylight, d_blocklight, d_face_mask);
            }
            return -6;
        }
        if (!d_density) {
            if (chunkup_cuda_pinned_ensure(&g_chunkup_pinned_single, block_count) != 0) {
                return -10;
            }
            d_density = g_chunkup_pinned_single.device_density;
            if (chunkup_cuda_check(cudaMemcpy(d_density, buffers->density, density_bytes, cudaMemcpyHostToDevice)) != 0) {
                return -10;
            }
        }

        uint8_t* d_biome = NULL;
        uint8_t* d_surface = NULL;
        const size_t surface_bytes = (size_t)CHUNKUP_BLOCKS_PER_SECTION * CHUNKUP_SURFACE_LAYERS;
        if (chunkup_cuda_check(cudaMalloc(&d_biome, CHUNKUP_BLOCKS_PER_SECTION)) != 0 ||
            chunkup_cuda_check(cudaMalloc(&d_surface, surface_bytes)) != 0) {
            if (d_biome) cudaFree(d_biome);
            if (d_surface) cudaFree(d_surface);
            return -10;
        }
        if (buffers->biome_kind) {
            if (chunkup_cuda_check(cudaMemcpy(d_biome, buffers->biome_kind, CHUNKUP_BLOCKS_PER_SECTION, cudaMemcpyHostToDevice)) != 0) {
                cudaFree(d_biome);
                cudaFree(d_surface);
                return -10;
            }
        } else if (chunkup_cuda_check(cudaMemset(d_biome, 0, CHUNKUP_BLOCKS_PER_SECTION)) != 0) {
            cudaFree(d_biome);
            cudaFree(d_surface);
            return -10;
        }

        chunkup_kernel_surface_thin<<<1, dim3(16, 16, 1)>>>(
            d_density,
            d_biome,
            job->height,
            buffers->stride_y,
            d_surface
        );
        if (chunkup_cuda_check(cudaGetLastError()) != 0 ||
            chunkup_cuda_check(cudaDeviceSynchronize()) != 0) {
            cudaFree(d_biome);
            cudaFree(d_surface);
            return -10;
        }
        if (chunkup_cuda_check(cudaMemcpy(buffers->surface_layers, d_surface, surface_bytes, cudaMemcpyDeviceToHost)) != 0) {
            cudaFree(d_biome);
            cudaFree(d_surface);
            return -10;
        }
        cudaFree(d_biome);
        cudaFree(d_surface);
        result->ops_completed |= CHUNKUP_OP_SURFACE_THIN;
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

    chunkup_cuda_free_buffers(
        use_pinned_noise ? NULL : d_density,
        use_pinned_noise ? NULL : d_fluid,
        d_skylight,
        d_blocklight,
        d_face_mask
    );
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
    const size_t total_blocks = (size_t)batch_count * blocks_per_chunk;

    if (chunkup_cuda_pinned_ensure(&g_chunkup_pinned_batch, total_blocks) != 0) {
        return -10;
    }

    float* d_density = g_chunkup_pinned_batch.device_density;
    uint8_t* d_fluid = g_chunkup_pinned_batch.device_fluid;

    if (host_fluid && !d_fluid) {
        return -10;
    }
    if (chunkup_cuda_ensure_chunk_coords(batch_count) != 0) {
        return -10;
    }

    if (chunkup_cuda_check(cudaMemcpy(g_chunkup_d_chunk_xs, chunk_xs, (size_t)batch_count * sizeof(int32_t), cudaMemcpyHostToDevice)) != 0 ||
        chunkup_cuda_check(cudaMemcpy(g_chunkup_d_chunk_zs, chunk_zs, (size_t)batch_count * sizeof(int32_t), cudaMemcpyHostToDevice)) != 0) {
        return -10;
    }

    ChunkupNoiseBundle host_bundle;
    chunkup_noise_init_bundle(&host_bundle, template_job->seed);
    if (chunkup_cuda_check(cudaMemcpyToSymbol(chunkup_device_bundle, &host_bundle, sizeof(host_bundle))) != 0) {
        return -10;
    }

    const int z_slices = (template_job->height + (int)CHUNKUP_CUDA_Y_TILE - 1) / (int)CHUNKUP_CUDA_Y_TILE;
    const dim3 block(16, 16, CHUNKUP_CUDA_Y_TILE);
    const dim3 grid(1, 1, (unsigned int)z_slices * (unsigned int)batch_count);

    char params[192];
    snprintf(
        params,
        sizeof(params),
        "BatchCount=%d,Height=%d,ZSlices=%d,GridZ=%u,Block=%ux%ux%u",
        batch_count,
        template_job->height,
        z_slices,
        grid.z,
        block.x,
        block.y,
        block.z
    );
    CHUNKUP_SL_INFO_START("CUDA Density Batch Module", "Launching CUDA density fill batch kernel", params);

    chunkup_kernel_density_fill_batch<<<grid, block>>>(
        g_chunkup_d_chunk_xs,
        g_chunkup_d_chunk_zs,
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
        return -10;
    }

    CHUNKUP_SL_INFO_COMPLETE(
        "CUDA Density Batch Module",
        "CUDA density fill batch kernel finished",
        params
    );

    if (chunkup_cuda_pinned_copy_density_d2h(&g_chunkup_pinned_batch, total_blocks) != 0) {
        return -10;
    }
    if (host_fluid && chunkup_cuda_pinned_copy_fluid_d2h(&g_chunkup_pinned_batch, total_blocks) != 0) {
        return -10;
    }
    memcpy(host_density, g_chunkup_pinned_batch.host_density, density_bytes);
    if (host_fluid) {
        memcpy(host_fluid, g_chunkup_pinned_batch.host_fluid, light_bytes);
    }

    result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    return cudaGetLastError() == cudaSuccess ? 0 : -10;
}
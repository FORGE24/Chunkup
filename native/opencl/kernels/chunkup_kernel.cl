// Chunkup OpenCL kernels — skylight / face_cull + router-based density fill
// Router math is in chunkup_router_codegen.clh (prepended at compile time).

#define CHUNKUP_BLOCKS_PER_SECTION (CHUNKUP_CHUNK_SIZE * CHUNKUP_CHUNK_SIZE)

/**
 * 从预计算角点缓存做三线性插值（不调用 3D 噪声）。
 * 与 chunkup_cell_interpolate_density 对齐。
 */
inline float chunkup_cell_interpolate_density_cache_cl(
    __local const float* corner_density,
    int ck_base,
    int y_corners,
    int lx,
    int ly,
    int lz
) {
    const int ci = chunkup_cell_index_x(lx);
    const int cj = chunkup_cell_index_x(lz);
    const int ck = chunkup_cell_index_y(ly);

    const float tx = chunkup_cell_frac(lx - ci * (int)CHUNKUP_CELL_W, (int)CHUNKUP_CELL_W);
    const float ty = chunkup_cell_frac(ly - ck * (int)CHUNKUP_CELL_H, (int)CHUNKUP_CELL_H);
    const float tz = chunkup_cell_frac(lz - cj * (int)CHUNKUP_CELL_W, (int)CHUNKUP_CELL_W);

    const int ck_local = ck - ck_base;

    const int stride_ck = 5;
    const int stride_ci = y_corners * stride_ck;

    const float c000 = corner_density[ci * stride_ci + ck_local * stride_ck + cj];
    const float c100 = corner_density[(ci + 1) * stride_ci + ck_local * stride_ck + cj];
    const float c010 = corner_density[ci * stride_ci + (ck_local + 1) * stride_ck + cj];
    const float c110 = corner_density[(ci + 1) * stride_ci + (ck_local + 1) * stride_ck + cj];
    const float c001 = corner_density[ci * stride_ci + ck_local * stride_ck + (cj + 1)];
    const float c101 = corner_density[(ci + 1) * stride_ci + ck_local * stride_ck + (cj + 1)];
    const float c011 = corner_density[ci * stride_ci + (ck_local + 1) * stride_ck + (cj + 1)];
    const float c111 = corner_density[(ci + 1) * stride_ci + (ck_local + 1) * stride_ck + (cj + 1)];

    return chunkup_trilinear(tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111);
}

inline int chunkup_is_solid_cl(float density) { return density > 0.0f; }

inline int chunkup_skylight_opacity_cl(float density) {
    if (density <= 0.0f) {
        return 0;
    }
    if (density >= 0.99f) {
        return 15;
    }
    int opacity = (int)(density * 15.0f + 0.5f);
    if (opacity < 1) {
        return 1;
    }
    if (opacity > 15) {
        return 15;
    }
    return opacity;
}

inline int chunkup_skylight_propagate_cl(int light, float density) {
    const int opacity = chunkup_skylight_opacity_cl(density);
    if (opacity >= 15) {
        return 0;
    }
    if (opacity > 0) {
        const int next = light - opacity;
        return next < 0 ? 0 : next;
    }
    if (light <= 0) {
        return 0;
    }
    return light - 1;
}

inline float chunkup_density_sample_cl(
    __global const float* density,
    int lx,
    int ly,
    int lz,
    int height,
    uint stride_y
) {
    if (lx < 0 || lz < 0 || lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return -1.0f;
    }
    if (ly < 0 || ly >= height) {
        return -1.0f;
    }
    return density[chunkup_block_index_cl(lx, ly, lz, stride_y)];
}

#define CHUNKUP_OPENCL_Y_TILE 4u

/**
 * Router 密度填充 — Y 维并行（与 CUDA 对齐）。
 */
__kernel void chunkup_kernel_density_fill(
    __global float* density,
    __global uchar* fluid,
    __constant ChunkupNoiseBundle* bundle,
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint stride_y
) {
    __local ChunkupCellCache2D cell_cache;
    __local float corner_density[5 * 3 * 5];

    const int lx = (int)get_local_id(0);
    const int lz = (int)get_local_id(1);
    const int ly = (int)(get_group_id(2) * get_local_size(2) + get_local_id(2));

    const int tid = (int)(get_local_id(2) * get_local_size(1) * get_local_size(0) +
        get_local_id(1) * get_local_size(0) + get_local_id(0));
    const int block_threads = (int)(get_local_size(0) * get_local_size(1) * get_local_size(2));

    if (tid < 25) {
        const int ci = tid / 5;
        const int cj = tid % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        cell_cache.samples[ci][cj] = chunkup_router_sample_2d(bundle, wx, wz);
    }
    barrier(CLK_LOCAL_MEM_FENCE);

    const int y_tile_start = (int)(get_group_id(2) * get_local_size(2));
    const int y_tile_end = y_tile_start + (int)get_local_size(2);
    const int ck_base = y_tile_start / (int)CHUNKUP_CELL_H;
    const int ck_last = ((y_tile_end > 0 ? y_tile_end - 1 : 0)) / (int)CHUNKUP_CELL_H;
    const int y_corners = ck_last - ck_base + 2;

    const int total_corners = 5 * y_corners * 5;
    for (int i = tid; i < total_corners; i += block_threads) {
        const int ci = i / (y_corners * 5);
        const int rem = i % (y_corners * 5);
        const int ck = rem / 5;
        const int cj = rem % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wy = (float)(min_y + (ck_base + ck) * (int)CHUNKUP_CELL_H);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        const ChunkupRouterSample2D* s2d = &cell_cache.samples[ci][cj];
        corner_density[ci * (y_corners * 5) + ck * 5 + cj] =
            chunkup_router_initial_density(bundle, s2d, wx, wy, wz);
    }
    barrier(CLK_LOCAL_MEM_FENCE);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
    const float wx = (float)(base_x + lx);
    const float wy = (float)(min_y + ly);
    const float wz = (float)(base_z + lz);
    const float d = chunkup_cell_interpolate_density_cache_cl(
        corner_density, ck_base, y_corners, lx, ly, lz
    );
    density[idx] = d;
    if (fluid) {
        fluid[idx] = chunkup_router_aquifer_fluid(bundle, wx, wy, wz, d);
    }
}

__kernel void chunkup_kernel_density_fill_batch(
    __global float* density,
    __global uchar* fluid,
    __constant ChunkupNoiseBundle* bundle,
    __global const int* chunk_xs,
    __global const int* chunk_zs,
    int min_y,
    int height,
    uint stride_y,
    uint blocks_per_chunk,
    int batch_count
) {
    const int z_slices = (height + (int)CHUNKUP_OPENCL_Y_TILE - 1) / (int)CHUNKUP_OPENCL_Y_TILE;
    const int chunk_idx = (int)get_group_id(2) / z_slices;
    if (chunk_idx >= batch_count) {
        return;
    }

    __local ChunkupCellCache2D cell_cache;
    __local float corner_density[5 * 3 * 5];

    const int base_x = chunk_xs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = chunk_zs[chunk_idx] * (int)CHUNKUP_CHUNK_SIZE;
    __global float* chunk_density = density + (uint)chunk_idx * blocks_per_chunk;
    __global uchar* chunk_fluid = fluid ? fluid + (uint)chunk_idx * blocks_per_chunk : 0;

    const int lx = (int)get_local_id(0);
    const int lz = (int)get_local_id(1);
    const int ly = (int)((get_group_id(2) % z_slices) * (int)CHUNKUP_OPENCL_Y_TILE + get_local_id(2));

    const int tid = (int)(get_local_id(2) * get_local_size(1) * get_local_size(0) +
        get_local_id(1) * get_local_size(0) + get_local_id(0));
    const int block_threads = (int)(get_local_size(0) * get_local_size(1) * get_local_size(2));

    if (tid < 25) {
        const int ci = tid / 5;
        const int cj = tid % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        cell_cache.samples[ci][cj] = chunkup_router_sample_2d(bundle, wx, wz);
    }
    barrier(CLK_LOCAL_MEM_FENCE);

    const int y_tile_start = (int)((get_group_id(2) % z_slices) * (int)CHUNKUP_OPENCL_Y_TILE);
    const int y_tile_end = y_tile_start + (int)CHUNKUP_OPENCL_Y_TILE;
    const int ck_base = y_tile_start / (int)CHUNKUP_CELL_H;
    const int ck_last = ((y_tile_end > 0 ? y_tile_end - 1 : 0)) / (int)CHUNKUP_CELL_H;
    const int y_corners = ck_last - ck_base + 2;

    const int total_corners = 5 * y_corners * 5;
    for (int i = tid; i < total_corners; i += block_threads) {
        const int ci = i / (y_corners * 5);
        const int rem = i % (y_corners * 5);
        const int ck = rem / 5;
        const int cj = rem % 5;
        const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
        const float wy = (float)(min_y + (ck_base + ck) * (int)CHUNKUP_CELL_H);
        const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
        const ChunkupRouterSample2D* s2d = &cell_cache.samples[ci][cj];
        corner_density[ci * (y_corners * 5) + ck * 5 + cj] =
            chunkup_router_initial_density(bundle, s2d, wx, wy, wz);
    }
    barrier(CLK_LOCAL_MEM_FENCE);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
    const float wx = (float)(base_x + lx);
    const float wy = (float)(min_y + ly);
    const float wz = (float)(base_z + lz);
    const float d = chunkup_cell_interpolate_density_cache_cl(
        corner_density, ck_base, y_corners, lx, ly, lz
    );
    chunk_density[idx] = d;
    if (chunk_fluid) {
        chunk_fluid[idx] = chunkup_router_aquifer_fluid(bundle, wx, wy, wz, d);
    }
}

__kernel void chunkup_kernel_skylight(
    __global const float* density,
    __global uchar* skylight,
    int height,
    uint stride_y
) {
    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
        const float sample = density[idx];
        if (chunkup_skylight_opacity_cl(sample) >= 15) {
            light = 0;
        }
        skylight[idx] = (uchar)light;
        light = chunkup_skylight_propagate_cl(light, sample);
    }
}

__kernel void chunkup_kernel_face_cull(
    __global const float* density,
    __global uchar* face_mask,
    int height,
    uint stride_y
) {
    const int lx = (int)get_global_id(0);
    const int ly = (int)get_global_id(1);
    const int lz = (int)get_global_id(2);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
    if (!chunkup_is_solid_cl(density[idx])) {
        face_mask[idx] = 0;
        return;
    }

    uchar mask = 0;
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx, ly + 1, lz, height, stride_y))) {
        mask |= 1 << 0;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx, ly - 1, lz, height, stride_y))) {
        mask |= 1 << 1;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx, ly, lz - 1, height, stride_y))) {
        mask |= 1 << 2;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx, ly, lz + 1, height, stride_y))) {
        mask |= 1 << 3;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx - 1, ly, lz, height, stride_y))) {
        mask |= 1 << 4;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(density, lx + 1, ly, lz, height, stride_y))) {
        mask |= 1 << 5;
    }
    face_mask[idx] = mask;
}

__kernel void chunkup_kernel_skylight_batch(
    __global const float* density,
    __global uchar* skylight,
    int height,
    uint stride_y,
    uint blocks_per_chunk,
    int batch_count
) {
    const int chunk_idx = (int)get_global_id(2);
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    const __global float* chunk_density = density + (uint)chunk_idx * blocks_per_chunk;
    __global uchar* chunk_light = skylight + (uint)chunk_idx * blocks_per_chunk;

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
        const float sample = chunk_density[idx];
        if (chunkup_skylight_opacity_cl(sample) >= 15) {
            light = 0;
        }
        chunk_light[idx] = (uchar)light;
        light = chunkup_skylight_propagate_cl(light, sample);
    }
}

__kernel void chunkup_kernel_face_cull_batch(
    __global const float* density,
    __global uchar* face_mask,
    int height,
    uint stride_y,
    uint blocks_per_chunk,
    int batch_count
) {
    const int flat_z = (int)get_global_id(2);
    const int chunk_idx = flat_z / height;
    const int ly = flat_z % height;
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const __global float* chunk_density = density + (uint)chunk_idx * blocks_per_chunk;
    __global uchar* chunk_faces = face_mask + (uint)chunk_idx * blocks_per_chunk;

    const uint idx = chunkup_block_index_cl(lx, ly, lz, stride_y);
    if (!chunkup_is_solid_cl(chunk_density[idx])) {
        chunk_faces[idx] = 0;
        return;
    }

    uchar mask = 0;
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx, ly + 1, lz, height, stride_y))) {
        mask |= 1 << 0;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx, ly - 1, lz, height, stride_y))) {
        mask |= 1 << 1;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx, ly, lz - 1, height, stride_y))) {
        mask |= 1 << 2;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx, ly, lz + 1, height, stride_y))) {
        mask |= 1 << 3;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx - 1, ly, lz, height, stride_y))) {
        mask |= 1 << 4;
    }
    if (!chunkup_is_solid_cl(chunkup_density_sample_cl(chunk_density, lx + 1, ly, lz, height, stride_y))) {
        mask |= 1 << 5;
    }
    chunk_faces[idx] = mask;
}

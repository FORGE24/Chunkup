#pragma once

/**
 * Cell 插值缓存 — 对齐 NoiseChunk.fillSlice（4×4×8 cell）。
 *
 * 1. 在 cell 角点采样 2D router（continents/offset/factor）
 * 2. 在 cell 角点评估 initial_density
 * 3. 对块坐标三线性插值
 */

#include "chunkup_compat.h"
#include "chunkup_density_router.h"
#include "chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_CELL_W CHUNKUP_ROUTER_CELL_WIDTH
#define CHUNKUP_CELL_H CHUNKUP_ROUTER_CELL_HEIGHT

typedef struct ChunkupCellCache2D {
    ChunkupRouterSample2D samples[5][5];
} ChunkupCellCache2D;

CHUNKUP_FN int chunkup_cell_index_x(int lx) {
    return lx / (int)CHUNKUP_CELL_W;
}

CHUNKUP_FN int chunkup_cell_index_y(int ly) {
    return ly / (int)CHUNKUP_CELL_H;
}

CHUNKUP_FN float chunkup_cell_frac(int local, int cell_size) {
    return (float)local / (float)cell_size;
}

CHUNKUP_FN void chunkup_cell_build_2d_cache(
    const ChunkupNoiseBundle* bundle,
    int base_x,
    int base_z,
    ChunkupCellCache2D* cache
) {
    for (int ci = 0; ci <= 4; ++ci) {
        for (int cj = 0; cj <= 4; ++cj) {
            const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
            const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
            cache->samples[ci][cj] = chunkup_router_sample_2d(bundle, wx, wz);
        }
    }
}

CHUNKUP_FN float chunkup_cell_corner_density(
    const ChunkupNoiseBundle* bundle,
    const ChunkupCellCache2D* cache,
    int ci,
    int ck,
    int cj,
    int base_x,
    int base_z,
    int min_y
) {
    const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
    const float wy = (float)(min_y + ck * (int)CHUNKUP_CELL_H);
    const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
    const ChunkupRouterSample2D* s2d = &cache->samples[ci][cj];
    return chunkup_router_initial_density(bundle, s2d, wx, wy, wz);
}

CHUNKUP_FN float chunkup_cell_interpolated_density(
    const ChunkupNoiseBundle* bundle,
    const ChunkupCellCache2D* cache,
    int base_x,
    int base_z,
    int min_y,
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

    const float c000 = chunkup_cell_corner_density(bundle, cache, ci, ck, cj, base_x, base_z, min_y);
    const float c100 = chunkup_cell_corner_density(bundle, cache, ci + 1, ck, cj, base_x, base_z, min_y);
    const float c010 = chunkup_cell_corner_density(bundle, cache, ci, ck + 1, cj, base_x, base_z, min_y);
    const float c110 = chunkup_cell_corner_density(bundle, cache, ci + 1, ck + 1, cj, base_x, base_z, min_y);
    const float c001 = chunkup_cell_corner_density(bundle, cache, ci, ck, cj + 1, base_x, base_z, min_y);
    const float c101 = chunkup_cell_corner_density(bundle, cache, ci + 1, ck, cj + 1, base_x, base_z, min_y);
    const float c011 = chunkup_cell_corner_density(bundle, cache, ci, ck + 1, cj + 1, base_x, base_z, min_y);
    const float c111 = chunkup_cell_corner_density(bundle, cache, ci + 1, ck + 1, cj + 1, base_x, base_z, min_y);

    return chunkup_trilinear(tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111);
}

CHUNKUP_FN void chunkup_cell_fill_chunk(
    const ChunkupNoiseBundle* bundle,
    int base_x,
    int base_z,
    int min_y,
    int height,
    float* density,
    uint8_t* fluid,
    uint32_t stride_y
) {
    ChunkupCellCache2D cache;
    chunkup_cell_build_2d_cache(bundle, base_x, base_z, &cache);

    for (int ly = 0; ly < height; ++ly) {
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
                const float wx = (float)(base_x + lx);
                const float wy = (float)(min_y + ly);
                const float wz = (float)(base_z + lz);

                const float d = chunkup_cell_interpolated_density(
                    bundle,
                    &cache,
                    base_x,
                    base_z,
                    min_y,
                    lx,
                    ly,
                    lz
                );
                density[idx] = d;

                if (fluid) {
                    fluid[idx] = chunkup_router_aquifer_fluid(bundle, wx, wy, wz, d);
                }
            }
        }
    }
}

#ifdef __cplusplus
}
#endif

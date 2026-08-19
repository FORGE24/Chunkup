#pragma once

/**
 * Cell 插值缓存 — 对齐 NoiseChunk.fillSlice（4×4×8 cell）。
 *
 * 修复：原实现每个 block 都重新计算 8 次 initial_density（含 3D 噪声），
 * 计算量是原版 ~640 倍。现在先一次性计算所有 cell 角点密度，
 * 再对每个 block 做纯三线性插值，计算量降低 2-3 个数量级。
 *
 * 1. 在 cell 角点采样 2D router（continents/offset/factor）→ ChunkupCellCache2D
 * 2. 在 cell 角点评估 initial_density → corner_density[] 缓存
 * 3. 对块坐标三线性插值（从缓存读取，不再调用 3D 噪声）
 */

#include "chunkup_compat.h"
#include "chunkup_density_router.h"
#include "chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_CELL_W CHUNKUP_ROUTER_CELL_WIDTH
#define CHUNKUP_CELL_H CHUNKUP_ROUTER_CELL_HEIGHT

/* Y 方向最大角点数；覆盖 height=512 (cell_h=8 → 65 角点) */
#define CHUNKUP_CELL_MAX_Y_CORNERS 66

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

/**
 * 一次性构建 cell 角点密度缓存（CPU 串行版本）。
 *
 * 缓存布局：corner_density[ci * (y_corners * 5) + ck_local * 5 + cj]
 * ci ∈ [0,4], ck_local ∈ [0, y_corners-1], cj ∈ [0,4]
 *
 * @param ck_base      第一个 Y 角点的全局 cell 索引
 * @param y_corners     Y 方向角点数量
 * @param corner_density 输出数组，大小至少 5 * y_corners * 5
 */
CHUNKUP_FN void chunkup_cell_build_corner_density(
    const ChunkupNoiseBundle* bundle,
    const ChunkupCellCache2D* cache_2d,
    int base_x,
    int base_z,
    int min_y,
    int ck_base,
    int y_corners,
    float* corner_density
) {
    const int stride_ck = 5;
    const int stride_ci = y_corners * stride_ck;
    for (int ci = 0; ci <= 4; ++ci) {
        for (int ck = 0; ck < y_corners; ++ck) {
            for (int cj = 0; cj <= 4; ++cj) {
                const float wx = (float)(base_x + ci * (int)CHUNKUP_CELL_W);
                const float wy = (float)(min_y + (ck_base + ck) * (int)CHUNKUP_CELL_H);
                const float wz = (float)(base_z + cj * (int)CHUNKUP_CELL_W);
                const ChunkupRouterSample2D* s2d = &cache_2d->samples[ci][cj];
                corner_density[ci * stride_ci + ck * stride_ck + cj] =
                    chunkup_router_initial_density(bundle, s2d, wx, wy, wz);
            }
        }
    }
}

/**
 * 从预计算角点缓存做三线性插值（不调用 3D 噪声）。
 *
 * @param corner_density  预计算角点密度（由 chunkup_cell_build_corner_density 填充）
 * @param ck_base         第一个 Y 角点的全局 cell 索引
 * @param y_corners        Y 方向角点数量
 * @param lx, ly, lz      局部块坐标
 */
CHUNKUP_FN float chunkup_cell_interpolate_density(
    const float* corner_density,
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
    ChunkupCellCache2D cache_2d;
    chunkup_cell_build_2d_cache(bundle, base_x, base_z, &cache_2d);

    /* 一次性构建所有 cell 角点密度 */
    const int ck_base = 0;
    const int y_corners = (height + (int)CHUNKUP_CELL_H - 1) / (int)CHUNKUP_CELL_H + 1;
    float corner_density[5 * CHUNKUP_CELL_MAX_Y_CORNERS * 5];
    chunkup_cell_build_corner_density(
        bundle, &cache_2d, base_x, base_z, min_y,
        ck_base, y_corners, corner_density
    );

    for (int ly = 0; ly < height; ++ly) {
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
                const float wx = (float)(base_x + lx);
                const float wy = (float)(min_y + ly);
                const float wz = (float)(base_z + lz);

                const float d = chunkup_cell_interpolate_density(
                    corner_density, ck_base, y_corners, lx, ly, lz
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

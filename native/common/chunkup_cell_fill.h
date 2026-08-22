#pragma once

/**
 * Cell 插值缓存 — 对齐 NoiseChunk.fillSlice（4×4×8 cell）。
 *
 * 修复史：
 * 1. 原实现每 block 重新计算 8 次 initial_density（~640× 冗余）→ 角点缓存 + 三线性插值。
 * 2. 2026-08-22：密度源从 chunkup_router_initial_density（粗近似：无 jaggedness、
 *    单变量 spline、自造 LCG 噪声）换成 chunkup_wg_eval_direct(FINAL_DENSITY)——
 *    wg_eval 为 vanilla 位精确实现（wg_compare 对拍 ALL EXACT），
 *    含完整三变量 spline、jaggedness、64 位 seed 派生链。
 *
 * 现在的流程：
 * 1. 在 cell 角点(5×N×5)评估 final_density（wg_eval direct）→ corner_density[] 缓存
 * 2. 对块坐标三线性插值（从缓存读取，不再调用 3D 噪声）
 * 3. density ≤ 0 时用 wg_eval aquifer 流体判定（barrier/floodedness/spread/lava）
 */

#include "chunkup_compat.h"
#include "chunkup_wg_eval.h"
#include "chunkup_kernel.h"
#include "chunkup_spline.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_CELL_W 4
#define CHUNKUP_CELL_H 8

/* Y 方向最大角点数；覆盖 height=512 (cell_h=8 → 65 角点) */
#define CHUNKUP_CELL_MAX_Y_CORNERS 66

CHUNKUP_FN int chunkup_cell_index_x(int lx) {
    return lx / (int)CHUNKUP_CELL_W;
}

CHUNKUP_FN int chunkup_cell_index_y(int ly) {
    return ly / (int)CHUNKUP_CELL_H;
}

CHUNKUP_FN float chunkup_cell_frac(int local, int cell_size) {
    return (float)local / (float)cell_size;
}

/**
 * 一次性构建 cell 角点密度缓存（wg_eval final_density，位精确）。
 *
 * 缓存布局：corner_density[ci * (y_corners * 5) + ck_local * 5 + cj]
 * ci ∈ [0,4], ck_local ∈ [0, y_corners-1], cj ∈ [0,4]
 *
 * @param wg            wg_eval 世界（64 位 seed 派生）
 * @param ck_base       第一个 Y 角点的全局 cell 索引
 * @param y_corners     Y 方向角点数量
 * @param corner_density 输出数组，大小至少 5 * y_corners * 5
 */
CHUNKUP_FN void chunkup_cell_build_corner_density(
    ChunkupWgWorld* wg,
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
                const int wx = base_x + ci * (int)CHUNKUP_CELL_W;
                const int wy = min_y + (ck_base + ck) * (int)CHUNKUP_CELL_H;
                const int wz = base_z + cj * (int)CHUNKUP_CELL_W;
                corner_density[ci * stride_ci + ck * stride_ck + cj] =
                    (float)chunkup_wg_eval_direct(wg, CHUNKUP_WG_DF_FINAL_DENSITY, wx, wy, wz);
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
    ChunkupWgWorld* wg,
    int base_x,
    int base_z,
    int min_y,
    int height,
    float* density,
    uint8_t* fluid,
    uint32_t stride_y
) {
    /* 一次性构建所有 cell 角点密度（wg_eval final_density，位精确） */
    const int ck_base = 0;
    const int y_corners = (height + (int)CHUNKUP_CELL_H - 1) / (int)CHUNKUP_CELL_H + 1;
    float corner_density[5 * CHUNKUP_CELL_MAX_Y_CORNERS * 5];
    chunkup_cell_build_corner_density(
        wg, base_x, base_z, min_y, ck_base, y_corners, corner_density
    );

    for (int ly = 0; ly < height; ++ly) {
        for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
            for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
                const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
                const int wx = base_x + lx;
                const int wy = min_y + ly;
                const int wz = base_z + lz;

                const float d = chunkup_cell_interpolate_density(
                    corner_density, ck_base, y_corners, lx, ly, lz
                );
                density[idx] = d;

                if (fluid) {
                    fluid[idx] = chunkup_wg_aquifer_fluid(wg, wx, wy, wz, (double)d);
                }
            }
        }
    }
}

#ifdef __cplusplus
}
#endif

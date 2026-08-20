#pragma once

/**
 * Minecraft 1.20.1 overworld noise_router 精确求值器（表驱动）。
 *
 * 对应 vanilla：
 * - RandomState（噪声实例派生：fromHashOf("minecraft:xxx") / "minecraft:terrain"）
 * - NoiseChunk（FlatCache quart 网格 / NoiseInterpolator 角点 + lerp3）
 * - DensityFunction 树求值（Ap2/Mapped/RangeChoice/Spline/ShiftedNoise/...）
 *
 * 值精确策略：
 * - 样条全部 float 运算（Java CubicSpline/Codec.FLOAT）
 * - flat_cache：quart 吸附 + y=0 的 5×5 预计算网格（子节点先于父节点，模拟 mapAll 包装序）
 * - interpolated：cell 角点(5×49×5) + Mth.lerp3（X→Y→Z 顺序）
 * - cache_2d / cache_once / cache_all_in_cell 为纯 memoization，跳过不影响值
 * - blend_alpha=1 / blend_offset=0 / blend_density=恒等 / beardifier=0（空 Blender、无结构）
 */

#include "chunkup_worldgen_tables.h"
#include "chunkup_xoroshiro.h"
#include <math.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------------------------------------------------------- 常量 */

#define CHUNKUP_WG_MAX_FLAT 8
#define CHUNKUP_WG_MAX_INTERP 8
#define CHUNKUP_WG_NOISE_SIZE_XZ 4                                   /* 16/cellWidth */
#define CHUNKUP_WG_CORNER_Y (CHUNKUP_WG_HEIGHT / CHUNKUP_WG_CELL_HEIGHT + 1)  /* 49 */

/* ---------------------------------------------------------------- 世界状态（每 seed 一次） */

typedef struct ChunkupWgWorld {
    ChunkupPositionalFactory pf;                 /* newInstance(seed).forkPositional() */
    ChunkupNormalNoiseD noises[CHUNKUP_WG_NOISE_COUNT];
    ChunkupBlendedNoiseD blended;                /* old_blended_noise（base_3d_noise） */

    /* final_density 可达 marker 节点 → 槽位（-1 = 未分配） */
    int8_t flat_slot[CHUNKUP_WG_DF_NODE_COUNT];
    int8_t interp_slot[CHUNKUP_WG_DF_NODE_COUNT];
    int32_t flat_order[CHUNKUP_WG_MAX_FLAT];     /* 拓扑序：子节点先于父节点 */
    int32_t interp_order[CHUNKUP_WG_MAX_INTERP];
    int32_t flat_count;
    int32_t interp_count;
    int32_t init_ok;
} ChunkupWgWorld;

/* ---------------------------------------------------------------- 区块状态 */

typedef struct ChunkupWgChunk {
    ChunkupWgWorld* world;
    int32_t chunk_x, chunk_z;
    int32_t min_bx, min_bz;
    int32_t first_noise_x, first_noise_z;        /* quart */
    double flat_vals[CHUNKUP_WG_MAX_FLAT][CHUNKUP_WG_NOISE_SIZE_XZ + 1][CHUNKUP_WG_NOISE_SIZE_XZ + 1];
    double interp_vals[CHUNKUP_WG_MAX_INTERP][CHUNKUP_WG_NOISE_SIZE_XZ + 1][CHUNKUP_WG_CORNER_Y][CHUNKUP_WG_NOISE_SIZE_XZ + 1];
} ChunkupWgChunk;

/* ---------------------------------------------------------------- 前置声明 */

CHUNKUP_FN double chunkup_wg_df(ChunkupWgChunk* c, int32_t idx, int32_t bx, int32_t by, int32_t bz);
CHUNKUP_FN float chunkup_wg_spline_apply(ChunkupWgChunk* c, int32_t sidx, int32_t bx, int32_t by, int32_t bz);
CHUNKUP_FN float chunkup_wg_spline_point_value(ChunkupWgChunk* c, const ChunkupSplinePoint* pt, int32_t bx, int32_t by, int32_t bz);

/* ---------------------------------------------------------------- 工具 */

CHUNKUP_FN float chunkup_mth_lerpf(float f, float g, float h) {
    /* Mth.lerp(float, float, float) */
    return g + f * (h - g);
}

CHUNKUP_FN double chunkup_mth_clamp(double d, double e, double f) {
    /* Mth.clamp(double, double, double) */
    return d < e ? e : (d > f ? f : d);
}

/* ---------------------------------------------------------------- 样条（float 精度） */

CHUNKUP_FN float chunkup_wg_spline_point_value(ChunkupWgChunk* c, const ChunkupSplinePoint* pt, int32_t bx, int32_t by, int32_t bz) {
    if (pt->value_spline >= 0) {
        /* 常量点：生成器已圆整到 float32 存储 */
        return (float)CHUNKUP_WG_DF_NODES[pt->value_spline].v0;
    }
    return chunkup_wg_spline_apply(c, -pt->value_spline - 2, bx, by, bz);
}

CHUNKUP_FN float chunkup_wg_spline_linear_extend(
    float f, const ChunkupSplinePoint* pts, int32_t i, float value
) {
    /* CubicSpline.Multipoint.linearExtend */
    const float d = (float)pts[i].derivative;
    return d == 0.0f ? value : value + d * (f - (float)pts[i].location);
}

CHUNKUP_FN float chunkup_wg_spline_apply(ChunkupWgChunk* c, int32_t sidx, int32_t bx, int32_t by, int32_t bz) {
    const ChunkupSplineNode* sn = &CHUNKUP_WG_SPLINE_NODES[sidx];
    const ChunkupSplinePoint* pts = &CHUNKUP_WG_SPLINE_POINTS[sn->point_start];
    const int n = sn->point_count;

    /* Coordinate.apply = (float)function.compute(ctx) */
    const float f = (float)chunkup_wg_df(c, sn->coord_df, bx, by, bz);

    /* findIntervalStart = binarySearch(0, n, i -> f < loc[i]) - 1（线性等价） */
    int i = 0;
    while (i < n && !(f < (float)pts[i].location)) {
        ++i;
    }
    const int idx = i - 1;

    if (idx < 0) {
        const float v = chunkup_wg_spline_point_value(c, &pts[0], bx, by, bz);
        return chunkup_wg_spline_linear_extend(f, pts, 0, v);
    }
    if (idx == n - 1) {
        const float v = chunkup_wg_spline_point_value(c, &pts[idx], bx, by, bz);
        return chunkup_wg_spline_linear_extend(f, pts, idx, v);
    }

    const float g = (float)pts[idx].location;
    const float h = (float)pts[idx + 1].location;
    const float k = (f - g) / (h - g);
    const float nv = chunkup_wg_spline_point_value(c, &pts[idx], bx, by, bz);
    const float ov = chunkup_wg_spline_point_value(c, &pts[idx + 1], bx, by, bz);
    const float l = (float)pts[idx].derivative;
    const float m = (float)pts[idx + 1].derivative;
    const float p = l * (h - g) - (ov - nv);
    const float q = -m * (h - g) + (ov - nv);
    return chunkup_mth_lerpf(k, nv, ov) + k * (1.0f - k) * chunkup_mth_lerpf(k, p, q);
}

/* ---------------------------------------------------------------- DF 树求值 */

CHUNKUP_FN double chunkup_wg_df(ChunkupWgChunk* c, int32_t idx, int32_t bx, int32_t by, int32_t bz) {
    const ChunkupDfNode* nd = &CHUNKUP_WG_DF_NODES[idx];

    switch (nd->type) {
    case CHUNKUP_DF_CONSTANT:
        return nd->v0;

    case CHUNKUP_DF_NOISE:
        /* Noise.compute: noise(blockX * xzScale, blockY * yScale, blockZ * xzScale) */
        return chunkup_normal_get(&c->world->noises[nd->d], bx * nd->v0, by * nd->v1, bz * nd->v0);

    case CHUNKUP_DF_SHIFTED_NOISE: {
        const double x = bx * nd->v0 + chunkup_wg_df(c, nd->a, bx, by, bz);
        const double y = by * nd->v1 + chunkup_wg_df(c, nd->b, bx, by, bz);
        const double z = bz * nd->v0 + chunkup_wg_df(c, nd->c, bx, by, bz);
        return chunkup_normal_get(&c->world->noises[nd->d], x, y, z);
    }

    case CHUNKUP_DF_SHIFT_A:
        /* ShiftA.compute(blockX, 0, blockZ) → getValue(x*0.25, 0, z*0.25) * 4 */
        return chunkup_normal_get(&c->world->noises[nd->d], bx * 0.25, 0.0, bz * 0.25) * 4.0;

    case CHUNKUP_DF_SHIFT_B:
        /* ShiftB.compute(blockZ, blockX, 0) → getValue(z*0.25, x*0.25, 0) * 4 */
        return chunkup_normal_get(&c->world->noises[nd->d], bz * 0.25, bx * 0.25, 0.0) * 4.0;

    case CHUNKUP_DF_SHIFT:
        return chunkup_normal_get(&c->world->noises[nd->d], bx * 0.25, by * 0.25, bz * 0.25) * 4.0;

    case CHUNKUP_DF_ADD:
        return chunkup_wg_df(c, nd->a, bx, by, bz) + chunkup_wg_df(c, nd->b, bx, by, bz);

    case CHUNKUP_DF_MUL: {
        /* Ap2 MUL: d == 0 → 0（不计算 argument2） */
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d == 0.0 ? 0.0 : d * chunkup_wg_df(c, nd->b, bx, by, bz);
    }

    case CHUNKUP_DF_MIN: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        const double e = chunkup_wg_df(c, nd->b, bx, by, bz);
        return d <= e ? d : e;
    }

    case CHUNKUP_DF_MAX: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        const double e = chunkup_wg_df(c, nd->b, bx, by, bz);
        return d >= e ? d : e;
    }

    case CHUNKUP_DF_ABS:
        return fabs(chunkup_wg_df(c, nd->a, bx, by, bz));

    case CHUNKUP_DF_SQUARE: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d * d;
    }

    case CHUNKUP_DF_CUBE: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d * d * d;
    }

    case CHUNKUP_DF_HALF_NEG: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d > 0.0 ? d : d * 0.5;
    }

    case CHUNKUP_DF_QUARTER_NEG: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d > 0.0 ? d : d * 0.25;
    }

    case CHUNKUP_DF_SQUEEZE: {
        /* Mapped.SQUEEZE: e = clamp(d, -1, 1); e/2 - e*e*e/24 */
        const double e = chunkup_mth_clamp(chunkup_wg_df(c, nd->a, bx, by, bz), -1.0, 1.0);
        return e / 2.0 - e * e * e / 24.0;
    }

    case CHUNKUP_DF_CLAMP:
        /* Clamp.transform = Mth.clamp(input, min, max) */
        return chunkup_mth_clamp(chunkup_wg_df(c, nd->a, bx, by, bz), nd->v0, nd->v1);

    case CHUNKUP_DF_RANGE_CHOICE: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        return d >= nd->v0 && d < nd->v1
            ? chunkup_wg_df(c, nd->b, bx, by, bz)
            : chunkup_wg_df(c, nd->c, bx, by, bz);
    }

    case CHUNKUP_DF_SPLINE:
        /* Spline.compute = (double)spline.apply(point) —— float 精度
         * a 字段编码：-2-sidx（生成器 build_spline 负数编码），解码取正索引 */
        return (double)chunkup_wg_spline_apply(c, -nd->a - 2, bx, by, bz);

    case CHUNKUP_DF_Y_CLAMPED_GRADIENT: {
        /* Mth.clampedMap(blockY, fromY, toY, fromValue, toValue) */
        const double t = ((double)by - nd->v0) / (nd->v1 - nd->v0);
        return t < 0.0 ? nd->v2 : (t > 1.0 ? nd->v3 : nd->v2 + t * (nd->v3 - nd->v2));
    }

    case CHUNKUP_DF_MARKER_INTERPOLATED: {
        const int8_t slot = c->world->interp_slot[idx];
        if (slot < 0) {
            return chunkup_wg_df(c, nd->a, bx, by, bz);
        }
        const int32_t lx = bx - c->min_bx;
        const int32_t lz = bz - c->min_bz;
        const int32_t ly = by - CHUNKUP_WG_MIN_Y;
        if ((lx | lz) < 0 || lx >= 16 || lz >= 16 || ly < 0 || ly >= CHUNKUP_WG_HEIGHT) {
            return chunkup_wg_df(c, nd->a, bx, by, bz);
        }
        const int32_t cx = lx >> 2;
        const int32_t ix = lx & 3;
        const int32_t cz = lz >> 2;
        const int32_t iz = lz & 3;
        const int32_t cy = ly / CHUNKUP_WG_CELL_HEIGHT;
        const int32_t iy = ly - cy * CHUNKUP_WG_CELL_HEIGHT;
        const double (*const g)[CHUNKUP_WG_CORNER_Y][CHUNKUP_WG_NOISE_SIZE_XZ + 1] = c->interp_vals[slot];
        const double n000 = g[cx][cy][cz];
        const double n100 = g[cx + 1][cy][cz];
        const double n010 = g[cx][cy + 1][cz];
        const double n110 = g[cx + 1][cy + 1][cz];
        const double n001 = g[cx][cy][cz + 1];
        const double n101 = g[cx + 1][cy][cz + 1];
        const double n011 = g[cx][cy + 1][cz + 1];
        const double n111 = g[cx + 1][cy + 1][cz + 1];
        /* Mth.lerp3(inCellX/cw, inCellY/ch, inCellZ/cw, ...) —— X→Y→Z */
        return chunkup_mth_lerp3(
            (double)ix / (double)CHUNKUP_WG_CELL_WIDTH,
            (double)iy / (double)CHUNKUP_WG_CELL_HEIGHT,
            (double)iz / (double)CHUNKUP_WG_CELL_WIDTH,
            n000, n100, n010, n110, n001, n101, n011, n111
        );
    }

    case CHUNKUP_DF_MARKER_FLAT_CACHE: {
        const int8_t slot = c->world->flat_slot[idx];
        if (slot >= 0) {
            /* QuartPos.fromBlock = x >> 2 */
            const int32_t k = (bx >> 2) - c->first_noise_x;
            const int32_t l = (bz >> 2) - c->first_noise_z;
            if (k >= 0 && l >= 0 && k <= CHUNKUP_WG_NOISE_SIZE_XZ && l <= CHUNKUP_WG_NOISE_SIZE_XZ) {
                return c->flat_vals[slot][k][l];
            }
        }
        return chunkup_wg_df(c, nd->a, bx, by, bz);
    }

    case CHUNKUP_DF_MARKER_CACHE_2D:
    case CHUNKUP_DF_MARKER_CACHE_ONCE:
    case CHUNKUP_DF_MARKER_CACHE_ALL_IN_CELL:
        /* 纯 memoization，值等价于直接透传 */
        return chunkup_wg_df(c, nd->a, bx, by, bz);

    case CHUNKUP_DF_BLEND_ALPHA:
        return 1.0;   /* Blender.empty() */

    case CHUNKUP_DF_BLEND_OFFSET:
        return 0.0;   /* Blender.empty() */

    case CHUNKUP_DF_BEARDIFIER:
        return 0.0;   /* 无结构 */

    case CHUNKUP_DF_BLEND_DENSITY:
        return chunkup_wg_df(c, nd->a, bx, by, bz);   /* 空 Blender 恒等 */

    case CHUNKUP_DF_WEIRD_SCALED: {
        const double d = chunkup_wg_df(c, nd->a, bx, by, bz);
        double e;
        if (nd->v0 == 0.0) {
            /* QuantizedSpaghettiRarity.getSpaghettiRarity3D */
            e = d < -0.5 ? 0.75 : (d < 0.0 ? 1.0 : (d < 0.5 ? 1.5 : 2.0));
        } else {
            /* getSphaghettiRarity2D */
            e = d < -0.75 ? 0.5 : (d < -0.5 ? 0.75 : (d < 0.5 ? 1.0 : (d < 0.75 ? 2.0 : 3.0)));
        }
        return e * fabs(chunkup_normal_get(&c->world->noises[nd->d], bx / e, by / e, bz / e));
    }

    case CHUNKUP_DF_OLD_BLENDED:
        return chunkup_blended_get(&c->world->blended, (double)bx, (double)by, (double)bz);

    case CHUNKUP_DF_END_ISLANDS:
        /* overworld 不可达 */
        return 0.0;

    default:
        return 0.0;
    }
}

/* ---------------------------------------------------------------- 可达性扫描（DFS 后序 = mapAll 包装序） */

CHUNKUP_FN void chunkup_wg_scan_spline(ChunkupWgWorld* w, int32_t sidx, uint8_t* visited);

CHUNKUP_FN void chunkup_wg_scan_dfs(ChunkupWgWorld* w, int32_t idx, uint8_t* visited) {
    if (idx < 0 || visited[idx]) {
        return;
    }
    visited[idx] = 1;
    const ChunkupDfNode* nd = &CHUNKUP_WG_DF_NODES[idx];

    if (nd->type == CHUNKUP_DF_SPLINE) {
        /* SPLINE 节点的 a 是负数编码的 spline 索引（-2-sidx），需解码 */
        chunkup_wg_scan_spline(w, -nd->a - 2, visited);
    } else {
        chunkup_wg_scan_dfs(w, nd->a, visited);
        chunkup_wg_scan_dfs(w, nd->b, visited);
        chunkup_wg_scan_dfs(w, nd->c, visited);
    }

    /* 后序：子节点先注册（shift_x 的 flat 网格先于 continents 构建） */
    if (nd->type == CHUNKUP_DF_MARKER_FLAT_CACHE && w->flat_count < CHUNKUP_WG_MAX_FLAT) {
        w->flat_slot[idx] = (int8_t)w->flat_count;
        w->flat_order[w->flat_count] = idx;
        w->flat_count++;
    }
    if (nd->type == CHUNKUP_DF_MARKER_INTERPOLATED && w->interp_count < CHUNKUP_WG_MAX_INTERP) {
        w->interp_slot[idx] = (int8_t)w->interp_count;
        w->interp_order[w->interp_count] = idx;
        w->interp_count++;
    }
}

CHUNKUP_FN void chunkup_wg_scan_spline(ChunkupWgWorld* w, int32_t sidx, uint8_t* visited) {
    const ChunkupSplineNode* sn = &CHUNKUP_WG_SPLINE_NODES[sidx];
    chunkup_wg_scan_dfs(w, sn->coord_df, visited);
    for (int32_t i = 0; i < sn->point_count; ++i) {
        const ChunkupSplinePoint* pt = &CHUNKUP_WG_SPLINE_POINTS[sn->point_start + i];
        if (pt->value_spline <= -2) {
            chunkup_wg_scan_spline(w, -pt->value_spline - 2, visited);
        }
        /* >= 0 为 DF 常量节点，无 marker */
    }
}

/* ---------------------------------------------------------------- 世界初始化 */

CHUNKUP_FN void chunkup_wg_world_init(ChunkupWgWorld* w, uint64_t seed) {
    memset(w, 0, sizeof(*w));

    /* random = XoroshiroRandomSource(seed).forkPositional() */
    ChunkupRandomSource root;
    chunkup_rs_init_seed64(&root, seed);
    w->pf = chunkup_rs_fork_positional(&root);

    /* 噪声实例：NormalNoise.create(random.fromHashOf("minecraft:xxx"), params) */
    for (int i = 0; i < CHUNKUP_WG_NOISE_COUNT; ++i) {
        ChunkupRandomSource rs;
        const char* key = CHUNKUP_WG_NOISE_KEYS[i];
        chunkup_pf_from_hash_of(&w->pf, key, strlen(key), &rs);
        chunkup_normal_init(
            &w->noises[i], &rs,
            CHUNKUP_WG_NOISE_FIRST_OCTAVE[i],
            CHUNKUP_WG_NOISE_AMPS[i],
            CHUNKUP_WG_NOISE_AMP_LEN[i]
        );
    }

    /* BlendedNoise：random.fromHashOf("minecraft:terrain")（不 fork） */
    {
        ChunkupRandomSource brs;
        chunkup_pf_from_hash_of(&w->pf, "minecraft:terrain", (int)strlen("minecraft:terrain"), &brs);
        chunkup_blended_init(
            &w->blended, &brs,
            CHUNKUP_WG_BLENDED_XZ_SCALE, CHUNKUP_WG_BLENDED_Y_SCALE,
            CHUNKUP_WG_BLENDED_XZ_FACTOR, CHUNKUP_WG_BLENDED_Y_FACTOR,
            CHUNKUP_WG_BLENDED_SMEAR
        );
    }

    /* 槽位默认 -1（未分配）——memset 0 会让不可达 marker 误判为槽 0 */
    memset(w->flat_slot, -1, sizeof(w->flat_slot));
    memset(w->interp_slot, -1, sizeof(w->interp_slot));

    /* final_density 可达性扫描 */
    {
        uint8_t visited[CHUNKUP_WG_DF_NODE_COUNT];
        memset(visited, 0, sizeof(visited));
        chunkup_wg_scan_dfs(w, CHUNKUP_WG_DF_FINAL_DENSITY, visited);
    }
    w->init_ok = 1;
}

/* ---------------------------------------------------------------- 区块初始化 */

CHUNKUP_FN void chunkup_wg_chunk_init(ChunkupWgChunk* c, ChunkupWgWorld* w, int32_t chunk_x, int32_t chunk_z) {
    c->world = w;
    c->chunk_x = chunk_x;
    c->chunk_z = chunk_z;
    c->min_bx = chunk_x * 16;
    c->min_bz = chunk_z * 16;
    c->first_noise_x = c->min_bx >> 2;
    c->first_noise_z = c->min_bz >> 2;

    /* FlatCache 预计算：5×5 quart 网格，位置 (quartX*4, 0, quartZ*4)，拓扑序 */
    for (int32_t s = 0; s < w->flat_count; ++s) {
        const int32_t child = CHUNKUP_WG_DF_NODES[w->flat_order[s]].a;
        for (int i = 0; i <= CHUNKUP_WG_NOISE_SIZE_XZ; ++i) {
            const int32_t bx = (c->first_noise_x + i) * 4;
            for (int l = 0; l <= CHUNKUP_WG_NOISE_SIZE_XZ; ++l) {
                const int32_t bz = (c->first_noise_z + l) * 4;
                c->flat_vals[s][i][l] = chunkup_wg_df(c, child, bx, 0, bz);
            }
        }
    }

    /* NoiseInterpolator 角点：cell 角点网格 5×49×5 */
    for (int32_t s = 0; s < w->interp_count; ++s) {
        const int32_t child = CHUNKUP_WG_DF_NODES[w->interp_order[s]].a;
        for (int cx = 0; cx <= CHUNKUP_WG_NOISE_SIZE_XZ; ++cx) {
            const int32_t bx = c->min_bx + cx * CHUNKUP_WG_CELL_WIDTH;
            for (int cz = 0; cz <= CHUNKUP_WG_NOISE_SIZE_XZ; ++cz) {
                const int32_t bz = c->min_bz + cz * CHUNKUP_WG_CELL_WIDTH;
                for (int cy = 0; cy < CHUNKUP_WG_CORNER_Y; ++cy) {
                    const int32_t by = (CHUNKUP_WG_MIN_Y / CHUNKUP_WG_CELL_HEIGHT + cy) * CHUNKUP_WG_CELL_HEIGHT;
                    c->interp_vals[s][cx][cy][cz] = chunkup_wg_df(c, child, bx, by, bz);
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- 查询 API */

/** 任意方块坐标的 final_density（区块内；含插值）。> 0 → 实心（石头）。 */
CHUNKUP_FN double chunkup_wg_block_density(ChunkupWgChunk* c, int32_t bx, int32_t by, int32_t bz) {
    return chunkup_wg_df(c, CHUNKUP_WG_DF_FINAL_DENSITY, bx, by, bz);
}

/** 任意方块坐标的 initial_density_without_jaggedness（surface/生物群系用）。 */
CHUNKUP_FN double chunkup_wg_initial_density(ChunkupWgChunk* c, int32_t bx, int32_t by, int32_t bz) {
    return chunkup_wg_df(c, CHUNKUP_WG_DF_INITIAL_DENSITY_WITHOUT_JAGGEDNESS, bx, by, bz);
}

/* ---------------------------------------------------------------- Direct 求值 API */

/**
 * Direct 求值：给定 (world, block_xyz) 直接递归求值 DF 树，不经
 * FlatCache / NoiseInterpolator 预计算缓存。对应 vanilla：
 *   DensityFunction.compute(SinglePointContext(x, y, z))
 * marker 节点全透传，与 WgDump.java 黄金 dump 模式一致。
 *
 * 实现：file-scope stub ChunkupWgChunk 的 min_bx/min_bz/first_noise_*
 * 设为 INT32_MIN，强制 flat/interp 边界检查失败 → 透传递归路径，
 * stub.flat_vals / interp_vals 从不被访问。
 *
 * 适用：对拍验证、LOD 远景单点密度查询、F3 调试输出、生物群系 surface 单点。
 * 性能：每次调用整棵子树全递归（无 memoization），比 chunk-aware 路径
 *       慢 1-2 个数量级。批量化请用 chunkup_wg_chunk_init + chunkup_wg_df。
 *
 * 线程安全：file-scope static stub 非线程安全；多线程请自行构造
 *           ChunkupWgChunk 并调用 chunkup_wg_df。
 */
CHUNKUP_FN double chunkup_wg_eval_direct(
    ChunkupWgWorld* w, int32_t df_idx,
    int32_t bx, int32_t by, int32_t bz);

/** 单点 final_density（无 NoiseChunk 包装；> 0 → 实心）。 */
CHUNKUP_FN double chunkup_wg_block_density_direct(
    ChunkupWgWorld* w, int32_t bx, int32_t by, int32_t bz);

/** 单点 initial_density_without_jaggedness（无 NoiseChunk 包装）。 */
CHUNKUP_FN double chunkup_wg_initial_density_direct(
    ChunkupWgWorld* w, int32_t bx, int32_t by, int32_t bz);

/* --- 实现 --- */

static ChunkupWgChunk chunkup_wg_direct_stub;

CHUNKUP_FN double chunkup_wg_eval_direct(
    ChunkupWgWorld* w, int32_t df_idx,
    int32_t bx, int32_t by, int32_t bz) {
    /* min_bx/min_bz/first_noise_* 全 INT32_MIN：任意 block 坐标减出来
     * 都越界（k<0 或 lx>=16），flat_cache / interp marker 走 fallthrough
     * 递归路径，stub.flat_vals / interp_vals 永不访问。 */
    chunkup_wg_direct_stub.world = w;
    chunkup_wg_direct_stub.min_bx = INT32_MIN;
    chunkup_wg_direct_stub.min_bz = INT32_MIN;
    chunkup_wg_direct_stub.first_noise_x = INT32_MIN;
    chunkup_wg_direct_stub.first_noise_z = INT32_MIN;
    return chunkup_wg_df(&chunkup_wg_direct_stub, df_idx, bx, by, bz);
}

CHUNKUP_FN double chunkup_wg_block_density_direct(
    ChunkupWgWorld* w, int32_t bx, int32_t by, int32_t bz) {
    return chunkup_wg_eval_direct(w, CHUNKUP_WG_DF_FINAL_DENSITY, bx, by, bz);
}

CHUNKUP_FN double chunkup_wg_initial_density_direct(
    ChunkupWgWorld* w, int32_t bx, int32_t by, int32_t bz) {
    return chunkup_wg_eval_direct(w, CHUNKUP_WG_DF_INITIAL_DENSITY_WITHOUT_JAGGEDNESS, bx, by, bz);
}

#ifdef __cplusplus
}
#endif

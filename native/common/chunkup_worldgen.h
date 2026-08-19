#pragma once

/**
 * Minecraft 1.20.1 worldgen 密度函数树表驱动数据结构。
 *
 * 与 build/gen_worldgen_tables.py 生成的 chunkup_worldgen_tables.h 配套。
 * 节点类型枚举数值必须与生成器 TYPE_ORDER 一致。
 */

#include "chunkup_compat.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- DF 节点类型（与 gen_worldgen_tables.py TYPE_ORDER 一致） ---------- */

typedef enum ChunkupDfType {
    CHUNKUP_DF_CONSTANT = 0,
    CHUNKUP_DF_NOISE,
    CHUNKUP_DF_SHIFTED_NOISE,
    CHUNKUP_DF_SHIFT_A,
    CHUNKUP_DF_SHIFT_B,
    CHUNKUP_DF_SHIFT,
    CHUNKUP_DF_ADD,
    CHUNKUP_DF_MUL,
    CHUNKUP_DF_MIN,
    CHUNKUP_DF_MAX,
    CHUNKUP_DF_ABS,
    CHUNKUP_DF_SQUARE,
    CHUNKUP_DF_CUBE,
    CHUNKUP_DF_HALF_NEG,
    CHUNKUP_DF_QUARTER_NEG,
    CHUNKUP_DF_SQUEEZE,
    CHUNKUP_DF_CLAMP,
    CHUNKUP_DF_RANGE_CHOICE,
    CHUNKUP_DF_SPLINE,
    CHUNKUP_DF_Y_CLAMPED_GRADIENT,
    CHUNKUP_DF_MARKER_INTERPOLATED,
    CHUNKUP_DF_MARKER_FLAT_CACHE,
    CHUNKUP_DF_MARKER_CACHE_2D,
    CHUNKUP_DF_MARKER_CACHE_ONCE,
    CHUNKUP_DF_MARKER_CACHE_ALL_IN_CELL,
    CHUNKUP_DF_BLEND_ALPHA,
    CHUNKUP_DF_BLEND_OFFSET,
    CHUNKUP_DF_BEARDIFIER,
    CHUNKUP_DF_BLEND_DENSITY,
    CHUNKUP_DF_WEIRD_SCALED,
    CHUNKUP_DF_OLD_BLENDED,
    CHUNKUP_DF_END_ISLANDS,
} ChunkupDfType;

/**
 * DF 节点（16 字段定长，便于 GPU 常量内存/表查找）。
 *
 * 字段语义按 type：
 * - CONSTANT:            v0 = 值
 * - NOISE:               d = noise 索引, v0 = xz_scale, v1 = y_scale
 * - SHIFTED_NOISE:       a/b/c = shift_x/shift_y/shift_z DF, d = noise 索引, v0 = xz_scale, v1 = y_scale
 * - SHIFT_A/B/SHIFT:     d = noise 索引
 * - ADD/MUL/MIN/MAX:     a, b = 操作数 DF
 * - ABS/SQUARE/CUBE/HALF_NEG/QUARTER_NEG/SQUEEZE: a = 输入 DF
 * - CLAMP:               a = 输入 DF, v0 = min, v1 = max
 * - RANGE_CHOICE:        a = input, b = when_in_range, c = when_out_of_range, v0 = min_inclusive, v1 = max_exclusive
 * - SPLINE:              a = spline 节点索引
 * - Y_CLAMPED_GRADIENT:  v0 = from_y, v1 = to_y, v2 = from_value, v3 = to_value
 * - MARKER_*:            a = 包装 DF
 * - BLEND_ALPHA/OFFSET/BEARDIFIER/END_ISLANDS: 无参数
 * - BLEND_DENSITY:       a = 输入 DF
 * - WEIRD_SCALED:        a = input DF, d = noise 索引, v0 = rarity(0=type_1, 1=type_2)
 * - OLD_BLENDED:         v0 = xz_scale, v1 = y_scale, v2 = xz_factor, v3 = y_factor, d = smear_scale_multiplier
 */
typedef struct ChunkupDfNode {
    int32_t type;
    int32_t a;
    int32_t b;
    int32_t c;
    int32_t d;
    double v0;
    double v1;
    double v2;
    double v3;
} ChunkupDfNode;

/**
 * 样条节点（CubicSpline.Multipoint 扁平化）。
 * coord_df: 坐标 DF 节点索引
 * point_start / point_count: 在点表中的区间
 */
typedef struct ChunkupSplineNode {
    int32_t coord_df;
    int32_t point_start;
    int32_t point_count;
} ChunkupSplineNode;

/**
 * 样条控制点。
 * value_spline: >= 0 为 DF 常量节点索引；<= -2 为 spline 节点索引（-2-idx）
 */
typedef struct ChunkupSplinePoint {
    double location;
    double derivative;
    int32_t value_spline;
} ChunkupSplinePoint;

#ifdef __cplusplus
}
#endif

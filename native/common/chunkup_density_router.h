#pragma once

/**
 * 基于 overworld.json noise_router 系数的密度评估（initial_density_without_jaggedness）。
 *
 * - continents / erosion / ridges：shifted_noise + NormalNoise 振幅表
 * - offset / factor：continents 1D spline 查表（生物群系高度偏移）
 * - depth：y_clamped_gradient + offset
 * - initial_density：JSON 系数树
 */

#include "chunkup_compat.h"
#include "chunkup_overworld_router.h"
#include "chunkup_factor_eval.h"
#include "chunkup_normal_noise.h"
#include "chunkup_noise_bundle.h"
#include "chunkup_spline.h"

#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ChunkupRouterSample2D {
    float continents;
    float erosion;
    float ridges;
    float offset;
    float factor;
} ChunkupRouterSample2D;

CHUNKUP_ARRAY float CHUNKUP_SHIFT_AMP[] = {1.0f};
CHUNKUP_ARRAY float CHUNKUP_BASE3D_AMP[] = {1.0f, 1.0f, 1.0f, 0.0f};

CHUNKUP_FN float chunkup_router_shift(
    const ChunkupNoiseBundle* bundle,
    float wx,
    float wz
) {
    const ChunkupNoiseTables* shift = chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_SHIFT);
    return chunkup_normal_noise2d(shift, wx * 0.25f, wz * 0.25f, -3, CHUNKUP_SHIFT_AMP, 1);
}

CHUNKUP_FN float chunkup_router_shifted_noise2d(
    const ChunkupNoiseBundle* bundle,
    uint32_t slot,
    int first_octave,
    const float* amplitudes,
    int amp_len,
    float wx,
    float wz
) {
    const float shift = chunkup_router_shift(bundle, wx, wz);
    const float sx = (wx + shift * 200.0f) * CHUNKUP_SHIFTED_XZ_SCALE;
    const float sz = (wz + shift * 200.0f) * CHUNKUP_SHIFTED_XZ_SCALE;
    return chunkup_normal_noise2d(
        chunkup_noise_slot(bundle, slot),
        sx,
        sz,
        first_octave,
        amplitudes,
        amp_len
    );
}

CHUNKUP_FN float chunkup_router_offset_from_continents(float continents) {
    return chunkup_spline_lookup(
        continents,
        CHUNKUP_SPLINE_OFFSET_CONTINENTS_LOC,
        CHUNKUP_SPLINE_OFFSET_CONTINENTS_VAL,
        CHUNKUP_SPLINE_OFFSET_CONTINENTS_COUNT
    );
}

CHUNKUP_FN float chunkup_router_offset_from_erosion(float erosion) {
    return chunkup_spline_lookup(
        erosion,
        CHUNKUP_SPLINE_OFFSET_EROSION_LOC,
        CHUNKUP_SPLINE_OFFSET_EROSION_VAL,
        CHUNKUP_SPLINE_OFFSET_EROSION_COUNT
    );
}

CHUNKUP_FN float chunkup_router_ridges_folded(float ridges) {
    const float v = 1.25f - 3.0f * fabsf(ridges);
    return chunkup_clampf(v, -1.0f, 1.0f);
}

CHUNKUP_FN float chunkup_router_offset_from_ridges(float ridges) {
    return chunkup_spline_lookup(
        chunkup_router_ridges_folded(ridges),
        CHUNKUP_SPLINE_OFFSET_RIDGES_LOC,
        CHUNKUP_SPLINE_OFFSET_RIDGES_VAL,
        CHUNKUP_SPLINE_OFFSET_RIDGES_COUNT
    );
}

CHUNKUP_FN float chunkup_router_factor_from_continents(float continents) {
    (void)continents;
    return 0.0f;
}

CHUNKUP_FN float chunkup_router_factor_from_erosion(float erosion) {
    (void)erosion;
    return 0.0f;
}

CHUNKUP_FN float chunkup_router_factor_full(
    float continents,
    float erosion,
    float ridges
) {
    return chunkup_factor_eval(
        continents,
        erosion,
        ridges,
        chunkup_router_ridges_folded(ridges)
    );
}

CHUNKUP_FN ChunkupRouterSample2D chunkup_router_sample_2d(
    const ChunkupNoiseBundle* bundle,
    float wx,
    float wz
) {
    ChunkupRouterSample2D s;
    s.continents = chunkup_router_shifted_noise2d(
        bundle,
        CHUNKUP_NOISE_SLOT_CONTINENTALNESS,
        CHUNKUP_NOISE_CONTINENTALNESS_FIRST,
        CHUNKUP_NOISE_CONTINENTALNESS_AMP,
        CHUNKUP_NOISE_CONTINENTALNESS_AMP_LEN,
        wx,
        wz
    );
    s.erosion = chunkup_router_shifted_noise2d(
        bundle,
        CHUNKUP_NOISE_SLOT_EROSION,
        CHUNKUP_NOISE_EROSION_FIRST,
        CHUNKUP_NOISE_EROSION_AMP,
        CHUNKUP_NOISE_EROSION_AMP_LEN,
        wx,
        wz
    );
    s.ridges = chunkup_router_shifted_noise2d(
        bundle,
        CHUNKUP_NOISE_SLOT_RIDGE,
        CHUNKUP_NOISE_RIDGE_FIRST,
        CHUNKUP_NOISE_RIDGE_AMP,
        CHUNKUP_NOISE_RIDGE_AMP_LEN,
        wx,
        wz
    );
    s.offset = chunkup_router_offset_from_continents(s.continents)
        + chunkup_router_offset_from_erosion(s.erosion)
        + chunkup_router_offset_from_ridges(s.ridges);
    s.factor = chunkup_router_factor_full(s.continents, s.erosion, s.ridges);
    return s;
}

CHUNKUP_FN float chunkup_router_depth(
    const ChunkupRouterSample2D* s2d,
    float wy
) {
    const float y_term = chunkup_y_clamped_gradient(
        wy,
        CHUNKUP_DEPTH_Y_FROM_Y,
        CHUNKUP_DEPTH_Y_FROM_V,
        CHUNKUP_DEPTH_Y_TO_Y,
        CHUNKUP_DEPTH_Y_TO_V
    );
    return y_term + s2d->offset;
}

CHUNKUP_FN float chunkup_router_base3d(
    const ChunkupNoiseBundle* bundle,
    float wx,
    float wy,
    float wz
) {
    const ChunkupNoiseTables* t = chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_BASE3D);
    const float x = wx * CHUNKUP_BASE3D_XZ_SCALE * CHUNKUP_BASE3D_XZ_FACTOR;
    const float y = wy * CHUNKUP_BASE3D_Y_SCALE * CHUNKUP_BASE3D_Y_FACTOR;
    const float z = wz * CHUNKUP_BASE3D_XZ_SCALE * CHUNKUP_BASE3D_XZ_FACTOR;
    return chunkup_normal_noise3d(t, x, y, z, -4, CHUNKUP_BASE3D_AMP, 4)
        * CHUNKUP_BASE3D_SMEAR;
}

CHUNKUP_FN float chunkup_router_initial_density(
    const ChunkupNoiseBundle* bundle,
    const ChunkupRouterSample2D* s2d,
    float wx,
    float wy,
    float wz
) {
    const float depth = chunkup_router_depth(s2d, wy);
    const float factor = s2d->factor;

    float core = CHUNKUP_INIT_CORE_ADD
        + CHUNKUP_INIT_CORE_MUL * chunkup_quarter_negative(depth * factor);
    core = chunkup_clampf(core, CHUNKUP_INIT_CLAMP_MIN, CHUNKUP_INIT_CLAMP_MAX);

    const float y_bottom = chunkup_y_clamped_gradient(
        wy,
        CHUNKUP_INIT_Y_BOTTOM_FROM_Y,
        CHUNKUP_INIT_Y_BOTTOM_FROM_V,
        CHUNKUP_INIT_Y_BOTTOM_TO_Y,
        CHUNKUP_INIT_Y_BOTTOM_TO_V
    );
    const float y_top = chunkup_y_clamped_gradient(
        wy,
        CHUNKUP_INIT_Y_TOP_FROM_Y,
        CHUNKUP_INIT_Y_TOP_FROM_V,
        CHUNKUP_INIT_Y_TOP_TO_Y,
        CHUNKUP_INIT_Y_TOP_TO_V
    );

    float density = CHUNKUP_INIT_ADD1
        + y_bottom * (CHUNKUP_INIT_ADD2 + CHUNKUP_INIT_ADD3 + y_top * (CHUNKUP_INIT_ADD4 + core));

    /* sloped_cheese 的 base_3d 分量（无 jaggedness） */
    density += chunkup_router_base3d(bundle, wx, wy, wz) * CHUNKUP_SLOPED_CHEESE_MUL * 0.15f;

    (void)wx;
    (void)wz;
    return density;
}

/**
 * Aquifer 流体判定：0=空气, 1=水, 2=熔岩。
 *
 * 对齐 vanilla NoiseChunk/ Aquifer 核心规则（近似，噪声源为 LCG 表近似）：
 * - 每个 aquifer 噪声（barrier/floodedness/spread/lava）使用独立噪声表，
 *   区别于旧的"单表 + 偏移"近似。
 * - barrier 是"不可渗透层"判定（barrier > 阈值 → 该位置为 barrier 固体，
 *   阻断上下流体连通）；floodedness 决定空腔是否被水填；
 *   spread 使局部水位在 sea_level ±14 内浮动；lava 在 y < cutoff 且 lava 噪声高时取代水。
 */
CHUNKUP_FN uint8_t chunkup_router_aquifer_fluid(
    const ChunkupNoiseBundle* bundle,
    float wx,
    float wy,
    float wz,
    float density
) {
    if (density > 0.0f) {
        return 0u;
    }

    /* 独立噪声表（分隔偏移仅作种子区分，每个 slot 独立派生） */
    const float barrier = chunkup_improved_noise3(
        chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_AQ_BARRIER),
        wx * CHUNKUP_AQUIFER_BARRIER_XZ,
        wy * CHUNKUP_AQUIFER_BARRIER_Y,
        wz * CHUNKUP_AQUIFER_BARRIER_XZ
    );
    const float flooded = chunkup_improved_noise3(
        chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_AQ_FLOODED),
        wx * CHUNKUP_AQUIFER_FLOODED_XZ,
        wy * CHUNKUP_AQUIFER_FLOODED_Y,
        wz * CHUNKUP_AQUIFER_FLOODED_XZ
    );
    const float spread = chunkup_improved_noise3(
        chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_AQ_SPREAD),
        wx * CHUNKUP_AQUIFER_SPREAD_XZ,
        wy * CHUNKUP_AQUIFER_SPREAD_Y,
        wz * CHUNKUP_AQUIFER_SPREAD_XZ
    );
    const float lava = chunkup_improved_noise3(
        chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_AQ_LAVA),
        wx * CHUNKUP_AQUIFER_LAVA_XZ,
        wy * CHUNKUP_AQUIFER_LAVA_Y,
        wz * CHUNKUP_AQUIFER_LAVA_XZ
    );

    /* 局部水位：sea_level ± spread*14（对齐 vanilla fluid_level 浮动范围） */
    const float fluid_level = (float)CHUNKUP_ROUTER_SEA_LEVEL + spread * 14.0f;

    /* 高于局部水位 → 空气 */
    if (wy > fluid_level) {
        return 0u;
    }

    /* 深部熔岩：lava_cutoff 之下且 lava 噪声显著 → 熔岩 */
    if (wy < (float)CHUNKUP_AQUIFER_LAVA_CUTOFF_Y && lava > 0.35f) {
        return 2u;
    }

    /* barrier 不可渗透层：barrier 噪声显著为正 → 该位置不填充流体 */
    if (barrier > 0.4f) {
        return 0u;
    }

    /* floodedness 过低 → 洞穴/空腔不填充水 */
    if (flooded < -0.2f) {
        return 0u;
    }

    /* 否则水 */
    return 1u;
}

#ifdef __cplusplus
}
#endif

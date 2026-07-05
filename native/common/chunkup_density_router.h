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
    return chunkup_spline_lookup(
        continents,
        CHUNKUP_SPLINE_FACTOR_CONTINENTS_LOC,
        CHUNKUP_SPLINE_FACTOR_CONTINENTS_VAL,
        CHUNKUP_SPLINE_FACTOR_CONTINENTS_COUNT
    );
}

CHUNKUP_FN float chunkup_router_factor_from_erosion(float erosion) {
    return chunkup_spline_lookup(
        erosion,
        CHUNKUP_SPLINE_FACTOR_EROSION_LOC,
        CHUNKUP_SPLINE_FACTOR_EROSION_VAL,
        CHUNKUP_SPLINE_FACTOR_EROSION_COUNT
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
    s.factor = chunkup_router_factor_from_continents(s.continents)
        + chunkup_router_factor_from_erosion(s.erosion);
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

/** Aquifer 近似：0=空气, 1=水, 2=熔岩 */
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

    const ChunkupNoiseTables* aq = chunkup_noise_slot(bundle, CHUNKUP_NOISE_SLOT_AQUIFER);
    const float barrier = chunkup_improved_noise3(
        aq,
        wx * CHUNKUP_AQUIFER_BARRIER_XZ,
        wy * CHUNKUP_AQUIFER_BARRIER_Y,
        wz * CHUNKUP_AQUIFER_BARRIER_XZ
    );
    const float flooded = chunkup_improved_noise3(
        aq,
        wx * CHUNKUP_AQUIFER_FLOODED_XZ + 31.0f,
        wy * CHUNKUP_AQUIFER_FLOODED_Y,
        wz * CHUNKUP_AQUIFER_FLOODED_XZ + 31.0f
    );
    const float spread = chunkup_improved_noise3(
        aq,
        wx * CHUNKUP_AQUIFER_SPREAD_XZ + 67.0f,
        wy * CHUNKUP_AQUIFER_SPREAD_Y,
        wz * CHUNKUP_AQUIFER_SPREAD_XZ + 67.0f
    );
    const float lava = chunkup_improved_noise3(
        aq,
        wx * CHUNKUP_AQUIFER_LAVA_XZ + 103.0f,
        wy * CHUNKUP_AQUIFER_LAVA_Y,
        wz * CHUNKUP_AQUIFER_LAVA_XZ + 103.0f
    );

    const float fluid_level = (float)CHUNKUP_ROUTER_SEA_LEVEL + spread * 14.0f;
    const float status = flooded - barrier;

    if (wy > fluid_level || status <= 0.05f) {
        return 0u;
    }
    if (wy < (float)CHUNKUP_AQUIFER_LAVA_CUTOFF_Y && lava > 0.35f) {
        return 2u;
    }
    return 1u;
}

#ifdef __cplusplus
}
#endif

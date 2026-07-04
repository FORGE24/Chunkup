#pragma once

#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

static inline float chunkup_clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static inline float chunkup_lerp1d(float a, float b, float t) {
    return a + (b - a) * t;
}

/** 分段线性样条查表（控制点已按 location 升序）。 */
static inline float chunkup_spline_lookup(
    float x,
    const float* loc,
    const float* val,
    int count
) {
    if (count <= 0) {
        return 0.0f;
    }
    if (count == 1 || x <= loc[0]) {
        return val[0];
    }
    if (x >= loc[count - 1]) {
        return val[count - 1];
    }

    for (int i = 0; i < count - 1; ++i) {
        if (x >= loc[i] && x <= loc[i + 1]) {
            const float span = loc[i + 1] - loc[i];
            if (span <= 1e-6f) {
                return val[i + 1];
            }
            const float t = (x - loc[i]) / span;
            return chunkup_lerp1d(val[i], val[i + 1], t);
        }
    }
    return val[count - 1];
}

static inline float chunkup_y_clamped_gradient(
    float y,
    int from_y,
    float from_value,
    int to_y,
    float to_value
) {
    if (from_y == to_y) {
        return from_value;
    }
    const float t = chunkup_clampf((y - (float)from_y) / (float)(to_y - from_y), 0.0f, 1.0f);
    return chunkup_lerp1d(from_value, to_value, t);
}

static inline float chunkup_quarter_negative(float v) {
    return v > 0.0f ? -v : v;
}

static inline float chunkup_trilinear(
    float tx,
    float ty,
    float tz,
    float c000,
    float c100,
    float c010,
    float c110,
    float c001,
    float c101,
    float c011,
    float c111
) {
    const float a00 = chunkup_lerp1d(c000, c100, tx);
    const float a10 = chunkup_lerp1d(c010, c110, tx);
    const float a01 = chunkup_lerp1d(c001, c101, tx);
    const float a11 = chunkup_lerp1d(c011, c111, tx);
    const float a0 = chunkup_lerp1d(a00, a10, ty);
    const float a1 = chunkup_lerp1d(a01, a11, ty);
    return chunkup_lerp1d(a0, a1, tz);
}

#ifdef __cplusplus
}
#endif

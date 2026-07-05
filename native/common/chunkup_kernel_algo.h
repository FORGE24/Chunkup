#pragma once

#include "chunkup_kernel.h"
#include "chunkup_noise_bundle.h"
#include "chunkup_cell_fill.h"

#ifdef __CUDACC__
#define CHUNKUP_HD __host__ __device__ __forceinline__
#else
#define CHUNKUP_HD static inline
#endif

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__CUDACC__)
__constant__ ChunkupNoiseBundle chunkup_device_bundle;
#else
extern ChunkupNoiseBundle chunkup_active_bundle;
#endif

CHUNKUP_HD float chunkup_density_at(int wx, int wy, int wz, uint32_t seed) {
    (void)seed;
#if defined(__CUDACC__)
    const ChunkupNoiseBundle* bundle = &chunkup_device_bundle;
#else
    const ChunkupNoiseBundle* bundle = &chunkup_active_bundle;
#endif
    const float fx = (float)wx;
    const float fy = (float)wy;
    const float fz = (float)wz;
    ChunkupRouterSample2D s2d = chunkup_router_sample_2d(bundle, fx, fz);
    return chunkup_router_initial_density(bundle, &s2d, fx, fy, fz);
}

CHUNKUP_HD int chunkup_is_solid(float density) {
    return density > 0.0f;
}

/** 天空光：density<=0 透明；>=0.99 完全不透光；中间值按 opacity/15 编码（见 ChunkDensityReader）。 */
CHUNKUP_HD int chunkup_skylight_opacity(float density) {
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

CHUNKUP_HD int chunkup_skylight_propagate(int light, float density) {
    const int opacity = chunkup_skylight_opacity(density);
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

CHUNKUP_HD float chunkup_density_sample(
    const float* density,
    int lx,
    int ly,
    int lz,
    uint32_t height,
    uint32_t stride_y
) {
    if (lx < 0 || lz < 0 || lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return -1.0f;
    }
    if (ly < 0 || ly >= (int)height) {
        return -1.0f;
    }
    return density[chunkup_block_index(lx, ly, lz, stride_y)];
}

#ifdef __cplusplus
}
#endif

#undef CHUNKUP_HD

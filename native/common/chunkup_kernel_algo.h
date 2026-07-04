#pragma once

#include "chunkup_kernel.h"

#ifdef __CUDACC__
#define CHUNKUP_HD __host__ __device__ __forceinline__
#else
#define CHUNKUP_HD static inline
#endif

#ifdef __cplusplus
extern "C" {
#endif

CHUNKUP_HD uint32_t chunkup_hash_u32(uint32_t x, uint32_t y, uint32_t z, uint32_t seed) {
    uint32_t h = seed ^ x * 374761393u ^ y * 668265263u ^ z * 2147483647u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}

CHUNKUP_HD float chunkup_density_at(int wx, int wy, int wz, uint32_t seed) {
    float n = (float)(chunkup_hash_u32((uint32_t)wx, (uint32_t)wy, (uint32_t)wz, seed) & 0xFFFFu) / 65535.0f;
    float height_bias = ((float)wy / 256.0f) - 0.45f;
    return n * 0.4f + height_bias;
}

CHUNKUP_HD int chunkup_is_solid(float density) {
    return density > 0.0f;
}

CHUNKUP_HD uint32_t chunkup_block_index(int lx, int ly, int lz, uint32_t stride_y) {
    return (uint32_t)ly * stride_y + (uint32_t)lz * CHUNKUP_CHUNK_SIZE + (uint32_t)lx;
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

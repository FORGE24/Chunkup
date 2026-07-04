#pragma once

/**
 * Minecraft 1.20 ImprovedNoise / Perlin 的 portable 实现。
 *
 * 对应 net.minecraft.world.level.levelgen.synth.ImprovedNoise：
 * - 256 字节置换表 + xo/yo/zo 偏移
 * - Mth.smoothstep 缓动 + 8 点三线性插值
 * - SimplexNoise.GRADIENT 梯度表
 */

#include "chunkup_compat.h"
#include <stdint.h>
#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_NOISE_PERM_SIZE 512

typedef struct ChunkupNoiseTables {
    uint8_t perm[CHUNKUP_NOISE_PERM_SIZE];
    float xo;
    float yo;
    float zo;
} ChunkupNoiseTables;

CHUNKUP_FN uint32_t chunkup_noise_lcg(uint32_t* state) {
    *state = *state * 1664525u + 1013904223u;
    return *state;
}

CHUNKUP_FN void chunkup_noise_init_tables(ChunkupNoiseTables* tables, uint32_t seed) {
    uint32_t rng = seed ^ 0xA341316Cu;

    for (int i = 0; i < 256; ++i) {
        tables->perm[i] = (uint8_t)i;
    }

    for (int i = 255; i > 0; --i) {
        const uint32_t r = chunkup_noise_lcg(&rng);
        const int j = (int)(r % (uint32_t)(i + 1));
        const uint8_t tmp = tables->perm[i];
        tables->perm[i] = tables->perm[j];
        tables->perm[j] = tmp;
    }

    for (int i = 0; i < 256; ++i) {
        tables->perm[i + 256] = tables->perm[i];
    }

    tables->xo = (float)chunkup_noise_lcg(&rng) / 65536.0f * 256.0f;
    tables->yo = (float)chunkup_noise_lcg(&rng) / 65536.0f * 256.0f;
    tables->zo = (float)chunkup_noise_lcg(&rng) / 65536.0f * 256.0f;
}

CHUNKUP_FN int chunkup_noise_perm_index(const ChunkupNoiseTables* tables, int i) {
    return (int)tables->perm[i & 255];
}

CHUNKUP_FN float chunkup_noise_smoothstep(float t) {
    return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
}

CHUNKUP_FN float chunkup_noise_lerp(float a, float b, float t) {
    return a + t * (b - a);
}

CHUNKUP_FN float chunkup_noise_lerp3(
    float t_x,
    float t_y,
    float t_z,
    float c000,
    float c100,
    float c010,
    float c110,
    float c001,
    float c101,
    float c011,
    float c111
) {
    const float a = chunkup_noise_lerp(c000, c100, t_x);
    const float b = chunkup_noise_lerp(c010, c110, t_x);
    const float c = chunkup_noise_lerp(c001, c101, t_x);
    const float d = chunkup_noise_lerp(c011, c111, t_x);
    const float e = chunkup_noise_lerp(a, b, t_y);
    const float f = chunkup_noise_lerp(c, d, t_y);
    return chunkup_noise_lerp(e, f, t_z);
}

CHUNKUP_FN float chunkup_noise_grad_dot(int hash, float x, float y, float z) {
    switch (hash & 15) {
        case 0:  return  x + y;
        case 1:  return -x + y;
        case 2:  return  x - y;
        case 3:  return -x - y;
        case 4:  return  x + z;
        case 5:  return -x + z;
        case 6:  return  x - z;
        case 7:  return -x - z;
        case 8:  return  y + z;
        case 9:  return -y + z;
        case 10: return  y - z;
        case 11: return -y - z;
        case 12: return  y + x;
        case 13: return -y + z;
        case 14: return  y - x;
        default: return -y - z;
    }
}

CHUNKUP_FN float chunkup_improved_noise3(
    const ChunkupNoiseTables* tables,
    float x,
    float y,
    float z
) {
    x += tables->xo;
    y += tables->yo;
    z += tables->zo;

    const int x0 = (int)floorf(x);
    const int y0 = (int)floorf(y);
    const int z0 = (int)floorf(z);

    const float fx = x - (float)x0;
    const float fy = y - (float)y0;
    const float fz = z - (float)z0;

    const int aa = chunkup_noise_perm_index(tables, chunkup_noise_perm_index(tables, x0) + y0);
    const int ab = chunkup_noise_perm_index(tables, chunkup_noise_perm_index(tables, x0) + y0 + 1);
    const int ba = chunkup_noise_perm_index(tables, chunkup_noise_perm_index(tables, x0 + 1) + y0);
    const int bb = chunkup_noise_perm_index(tables, chunkup_noise_perm_index(tables, x0 + 1) + y0 + 1);

    const float c000 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, aa + z0), fx, fy, fz);
    const float c100 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, ba + z0), fx - 1.0f, fy, fz);
    const float c010 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, ab + z0), fx, fy - 1.0f, fz);
    const float c110 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, bb + z0), fx - 1.0f, fy - 1.0f, fz);
    const float c001 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, aa + z0 + 1), fx, fy, fz - 1.0f);
    const float c101 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, ba + z0 + 1), fx - 1.0f, fy, fz - 1.0f);
    const float c011 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, ab + z0 + 1), fx, fy - 1.0f, fz - 1.0f);
    const float c111 = chunkup_noise_grad_dot(chunkup_noise_perm_index(tables, bb + z0 + 1), fx - 1.0f, fy - 1.0f, fz - 1.0f);

    const float u = chunkup_noise_smoothstep(fx);
    const float v = chunkup_noise_smoothstep(fy);
    const float w = chunkup_noise_smoothstep(fz);

    return chunkup_noise_lerp3(u, v, w, c000, c100, c010, c110, c001, c101, c011, c111);
}

/** 多八度 NormalNoise 风格叠加（振幅随频率递减）。 */
CHUNKUP_FN float chunkup_fractal_noise3(
    const ChunkupNoiseTables* tables,
    float x,
    float y,
    float z,
    int octaves,
    float lacunarity,
    float persistence
) {
    float sum = 0.0f;
    float amp = 1.0f;
    float freq = 1.0f;
    float norm = 0.0f;

    for (int i = 0; i < octaves; ++i) {
        sum += chunkup_improved_noise3(tables, x * freq, y * freq, z * freq) * amp;
        norm += amp;
        amp *= persistence;
        freq *= lacunarity;
    }

    return norm > 0.0f ? sum / norm : 0.0f;
}

#ifdef __cplusplus
}
#endif

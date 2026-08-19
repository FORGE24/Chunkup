#pragma once

/**
 * Minecraft 1.20.1 噪声层精确复刻（double 精度）。
 *
 * 对应 vanilla：
 * - net.minecraft.world.level.levelgen.synth.ImprovedNoise（256 置换表，索引全部 & 0xFF）
 * - net.minecraft.world.level.levelgen.synth.PerlinNoise（新版：octave_N 派生，wrap 到 ±33554432）
 * - net.minecraft.world.level.levelgen.synth.NormalNoise（双 PerlinNoise + valueFactor）
 *
 * 位精确：与 Java double 运算一致。
 */

#include "chunkup_compat.h"
#include "chunkup_xoroshiro.h"
#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_PERLIN_MAX_OCTAVES 32

/* ---------- ImprovedNoise ---------- */

typedef struct ChunkupImprovedNoiseD {
    uint8_t p[256];
    double xo;
    double yo;
    double zo;
} ChunkupImprovedNoiseD;

CHUNKUP_FN void chunkup_improved_init(
    ChunkupImprovedNoiseD* n,
    ChunkupRandomSource* rs
) {
    n->xo = chunkup_rs_next_double(rs) * 256.0;
    n->yo = chunkup_rs_next_double(rs) * 256.0;
    n->zo = chunkup_rs_next_double(rs) * 256.0;
    for (int i = 0; i < 256; ++i) {
        n->p[i] = (uint8_t)i;
    }
    for (int i = 0; i < 256; ++i) {
        const int j = chunkup_rs_next_int_bound(rs, 256 - i);
        const uint8_t b = n->p[i];
        n->p[i] = n->p[i + j];
        n->p[i + j] = b;
    }
}

CHUNKUP_FN int chunkup_improved_pi(const ChunkupImprovedNoiseD* n, int i) {
    return (int)n->p[i & 0xFF];
}

CHUNKUP_FN double chunkup_improved_grad_dot(int i, double d, double e, double f) {
    /* SimplexNoise.GRADIENT[i & 15] 的 dot */
    switch (i & 15) {
        case 0:  return d + e;      /* { 1, 1, 0} */
        case 1:  return -d + e;     /* {-1, 1, 0} */
        case 2:  return d - e;      /* { 1,-1, 0} */
        case 3:  return -d - e;     /* {-1,-1, 0} */
        case 4:  return d + f;      /* { 1, 0, 1} */
        case 5:  return -d + f;     /* {-1, 0, 1} */
        case 6:  return d - f;      /* { 1, 0,-1} */
        case 7:  return -d - f;     /* {-1, 0,-1} */
        case 8:  return e + f;      /* { 0, 1, 1} */
        case 9:  return -e + f;     /* { 0,-1, 1} */
        case 10: return e - f;      /* { 0, 1,-1} */
        case 11: return -e - f;     /* { 0,-1,-1} */
        case 12: return d + e;      /* { 1, 1, 0}（重复） */
        case 13: return -e + f;     /* { 0,-1, 1}（重复） */
        case 14: return -d + e;     /* {-1, 1, 0}（重复） */
        default: return -e - f;     /* { 0,-1,-1}（重复） */
    }
}

CHUNKUP_FN double chunkup_mth_smoothstep(double d) {
    return d * d * d * (d * (d * 6.0 - 15.0) + 10.0);
}

CHUNKUP_FN double chunkup_mth_lerp(double d, double e, double f) {
    return e + d * (f - e);
}

CHUNKUP_FN double chunkup_mth_lerp3(
    double d, double e, double f,
    double g, double h, double i, double j,
    double k, double l, double m, double n
) {
    /* lerp(f, lerp2(d, e, g,h,i,j), lerp2(d, e, k,l,m,n)) */
    const double a = chunkup_mth_lerp(e, chunkup_mth_lerp(d, g, h), chunkup_mth_lerp(d, i, j));
    const double b = chunkup_mth_lerp(e, chunkup_mth_lerp(d, k, l), chunkup_mth_lerp(d, m, n));
    return chunkup_mth_lerp(f, a, b);
}

CHUNKUP_FN double chunkup_improved_sample_and_lerp(
    const ChunkupImprovedNoiseD* n,
    int l, int m, int o,
    double p, double q, double r,
    double q_smooth
) {
    const int a = chunkup_improved_pi(n, l);
    const int b = chunkup_improved_pi(n, l + 1);
    const int c = chunkup_improved_pi(n, a + m);
    const int dd = chunkup_improved_pi(n, a + m + 1);
    const int ee = chunkup_improved_pi(n, b + m);
    const int ff = chunkup_improved_pi(n, b + m + 1);

    const double g0 = chunkup_improved_grad_dot(chunkup_improved_pi(n, c + o), p, q, r);
    const double g1 = chunkup_improved_grad_dot(chunkup_improved_pi(n, ee + o), p - 1.0, q, r);
    const double g2 = chunkup_improved_grad_dot(chunkup_improved_pi(n, dd + o), p, q - 1.0, r);
    const double g3 = chunkup_improved_grad_dot(chunkup_improved_pi(n, ff + o), p - 1.0, q - 1.0, r);
    const double g4 = chunkup_improved_grad_dot(chunkup_improved_pi(n, c + o + 1), p, q, r - 1.0);
    const double g5 = chunkup_improved_grad_dot(chunkup_improved_pi(n, ee + o + 1), p - 1.0, q, r - 1.0);
    const double g6 = chunkup_improved_grad_dot(chunkup_improved_pi(n, dd + o + 1), p, q - 1.0, r - 1.0);
    const double g7 = chunkup_improved_grad_dot(chunkup_improved_pi(n, ff + o + 1), p - 1.0, q - 1.0, r - 1.0);

    const double sy = chunkup_mth_smoothstep(p);
    const double sz = chunkup_mth_smoothstep(q_smooth);
    const double sw = chunkup_mth_smoothstep(r);
    return chunkup_mth_lerp3(sy, sz, sw, g0, g1, g2, g3, g4, g5, g6, g7);
}

/** ImprovedNoise.noise(x, y, z)（无 y-clamp，即 g=h=0）。 */
CHUNKUP_FN double chunkup_improved_noise(
    const ChunkupImprovedNoiseD* n,
    double d,
    double e,
    double f
) {
    const double i = d + n->xo;
    const double j = e + n->yo;
    const double k = f + n->zo;
    const int l = (int)floor(i);
    const int m = (int)floor(j);
    const int o = (int)floor(k);
    const double p = i - l;
    const double q = j - m;
    const double r = k - o;
    return chunkup_improved_sample_and_lerp(n, l, m, o, p, q, r, q);
}

/**
 * ImprovedNoise.noise(x, y, z, yClampGap, clampStart)——带 y 阶梯化（BlendedNoise 用）。
 * Java: if (g != 0) { r = (h >= 0 && h < p) ? h : p; s = floor(r/g + 1.0E-7F) * g; } else s = 0;
 *       sampleAndLerp(..., p - s, q, ..., p)
 */
CHUNKUP_FN double chunkup_improved_noise_clamped(
    const ChunkupImprovedNoiseD* n,
    double d,
    double e,
    double f,
    double g,
    double h
) {
    const double i = d + n->xo;
    const double j = e + n->yo;
    const double k = f + n->zo;
    const int l = (int)floor(i);
    const int m = (int)floor(j);
    const int o = (int)floor(k);
    const double p = i - l;
    const double q = j - m;
    const double r = k - o;
    double s = 0.0;
    if (g != 0.0) {
        const double rr = (h >= 0.0 && h < q) ? h : q;
        s = floor(rr / g + (double)(float)1.0e-7) * g;
    }
    return chunkup_improved_sample_and_lerp(n, l, m, o, p, q - s, r, q);
}

/* ---------- PerlinNoise ---------- */

typedef struct ChunkupPerlinNoiseD {
    ChunkupImprovedNoiseD levels[CHUNKUP_PERLIN_MAX_OCTAVES];
    double amplitudes[CHUNKUP_PERLIN_MAX_OCTAVES];
    int32_t level_count;
    int32_t first_octave;
    double lowest_freq_input_factor;   /* 2^(-j), j = -firstOctave */
    double lowest_freq_value_factor;   /* 2^(i-1) / (2^i - 1) */
} ChunkupPerlinNoiseD;

/** PerlinNoise.wrap: d - lfloor(d/33554432 + 0.5) * 33554432 */
CHUNKUP_FN double chunkup_perlin_wrap(double d) {
    return d - floor(d / 3.3554432E7 + 0.5) * 3.3554432E7;
}

/**
 * PerlinNoise.create(randomSource, firstOctave, amplitudes)（新版 bl=true）。
 * 从同一 randomSource 连续创建（forkPositional 消耗 2 个 nextLong）。
 */
CHUNKUP_FN void chunkup_perlin_init(
    ChunkupPerlinNoiseD* pn,
    ChunkupRandomSource* rs,
    int32_t first_octave,
    const double* amplitudes,
    int32_t amp_len
) {
    pn->first_octave = first_octave;
    pn->level_count = amp_len;
    for (int i = 0; i < amp_len; ++i) {
        pn->amplitudes[i] = amplitudes[i];
    }

    const ChunkupPositionalFactory fork = chunkup_rs_fork_positional(rs);
    for (int k = 0; k < amp_len; ++k) {
        if (amplitudes[k] != 0.0) {
            /* fromHashOf("octave_" + (firstOctave + k)) */
            char buf[32];
            const int octave = first_octave + k;
            int pos = 0;
            const char* prefix = "octave_";
            while (*prefix) {
                buf[pos++] = *prefix++;
            }
            if (octave < 0) {
                buf[pos++] = '-';
            }
            char digits[12];
            int nd = 0;
            int v = octave < 0 ? -octave : octave;
            if (v == 0) {
                digits[nd++] = '0';
            }
            while (v > 0) {
                digits[nd++] = (char)('0' + (v % 10));
                v /= 10;
            }
            while (nd > 0) {
                buf[pos++] = digits[--nd];
            }
            buf[pos] = '\0';

            ChunkupRandomSource octave_rs;
            chunkup_pf_from_hash_of(&fork, buf, (size_t)pos, &octave_rs);
            chunkup_improved_init(&pn->levels[k], &octave_rs);
        }
    }

    const int j = -first_octave;
    const int i = amp_len;
    pn->lowest_freq_input_factor = pow(2.0, (double)(-j));
    pn->lowest_freq_value_factor = pow(2.0, (double)(i - 1)) / (pow(2.0, (double)i) - 1.0);
}

/** PerlinNoise.getValue(x, y, z)。 */
CHUNKUP_FN double chunkup_perlin_get(
    const ChunkupPerlinNoiseD* pn,
    double d,
    double e,
    double f
) {
    double sum = 0.0;
    double jf = pn->lowest_freq_input_factor;
    double kf = pn->lowest_freq_value_factor;
    for (int l = 0; l < pn->level_count; ++l) {
        if (pn->amplitudes[l] != 0.0) {
            const double m = chunkup_improved_noise(
                &pn->levels[l],
                chunkup_perlin_wrap(d * jf),
                chunkup_perlin_wrap(e * jf),
                chunkup_perlin_wrap(f * jf)
            );
            sum += pn->amplitudes[l] * m * kf;
        }
        jf *= 2.0;
        kf /= 2.0;
    }
    return sum;
}

/* ---------- NormalNoise ---------- */

typedef struct ChunkupNormalNoiseD {
    ChunkupPerlinNoiseD first;
    ChunkupPerlinNoiseD second;
    double value_factor;
} ChunkupNormalNoiseD;

CHUNKUP_FN double chunkup_normal_expected_deviation(int i) {
    return 0.1 * (1.0 + 1.0 / (double)(i + 1));
}

/** NormalNoise.create(randomSource, firstOctave, amplitudes)。 */
CHUNKUP_FN void chunkup_normal_init(
    ChunkupNormalNoiseD* nn,
    ChunkupRandomSource* rs,
    int32_t first_octave,
    const double* amplitudes,
    int32_t amp_len
) {
    chunkup_perlin_init(&nn->first, rs, first_octave, amplitudes, amp_len);
    chunkup_perlin_init(&nn->second, rs, first_octave, amplitudes, amp_len);

    int min_idx = -1;
    int max_idx = -1;
    for (int i = 0; i < amp_len; ++i) {
        if (amplitudes[i] != 0.0) {
            if (min_idx < 0) {
                min_idx = i;
            }
            max_idx = i;
        }
    }
    const int range = (min_idx >= 0 && max_idx >= 0) ? (max_idx - min_idx) : 0;
    nn->value_factor = 0.16666666666666666 / chunkup_normal_expected_deviation(range);
}

/** NormalNoise.getValue(x, y, z)。 */
CHUNKUP_FN double chunkup_normal_get(
    const ChunkupNormalNoiseD* nn,
    double d,
    double e,
    double f
) {
    const double g = d * 1.0181268882175227;
    const double h = e * 1.0181268882175227;
    const double i = f * 1.0181268882175227;
    return (chunkup_perlin_get(&nn->first, d, e, f) + chunkup_perlin_get(&nn->second, g, h, i)) * nn->value_factor;
}

/* ---------- Legacy PerlinNoise（BlendedNoise 专用，bl=false 构造） ---------- */

CHUNKUP_FN void chunkup_rs_consume_count(ChunkupRandomSource* rs, int count) {
    for (int i = 0; i < count; ++i) {
        chunkup_xoro_next(&rs->rng);
    }
}

/**
 * PerlinNoise legacy 构造（createLegacyForBlendedNoise）。
 * amplitudes 全非零时：首个 ImprovedNoise 复用于 level[j]，其余顺序创建。
 */
CHUNKUP_FN void chunkup_perlin_init_legacy(
    ChunkupPerlinNoiseD* pn,
    ChunkupRandomSource* rs,
    int32_t first_octave,
    const double* amplitudes,
    int32_t amp_len
) {
    pn->first_octave = first_octave;
    pn->level_count = amp_len;
    for (int i = 0; i < amp_len; ++i) {
        pn->amplitudes[i] = amplitudes[i];
    }

    const int j = -first_octave;
    chunkup_improved_init(&pn->levels[0], rs);  /* 临时：首个 ImprovedNoise */
    /* 找到首个非零振幅位置（即 level[j] 若 j 在范围内且非零） */
    if (j >= 0 && j < amp_len && amplitudes[j] != 0.0) {
        /* 首个 ImprovedNoise 放到 levels[j]，再从 j-1 往 0 依次创建 */
        const ChunkupImprovedNoiseD first = pn->levels[0];
        for (int k = j - 1; k >= 0; --k) {
            if (k < amp_len) {
                if (amplitudes[k] != 0.0) {
                    chunkup_improved_init(&pn->levels[k], rs);
                } else {
                    chunkup_rs_consume_count(rs, 262);
                }
            } else {
                chunkup_rs_consume_count(rs, 262);
            }
        }
        /* 首个放最后赋值避免被覆盖（k 循环从 j-1 开始不会碰 levels[j]） */
        pn->levels[j] = first;
        /* k >= amp_len 的部分（正 octave）不出现（overworld 参数 j = amp_len-1） */
    } else {
        /* 首个 ImprovedNoise 不被使用：按 legacy 逻辑从 j-1 往 0（此处不出现） */
        for (int k = j - 1; k >= 0; --k) {
            if (k < amp_len) {
                if (amplitudes[k] != 0.0) {
                    chunkup_improved_init(&pn->levels[k], rs);
                } else {
                    chunkup_rs_consume_count(rs, 262);
                }
            } else {
                chunkup_rs_consume_count(rs, 262);
            }
        }
    }

    const int i = amp_len;
    pn->lowest_freq_input_factor = pow(2.0, (double)(-j));
    pn->lowest_freq_value_factor = pow(2.0, (double)(i - 1)) / (pow(2.0, (double)i) - 1.0);
}

/* ---------- BlendedNoise（old_blended_noise） ---------- */

typedef struct ChunkupBlendedNoiseD {
    ChunkupPerlinNoiseD min_limit;   /* octaves -15..0 */
    ChunkupPerlinNoiseD max_limit;   /* octaves -15..0 */
    ChunkupPerlinNoiseD main;        /* octaves -7..0  */
    double xz_multiplier;            /* 684.412 * xzScale */
    double y_multiplier;             /* 684.412 * yScale  */
    double xz_factor;
    double y_factor;
    double smear_scale_multiplier;
} ChunkupBlendedNoiseD;

CHUNKUP_FN double chunkup_mth_clamped_lerp(double d, double e, double f) {
    return f < 0.0 ? d : (f > 1.0 ? e : chunkup_mth_lerp(f, d, e));
}

CHUNKUP_FN void chunkup_blended_init(
    ChunkupBlendedNoiseD* bn,
    ChunkupRandomSource* rs,
    double xz_scale,
    double y_scale,
    double xz_factor,
    double y_factor,
    double smear_scale_multiplier
) {
    /* legacy 构造顺序：minLimit(-15..0), maxLimit(-15..0), main(-7..0)，共享同一 rs */
    double amps16[CHUNKUP_PERLIN_MAX_OCTAVES];
    double amps8[CHUNKUP_PERLIN_MAX_OCTAVES];
    for (int i = 0; i < 16; ++i) {
        amps16[i] = 1.0;
    }
    for (int i = 0; i < 8; ++i) {
        amps8[i] = 1.0;
    }
    chunkup_perlin_init_legacy(&bn->min_limit, rs, -15, amps16, 16);
    chunkup_perlin_init_legacy(&bn->max_limit, rs, -15, amps16, 16);
    chunkup_perlin_init_legacy(&bn->main, rs, -7, amps8, 8);

    bn->xz_multiplier = 684.412 * xz_scale;
    bn->y_multiplier = 684.412 * y_scale;
    bn->xz_factor = xz_factor;
    bn->y_factor = y_factor;
    bn->smear_scale_multiplier = smear_scale_multiplier;
}

/** BlendedNoise.compute(blockX, blockY, blockZ)。 */
CHUNKUP_FN double chunkup_blended_get(
    const ChunkupBlendedNoiseD* bn,
    double bx,
    double by,
    double bz
) {
    const double d = bx * bn->xz_multiplier;
    const double e = by * bn->y_multiplier;
    const double f = bz * bn->xz_multiplier;
    const double g = d / bn->xz_factor;
    const double h = e / bn->y_factor;
    const double i = f / bn->xz_factor;
    const double j = bn->y_multiplier * bn->smear_scale_multiplier;
    const double k = j / bn->y_factor;
    double n = 0.0;
    double l = 0.0;
    double m = 0.0;
    const int bl2 = 0, bl3 = 0;
    (void)bl2;
    (void)bl3;

    double o = 1.0;
    for (int p = 0; p < 8; ++p) {
        /* getOctaveNoise(i) = noiseLevels[len-1-i] */
        const ChunkupImprovedNoiseD* inoise = &bn->main.levels[bn->main.level_count - 1 - p];
        n += chunkup_improved_noise_clamped(
            inoise,
            chunkup_perlin_wrap(g * o),
            chunkup_perlin_wrap(h * o),
            chunkup_perlin_wrap(i * o),
            k * o, h * o
        ) / o;
        o /= 2.0;
    }

    const double q = (n / 10.0 + 1.0) / 2.0;
    const int q_ge1 = q >= 1.0;
    const int q_le0 = q <= 0.0;
    o = 1.0;
    for (int r = 0; r < 16; ++r) {
        const double s = chunkup_perlin_wrap(d * o);
        const double t = chunkup_perlin_wrap(e * o);
        const double u = chunkup_perlin_wrap(f * o);
        const double v = j * o;
        if (!q_ge1) {
            const ChunkupImprovedNoiseD* inoise = &bn->min_limit.levels[bn->min_limit.level_count - 1 - r];
            l += chunkup_improved_noise_clamped(inoise, s, t, u, v, e * o) / o;
        }
        if (!q_le0) {
            const ChunkupImprovedNoiseD* inoise = &bn->max_limit.levels[bn->max_limit.level_count - 1 - r];
            m += chunkup_improved_noise_clamped(inoise, s, t, u, v, e * o) / o;
        }
        o /= 2.0;
    }

    return chunkup_mth_clamped_lerp(l / 512.0, m / 512.0, q) / 128.0;
}

#ifdef __cplusplus
}
#endif

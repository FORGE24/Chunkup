#pragma once

/**
 * Minecraft 1.20.1 overworld buildSurface 完整复刻（portable C）。
 *
 * 对应 vanilla：
 * - SurfaceRuleData.overworldLike(true, false, true)  完整规则树（硬编码）
 * - SurfaceSystem.buildSurface 主循环（stoneDepth/waterHeight 状态机）
 * - SurfaceRules.Context（LazyXZ/LazyY 条件惰性求值）
 * - SurfaceSystem 噪声（surface/surface_secondary/calcite/... 15 个）
 * - clayBands 192 格条纹生成（fromHashOf("clay_bands")）
 * - preliminarySurfaceLevel（initial_density_without_jaggedness 直查）
 * - BiomeManager.getBiome 8 角点 Voronoi 扰动（quart 网格 + zoom seed）
 * - Biome.coldEnoughToSnow（高度衰减温度 + PerlinSimplexNoise）
 *
 * 精度策略：随机源/噪声全部走 chunkup_xoroshiro.h / chunkup_perlin.h 的
 * 位精确实现；规则树与条件语义 1:1 对照反编译源码。
 *
 * 未实现（后续补）：erodedBadlandsExtension / frozenOceanExtension 扩展柱。
 */

#include "chunkup_compat.h"
#include "chunkup_perlin.h"
#include "chunkup_wg_eval.h"
#include "chunkup_xoroshiro.h"
#include <float.h>
#include <math.h>
#include <string.h>
#ifdef CHUNKUP_SR_DEBUG
#include <stdio.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ================================================================ 块 ID */

typedef enum ChunkupSrBlock {
    SR_BLOCK_SKIP = 0,          /* 0 = 不替换 */
    SR_BLOCK_AIR = 1,
    SR_BLOCK_BEDROCK = 2,
    SR_BLOCK_WHITE_TERRACOTTA = 3,
    SR_BLOCK_ORANGE_TERRACOTTA = 4,
    SR_BLOCK_TERRACOTTA = 5,
    SR_BLOCK_YELLOW_TERRACOTTA = 6,
    SR_BLOCK_BROWN_TERRACOTTA = 7,
    SR_BLOCK_RED_TERRACOTTA = 8,
    SR_BLOCK_LIGHT_GRAY_TERRACOTTA = 9,
    SR_BLOCK_RED_SAND = 10,
    SR_BLOCK_RED_SANDSTONE = 11,
    SR_BLOCK_STONE = 12,
    SR_BLOCK_DEEPSLATE = 13,
    SR_BLOCK_DIRT = 14,
    SR_BLOCK_PODZOL = 15,
    SR_BLOCK_COARSE_DIRT = 16,
    SR_BLOCK_MYCELIUM = 17,
    SR_BLOCK_GRASS_BLOCK = 18,
    SR_BLOCK_CALCITE = 19,
    SR_BLOCK_GRAVEL = 20,
    SR_BLOCK_SAND = 21,
    SR_BLOCK_SANDSTONE = 22,
    SR_BLOCK_PACKED_ICE = 23,
    SR_BLOCK_SNOW_BLOCK = 24,
    SR_BLOCK_MUD = 25,
    SR_BLOCK_POWDER_SNOW = 26,
    SR_BLOCK_ICE = 27,
    SR_BLOCK_WATER = 28,
    SR_BLOCK_LAVA = 29,         /* 噪声阶段 aquifer 产物（仅作流体判定，不被规则替换） */
} ChunkupSrBlock;

/* ================================================================ biome 分类（Kotlin 侧映射） */

typedef enum ChunkupSrBiome {
    SR_BIOME_OTHER = 0,
    SR_BIOME_FROZEN_PEAKS = 1,
    SR_BIOME_SNOWY_SLOPES = 2,
    SR_BIOME_JAGGED_PEAKS = 3,
    SR_BIOME_GROVE = 4,
    SR_BIOME_WINDSWEPT_SAVANNA = 5,
    SR_BIOME_WINDSWEPT_GRAVELLY_HILLS = 6,
    SR_BIOME_WINDSWEPT_HILLS = 7,
    SR_BIOME_MANGROVE_SWAMP = 8,
    SR_BIOME_OLD_GROWTH_PINE_TAIGA = 9,
    SR_BIOME_OLD_GROWTH_SPRUCE_TAIGA = 10,
    SR_BIOME_ICE_SPIKES = 11,
    SR_BIOME_MUSHROOM_FIELDS = 12,
    SR_BIOME_STONY_PEAKS = 13,
    SR_BIOME_STONY_SHORE = 14,
    SR_BIOME_DRIPSTONE_CAVES = 15,
    SR_BIOME_WARM_OCEAN = 16,
    SR_BIOME_BEACH = 17,
    SR_BIOME_SNOWY_BEACH = 18,
    SR_BIOME_DESERT = 19,
    SR_BIOME_BADLANDS = 20,
    SR_BIOME_ERODED_BADLANDS = 21,
    SR_BIOME_WOODED_BADLANDS = 22,
    SR_BIOME_SWAMP = 23,
    SR_BIOME_FROZEN_OCEAN = 24,
    SR_BIOME_DEEP_FROZEN_OCEAN = 25,
    SR_BIOME_LUKEWARM_OCEAN = 26,
    SR_BIOME_DEEP_LUKEWARM_OCEAN = 27,
    SR_BIOME_COUNT = 28,
} ChunkupSrBiome;

/* ================================================================ java.util.Random（LegacyRandomSource 语义） */

typedef struct ChunkupJavaRandom {
    uint64_t seed;   /* 48-bit */
} ChunkupJavaRandom;

CHUNKUP_FN void chunkup_jr_set_seed(ChunkupJavaRandom* r, uint64_t seed) {
    r->seed = (seed ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1ULL);
}

CHUNKUP_FN int32_t chunkup_jr_next(ChunkupJavaRandom* r, int bits) {
    r->seed = (r->seed * 0x5DEECE66DULL + 0xBULL) & ((1ULL << 48) - 1ULL);
    return (int32_t)(uint32_t)(r->seed >> (48 - bits));
}

CHUNKUP_FN int32_t chunkup_jr_next_int(ChunkupJavaRandom* r, int32_t bound) {
    /* java.util.Random.nextInt(bound) */
    if (bound <= 0) return 0;
    if ((bound & -bound) == bound) {
        return (int32_t)((int64_t)((uint64_t)bound * (uint64_t)(uint32_t)chunkup_jr_next(r, 31)) >> 31);
    }
    int32_t bits, val;
    do {
        bits = chunkup_jr_next(r, 31);
        val = bits % bound;
    } while ((int32_t)((uint32_t)bits - (uint32_t)val + (uint32_t)bound) < 0);
    return val;
}

CHUNKUP_FN double chunkup_jr_next_double(ChunkupJavaRandom* r) {
    return (((uint64_t)(uint32_t)chunkup_jr_next(r, 26) << 27) + (uint64_t)(uint32_t)chunkup_jr_next(r, 27)) * 0x1.0p-54;
}

CHUNKUP_FN int chunkup_jr_next_boolean(ChunkupJavaRandom* r) {
    return chunkup_jr_next(r, 1) != 0;
}

/* XoroshiroRandomSource.nextBoolean() = next(1) != 0 */
CHUNKUP_FN int chunkup_rs_next_boolean(ChunkupRandomSource* rs) {
    return (int)(chunkup_rs_next_bits(rs, 1) & 1ULL) != 0;
}

/* ================================================================ SimplexNoise（温度噪声用，固定种子 1234） */

#define SR_SIMPLEX_PERM 512

typedef struct ChunkupSimplexNoise {
    int32_t p[SR_SIMPLEX_PERM];
    double xo, yo, zo;
} ChunkupSimplexNoise;

static const int32_t SR_SIMPLEX_GRAD[16][3] = {
    {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
    {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
    {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
    {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1},
};

/* SimplexNoise(RandomSource)：nextDouble×3 + Fisher-Yates(nextInt(256-ix)) */
CHUNKUP_FN void chunkup_simplex_init(ChunkupSimplexNoise* s, ChunkupJavaRandom* r) {
    s->xo = chunkup_jr_next_double(r) * 256.0;
    s->yo = chunkup_jr_next_double(r) * 256.0;
    s->zo = chunkup_jr_next_double(r) * 256.0;
    for (int i = 0; i < 256; ++i) {
        s->p[i] = i;
    }
    for (int ix = 0; ix < 256; ++ix) {
        const int j = chunkup_jr_next_int(r, 256 - ix);
        const int k = s->p[ix];
        s->p[ix] = s->p[j + ix];
        s->p[j + ix] = k;
    }
}

CHUNKUP_FN int32_t sr_simplex_p(const ChunkupSimplexNoise* s, int i) {
    return s->p[i & 0xFF];
}

CHUNKUP_FN double sr_simplex_dot(const int32_t* g, double d, double e, double f) {
    return (double)g[0] * d + (double)g[1] * e + (double)g[2] * f;
}

CHUNKUP_FN double sr_simplex_corner(int gi, double d, double e, double f, double g) {
    double h = g - d * d - e * e - f * f;
    if (h < 0.0) {
        return 0.0;
    }
    h *= h;
    return h * h * sr_simplex_dot(SR_SIMPLEX_GRAD[gi & 15], d, e, f);
}

/* SimplexNoise.getValue(x, z) —— 2D */
CHUNKUP_FN double chunkup_simplex_get2(const ChunkupSimplexNoise* s, double d, double e) {
    const double F2 = 0.5 * (1.7320508075688772 - 1.0);
    const double G2 = (3.0 - 1.7320508075688772) / 6.0;

    const double f = (d + e) * F2;
    const int i = (int)floor(d + f);
    const int j = (int)floor(e + f);
    const double g = (double)(i + j) * G2;
    const double h = (double)i - g;
    const double k = (double)j - g;
    const double l = d - h;
    const double m = e - k;
    int n, o;
    if (l > m) { n = 1; o = 0; } else { n = 0; o = 1; }

    const double p = l - (double)n + G2;
    const double q = m - (double)o + G2;
    const double r = l - 1.0 + 2.0 * G2;
    const double ss = m - 1.0 + 2.0 * G2;
    const int t = i & 0xFF;
    const int u = j & 0xFF;
    const int v = sr_simplex_p(s, t + sr_simplex_p(s, u)) % 12;
    const int w = sr_simplex_p(s, t + n + sr_simplex_p(s, u + o)) % 12;
    const int x = sr_simplex_p(s, t + 1 + sr_simplex_p(s, u + 1)) % 12;
    const double y = sr_simplex_corner(v, l, m, 0.0, 0.5);
    const double z = sr_simplex_corner(w, p, q, 0.0, 0.5);
    const double aa = sr_simplex_corner(x, r, ss, 0.0, 0.5);
    return 70.0 * (y + z + aa);
}

/* ================================================================ 噪声槽位 */

typedef enum ChunkupSrNoiseSlot {
    SR_NOISE_SURFACE = 0,
    SR_NOISE_SURFACE_SECONDARY = 1,
    SR_NOISE_SURFACE_SWAMP = 2,      /* key: minecraft:surface_swamp */
    SR_NOISE_CALCITE = 3,
    SR_NOISE_GRAVEL = 4,
    SR_NOISE_POWDER_SNOW = 5,
    SR_NOISE_PACKED_ICE = 6,
    SR_NOISE_ICE = 7,
    SR_NOISE_CLAY_BANDS_OFFSET = 8,
    SR_NOISE_BADLANDS_PILLAR = 9,
    SR_NOISE_BADLANDS_PILLAR_ROOF = 10,
    SR_NOISE_BADLANDS_SURFACE = 11,
    SR_NOISE_ICEBERG_PILLAR = 12,
    SR_NOISE_ICEBERG_PILLAR_ROOF = 13,
    SR_NOISE_ICEBERG_SURFACE = 14,
    SR_NOISE_COUNT = 15,
} ChunkupSrNoiseSlot;

static const char* const SR_NOISE_KEYS[SR_NOISE_COUNT] = {
    "minecraft:surface",
    "minecraft:surface_secondary",
    "minecraft:surface_swamp",
    "minecraft:calcite",
    "minecraft:gravel",
    "minecraft:powder_snow",
    "minecraft:packed_ice",
    "minecraft:ice",
    "minecraft:clay_bands_offset",
    "minecraft:badlands_pillar",
    "minecraft:badlands_pillar_roof",
    "minecraft:badlands_surface",
    "minecraft:iceberg_pillar",
    "minecraft:iceberg_pillar_roof",
    "minecraft:iceberg_surface",
};

static const int32_t SR_NOISE_FIRST_OCTAVE[SR_NOISE_COUNT] = {
    -6, -6, -2, -9, -8, -6, -7, -4, -8, -2, -8, -6, -6, -3, -6,
};

static const double SR_NOISE_AMPS_SURFACE[3] = {1.0, 1.0, 1.0};
static const double SR_NOISE_AMPS_SURFACE_SEC[4] = {1.0, 1.0, 0.0, 1.0};
static const double SR_NOISE_AMPS_1[1] = {1.0};
static const double SR_NOISE_AMPS_4[4] = {1.0, 1.0, 1.0, 1.0};
static const double SR_NOISE_AMPS_3[3] = {1.0, 1.0, 1.0};

static const double* const SR_NOISE_AMPS[SR_NOISE_COUNT] = {
    SR_NOISE_AMPS_SURFACE,       /* surface: [1,1,1] */
    SR_NOISE_AMPS_SURFACE_SEC,   /* surface_secondary: [1,1,0,1] */
    SR_NOISE_AMPS_1,             /* surface_swamp: [1] */
    SR_NOISE_AMPS_4,             /* calcite */
    SR_NOISE_AMPS_4,             /* gravel */
    SR_NOISE_AMPS_4,             /* powder_snow */
    SR_NOISE_AMPS_4,             /* packed_ice */
    SR_NOISE_AMPS_4,             /* ice */
    SR_NOISE_AMPS_1,             /* clay_bands_offset */
    SR_NOISE_AMPS_4,             /* badlands_pillar */
    SR_NOISE_AMPS_1,             /* badlands_pillar_roof */
    SR_NOISE_AMPS_3,             /* badlands_surface */
    SR_NOISE_AMPS_4,             /* iceberg_pillar */
    SR_NOISE_AMPS_1,             /* iceberg_pillar_roof */
    SR_NOISE_AMPS_3,             /* iceberg_surface */
};

static const int32_t SR_NOISE_AMP_LEN[SR_NOISE_COUNT] = {
    3, 4, 1, 4, 4, 4, 4, 4, 1, 4, 1, 3, 4, 1, 3,
};

/* ================================================================ 世界状态 */

#define SR_CLAY_BANDS 192

typedef enum ChunkupSrVgrad {
    SR_VGRAD_BEDROCK_FLOOR = 0,   /* true=-64, false=-59 */
    SR_VGRAD_DEEPSLATE = 1,       /* true=0, false=8 */
    SR_VGRAD_COUNT = 2,
} ChunkupSrVgrad;

static const char* const SR_VGRAD_KEYS[SR_VGRAD_COUNT] = {
    "minecraft:bedrock_floor",
    "minecraft:deepslate",
};

typedef struct ChunkupSrWorld {
    ChunkupPositionalFactory pf;              /* RandomState.random */
    ChunkupNormalNoiseD noises[SR_NOISE_COUNT];
    ChunkupPositionalFactory vgrad_pf[SR_VGRAD_COUNT]; /* fromHashOf(key).forkPositional() */
    uint16_t clay_bands[SR_CLAY_BANDS];
    ChunkupSimplexNoise temp_noise;           /* PerlinSimplexNoise(LegacyRandom(1234), [0]) */
    ChunkupWgWorld wg;                        /* preliminary surface 用 */
    int init_ok;
} ChunkupSrWorld;

/* clayBands 生成（SurfaceSystem.generateBands） */
CHUNKUP_FN void chunkup_sr_generate_bands(ChunkupSrWorld* w, ChunkupRandomSource* rs) {
    /* Arrays.fill(TERRACOTTA) */
    for (int i = 0; i < SR_CLAY_BANDS; ++i) {
        w->clay_bands[i] = SR_BLOCK_TERRACOTTA;
    }

    /* 橙色条纹 */
    for (int i = 0; i < SR_CLAY_BANDS; i++) {
        i += chunkup_rs_next_int_bound(rs, 5) + 1;
        if (i < SR_CLAY_BANDS) {
            w->clay_bands[i] = SR_BLOCK_ORANGE_TERRACOTTA;
        }
    }

    /* makeBands ×3：YELLOW(1) / BROWN(2) / RED(1) */
    const uint16_t bands3[3] = {SR_BLOCK_YELLOW_TERRACOTTA, SR_BLOCK_BROWN_TERRACOTTA, SR_BLOCK_RED_TERRACOTTA};
    const int th3[3] = {1, 2, 1};
    for (int b = 0; b < 3; ++b) {
        const int j = 6 + chunkup_rs_next_int_bound(rs, 15 - 6 + 1);  /* nextIntBetweenInclusive(6,15) */
        for (int k = 0; k < j; ++k) {
            const int l = th3[b] + chunkup_rs_next_int_bound(rs, 3);
            const int m = chunkup_rs_next_int_bound(rs, SR_CLAY_BANDS);
            for (int n = 0; m + n < SR_CLAY_BANDS && n < l; ++n) {
                w->clay_bands[m + n] = bands3[b];
            }
        }
    }

    /* 白/浅灰条纹 */
    const int i_top = 9 + chunkup_rs_next_int_bound(rs, 15 - 9 + 1);  /* nextIntBetweenInclusive(9,15) */
    int j = 0;
    for (int k = 0; j < i_top && k < SR_CLAY_BANDS; k += chunkup_rs_next_int_bound(rs, 16) + 4) {
        w->clay_bands[k] = SR_BLOCK_WHITE_TERRACOTTA;
        if (k - 1 > 0 && chunkup_rs_next_boolean(rs)) {
            w->clay_bands[k - 1] = SR_BLOCK_LIGHT_GRAY_TERRACOTTA;
        }
        if (k + 1 < SR_CLAY_BANDS && chunkup_rs_next_boolean(rs)) {
            w->clay_bands[k + 1] = SR_BLOCK_LIGHT_GRAY_TERRACOTTA;
        }
        ++j;
    }
}

/* 世界初始化：seed 与 density pipeline 无关（RandomState 同源） */
CHUNKUP_FN void chunkup_sr_world_init(ChunkupSrWorld* w, uint64_t world_seed) {
    memset(w, 0, sizeof(*w));

    /* random = XoroshiroRandomSource(seed).forkPositional() —— 与 wg_eval 一致 */
    ChunkupRandomSource root;
    chunkup_rs_init_seed64(&root, world_seed);
    w->pf = chunkup_rs_fork_positional(&root);

    /* 15 个 surface 噪声：random.fromHashOf(key) */
    for (int i = 0; i < SR_NOISE_COUNT; ++i) {
        ChunkupRandomSource rs;
        chunkup_pf_from_hash_of(&w->pf, SR_NOISE_KEYS[i], strlen(SR_NOISE_KEYS[i]), &rs);
        chunkup_normal_init(
            &w->noises[i], &rs,
            SR_NOISE_FIRST_OCTAVE[i], SR_NOISE_AMPS[i], SR_NOISE_AMP_LEN[i]
        );
    }

    /* verticalGradient 工厂：fromHashOf(key).forkPositional() */
    for (int i = 0; i < SR_VGRAD_COUNT; ++i) {
        ChunkupRandomSource rs;
        chunkup_pf_from_hash_of(&w->pf, SR_VGRAD_KEYS[i], strlen(SR_VGRAD_KEYS[i]), &rs);
        w->vgrad_pf[i] = chunkup_rs_fork_positional(&rs);
    }

    /* clayBands：fromHashOf("minecraft:clay_bands")（不 fork） */
    {
        ChunkupRandomSource rs;
        chunkup_pf_from_hash_of(&w->pf, "minecraft:clay_bands", strlen("minecraft:clay_bands"), &rs);
        chunkup_sr_generate_bands(w, &rs);
    }

    /* 温度噪声：new PerlinSimplexNoise(new WorldgenRandom(new LegacyRandomSource(1234L)), [0])
     * octaves=[0] → 单层 SimplexNoise（PerlinSimplexNoise 构造后 highestFreq=1/1） */
    {
        ChunkupJavaRandom jr;
        chunkup_jr_set_seed(&jr, 1234ULL);
        chunkup_simplex_init(&w->temp_noise, &jr);
    }

    /* preliminary surface 用的 DF 世界 */
    chunkup_wg_world_init(&w->wg, world_seed);

    w->init_ok = 1;
}

/* SurfaceSystem.getBand */
CHUNKUP_FN uint16_t chunkup_sr_get_band(ChunkupSrWorld* w, int x, int y, int z) {
    const int l = (int)round(chunkup_normal_get(&w->noises[SR_NOISE_CLAY_BANDS_OFFSET], (double)x, 0.0, (double)z) * 4.0);
    const int idx = (y + l + SR_CLAY_BANDS) % SR_CLAY_BANDS;
    return w->clay_bands[idx];
}

/* SurfaceSystem.getSurfaceDepth */
CHUNKUP_FN int chunkup_sr_surface_depth(ChunkupSrWorld* w, int x, int z) {
    const double d = chunkup_normal_get(&w->noises[SR_NOISE_SURFACE], (double)x, 0.0, (double)z);
    ChunkupRandomSource rs;
    chunkup_pf_at(&w->pf, x, 0, z, &rs);
    return (int)(d * 2.75 + 3.0 + chunkup_rs_next_double(&rs) * 0.25);
}

/* SurfaceSystem.getSurfaceSecondary */
CHUNKUP_FN double chunkup_sr_surface_secondary(ChunkupSrWorld* w, int x, int z) {
    return chunkup_normal_get(&w->noises[SR_NOISE_SURFACE_SECONDARY], (double)x, 0.0, (double)z);
}

/* NoiseChunk.preliminarySurfaceLevel(x, z)：
 * quart 对齐，y 从 minY+height 步进 -cellHeight(8) 向下找
 * initial_density_without_jaggedness > 0.390625。 */
CHUNKUP_FN int32_t chunkup_sr_preliminary_surface_level(ChunkupSrWorld* w, int x, int z) {
    const int k = x & ~3;   /* QuartPos.toBlock(QuartPos.fromBlock(x)) */
    const int l = z & ~3;
    const int min_y = CHUNKUP_WG_MIN_Y;
    const int top = min_y + CHUNKUP_WG_HEIGHT;
    for (int m = top; m >= min_y; m -= CHUNKUP_WG_CELL_HEIGHT) {
        if (chunkup_wg_initial_density_direct(&w->wg, k, m, l) > 0.390625) {
            return m;
        }
    }
    return INT32_MAX;
}

/* ================================================================ biome 集合（bitmask） */

#define SR_BIT(b) (1u << (b))

typedef enum ChunkupSrBiomeSet {
    SR_BS_FROZEN_OCEAN2 = 0,   /* FROZEN_OCEAN|DEEP_FROZEN_OCEAN */
    SR_BS_BEACH3,              /* WARM_OCEAN|BEACH|SNOWY_BEACH */
    SR_BS_DESERT,
    SR_BS_BADLANDS3,           /* BADLANDS|ERODED_BADLANDS|WOODED_BADLANDS */
    SR_BS_WOODED_BADLANDS,
    SR_BS_SWAMP,
    SR_BS_MANGROVE,
    SR_BS_FROZEN_PEAKS_JAGGED, /* FROZEN_PEAKS|JAGGED_PEAKS */
    SR_BS_WARM_LUKEWARM3,      /* WARM_OCEAN|LUKEWARM_OCEAN|DEEP_LUKEWARM_OCEAN */
    SR_BS_STONY_PEAKS,
    SR_BS_STONY_SHORE,
    SR_BS_WINDSWEPT_HILLS,
    SR_BS_WINDSWEPT_SAVANNA,
    SR_BS_WINDSWEPT_GRAVELLY,
    SR_BS_OLD_GROWTH2,         /* OLD_GROWTH_PINE|OLD_GROWTH_SPRUCE */
    SR_BS_ICE_SPIKES,
    SR_BS_MUSHROOM,
    SR_BS_DRIPSTONE,
    SR_BS_FROZEN_PEAKS,
    SR_BS_SNOWY_SLOPES,
    SR_BS_JAGGED_PEAKS,
    SR_BS_GROVE,
    SR_BS_COUNT,
} ChunkupSrBiomeSet;

static const uint32_t SR_BIOME_SETS[SR_BS_COUNT] = {
    SR_BIT(SR_BIOME_FROZEN_OCEAN) | SR_BIT(SR_BIOME_DEEP_FROZEN_OCEAN),
    SR_BIT(SR_BIOME_WARM_OCEAN) | SR_BIT(SR_BIOME_BEACH) | SR_BIT(SR_BIOME_SNOWY_BEACH),
    SR_BIT(SR_BIOME_DESERT),
    SR_BIT(SR_BIOME_BADLANDS) | SR_BIT(SR_BIOME_ERODED_BADLANDS) | SR_BIT(SR_BIOME_WOODED_BADLANDS),
    SR_BIT(SR_BIOME_WOODED_BADLANDS),
    SR_BIT(SR_BIOME_SWAMP),
    SR_BIT(SR_BIOME_MANGROVE_SWAMP),
    SR_BIT(SR_BIOME_FROZEN_PEAKS) | SR_BIT(SR_BIOME_JAGGED_PEAKS),
    SR_BIT(SR_BIOME_WARM_OCEAN) | SR_BIT(SR_BIOME_LUKEWARM_OCEAN) | SR_BIT(SR_BIOME_DEEP_LUKEWARM_OCEAN),
    SR_BIT(SR_BIOME_STONY_PEAKS),
    SR_BIT(SR_BIOME_STONY_SHORE),
    SR_BIT(SR_BIOME_WINDSWEPT_HILLS),
    SR_BIT(SR_BIOME_WINDSWEPT_SAVANNA),
    SR_BIT(SR_BIOME_WINDSWEPT_GRAVELLY_HILLS),
    SR_BIT(SR_BIOME_OLD_GROWTH_PINE_TAIGA) | SR_BIT(SR_BIOME_OLD_GROWTH_SPRUCE_TAIGA),
    SR_BIT(SR_BIOME_ICE_SPIKES),
    SR_BIT(SR_BIOME_MUSHROOM_FIELDS),
    SR_BIT(SR_BIOME_DRIPSTONE_CAVES),
    SR_BIT(SR_BIOME_FROZEN_PEAKS),
    SR_BIT(SR_BIOME_SNOWY_SLOPES),
    SR_BIT(SR_BIOME_JAGGED_PEAKS),
    SR_BIT(SR_BIOME_GROVE),
};

/* ================================================================ 条件表 */

typedef enum ChunkupSrCondType {
    SRC_Y = 0,             /* i0=anchorY, i1=mult, b0=addStoneDepth */
    SRC_WATER,             /* i0=offset, i1=mult, b0=addStoneDepth */
    SRC_HOLE,
    SRC_STEEP,
    SRC_BIOME,             /* sub=biome set */
    SRC_NOISE,             /* sub=noise slot, fmin/fmax */
    SRC_NOT,               /* sub=子条件 */
    SRC_ABOVE_PRELIM,
    SRC_STONE_DEPTH,       /* i0=offset, i1=secondaryRange, b0=addSurfaceDepth, b1=ceiling */
    SRC_VGRAD,             /* sub=vgrad idx, i0=trueY, i1=falseY */
    SRC_TEMPERATURE,
} ChunkupSrCondType;

typedef struct ChunkupSrCond {
    uint8_t type;
    uint8_t b0;      /* addStoneDepth / addSurfaceDepth */
    uint8_t b1;      /* ceiling */
    int16_t sub;     /* NOT 子条件 / biome set / noise slot / vgrad idx */
    int32_t i0;
    int32_t i1;
    double fmin;
    double fmax;
} ChunkupSrCond;

/* 条件索引（对照 SurfaceRuleData.overworldLike 的 conditionSource1..18） */
typedef enum ChunkupSrCondId {
    C_Y97_2 = 0,          /* yBlockCheck(97, 2) */
    C_Y256_0,             /* yBlockCheck(256, 0) */
    C_YStart63_M1,        /* yStartCheck(63, -1) */
    C_YStart74_1,         /* yStartCheck(74, 1) */
    C_Y60_0,              /* yBlockCheck(60, 0) */
    C_Y62_0,              /* yBlockCheck(62, 0) */
    C_Y63_0,              /* yBlockCheck(63, 0) */
    C_WATER_M1_0,         /* waterBlockCheck(-1, 0) */
    C_WATER_0_0,          /* waterBlockCheck(0, 0) */
    C_WATERStart_M6_M1,   /* waterStartCheck(-6, -1) */
    C_HOLE,               /* hole() */
    C_STEEP,              /* steep() */
    C_NOT_Y63,            /* not(yBlockCheck(63,0)) */
    C_NOT_HOLE,           /* not(hole()) */
    C_NOT_YStart74,       /* not(yStartCheck(74,1)) */
    C_ABOVE_PRELIM,       /* abovePreliminarySurface() */
    C_TEMP,               /* temperature() */
    C_NOISE_SURF_M909_M5454,   /* noise(SURFACE, -0.909, -0.5454) */
    C_NOISE_SURF_M1818_1818,
    C_NOISE_SURF_5454_909,
    C_NOISE_SWAMP_0,      /* noise(SWAMP, 0.0) → max=MAX */
    C_NOISE_CALCITE,      /* (-0.0125, 0.0125) */
    C_NOISE_GRAVEL,       /* (-0.05, 0.05) */
    C_NOISE_PS_45_58,     /* powder_snow (0.45, 0.58) */
    C_NOISE_PS_35_6,      /* powder_snow (0.35, 0.6) */
    C_NOISE_PI_M5_02,     /* packed_ice (-0.5, 0.2) */
    C_NOISE_PI_0_02,      /* packed_ice (0.0, 0.2) */
    C_NOISE_ICE_M0625_0025, /* ice (-0.0625, 0.025) */
    C_NOISE_ICE_0_0025,     /* ice (0.0, 0.025) */
    C_SURF_ABOVE_1,       /* surfaceNoiseAbove(1.0) */
    C_SURF_ABOVE_1_75,
    C_SURF_ABOVE_2,
    C_SURF_ABOVE_M0_5,
    C_SURF_ABOVE_M0_95,
    C_VGRAD_BEDROCK,      /* bedrock_floor: bottom(-64) → aboveBottom(5)=-59 */
    C_VGRAD_DEEPSLATE,    /* deepslate: 0 → 8 */
    C_ON_FLOOR,           /* stoneDepthCheck(0, false, FLOOR) */
    C_UNDER_FLOOR,        /* stoneDepthCheck(0, true, FLOOR) */
    C_DEEP_UNDER_FLOOR,   /* stoneDepthCheck(0, true, 6, FLOOR) */
    C_VERY_DEEP_UNDER_FLOOR, /* stoneDepthCheck(0, true, 30, FLOOR) */
    C_ON_CEILING,         /* stoneDepthCheck(0, false, CEILING) */
    C_BI_FROZEN_OCEAN2,
    C_BI_BEACH3,
    C_BI_DESERT,
    C_BI_BADLANDS3,
    C_BI_WOODED_BADLANDS,
    C_BI_SWAMP,
    C_BI_MANGROVE,
    C_BI_FROZEN_PEAKS_JAGGED,
    C_BI_WARM_LUKEWARM3,
    C_BI_STONY_PEAKS,
    C_BI_STONY_SHORE,
    C_BI_WINDSWEPT_HILLS,
    C_BI_WINDSWEPT_SAVANNA,
    C_BI_WINDSWEPT_GRAVELLY,
    C_BI_OLD_GROWTH2,
    C_BI_ICE_SPIKES,
    C_BI_MUSHROOM,
    C_BI_DRIPSTONE,
    C_BI_FROZEN_PEAKS,
    C_BI_SNOWY_SLOPES,
    C_BI_JAGGED_PEAKS,
    C_BI_GROVE,
    C_COUNT,
} ChunkupSrCondId;

static const ChunkupSrCond SR_CONDS[C_COUNT] = {
    /* C_Y97_2 */           {SRC_Y, 0, 0, 0, 97, 2, 0.0, 0.0},
    /* C_Y256_0 */          {SRC_Y, 0, 0, 0, 256, 0, 0.0, 0.0},
    /* C_YStart63_M1 */     {SRC_Y, 1, 0, 0, 63, -1, 0.0, 0.0},
    /* C_YStart74_1 */      {SRC_Y, 1, 0, 0, 74, 1, 0.0, 0.0},
    /* C_Y60_0 */           {SRC_Y, 0, 0, 0, 60, 0, 0.0, 0.0},
    /* C_Y62_0 */           {SRC_Y, 0, 0, 0, 62, 0, 0.0, 0.0},
    /* C_Y63_0 */           {SRC_Y, 0, 0, 0, 63, 0, 0.0, 0.0},
    /* C_WATER_M1_0 */      {SRC_WATER, 0, 0, 0, -1, 0, 0.0, 0.0},
    /* C_WATER_0_0 */       {SRC_WATER, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_WATERStart_M6_M1 */{SRC_WATER, 1, 0, 0, -6, -1, 0.0, 0.0},
    /* C_HOLE */            {SRC_HOLE, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_STEEP */           {SRC_STEEP, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_NOT_Y63 */         {SRC_NOT, 0, 0, C_Y63_0, 0, 0, 0.0, 0.0},
    /* C_NOT_HOLE */        {SRC_NOT, 0, 0, C_HOLE, 0, 0, 0.0, 0.0},
    /* C_NOT_YStart74 */    {SRC_NOT, 0, 0, C_YStart74_1, 0, 0, 0.0, 0.0},
    /* C_ABOVE_PRELIM */    {SRC_ABOVE_PRELIM, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_TEMP */            {SRC_TEMPERATURE, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_NOISE_SURF_M909_M5454 */ {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, -0.909, -0.5454},
    /* C_NOISE_SURF_M1818_1818 */ {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, -0.1818, 0.1818},
    /* C_NOISE_SURF_5454_909 */   {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, 0.5454, 0.909},
    /* C_NOISE_SWAMP_0 */   {SRC_NOISE, 0, 0, SR_NOISE_SURFACE_SWAMP, 0, 0, 0.0, DBL_MAX},
    /* C_NOISE_CALCITE */   {SRC_NOISE, 0, 0, SR_NOISE_CALCITE, 0, 0, -0.0125, 0.0125},
    /* C_NOISE_GRAVEL */    {SRC_NOISE, 0, 0, SR_NOISE_GRAVEL, 0, 0, -0.05, 0.05},
    /* C_NOISE_PS_45_58 */  {SRC_NOISE, 0, 0, SR_NOISE_POWDER_SNOW, 0, 0, 0.45, 0.58},
    /* C_NOISE_PS_35_6 */   {SRC_NOISE, 0, 0, SR_NOISE_POWDER_SNOW, 0, 0, 0.35, 0.6},
    /* C_NOISE_PI_M5_02 */  {SRC_NOISE, 0, 0, SR_NOISE_PACKED_ICE, 0, 0, -0.5, 0.2},
    /* C_NOISE_PI_0_02 */   {SRC_NOISE, 0, 0, SR_NOISE_PACKED_ICE, 0, 0, 0.0, 0.2},
    /* C_NOISE_ICE_M0625_0025 */ {SRC_NOISE, 0, 0, SR_NOISE_ICE, 0, 0, -0.0625, 0.025},
    /* C_NOISE_ICE_0_0025 */     {SRC_NOISE, 0, 0, SR_NOISE_ICE, 0, 0, 0.0, 0.025},
    /* C_SURF_ABOVE_1 */    {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, 1.0 / 8.25, DBL_MAX},
    /* C_SURF_ABOVE_1_75 */ {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, 1.75 / 8.25, DBL_MAX},
    /* C_SURF_ABOVE_2 */    {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, 2.0 / 8.25, DBL_MAX},
    /* C_SURF_ABOVE_M0_5 */ {SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, -0.5 / 8.25, DBL_MAX},
    /* C_SURF_ABOVE_M0_95 */{SRC_NOISE, 0, 0, SR_NOISE_SURFACE, 0, 0, -0.95 / 8.25, DBL_MAX},
    /* C_VGRAD_BEDROCK */   {SRC_VGRAD, 0, 0, SR_VGRAD_BEDROCK_FLOOR, -64, -59, 0.0, 0.0},
    /* C_VGRAD_DEEPSLATE */ {SRC_VGRAD, 0, 0, SR_VGRAD_DEEPSLATE, 0, 8, 0.0, 0.0},
    /* C_ON_FLOOR */        {SRC_STONE_DEPTH, 0, 0, 0, 0, 0, 0.0, 0.0},
    /* C_UNDER_FLOOR */     {SRC_STONE_DEPTH, 1, 0, 0, 0, 0, 0.0, 0.0},
    /* C_DEEP_UNDER_FLOOR */{SRC_STONE_DEPTH, 1, 0, 0, 0, 6, 0.0, 0.0},
    /* C_VERY_DEEP_UNDER_FLOOR */ {SRC_STONE_DEPTH, 1, 0, 0, 0, 30, 0.0, 0.0},
    /* C_ON_CEILING */      {SRC_STONE_DEPTH, 0, 1, 0, 0, 0, 0.0, 0.0},
    /* biome 集 */
    {SRC_BIOME, 0, 0, SR_BS_FROZEN_OCEAN2, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_BEACH3, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_DESERT, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_BADLANDS3, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_WOODED_BADLANDS, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_SWAMP, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_MANGROVE, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_FROZEN_PEAKS_JAGGED, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_WARM_LUKEWARM3, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_STONY_PEAKS, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_STONY_SHORE, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_WINDSWEPT_HILLS, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_WINDSWEPT_SAVANNA, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_WINDSWEPT_GRAVELLY, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_OLD_GROWTH2, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_ICE_SPIKES, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_MUSHROOM, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_DRIPSTONE, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_FROZEN_PEAKS, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_SNOWY_SLOPES, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_JAGGED_PEAKS, 0, 0, 0.0, 0.0},
    {SRC_BIOME, 0, 0, SR_BS_GROVE, 0, 0, 0.0, 0.0},
};

/* ================================================================ 规则树 */

typedef enum ChunkupSrRuleType {
    SRN_BLOCK = 0,
    SRN_TEST,
    SRN_SEQ,
    SRN_BAND,   /* bandlands() */
} ChunkupSrRuleType;

typedef struct ChunkupSrRule {
    uint8_t type;
    int16_t cond;   /* SRN_TEST */
    int16_t arg;    /* BLOCK: 块id；TEST/SEQ: 子树根 */
    int16_t next;   /* 同级下一项（sequence 失败回退），-1 结束 */
} ChunkupSrRule;

typedef enum ChunkupSrRuleId {
    R_ROOT = 0,
    R9, R9A, R9A1, R9B, R9B1, R9B1a, R9B2,
    R9C, R9C1, R9D, R9E,
    R_ruleSrc, R_ruleSrc2, R_ruleSrc3, R_ruleSrc4,
    R_ruleSrc5, R_ruleSrc6, R_ruleSrc7, R_ruleSrc8,
    R7A, R7B, R8A, R8B, R8C, R8D,
    R_SEQ_INLINE_SAND_RED,     /* sequence(ON_CEILING→RED_SANDSTONE, RED_SAND) */
    R_SEQ_INLINE_CALCITE,      /* stony_peaks: sequence(calcite→CALCITE, STONE) */
    R_SEQ_INLINE_GRAVEL,       /* stony_shore: sequence(gravel→ruleSrc3, STONE) */
    R_SEQ_INLINE_SNOWY_SLOPES5,/* snowy_slopes@7: sequence(steep→STONE, ruleSrc5, water→SNOW) */
    R_SEQ_INLINE_JAGGED7,      /* jagged_peaks@7: STONE */
    R_SEQ_INLINE_GROVE7,       /* grove@7: sequence(ruleSrc5, DIRT) */
    R_SEQ_INLINE_SNOWY_SLOPES8,/* snowy_slopes@8: sequence(steep→STONE, ruleSrc6, water→SNOW) */
    R_SEQ_INLINE_JAGGED8,      /* jagged_peaks@8: sequence(steep→STONE, water→SNOW) */
    R_SEQ_INLINE_GROVE8,       /* grove@8: sequence(ruleSrc6, water→SNOW) */
    R_SEQ_INLINE_FROZEN_OCEAN_WATER, /* ON_FLOOR→frozen→hole→WATER */
    R_ROOT2, R_ROOT3, R_BEDROCK_NODE, R_BEDROCK_BLOCK, R_DEEPSLATE_NODE,
    R9_S1, R9_S2, R9_S3, R9_S3a, R9_S4, R9_S5,
    R9A_S1, R9A_S1a, R9A_S2, R9A_S2a, R9A_S2b, R9A_S2c, R9A_S3, R9A_S3a, R9A_S3b, R9A_S3c,
    R_WATER_BLOCK,
    R9A1_S1, R9A1_S1b, R9A1_S1c, R9A1_S2, R_COARSE_DIRT_BLOCK,
    R9B_S1, R9B_S2, R9B_S3, R9B_S3a, R_WHITE_TERRACOTTA_BLOCK,
    R9B1_S1, R9B1_S2, R9B1_S3, R9B1_S4, R9B1_S5, R9B1_S6, R_ORANGE_TERRACOTTA_BLOCK,
    R9B1a_S1, R9B1a_S1b, R9B1a_S1c, R9B1a_S2, R_TERRACOTTA_BLOCK,
    R9B2_S1, R9B2_S1a, R9B2_S2,
    R_SEQ_INLINE_SAND_RED_S1, R_RED_SANDSTONE_BLOCK, R_RED_SAND_BLOCK,
    R9C_S1, R9C_S1a, R9C_S2,
    R9C1_S1, R9C1_S2, R_AIR_BLOCK, R_ICE_BLOCK,
    R9D_S1, R_SEQ_INLINE_FOW_A, R_SEQ_INLINE_FOW_B,
    R9D_S2, R9D_S3, R9D_S3a, R9D_S4, R9D_S4a, R_SANDSTONE_BLOCK,
    R9E_S1, R9E_S2, R9E_S3, R_STONE_BLOCK,
    R_ruleSrc_S1, R_GRASS_BLOCK, R_DIRT_BLOCK,
    R_ruleSrc2_S1, R_SAND_BLOCK,
    R_ruleSrc3_S1, R_GRAVEL_BLOCK,
    R_SEQ_INLINE_CALCITE_S1, R_CALCITE_BLOCK,
    R_SEQ_INLINE_GRAVEL_S1,
    R_ruleSrc4_S1, R_ruleSrc4_S2, R_ruleSrc4_S3, R_ruleSrc4_S3a,
    R_ruleSrc4_S4, R_ruleSrc4_S5, R_ruleSrc4_S6,
    R_ruleSrc5_a, R_ruleSrc6_a, R_POWDER_SNOW_BLOCK,
    R_ruleSrc7_S1, R_ruleSrc7_S2,
    R_SNOWY_SLOPES5_S1, R_SNOWY_SLOPES5_S2, R_SNOWY_SLOPES5_S3,
    R_ruleSrc7_S3, R_ruleSrc7_S4, R_ruleSrc7_S5, R_ruleSrc7_S6, R_ruleSrc7_S6a,
    R_ruleSrc7_S7, R_ruleSrc7_S8, R_ruleSrc7_S9,
    R_MUD_BLOCK, R_SNOW_BLOCK,
    R7A_S1, R7A_S2, R7A_S3, R7A_S4, R_PACKED_ICE_BLOCK,
    R7B_S1, R7B_S2, R7B_S3, R7B_S4,
    R_ruleSrc8_S1, R_ruleSrc8_S2,
    R_SNOWY_SLOPES8_S1, R_SNOWY_SLOPES8_S2, R_SNOWY_SLOPES8_S3,
    R_ruleSrc8_S3, R_JAGGED8_S1, R_JAGGED8_S2,
    R_ruleSrc8_S4, R_SEQ_INLINE_GROVE8_S2,
    R_ruleSrc8_S5, R_ruleSrc8_S6,
    R8B_S1, R8B_S2,
    R_ruleSrc8_S7, R8C_S1, R8C_S2, R8C_S3, R8C_S4,
    R_ruleSrc8_S8, R8D_S1, R8D_S2, R_PODZOL_BLOCK,
    R_ruleSrc8_S9, R_ruleSrc8_S9a, R_ruleSrc8_S10, R_ruleSrc8_S11,
    R_MYCELIUM_BLOCK, R_ruleSrc8_S12,
    R_RULE_COUNT_TOTAL,
} ChunkupSrRuleId;

static const ChunkupSrRule SR_RULES[R_RULE_COUNT_TOTAL] = {
    /* R_ROOT = sequence(bedrock_floor→BEDROCK, abovePrelim→R9, deepslate→DEEPSLATE) */
    [R_ROOT] = {SRN_SEQ, -1, R_BEDROCK_NODE, R_ROOT2},
    [R_ROOT2] = {SRN_TEST, C_ABOVE_PRELIM, R9, R_ROOT3},
    [R_ROOT3] = {SRN_TEST, C_VGRAD_DEEPSLATE, R_DEEPSLATE_NODE, -1},
    [R_BEDROCK_NODE] = {SRN_TEST, C_VGRAD_BEDROCK, R_BEDROCK_BLOCK, -1},
    [R_BEDROCK_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_BEDROCK, -1},
    [R_DEEPSLATE_NODE] = {SRN_BLOCK, -1, SR_BLOCK_DEEPSLATE, -1},

    /* ---- R9 ---- */
    [R9] = {SRN_SEQ, -1, R9_S1, R9_S2},
    [R9_S1] = {SRN_TEST, C_ON_FLOOR, R9A, R9_S2},
    [R9_S2] = {SRN_TEST, C_BI_BADLANDS3, R9B, R9_S3},
    [R9_S3] = {SRN_TEST, C_ON_FLOOR, R9_S3a, R9_S4},
    [R9_S3a] = {SRN_TEST, C_WATER_M1_0, R9C, R9_S4},
    [R9_S4] = {SRN_TEST, C_WATERStart_M6_M1, R9D, R9_S5},
    [R9_S5] = {SRN_TEST, C_ON_FLOOR, R9E, -1},

    /* R9A = ON_FLOOR 内 sequence(wooded_badlands…, swamp…, mangrove…) */
    [R9A] = {SRN_SEQ, -1, R9A_S1, R9A_S2},
    [R9A_S1] = {SRN_TEST, C_BI_WOODED_BADLANDS, R9A_S1a, R9A_S2},
    [R9A_S1a] = {SRN_TEST, C_Y97_2, R9A1, -1},
    [R9A_S2] = {SRN_TEST, C_BI_SWAMP, R9A_S2a, R9A_S3},
    [R9A_S2a] = {SRN_TEST, C_Y62_0, R9A_S2b, -1},
    [R9A_S2b] = {SRN_TEST, C_NOT_Y63, R9A_S2c, -1},
    [R9A_S2c] = {SRN_TEST, C_NOISE_SWAMP_0, R_WATER_BLOCK, -1},
    [R9A_S3] = {SRN_TEST, C_BI_MANGROVE, R9A_S3a, -1},
    [R9A_S3a] = {SRN_TEST, C_Y60_0, R9A_S3b, -1},
    [R9A_S3b] = {SRN_TEST, C_NOT_Y63, R9A_S3c, -1},
    [R9A_S3c] = {SRN_TEST, C_NOISE_SWAMP_0, R_WATER_BLOCK, -1},
    [R_WATER_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_WATER, -1},

    /* R9A1 = sequence(surf bands → COARSE_DIRT ×3, ruleSrc) */
    [R9A1] = {SRN_SEQ, -1, R9A1_S1, R9A1_S2},
    [R9A1_S1] = {SRN_TEST, C_NOISE_SURF_M909_M5454, R_COARSE_DIRT_BLOCK, R9A1_S1b},
    [R9A1_S1b] = {SRN_TEST, C_NOISE_SURF_M1818_1818, R_COARSE_DIRT_BLOCK, R9A1_S1c},
    [R9A1_S1c] = {SRN_TEST, C_NOISE_SURF_5454_909, R_COARSE_DIRT_BLOCK, R9A1_S2},
    [R9A1_S2] = {SRN_SEQ, -1, R_ruleSrc, -1},
    [R_COARSE_DIRT_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_COARSE_DIRT, -1},

    /* ---- R9B = badlands ---- */
    [R9B] = {SRN_SEQ, -1, R9B_S1, R9B_S2},
    [R9B_S1] = {SRN_TEST, C_ON_FLOOR, R9B1, R9B_S2},
    [R9B_S2] = {SRN_TEST, C_YStart63_M1, R9B2, R9B_S3},
    [R9B_S3] = {SRN_TEST, C_UNDER_FLOOR, R9B_S3a, -1},
    [R9B_S3a] = {SRN_TEST, C_WATERStart_M6_M1, R_WHITE_TERRACOTTA_BLOCK, -1},
    [R_WHITE_TERRACOTTA_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_WHITE_TERRACOTTA, -1},

    /* R9B1 = ON_FLOOR 内 badlands sequence */
    [R9B1] = {SRN_SEQ, -1, R9B1_S1, R9B1_S2},
    [R9B1_S1] = {SRN_TEST, C_Y256_0, R_ORANGE_TERRACOTTA_BLOCK, R9B1_S2},
    [R9B1_S2] = {SRN_TEST, C_YStart74_1, R9B1a, R9B1_S3},
    [R9B1_S3] = {SRN_TEST, C_WATER_M1_0, R_SEQ_INLINE_SAND_RED, R9B1_S4},
    [R9B1_S4] = {SRN_TEST, C_NOT_HOLE, R_ORANGE_TERRACOTTA_BLOCK, R9B1_S5},
    [R9B1_S5] = {SRN_TEST, C_WATERStart_M6_M1, R_WHITE_TERRACOTTA_BLOCK, R9B1_S6},
    [R9B1_S6] = {SRN_SEQ, -1, R_ruleSrc3, -1},
    [R_ORANGE_TERRACOTTA_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_ORANGE_TERRACOTTA, -1},

    /* R9B1a = sequence(surf bands → TERRACOTTA ×3, bandlands) */
    [R9B1a] = {SRN_SEQ, -1, R9B1a_S1, R9B1a_S2},
    [R9B1a_S1] = {SRN_TEST, C_NOISE_SURF_M909_M5454, R_TERRACOTTA_BLOCK, R9B1a_S1b},
    [R9B1a_S1b] = {SRN_TEST, C_NOISE_SURF_M1818_1818, R_TERRACOTTA_BLOCK, R9B1a_S1c},
    [R9B1a_S1c] = {SRN_TEST, C_NOISE_SURF_5454_909, R_TERRACOTTA_BLOCK, R9B1a_S2},
    [R9B1a_S2] = {SRN_BAND, -1, -1, -1},
    [R_TERRACOTTA_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_TERRACOTTA, -1},

    /* R9B2 = yStart(63,-1) sequence */
    [R9B2] = {SRN_SEQ, -1, R9B2_S1, R9B2_S2},
    [R9B2_S1] = {SRN_TEST, C_Y63_0, R9B2_S1a, R9B2_S2},
    [R9B2_S1a] = {SRN_TEST, C_NOT_YStart74, R_ORANGE_TERRACOTTA_BLOCK, -1},
    [R9B2_S2] = {SRN_BAND, -1, -1, -1},

    /* R_SEQ_INLINE_SAND_RED = sequence(ON_CEILING→RED_SANDSTONE, RED_SAND) */
    [R_SEQ_INLINE_SAND_RED] = {SRN_SEQ, -1, R_SEQ_INLINE_SAND_RED_S1, R_RED_SAND_BLOCK},
    [R_SEQ_INLINE_SAND_RED_S1] = {SRN_TEST, C_ON_CEILING, R_RED_SANDSTONE_BLOCK, -1},
    [R_RED_SANDSTONE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_RED_SANDSTONE, -1},
    [R_RED_SAND_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_RED_SAND, -1},

    /* ---- R9C = ON_FLOOR && water(-1,0) → sequence(frozen→hole→…, ruleSource8) ---- */
    [R9C] = {SRN_SEQ, -1, R9C_S1, R9C_S2},
    [R9C_S1] = {SRN_TEST, C_BI_FROZEN_OCEAN2, R9C_S1a, R9C_S2},
    [R9C_S1a] = {SRN_TEST, C_HOLE, R9C1, -1},
    [R9C_S2] = {SRN_SEQ, -1, R_ruleSrc8, -1},

    /* R9C1 = sequence(water(0,0)→AIR, temperature→ICE, WATER) */
    [R9C1] = {SRN_SEQ, -1, R9C1_S1, R9C1_S2},
    [R9C1_S1] = {SRN_TEST, C_WATER_0_0, R_AIR_BLOCK, R9C1_S2},
    [R9C1_S2] = {SRN_TEST, C_TEMP, R_ICE_BLOCK, R_WATER_BLOCK},
    [R_AIR_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_AIR, -1},
    [R_ICE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_ICE, -1},

    /* ---- R9D = waterStart(-6,-1) ---- */
    [R9D] = {SRN_SEQ, -1, R9D_S1, R9D_S2},
    [R9D_S1] = {SRN_SEQ, -1, R_SEQ_INLINE_FROZEN_OCEAN_WATER, R9D_S2},
    [R_SEQ_INLINE_FROZEN_OCEAN_WATER] = {SRN_TEST, C_ON_FLOOR, R_SEQ_INLINE_FOW_A, -1},
    [R_SEQ_INLINE_FOW_A] = {SRN_TEST, C_BI_FROZEN_OCEAN2, R_SEQ_INLINE_FOW_B, -1},
    [R_SEQ_INLINE_FOW_B] = {SRN_TEST, C_HOLE, R_WATER_BLOCK, -1},
    [R9D_S2] = {SRN_TEST, C_UNDER_FLOOR, R_ruleSrc7, R9D_S3},
    [R9D_S3] = {SRN_TEST, C_BI_BEACH3, R9D_S3a, R9D_S4},
    [R9D_S3a] = {SRN_TEST, C_DEEP_UNDER_FLOOR, R_SANDSTONE_BLOCK, -1},
    [R9D_S4] = {SRN_TEST, C_BI_DESERT, R9D_S4a, -1},
    [R9D_S4a] = {SRN_TEST, C_VERY_DEEP_UNDER_FLOOR, R_SANDSTONE_BLOCK, -1},
    [R_SANDSTONE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_SANDSTONE, -1},

    /* ---- R9E = ON_FLOOR → sequence(frozen|jagged→STONE, warm|lukewarm→ruleSrc2, ruleSrc3) ---- */
    [R9E] = {SRN_SEQ, -1, R9E_S1, R9E_S2},
    [R9E_S1] = {SRN_TEST, C_BI_FROZEN_PEAKS_JAGGED, R_STONE_BLOCK, R9E_S2},
    [R9E_S2] = {SRN_TEST, C_BI_WARM_LUKEWARM3, R_ruleSrc2, R9E_S3},
    [R9E_S3] = {SRN_SEQ, -1, R_ruleSrc3, -1},
    [R_STONE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_STONE, -1},

    /* ---- ruleSource = sequence(water(0,0)→GRASS_BLOCK, DIRT) ---- */
    [R_ruleSrc] = {SRN_SEQ, -1, R_ruleSrc_S1, R_DIRT_BLOCK},
    [R_ruleSrc_S1] = {SRN_TEST, C_WATER_0_0, R_GRASS_BLOCK, -1},
    [R_GRASS_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_GRASS_BLOCK, -1},
    [R_DIRT_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_DIRT, -1},

    /* ruleSource2 = sequence(ON_CEILING→SANDSTONE, SAND) */
    [R_ruleSrc2] = {SRN_SEQ, -1, R_ruleSrc2_S1, R_SAND_BLOCK},
    [R_ruleSrc2_S1] = {SRN_TEST, C_ON_CEILING, R_SANDSTONE_BLOCK, -1},
    [R_SAND_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_SAND, -1},

    /* ruleSource3 = sequence(ON_CEILING→STONE, GRAVEL) */
    [R_ruleSrc3] = {SRN_SEQ, -1, R_ruleSrc3_S1, R_GRAVEL_BLOCK},
    [R_ruleSrc3_S1] = {SRN_TEST, C_ON_CEILING, R_STONE_BLOCK, -1},
    [R_GRAVEL_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_GRAVEL, -1},

    /* ---- ruleSource4 ---- */
    [R_ruleSrc4] = {SRN_SEQ, -1, R_ruleSrc4_S1, R_ruleSrc4_S2},
    [R_ruleSrc4_S1] = {SRN_TEST, C_BI_STONY_PEAKS, R_SEQ_INLINE_CALCITE, R_ruleSrc4_S2},
    [R_SEQ_INLINE_CALCITE] = {SRN_SEQ, -1, R_SEQ_INLINE_CALCITE_S1, R_STONE_BLOCK},
    [R_SEQ_INLINE_CALCITE_S1] = {SRN_TEST, C_NOISE_CALCITE, R_CALCITE_BLOCK, -1},
    [R_CALCITE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_CALCITE, -1},
    [R_ruleSrc4_S2] = {SRN_TEST, C_BI_STONY_SHORE, R_SEQ_INLINE_GRAVEL, R_ruleSrc4_S3},
    [R_SEQ_INLINE_GRAVEL] = {SRN_SEQ, -1, R_SEQ_INLINE_GRAVEL_S1, R_STONE_BLOCK},
    [R_SEQ_INLINE_GRAVEL_S1] = {SRN_TEST, C_NOISE_GRAVEL, R_ruleSrc3, -1},
    [R_ruleSrc4_S3] = {SRN_TEST, C_BI_WINDSWEPT_HILLS, R_ruleSrc4_S3a, R_ruleSrc4_S4},
    [R_ruleSrc4_S3a] = {SRN_TEST, C_SURF_ABOVE_1, R_STONE_BLOCK, -1},
    [R_ruleSrc4_S4] = {SRN_TEST, C_BI_BEACH3, R_ruleSrc2, R_ruleSrc4_S5},
    [R_ruleSrc4_S5] = {SRN_TEST, C_BI_DESERT, R_ruleSrc2, R_ruleSrc4_S6},
    [R_ruleSrc4_S6] = {SRN_TEST, C_BI_DRIPSTONE, R_STONE_BLOCK, -1},

    /* ruleSource5 / 6 */
    [R_ruleSrc5] = {SRN_TEST, C_NOISE_PS_45_58, R_ruleSrc5_a, -1},
    [R_ruleSrc5_a] = {SRN_TEST, C_WATER_0_0, R_POWDER_SNOW_BLOCK, -1},
    [R_ruleSrc6] = {SRN_TEST, C_NOISE_PS_35_6, R_ruleSrc6_a, -1},
    [R_ruleSrc6_a] = {SRN_TEST, C_WATER_0_0, R_POWDER_SNOW_BLOCK, -1},
    [R_POWDER_SNOW_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_POWDER_SNOW, -1},

    /* ---- ruleSource7 ---- */
    [R_ruleSrc7] = {SRN_SEQ, -1, R_ruleSrc7_S1, R_ruleSrc7_S2},
    [R_ruleSrc7_S1] = {SRN_TEST, C_BI_FROZEN_PEAKS, R7A, R_ruleSrc7_S2},
    [R_ruleSrc7_S2] = {SRN_TEST, C_BI_SNOWY_SLOPES, R_SEQ_INLINE_SNOWY_SLOPES5, R_ruleSrc7_S3},
    [R_SEQ_INLINE_SNOWY_SLOPES5] = {SRN_SEQ, -1, R_SNOWY_SLOPES5_S1, R_SNOWY_SLOPES5_S2},
    [R_SNOWY_SLOPES5_S1] = {SRN_TEST, C_STEEP, R_STONE_BLOCK, R_SNOWY_SLOPES5_S2},
    [R_SNOWY_SLOPES5_S2] = {SRN_SEQ, -1, R_ruleSrc5, R_SNOWY_SLOPES5_S3},
    [R_SNOWY_SLOPES5_S3] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_ruleSrc7_S3] = {SRN_TEST, C_BI_JAGGED_PEAKS, R_SEQ_INLINE_JAGGED7, R_ruleSrc7_S4},
    [R_SEQ_INLINE_JAGGED7] = {SRN_BLOCK, -1, SR_BLOCK_STONE, -1},
    [R_ruleSrc7_S4] = {SRN_TEST, C_BI_GROVE, R_SEQ_INLINE_GROVE7, R_ruleSrc7_S5},
    [R_SEQ_INLINE_GROVE7] = {SRN_SEQ, -1, R_ruleSrc5, R_DIRT_BLOCK},
    [R_ruleSrc7_S5] = {SRN_SEQ, -1, R_ruleSrc4, R_ruleSrc7_S6},
    [R_ruleSrc7_S6] = {SRN_TEST, C_BI_WINDSWEPT_SAVANNA, R_ruleSrc7_S6a, R_ruleSrc7_S7},
    [R_ruleSrc7_S6a] = {SRN_TEST, C_SURF_ABOVE_1_75, R_STONE_BLOCK, -1},
    [R_ruleSrc7_S7] = {SRN_TEST, C_BI_WINDSWEPT_GRAVELLY, R7B, R_ruleSrc7_S8},
    [R_ruleSrc7_S8] = {SRN_TEST, C_BI_MANGROVE, R_MUD_BLOCK, R_ruleSrc7_S9},
    [R_ruleSrc7_S9] = {SRN_BLOCK, -1, SR_BLOCK_DIRT, -1},
    [R_MUD_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_MUD, -1},
    [R_SNOW_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_SNOW_BLOCK, -1},

    /* R7A = frozen_peaks@7 */
    [R7A] = {SRN_SEQ, -1, R7A_S1, R7A_S2},
    [R7A_S1] = {SRN_TEST, C_STEEP, R_PACKED_ICE_BLOCK, R7A_S2},
    [R7A_S2] = {SRN_TEST, C_NOISE_PI_M5_02, R_PACKED_ICE_BLOCK, R7A_S3},
    [R7A_S3] = {SRN_TEST, C_NOISE_ICE_M0625_0025, R_ICE_BLOCK, R7A_S4},
    [R7A_S4] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_PACKED_ICE_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_PACKED_ICE, -1},

    /* R7B = windswept_gravelly@7 */
    [R7B] = {SRN_SEQ, -1, R7B_S1, R7B_S2},
    [R7B_S1] = {SRN_TEST, C_SURF_ABOVE_2, R_ruleSrc3, R7B_S2},
    [R7B_S2] = {SRN_TEST, C_SURF_ABOVE_1, R_STONE_BLOCK, R7B_S3},
    [R7B_S3] = {SRN_TEST, C_SURF_ABOVE_M0_5, R_DIRT_BLOCK, R7B_S4},
    [R7B_S4] = {SRN_SEQ, -1, R_ruleSrc3, -1},

    /* ---- ruleSource8 ---- */
    [R_ruleSrc8] = {SRN_SEQ, -1, R_ruleSrc8_S1, R_ruleSrc8_S2},
    [R_ruleSrc8_S1] = {SRN_TEST, C_BI_FROZEN_PEAKS, R8A, R_ruleSrc8_S2},
    [R_ruleSrc8_S2] = {SRN_TEST, C_BI_SNOWY_SLOPES, R_SEQ_INLINE_SNOWY_SLOPES8, R_ruleSrc8_S3},
    [R_SEQ_INLINE_SNOWY_SLOPES8] = {SRN_SEQ, -1, R_SNOWY_SLOPES8_S1, R_SNOWY_SLOPES8_S2},
    [R_SNOWY_SLOPES8_S1] = {SRN_TEST, C_STEEP, R_STONE_BLOCK, R_SNOWY_SLOPES8_S2},
    [R_SNOWY_SLOPES8_S2] = {SRN_SEQ, -1, R_ruleSrc6, R_SNOWY_SLOPES8_S3},
    [R_SNOWY_SLOPES8_S3] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_ruleSrc8_S3] = {SRN_TEST, C_BI_JAGGED_PEAKS, R_SEQ_INLINE_JAGGED8, R_ruleSrc8_S4},
    [R_SEQ_INLINE_JAGGED8] = {SRN_SEQ, -1, R_JAGGED8_S1, R_JAGGED8_S2},
    [R_JAGGED8_S1] = {SRN_TEST, C_STEEP, R_STONE_BLOCK, R_JAGGED8_S2},
    [R_JAGGED8_S2] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_ruleSrc8_S4] = {SRN_TEST, C_BI_GROVE, R_SEQ_INLINE_GROVE8, R_ruleSrc8_S5},
    [R_SEQ_INLINE_GROVE8] = {SRN_SEQ, -1, R_ruleSrc6, R_SEQ_INLINE_GROVE8_S2},
    [R_SEQ_INLINE_GROVE8_S2] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_ruleSrc8_S5] = {SRN_SEQ, -1, R_ruleSrc4, R_ruleSrc8_S6},
    [R_ruleSrc8_S6] = {SRN_TEST, C_BI_WINDSWEPT_SAVANNA, R8B, R_ruleSrc8_S7},
    [R8B] = {SRN_SEQ, -1, R8B_S1, R8B_S2},
    [R8B_S1] = {SRN_TEST, C_SURF_ABOVE_1_75, R_STONE_BLOCK, R8B_S2},
    [R8B_S2] = {SRN_TEST, C_SURF_ABOVE_M0_5, R_COARSE_DIRT_BLOCK, -1},
    [R_ruleSrc8_S7] = {SRN_TEST, C_BI_WINDSWEPT_GRAVELLY, R8C, R_ruleSrc8_S8},
    [R8C] = {SRN_SEQ, -1, R8C_S1, R8C_S2},
    [R8C_S1] = {SRN_TEST, C_SURF_ABOVE_2, R_ruleSrc3, R8C_S2},
    [R8C_S2] = {SRN_TEST, C_SURF_ABOVE_1, R_STONE_BLOCK, R8C_S3},
    [R8C_S3] = {SRN_TEST, C_SURF_ABOVE_M0_5, R_ruleSrc, R8C_S4},
    [R8C_S4] = {SRN_SEQ, -1, R_ruleSrc3, -1},
    [R_ruleSrc8_S8] = {SRN_TEST, C_BI_OLD_GROWTH2, R8D, R_ruleSrc8_S9},
    [R8D] = {SRN_SEQ, -1, R8D_S1, R8D_S2},
    [R8D_S1] = {SRN_TEST, C_SURF_ABOVE_1_75, R_COARSE_DIRT_BLOCK, R8D_S2},
    [R8D_S2] = {SRN_TEST, C_SURF_ABOVE_M0_95, R_PODZOL_BLOCK, -1},
    [R_PODZOL_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_PODZOL, -1},
    [R_ruleSrc8_S9] = {SRN_TEST, C_BI_ICE_SPIKES, R_ruleSrc8_S9a, R_ruleSrc8_S10},
    [R_ruleSrc8_S9a] = {SRN_TEST, C_WATER_0_0, R_SNOW_BLOCK, -1},
    [R_ruleSrc8_S10] = {SRN_TEST, C_BI_MANGROVE, R_MUD_BLOCK, R_ruleSrc8_S11},
    [R_ruleSrc8_S11] = {SRN_TEST, C_BI_MUSHROOM, R_MYCELIUM_BLOCK, R_ruleSrc8_S12},
    [R_MYCELIUM_BLOCK] = {SRN_BLOCK, -1, SR_BLOCK_MYCELIUM, -1},
    [R_ruleSrc8_S12] = {SRN_SEQ, -1, R_ruleSrc, -1},
};

/* ================================================================ 求值引擎 */

/* XoroshiroRandomSource.nextFloat() = next(24) / 2^24 */
CHUNKUP_FN float chunkup_rs_next_float(ChunkupRandomSource* rs) {
    return (float)(chunkup_rs_next_bits(rs, 24) & 0xFFFFFFULL) * 5.9604645e-8f;
}

/* vanilla biome base temperature（Biome.climateSettings.temperature）。
 * overworld 的 temperatureModifier 均为 NONE，直接取基础值。
 * 仅 C_TEMP（frozen ocean 冰面）路径真正消费，其余为占位。 */
static const double SR_BIOME_TEMPERATURE[SR_BIOME_COUNT] = {
    0.7,    /* OTHER */
    -0.7,   /* FROZEN_PEAKS */
    -0.45,  /* SNOWY_SLOPES */
    -0.7,   /* JAGGED_PEAKS */
    -0.2,   /* GROVE */
    2.0,    /* WINDSWEPT_SAVANNA */
    0.2,    /* WINDSWEPT_GRAVELLY_HILLS */
    0.2,    /* WINDSWEPT_HILLS */
    0.8,    /* MANGROVE_SWAMP */
    0.3,    /* OLD_GROWTH_PINE_TAIGA */
    0.25,   /* OLD_GROWTH_SPRUCE_TAIGA */
    0.0,    /* ICE_SPIKES */
    0.9,    /* MUSHROOM_FIELDS */
    1.0,    /* STONY_PEAKS */
    0.05,   /* STONY_SHORE */
    0.8,    /* DRIPSTONE_CAVES */
    0.5,    /* WARM_OCEAN */
    0.8,    /* BEACH */
    0.05,   /* SNOWY_BEACH */
    2.0,    /* DESERT */
    2.0,    /* BADLANDS */
    2.0,    /* ERODED_BADLANDS */
    2.0,    /* WOODED_BADLANDS */
    0.8,    /* SWAMP */
    0.0,    /* FROZEN_OCEAN */
    0.0,    /* DEEP_FROZEN_OCEAN */
    0.5,    /* LUKEWARM_OCEAN */
    0.5,    /* DEEP_LUKEWARM_OCEAN */
};

/* SurfaceRules.Context 对应物：XZ 级惰性缓存 + Y 级状态 */
typedef struct ChunkupSrContext {
    ChunkupSrWorld* w;
    const int32_t* heightmap;   /* [16*16]，idx = x + z*16（Heightmap.getIndex） */

    /* ---- XZ 级（updateXZ 刷新）---- */
    int block_x, block_z;
    int surface_depth;
    double surface_secondary;
    int min_surface_level;
    uint8_t noise_cached[SR_NOISE_COUNT];
    double noise_value[SR_NOISE_COUNT];

    /* ---- Y 级（updateY 刷新）---- */
    int block_y;
    int water_height;           /* INT32_MIN = 无流体 */
    int stone_depth_above;
    int stone_depth_below;
    uint8_t biome;
} ChunkupSrContext;

/* 列级噪声缓存（NoiseThresholdCondition 是 LazyXZ） */
CHUNKUP_FN double chunkup_sr_noise_at(ChunkupSrContext* ctx, int slot) {
    if (!ctx->noise_cached[slot]) {
        ctx->noise_value[slot] = chunkup_normal_get(
            &ctx->w->noises[slot], (double)ctx->block_x, 0.0, (double)ctx->block_z);
        ctx->noise_cached[slot] = 1;
    }
    return ctx->noise_value[slot];
}

/* Context.updateXZ + getMinSurfaceLevel（16×16 surface cell 双线性插值） */
CHUNKUP_FN void chunkup_sr_update_xz(ChunkupSrContext* ctx, int x, int z) {
    ctx->block_x = x;
    ctx->block_z = z;
    ctx->surface_depth = chunkup_sr_surface_depth(ctx->w, x, z);
    ctx->surface_secondary = chunkup_sr_surface_secondary(ctx->w, x, z);
    for (int i = 0; i < SR_NOISE_COUNT; ++i) {
        ctx->noise_cached[i] = 0;
    }

    const int ci = x >> 4, cj = z >> 4;
    const int c00 = chunkup_sr_preliminary_surface_level(ctx->w, ci << 4, cj << 4);
    const int c10 = chunkup_sr_preliminary_surface_level(ctx->w, (ci + 1) << 4, cj << 4);
    const int c01 = chunkup_sr_preliminary_surface_level(ctx->w, ci << 4, (cj + 1) << 4);
    const int c11 = chunkup_sr_preliminary_surface_level(ctx->w, (ci + 1) << 4, (cj + 1) << 4);
    const float ax = (float)(x & 15) / 16.0f;
    const float az = (float)(z & 15) / 16.0f;
    const float l0 = (float)c00 + ax * ((float)c10 - (float)c00);
    const float l1 = (float)c01 + ax * ((float)c11 - (float)c01);
    const float lv = l0 + az * (l1 - l0);
    ctx->min_surface_level = (int)floor((double)lv) + ctx->surface_depth - 8;
}

/* Context.updateY */
CHUNKUP_FN void chunkup_sr_update_y(
    ChunkupSrContext* ctx, int stone_above, int stone_below,
    int water_height, uint8_t biome, int y
) {
    ctx->stone_depth_above = stone_above;
    ctx->stone_depth_below = stone_below;
    ctx->water_height = water_height;
    ctx->block_y = y;
    ctx->biome = biome;
}

/* Biome.getTemperature → coldEnoughToSnow（temperature() 条件） */
CHUNKUP_FN int chunkup_sr_cold_enough_to_snow(ChunkupSrContext* ctx) {
    double f = SR_BIOME_TEMPERATURE[ctx->biome];
    const int y = ctx->block_y;
    if (y > 80) {
        const double g = chunkup_simplex_get2(
            &ctx->w->temp_noise,
            (double)((float)ctx->block_x / 8.0f),
            (double)((float)ctx->block_z / 8.0f)
        ) * 8.0;
        f -= (g + (double)y - 80.0) * 0.05 / 40.0;
    }
    return (float)f < 0.15f;
}

/* 条件求值（SurfaceRules.Condition.test，语义 1:1 对照反编译源码） */
CHUNKUP_FN int chunkup_sr_eval_cond(ChunkupSrContext* ctx, int cond_id) {
    const ChunkupSrCond* c = &SR_CONDS[cond_id];
    switch ((ChunkupSrCondType)c->type) {
    case SRC_Y:
        /* YCondition: blockY + (addStoneDepth?stoneDepthAbove:0) >= anchorY + surfaceDepth*mult */
        return ctx->block_y + (c->b0 ? ctx->stone_depth_above : 0)
            >= c->i0 + ctx->surface_depth * c->i1;

    case SRC_WATER:
        /* WaterCondition */
        return ctx->water_height == INT32_MIN
            || ctx->block_y + (c->b0 ? ctx->stone_depth_above : 0)
            >= ctx->water_height + c->i0 + ctx->surface_depth * c->i1;

    case SRC_HOLE:
        return ctx->surface_depth <= 0;

    case SRC_STEEP: {
        /* SteepMaterialCondition：本 chunk 内 clamp 的邻列 WORLD_SURFACE_WG */
        const int i = ctx->block_x & 15, j = ctx->block_z & 15;
        const int k = j - 1 > 0 ? j - 1 : 0;
        const int l = j + 1 < 15 ? j + 1 : 15;
        const int m = ctx->heightmap[i + k * 16];
        const int n = ctx->heightmap[i + l * 16];
        if (n >= m + 4) return 1;
        const int o = i - 1 > 0 ? i - 1 : 0;
        const int p = i + 1 < 15 ? i + 1 : 15;
        const int q = ctx->heightmap[o + j * 16];
        const int r = ctx->heightmap[p + j * 16];
        return q >= r + 4;
    }

    case SRC_BIOME:
        return (int)((SR_BIOME_SETS[c->sub] >> ctx->biome) & 1u);

    case SRC_NOISE: {
        const double d = chunkup_sr_noise_at(ctx, c->sub);
        return d >= c->fmin && d <= c->fmax;
    }

    case SRC_NOT:
        return !chunkup_sr_eval_cond(ctx, c->sub);

    case SRC_ABOVE_PRELIM:
        return ctx->block_y >= ctx->min_surface_level;

    case SRC_STONE_DEPTH: {
        /* StoneDepthCheck：depth = ceiling?below:above；+addSurfaceDepth；+secondaryRange 映射 */
        const int depth = c->b1 ? ctx->stone_depth_below : ctx->stone_depth_above;
        const int j = c->b0 ? ctx->surface_depth : 0;
        int k = 0;
        if (c->i1 != 0) {
            k = (int)(((ctx->surface_secondary + 1.0) / 2.0) * (double)c->i1);
        }
        return depth <= 1 + c->i0 + j + k;
    }

    case SRC_VGRAD: {
        /* VerticalGradientCondition：y<=true→1；y>=false→0；否则 nextFloat < map(y,true,false,1,0) */
        const int y = ctx->block_y;
        if (y <= c->i0) return 1;
        if (y >= c->i1) return 0;
        const double d = (double)(c->i1 - y) / (double)(c->i1 - c->i0);
        ChunkupRandomSource rs;
        chunkup_pf_at(&ctx->w->vgrad_pf[c->sub], ctx->block_x, y, ctx->block_z, &rs);
        return (double)chunkup_rs_next_float(&rs) < d;
    }

    case SRC_TEMPERATURE:
        return chunkup_sr_cold_enough_to_snow(ctx);
    }
    return 0;
}

/* 规则树求值（SurfaceRule.tryApply）。返回块 id，SR_BLOCK_SKIP = 未命中。
 * 语义：TEST=条件成立且子树命中则返回；SEQ=子链命中则返回；两者失败均走 next
 * （sequence 链的下一项）。BLOCK/BAND=直接命中。与 vanilla SequenceRule/TestRule
 * 的失败传播一致；求值幂等（无跨调用状态），链冗余 next 只造成重复计算。 */
CHUNKUP_FN uint16_t chunkup_sr_eval_rule(ChunkupSrContext* ctx, int rule_id) {
    int id = rule_id;
    while (id >= 0) {
        const ChunkupSrRule* r = &SR_RULES[id];
        switch ((ChunkupSrRuleType)r->type) {
        case SRN_BLOCK:
            return (uint16_t)r->arg;
        case SRN_BAND:
            return chunkup_sr_get_band(ctx->w, ctx->block_x, ctx->block_y, ctx->block_z);
        case SRN_TEST:
            if (chunkup_sr_eval_cond(ctx, r->cond)) {
                const uint16_t out = chunkup_sr_eval_rule(ctx, r->arg);
                if (out != SR_BLOCK_SKIP) return out;
            }
            id = r->next;
            break;
        case SRN_SEQ: {
            const uint16_t out = chunkup_sr_eval_rule(ctx, r->arg);
            if (out != SR_BLOCK_SKIP) return out;
            id = r->next;
            break;
        }
        }
    }
    return SR_BLOCK_SKIP;
}

/* ================================================================ buildSurface 主入口 */

typedef struct ChunkupSrChunkInput {
    int32_t chunk_x, chunk_z;
    int32_t min_y, height;          /* overworld: -64 / 384 */
    uint16_t* blocks;               /* [256][height]，idx = (x*16+z)*height + (y-min_y)
                                     * 输入仅含 AIR/WATER/LAVA/STONE（噪声阶段产物），
                                     * 原位写回规则命中块 */
    const int32_t* heightmap_ws_wg; /* [16*16]，idx = x + z*16 */
    const uint8_t* biome_quart;     /* [4][height/4][4]，idx = (qx*4+qz)*(height/4)+qy */
} ChunkupSrChunkInput;

CHUNKUP_FN int chunkup_sr_block_is_fluid(uint16_t b) {
    return b == SR_BLOCK_WATER || b == SR_BLOCK_LAVA;
}

/* SurfaceSystem.buildSurface：逐列自上而下，stoneDepth/waterHeight 状态机 +
 * 规则树替换（仅 defaultBlock==STONE 时）。 */
CHUNKUP_FN void chunkup_sr_build_surface(ChunkupSrWorld* w, ChunkupSrChunkInput* in) {
    ChunkupSrContext ctx;
    ctx.w = w;
    ctx.heightmap = in->heightmap_ws_wg;
    const int qy_cnt = in->height / 4;
    const int qy_base = in->min_y / 4;

    for (int k = 0; k < 16; ++k) {         /* x 偏移 */
        for (int l = 0; l < 16; ++l) {     /* z 偏移 */
            const int m = in->chunk_x * 16 + k;
            const int n = in->chunk_z * 16 + l;
            const int col = (k * 16 + l) * in->height;
            /* p 越过 chunk 顶部时按 vanilla VOID_AIR 语义处理（blockColumn.getBlock
             * 越界返回 air），此处 clamp 防脏数据死循环，读取处再判一次。 */
            int p = in->heightmap_ws_wg[k + l * 16] + 1;
            if (p > in->min_y + in->height) p = in->min_y + in->height;
            chunkup_sr_update_xz(&ctx, m, n);

            int q = 0;                      /* stoneDepthAbove 累计 */
            int r = INT32_MIN;              /* waterHeight */
            int s = INT32_MAX;              /* 石头底追踪 */
            const int t = in->min_y;

            for (int u = p; u >= t; --u) {
                /* u == t+height（最高实心格在顶）时 vanilla 读 VOID_AIR */
                const uint16_t blk = (u >= t + in->height)
                    ? SR_BLOCK_AIR : in->blocks[col + (u - t)];
                if (blk == SR_BLOCK_AIR) {
                    q = 0;
                    r = INT32_MIN;
                } else if (chunkup_sr_block_is_fluid(blk)) {
                    if (r == INT32_MIN) r = u + 1;
                } else {
                    if (s >= u) {
                        /* 重扫下方找非石头格（越界 v=t-1 视为 VOID_AIR） */
                        s = INT32_MIN;
                        for (int v = u - 1; v >= t - 1; --v) {
                            const uint16_t b2 = (v < t) ? SR_BLOCK_AIR : in->blocks[col + (v - t)];
                            if (b2 == SR_BLOCK_AIR || chunkup_sr_block_is_fluid(b2)) {
                                s = v + 1;
                                break;
                            }
                        }
                    }
                    q++;
                    const int below = u - s + 1;
                    const int qx = (m >> 2) & 3, qz = (n >> 2) & 3;
                    const int qy = (u >> 2) - qy_base;
                    const uint8_t biome = in->biome_quart[(qx * 4 + qz) * qy_cnt + qy];
                    chunkup_sr_update_y(&ctx, q, below, r, biome, u);
                    if (blk == SR_BLOCK_STONE) {
                        const uint16_t out = chunkup_sr_eval_rule(&ctx, R_ROOT);
#ifdef CHUNKUP_SR_DEBUG
                        if (k == 0 && l == 0 && u >= 93) {
                            printf("[dbg] u=%d q=%d below=%d r=%d biome=%d out=%u\n",
                                   u, q, below, r, (int)biome, out);
                        }
#endif
                        if (out != SR_BLOCK_SKIP) {
                            in->blocks[col + (u - t)] = out;
                        }
                    }
                }
            }
        }
    }
}

#ifdef __cplusplus
}
#endif

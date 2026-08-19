#pragma once

/**
 * Minecraft 1.20.1 随机源精确复刻。
 *
 * 对应 vanilla：
 * - net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus
 * - net.minecraft.world.level.levelgen.RandomSupport (mixStafford13 / upgradeSeedTo128bit / MD5 seedFromHashOf)
 * - net.minecraft.world.level.levelgen.XoroshiroRandomSource (nextInt/nextDouble/forkPositional/fromHashOf/at)
 * - net.minecraft.util.Mth.getSeed
 *
 * 位精确：所有运算与 Java long 语义一致（回绕算术）。
 */

#include "chunkup_compat.h"
#include <stdint.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_GOLDEN_RATIO_64 0x9E3779B97F4A7C15ULL       /* -7046029254386353131L */
#define CHUNKUP_SILVER_RATIO_64 0x6A09E667F3BCC909ULL      /* 7640891576956012809L  */

/* ---------- Xoroshiro128++ ---------- */

typedef struct ChunkupXoro128 {
    uint64_t lo;
    uint64_t hi;
} ChunkupXoro128;

CHUNKUP_FN uint64_t chunkup_rotl64(uint64_t x, int k) {
    return (x << k) | (x >> (64 - k));
}

CHUNKUP_FN void chunkup_xoro_init(ChunkupXoro128* s, uint64_t lo, uint64_t hi) {
    s->lo = lo;
    s->hi = hi;
    if ((lo | hi) == 0ULL) {
        s->lo = CHUNKUP_GOLDEN_RATIO_64;
        s->hi = CHUNKUP_SILVER_RATIO_64;
    }
}

CHUNKUP_FN uint64_t chunkup_xoro_next(ChunkupXoro128* s) {
    const uint64_t l = s->lo;
    const uint64_t m = s->hi;
    const uint64_t n = chunkup_rotl64(l + m, 17) + l;
    const uint64_t m2 = m ^ l;
    s->lo = chunkup_rotl64(l, 49) ^ m2 ^ (m2 << 21);
    s->hi = chunkup_rotl64(m2, 28);
    return n;
}

/* ---------- RandomSupport ---------- */

CHUNKUP_FN uint64_t chunkup_mix_stafford13(uint64_t x) {
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

/**
 * upgradeSeedTo128bit: lo = seed ^ SILVER; hi = lo + GOLDEN; 然后 both mixStafford13。
 */
CHUNKUP_FN void chunkup_upgrade_seed_128(uint64_t seed, uint64_t* out_lo, uint64_t* out_hi) {
    const uint64_t lo = seed ^ CHUNKUP_SILVER_RATIO_64;
    const uint64_t hi = lo + CHUNKUP_GOLDEN_RATIO_64;
    *out_lo = chunkup_mix_stafford13(lo);
    *out_hi = chunkup_mix_stafford13(hi);
}

/* ---------- MD5（fromHashOf 需要） ---------- */

typedef struct {
    uint32_t a, b, c, d;
    uint64_t len;
    uint8_t buf[64];
    size_t buf_len;
} ChunkupMd5;

CHUNKUP_FN uint32_t chunkup_md5_f(uint32_t x, uint32_t y, uint32_t z) { return (x & y) | (~x & z); }
CHUNKUP_FN uint32_t chunkup_md5_g(uint32_t x, uint32_t y, uint32_t z) { return (x & z) | (y & ~z); }
CHUNKUP_FN uint32_t chunkup_md5_h(uint32_t x, uint32_t y, uint32_t z) { return x ^ y ^ z; }
CHUNKUP_FN uint32_t chunkup_md5_i(uint32_t x, uint32_t y, uint32_t z) { return y ^ (x | ~z); }
CHUNKUP_FN uint32_t chunkup_md5_rotl(uint32_t x, int c) { return (x << c) | (x >> (32 - c)); }

CHUNKUP_FN void chunkup_md5_block(ChunkupMd5* m, const uint8_t* p) {
    uint32_t w[16];
    for (int i = 0; i < 16; ++i) {
        w[i] = (uint32_t)p[i * 4] | ((uint32_t)p[i * 4 + 1] << 8) | ((uint32_t)p[i * 4 + 2] << 16) | ((uint32_t)p[i * 4 + 3] << 24);
    }
    uint32_t a = m->a, b = m->b, c = m->c, d = m->d;

#define CHUNKUP_MD5_STEP(f, a, b, c, d, x, t, s) \
    (a) += f((b), (c), (d)) + (x) + (t); \
    (a) = chunkup_md5_rotl((a), (s)); \
    (a) += (b);

    /* round 1: F */
    CHUNKUP_MD5_STEP(chunkup_md5_f, a, b, c, d, w[ 0], 0xD76AA478u,  7);
    CHUNKUP_MD5_STEP(chunkup_md5_f, d, a, b, c, w[ 1], 0xE8C7B756u, 12);
    CHUNKUP_MD5_STEP(chunkup_md5_f, c, d, a, b, w[ 2], 0x242070DBu, 17);
    CHUNKUP_MD5_STEP(chunkup_md5_f, b, c, d, a, w[ 3], 0xC1BDCEEEu, 22);
    CHUNKUP_MD5_STEP(chunkup_md5_f, a, b, c, d, w[ 4], 0xF57C0FAFu,  7);
    CHUNKUP_MD5_STEP(chunkup_md5_f, d, a, b, c, w[ 5], 0x4787C62Au, 12);
    CHUNKUP_MD5_STEP(chunkup_md5_f, c, d, a, b, w[ 6], 0xA8304613u, 17);
    CHUNKUP_MD5_STEP(chunkup_md5_f, b, c, d, a, w[ 7], 0xFD469501u, 22);
    CHUNKUP_MD5_STEP(chunkup_md5_f, a, b, c, d, w[ 8], 0x698098D8u,  7);
    CHUNKUP_MD5_STEP(chunkup_md5_f, d, a, b, c, w[ 9], 0x8B44F7AFu, 12);
    CHUNKUP_MD5_STEP(chunkup_md5_f, c, d, a, b, w[10], 0xFFFF5BB1u, 17);
    CHUNKUP_MD5_STEP(chunkup_md5_f, b, c, d, a, w[11], 0x895CD7BEu, 22);
    CHUNKUP_MD5_STEP(chunkup_md5_f, a, b, c, d, w[12], 0x6B901122u,  7);
    CHUNKUP_MD5_STEP(chunkup_md5_f, d, a, b, c, w[13], 0xFD987193u, 12);
    CHUNKUP_MD5_STEP(chunkup_md5_f, c, d, a, b, w[14], 0xA679438Eu, 17);
    CHUNKUP_MD5_STEP(chunkup_md5_f, b, c, d, a, w[15], 0x49B40821u, 22);
    /* round 2: G */
    CHUNKUP_MD5_STEP(chunkup_md5_g, a, b, c, d, w[ 1], 0xF61E2562u,  5);
    CHUNKUP_MD5_STEP(chunkup_md5_g, d, a, b, c, w[ 6], 0xC040B340u,  9);
    CHUNKUP_MD5_STEP(chunkup_md5_g, c, d, a, b, w[11], 0x265E5A51u, 14);
    CHUNKUP_MD5_STEP(chunkup_md5_g, b, c, d, a, w[ 0], 0xE9B6C7AAu, 20);
    CHUNKUP_MD5_STEP(chunkup_md5_g, a, b, c, d, w[ 5], 0xD62F105Du,  5);
    CHUNKUP_MD5_STEP(chunkup_md5_g, d, a, b, c, w[10], 0x02441453u,  9);
    CHUNKUP_MD5_STEP(chunkup_md5_g, c, d, a, b, w[15], 0xD8A1E681u, 14);
    CHUNKUP_MD5_STEP(chunkup_md5_g, b, c, d, a, w[ 4], 0xE7D3FBC8u, 20);
    CHUNKUP_MD5_STEP(chunkup_md5_g, a, b, c, d, w[ 9], 0x21E1CDE6u,  5);
    CHUNKUP_MD5_STEP(chunkup_md5_g, d, a, b, c, w[14], 0xC33707D6u,  9);
    CHUNKUP_MD5_STEP(chunkup_md5_g, c, d, a, b, w[ 3], 0xF4D50D87u, 14);
    CHUNKUP_MD5_STEP(chunkup_md5_g, b, c, d, a, w[ 8], 0x455A14EDu, 20);
    CHUNKUP_MD5_STEP(chunkup_md5_g, a, b, c, d, w[13], 0xA9E3E905u,  5);
    CHUNKUP_MD5_STEP(chunkup_md5_g, d, a, b, c, w[ 2], 0xFCEFA3F8u,  9);
    CHUNKUP_MD5_STEP(chunkup_md5_g, c, d, a, b, w[ 7], 0x676F02D9u, 14);
    CHUNKUP_MD5_STEP(chunkup_md5_g, b, c, d, a, w[12], 0x8D2A4C8Au, 20);
    /* round 3: H */
    CHUNKUP_MD5_STEP(chunkup_md5_h, a, b, c, d, w[ 5], 0xFFFA3942u,  4);
    CHUNKUP_MD5_STEP(chunkup_md5_h, d, a, b, c, w[ 8], 0x8771F681u, 11);
    CHUNKUP_MD5_STEP(chunkup_md5_h, c, d, a, b, w[11], 0x6D9D6122u, 16);
    CHUNKUP_MD5_STEP(chunkup_md5_h, b, c, d, a, w[14], 0xFDE5380Cu, 23);
    CHUNKUP_MD5_STEP(chunkup_md5_h, a, b, c, d, w[ 1], 0xA4BEEA44u,  4);
    CHUNKUP_MD5_STEP(chunkup_md5_h, d, a, b, c, w[ 4], 0x4BDECFA9u, 11);
    CHUNKUP_MD5_STEP(chunkup_md5_h, c, d, a, b, w[ 7], 0xF6BB4B60u, 16);
    CHUNKUP_MD5_STEP(chunkup_md5_h, b, c, d, a, w[10], 0xBEBFBC70u, 23);
    CHUNKUP_MD5_STEP(chunkup_md5_h, a, b, c, d, w[13], 0x289B7EC6u,  4);
    CHUNKUP_MD5_STEP(chunkup_md5_h, d, a, b, c, w[ 0], 0xEAA127FAu, 11);
    CHUNKUP_MD5_STEP(chunkup_md5_h, c, d, a, b, w[ 3], 0xD4EF3085u, 16);
    CHUNKUP_MD5_STEP(chunkup_md5_h, b, c, d, a, w[ 6], 0x04881D05u, 23);
    CHUNKUP_MD5_STEP(chunkup_md5_h, a, b, c, d, w[ 9], 0xD9D4D039u,  4);
    CHUNKUP_MD5_STEP(chunkup_md5_h, d, a, b, c, w[12], 0xE6DB99E5u, 11);
    CHUNKUP_MD5_STEP(chunkup_md5_h, c, d, a, b, w[15], 0x1FA27CF8u, 16);
    CHUNKUP_MD5_STEP(chunkup_md5_h, b, c, d, a, w[ 2], 0xC4AC5665u, 23);
    /* round 4: I */
    CHUNKUP_MD5_STEP(chunkup_md5_i, a, b, c, d, w[ 0], 0xF4292244u,  6);
    CHUNKUP_MD5_STEP(chunkup_md5_i, d, a, b, c, w[ 7], 0x432AFF97u, 10);
    CHUNKUP_MD5_STEP(chunkup_md5_i, c, d, a, b, w[14], 0xAB9423A7u, 15);
    CHUNKUP_MD5_STEP(chunkup_md5_i, b, c, d, a, w[ 5], 0xFC93A039u, 21);
    CHUNKUP_MD5_STEP(chunkup_md5_i, a, b, c, d, w[12], 0x655B59C3u,  6);
    CHUNKUP_MD5_STEP(chunkup_md5_i, d, a, b, c, w[ 3], 0x8F0CCC92u, 10);
    CHUNKUP_MD5_STEP(chunkup_md5_i, c, d, a, b, w[10], 0xFFEFF47Du, 15);
    CHUNKUP_MD5_STEP(chunkup_md5_i, b, c, d, a, w[ 1], 0x85845DD1u, 21);
    CHUNKUP_MD5_STEP(chunkup_md5_i, a, b, c, d, w[ 8], 0x6FA87E4Fu,  6);
    CHUNKUP_MD5_STEP(chunkup_md5_i, d, a, b, c, w[15], 0xFE2CE6E0u, 10);
    CHUNKUP_MD5_STEP(chunkup_md5_i, c, d, a, b, w[ 6], 0xA3014314u, 15);
    CHUNKUP_MD5_STEP(chunkup_md5_i, b, c, d, a, w[13], 0x4E0811A1u, 21);
    CHUNKUP_MD5_STEP(chunkup_md5_i, a, b, c, d, w[ 4], 0xF7537E82u,  6);
    CHUNKUP_MD5_STEP(chunkup_md5_i, d, a, b, c, w[11], 0xBD3AF235u, 10);
    CHUNKUP_MD5_STEP(chunkup_md5_i, c, d, a, b, w[ 2], 0x2AD7D2BBu, 15);
    CHUNKUP_MD5_STEP(chunkup_md5_i, b, c, d, a, w[ 9], 0xEB86D391u, 21);

#undef CHUNKUP_MD5_STEP

    m->a += a;
    m->b += b;
    m->c += c;
    m->d += d;
}

CHUNKUP_FN void chunkup_md5_init(ChunkupMd5* m) {
    m->a = 0x67452301u;
    m->b = 0xEFCDAB89u;
    m->c = 0x98BADCFEu;
    m->d = 0x10325476u;
    m->len = 0;
    m->buf_len = 0;
}

CHUNKUP_FN void chunkup_md5_update(ChunkupMd5* m, const uint8_t* data, size_t n) {
    m->len += n;
    while (n > 0) {
        const size_t take = (64 - m->buf_len < n) ? (64 - m->buf_len) : n;
        for (size_t i = 0; i < take; ++i) {
            m->buf[m->buf_len + i] = data[i];
        }
        m->buf_len += take;
        data += take;
        n -= take;
        if (m->buf_len == 64) {
            chunkup_md5_block(m, m->buf);
            m->buf_len = 0;
        }
    }
}

CHUNKUP_FN void chunkup_md5_final(ChunkupMd5* m, uint8_t out[16]) {
    const uint64_t bit_len = m->len * 8;
    const uint8_t pad = 0x80;
    chunkup_md5_update(m, &pad, 1);
    const uint8_t zero = 0;
    while (m->buf_len != 56) {
        chunkup_md5_update(m, &zero, 1);
    }
    uint8_t lenb[8];
    for (int i = 0; i < 8; ++i) {
        lenb[i] = (uint8_t)(bit_len >> (i * 8));
    }
    chunkup_md5_update(m, lenb, 8);

    const uint32_t vals[4] = { m->a, m->b, m->c, m->d };
    for (int i = 0; i < 4; ++i) {
        out[i * 4] = (uint8_t)(vals[i]);
        out[i * 4 + 1] = (uint8_t)(vals[i] >> 8);
        out[i * 4 + 2] = (uint8_t)(vals[i] >> 16);
        out[i * 4 + 3] = (uint8_t)(vals[i] >> 24);
    }
}

/**
 * MD5(str) → 128bit seed（Guava Hashing.md5 与 Longs.fromBytes 均为 big-endian 组装）。
 */
CHUNKUP_FN void chunkup_seed_from_hash(
    const char* str,
    size_t len,
    uint64_t* out_lo,
    uint64_t* out_hi
) {
    ChunkupMd5 m;
    chunkup_md5_init(&m);
    chunkup_md5_update(&m, (const uint8_t*)str, len);
    uint8_t digest[16];
    chunkup_md5_final(&m, digest);

    uint64_t lo = 0, hi = 0;
    for (int i = 0; i < 8; ++i) {
        lo = (lo << 8) | digest[i];
        hi = (hi << 8) | digest[8 + i];
    }
    *out_lo = lo;
    *out_hi = hi;
}

/* ---------- XoroshiroRandomSource ---------- */

typedef struct ChunkupRandomSource {
    ChunkupXoro128 rng;
} ChunkupRandomSource;

CHUNKUP_FN void chunkup_rs_init_seed64(ChunkupRandomSource* rs, uint64_t seed) {
    uint64_t lo, hi;
    chunkup_upgrade_seed_128(seed, &lo, &hi);
    chunkup_xoro_init(&rs->rng, lo, hi);
}

CHUNKUP_FN void chunkup_rs_init_128(ChunkupRandomSource* rs, uint64_t lo, uint64_t hi) {
    chunkup_xoro_init(&rs->rng, lo, hi);
}

CHUNKUP_FN int32_t chunkup_rs_next_int(ChunkupRandomSource* rs) {
    return (int32_t)chunkup_xoro_next(&rs->rng);
}

CHUNKUP_FN uint64_t chunkup_rs_next_bits(ChunkupRandomSource* rs, int bits) {
    return chunkup_xoro_next(&rs->rng) >> (64 - bits);
}

CHUNKUP_FN double chunkup_rs_next_double(ChunkupRandomSource* rs) {
    /* Java: nextBits(53) * DOUBLE_UNIT —— DOUBLE_UNIT 是 double 常量字段（值 = float 字面量 1.110223E-16F 拓宽）。
     * nextBits(53) 的 53 位 long 可被 double 精确表示，乘法为 double 精度（对拍验证）。 */
    const double unit = 1.110223e-16f;
    return (double)chunkup_rs_next_bits(rs, 53) * unit;
}

/** Java RandomSource.nextInt(bound)，无符号乘 rejection sampling。 */
CHUNKUP_FN int32_t chunkup_rs_next_int_bound(ChunkupRandomSource* rs, int32_t bound) {
    const uint64_t b = (uint64_t)bound;
    uint64_t l = (uint64_t)(uint32_t)chunkup_rs_next_int(rs);
    uint64_t m = l * b;
    uint64_t n = m & 0xFFFFFFFFULL;
    if (n < b) {
        /* j = Integer.remainderUnsigned(-bound, bound) = 2^32 mod bound */
        const uint64_t j = (0x100000000ULL - b) % b;
        while (n < j) {
            l = (uint64_t)(uint32_t)chunkup_rs_next_int(rs);
            m = l * b;
            n = m & 0xFFFFFFFFULL;
        }
    }
    return (int32_t)(m >> 32);
}

/* ---------- PositionalRandomFactory ---------- */

typedef struct ChunkupPositionalFactory {
    uint64_t seed_lo;
    uint64_t seed_hi;
} ChunkupPositionalFactory;

/** forkPositional(): 消耗 2 个 nextLong。 */
CHUNKUP_FN ChunkupPositionalFactory chunkup_rs_fork_positional(ChunkupRandomSource* rs) {
    ChunkupPositionalFactory f;
    f.seed_lo = chunkup_xoro_next(&rs->rng);
    f.seed_hi = chunkup_xoro_next(&rs->rng);
    return f;
}

/** fromHashOf(str): MD5(str) → 128bit → xor(factoryLo, factoryHi)。 */
CHUNKUP_FN void chunkup_pf_from_hash_of(
    const ChunkupPositionalFactory* f,
    const char* str,
    size_t len,
    ChunkupRandomSource* out_rs
) {
    uint64_t lo, hi;
    chunkup_seed_from_hash(str, len, &lo, &hi);
    chunkup_rs_init_128(out_rs, lo ^ f->seed_lo, hi ^ f->seed_hi);
}

/** Mth.getSeed(x, y, z)。 */
CHUNKUP_FN uint64_t chunkup_mth_get_seed(int32_t x, int32_t y, int32_t z) {
    /* Java: long l = i * 3129871 ^ k * 116129781L ^ j;
             l = l * l * 42317861L + l * 11L; return l >> 16;
       注意 i * 3129871 是 int×int（回绕）再符号扩展；k * 116129781L 是 long 乘法；
       l >> 16 是算术右移。 */
    const uint32_t a32 = (uint32_t)x * 3129871u;               /* int 乘法回绕 */
    uint64_t l = (uint64_t)(int64_t)(int32_t)a32               /* 符号扩展 */
               ^ (uint64_t)((int64_t)z * 116129781LL)
               ^ (uint64_t)(int64_t)y;
    l = l * l * 42317861ULL + l * 11ULL;
    return (uint64_t)((int64_t)l >> 16);                       /* 算术右移 */
}

/** at(x, y, z): getSeed ^ seedLo, seedHi。 */
CHUNKUP_FN void chunkup_pf_at(
    const ChunkupPositionalFactory* f,
    int32_t x,
    int32_t y,
    int32_t z,
    ChunkupRandomSource* out_rs
) {
    const uint64_t l = chunkup_mth_get_seed(x, y, z);
    chunkup_rs_init_128(out_rs, l ^ f->seed_lo, f->seed_hi);
}

#ifdef __cplusplus
}
#endif

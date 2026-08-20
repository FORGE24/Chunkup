/* 分层对拍：随机源 + NormalNoise */
#include <stdio.h>
#include "../../native/common/chunkup_wg_eval.h"

int main(void) {
    ChunkupRandomSource root;
    chunkup_rs_init_seed64(&root, 12345ULL);
    ChunkupPositionalFactory pf = chunkup_rs_fork_positional(&root);
    printf("PF seedLo: %lld, seedHi: %lld\n",
           (long long)(int64_t)pf.seed_lo, (long long)(int64_t)pf.seed_hi);

    const char* keys[] = {"minecraft:continentalness", "minecraft:erosion", "minecraft:terrain"};
    for (int k = 0; k < 3; ++k) {
        uint64_t hlo, hhi;
        chunkup_seed_from_hash(keys[k], strlen(keys[k]), &hlo, &hhi);
        printf("HASH %s lo=%lld hi=%lld\n", keys[k], (long long)(int64_t)hlo, (long long)(int64_t)hhi);
        ChunkupRandomSource rs;
        chunkup_pf_from_hash_of(&pf, keys[k], strlen(keys[k]), &rs);
        printf("SEED %s lo=%lld hi=%lld\n", keys[k],
               (long long)(int64_t)rs.rng.lo, (long long)(int64_t)rs.rng.hi);
        const double d0 = chunkup_rs_next_double(&rs);
        const double d1 = chunkup_rs_next_double(&rs);
        const double d2 = chunkup_rs_next_double(&rs);
        printf("RS %s d3 %.17g %.17g %.17g\n", keys[k], d0, d1, d2);
        for (int i = 0; i < 5; ++i) {
            printf("RS %s ib %d %d\n", keys[k], i, chunkup_rs_next_int_bound(&rs, 256 - i));
        }
    }

    /* NormalNoise: continentalness（与 Java WgDbg 相同参数） */
    ChunkupRandomSource nrs;
    chunkup_pf_from_hash_of(&pf, "minecraft:continentalness", 26, &nrs);
    const double amps[28] = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 1.5, 1.0,
                             1.0, 1.0, 1.0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.0, 1.0,
                             1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
    ChunkupNormalNoiseD nn;
    chunkup_normal_init(&nn, &nrs, -9, amps, 28);
    printf("NN continentalness(0.5,0.5,0.5) %.17g\n", chunkup_normal_get(&nn, 0.5, 0.5, 0.5));
    printf("NN continentalness(1.5,2.5,3.5) %.17g\n", chunkup_normal_get(&nn, 1.5, 2.5, 3.5));
    printf("NN continentalness(-100.25,64.0,200.75) %.17g\n", chunkup_normal_get(&nn, -100.25, 64.0, 200.75));

    /* ImprovedNoise 层对拍：octave_-9 与 octave_-8（与 Java WgDbg 相同流程） */
    {
        printf("PF-before-NRS2 seedLo: %lld, seedHi: %lld\n",
               (long long)(int64_t)pf.seed_lo, (long long)(int64_t)pf.seed_hi);
        {
            uint64_t hlo, hhi;
            chunkup_seed_from_hash("minecraft:continentalness", 26, &hlo, &hhi);
            printf("HASH2 lo=%lld hi=%lld\n", (long long)(int64_t)hlo, (long long)(int64_t)hhi);
            const char* a = keys[0];
            const char* b = "minecraft:continentalness";
            printf("STRCMP %d lenA=%d lenB=%d\n", strcmp(a, b), (int)strlen(a), (int)strlen(b));
            printf("BYTES-A %02x%02x%02x%02x%02x%02x\n", a[0],a[1],a[2],a[3],a[4],a[5]);
            printf("BYTES-B %02x%02x%02x%02x%02x%02x\n", b[0],b[1],b[2],b[3],b[4],b[5]);
        }
        ChunkupRandomSource nrs2;
        chunkup_pf_from_hash_of(&pf, "minecraft:continentalness", 26, &nrs2);
        printf("NRS2 state lo=%lld hi=%lld\n",
               (long long)(int64_t)nrs2.rng.lo, (long long)(int64_t)nrs2.rng.hi);
        {
            ChunkupRandomSource nrs3;
            chunkup_pf_from_hash_of(&pf, "minecraft:continentalness", 26, &nrs3);
            printf("NRS3 firstLong=%lld firstDouble=%.17g\n",
                   (long long)(int64_t)chunkup_xoro_next(&nrs3.rng),
                   chunkup_rs_next_double(&nrs3));
        }
        const ChunkupPositionalFactory fork = chunkup_rs_fork_positional(&nrs2);
        printf("FORK seedLo: %lld, seedHi: %lld\n",
               (long long)(int64_t)fork.seed_lo, (long long)(int64_t)fork.seed_hi);
        const char* okeys[] = {"octave_-9", "octave_-8", "octave_0"};
        for (int k = 0; k < 3; ++k) {
            uint64_t hlo, hhi;
            chunkup_seed_from_hash(okeys[k], strlen(okeys[k]), &hlo, &hhi);
            printf("OHASH %s lo=%lld hi=%lld\n", okeys[k],
                   (long long)(int64_t)hlo, (long long)(int64_t)hhi);
        }
        for (int k = 0; k < 3; ++k) {
            ChunkupRandomSource ors;
            chunkup_pf_from_hash_of(&fork, okeys[k], strlen(okeys[k]), &ors);
            ChunkupImprovedNoiseD in;
            chunkup_improved_init(&in, &ors);
            printf("IN %s xo=%.17g yo=%.17g zo=%.17g\n", okeys[k], in.xo, in.yo, in.zo);
            printf("IN %s noise(0.5,0.5,0.5) %.17g\n", okeys[k], chunkup_improved_noise(&in, 0.5, 0.5, 0.5));
            printf("IN %s noise(1.5,2.5,3.5) %.17g\n", okeys[k], chunkup_improved_noise(&in, 1.5, 2.5, 3.5));
        }
    }
    return 0;
}

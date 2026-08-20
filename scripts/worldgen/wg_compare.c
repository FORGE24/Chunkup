/* 对拍：C 求值器 vs vanilla 黄金 dump（无 NoiseChunk 包装，marker 透传模式） */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include "../../native/common/chunkup_wg_eval.h"

typedef struct {
    const char* name;
    int32_t root;
} FnMap;

static const FnMap FN_MAP[] = {
    {"barrier", CHUNKUP_WG_DF_BARRIER},
    {"fluid_level_floodedness", CHUNKUP_WG_DF_FLUID_LEVEL_FLOODEDNESS},
    {"fluid_level_spread", CHUNKUP_WG_DF_FLUID_LEVEL_SPREAD},
    {"lava", CHUNKUP_WG_DF_LAVA},
    {"temperature", CHUNKUP_WG_DF_TEMPERATURE},
    {"vegetation", CHUNKUP_WG_DF_VEGETATION},
    {"continents", CHUNKUP_WG_DF_CONTINENTS},
    {"erosion", CHUNKUP_WG_DF_EROSION},
    {"depth", CHUNKUP_WG_DF_DEPTH},
    {"ridges", CHUNKUP_WG_DF_RIDGES},
    {"initial_density_without_jaggedness", CHUNKUP_WG_DF_INITIAL_DENSITY_WITHOUT_JAGGEDNESS},
    {"final_density", CHUNKUP_WG_DF_FINAL_DENSITY},
};
#define FN_COUNT (int)(sizeof(FN_MAP) / sizeof(FN_MAP[0]))

typedef struct {
    long total, exact, close, miss;
    double max_delta;
    char first_miss[256];
    int has_miss;
} Stat;

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: wg_compare <seed> <gold.txt>\n");
        return 2;
    }
    const uint64_t seed = strtoull(argv[1], NULL, 10);
    FILE* f = fopen(argv[2], "r");
    if (!f) {
        fprintf(stderr, "cannot open %s\n", argv[2]);
        return 2;
    }

    static ChunkupWgWorld w;
    chunkup_wg_world_init(&w, seed);
    /* 对拍模式 = 无 NoiseChunk 包装：marker 全透传 */
    memset(w.flat_slot, -1, sizeof(w.flat_slot));
    memset(w.interp_slot, -1, sizeof(w.interp_slot));
    w.flat_count = 0;
    w.interp_count = 0;

    static ChunkupWgChunk c;
    chunkup_wg_chunk_init(&c, &w, 0, 0);   /* 预计算循环因 count=0 跳过 */

    Stat stats[FN_COUNT] = {0};
    char line[512];
    long nlines = 0;
    while (fgets(line, sizeof(line), f)) {
        char fname[64];
        int x, y, z;
        char hex[32];
        if (sscanf(line, "%63s %d %d %d %31s", fname, &x, &y, &z, hex) != 5) {
            continue;
        }
        nlines++;
        int fi = -1;
        for (int i = 0; i < FN_COUNT; ++i) {
            if (strcmp(FN_MAP[i].name, fname) == 0) {
                fi = i;
                break;
            }
        }
        if (fi < 0) {
            fprintf(stderr, "unknown fn: %s\n", fname);
            continue;
        }
        const uint64_t gold_bits = strtoull(hex, NULL, 16);
        const double gold = *(const double*)(const void*)&gold_bits;
        const double got = chunkup_wg_df(&c, FN_MAP[fi].root, x, y, z);
        const uint64_t got_bits = *(const uint64_t*)(const void*)&got;
        Stat* st = &stats[fi];
        st->total++;
        if (got_bits == gold_bits) {
            st->exact++;
        } else {
            const double d = fabs(gold - got);
            if (d <= 1e-12) {
                st->close++;
            } else {
                st->miss++;
                if (!st->has_miss) {
                    st->has_miss = 1;
                    snprintf(st->first_miss, sizeof(st->first_miss),
                             "(%d,%d,%d) gold=%.17g got=%.17g d=%g", x, y, z, gold, got, d);
                }
            }
            if (d > st->max_delta) {
                st->max_delta = d;
            }
        }
    }
    fclose(f);

    printf("%-36s %6s %6s %6s %6s %12s\n", "fn", "total", "exact", "close", "miss", "max_delta");
    long tot_miss = 0;
    for (int i = 0; i < FN_COUNT; ++i) {
        const Stat* st = &stats[i];
        tot_miss += st->miss;
        printf("%-36s %6ld %6ld %6ld %6ld %12.3g",
               FN_MAP[i].name, st->total, st->exact, st->close, st->miss, st->max_delta);
        if (st->has_miss) {
            printf("  FIRST: %s", st->first_miss);
        }
        printf("\n");
    }
    printf("lines=%ld total_miss=%ld -> %s\n", nlines, tot_miss,
           tot_miss == 0 ? "ALL EXACT" : "MISMATCH");
    return tot_miss == 0 ? 0 : 1;
}

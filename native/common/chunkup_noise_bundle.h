#pragma once

#include "chunkup_improved_noise.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_NOISE_SLOT_CONTINENTALNESS 0
#define CHUNKUP_NOISE_SLOT_EROSION 1
#define CHUNKUP_NOISE_SLOT_RIDGE 2
#define CHUNKUP_NOISE_SLOT_AQUIFER 3
#define CHUNKUP_NOISE_SLOT_BASE3D 4
#define CHUNKUP_NOISE_SLOT_SHIFT 5
#define CHUNKUP_NOISE_SLOT_COUNT 6

typedef struct ChunkupNoiseBundle {
    ChunkupNoiseTables slots[CHUNKUP_NOISE_SLOT_COUNT];
} ChunkupNoiseBundle;

static inline uint32_t chunkup_noise_salt(uint32_t world_seed, uint32_t salt) {
    return world_seed ^ (salt * 0x9E3779B9u) ^ 0xA341316Cu;
}

static inline void chunkup_noise_init_bundle(ChunkupNoiseBundle* bundle, uint32_t world_seed) {
    for (uint32_t i = 0; i < CHUNKUP_NOISE_SLOT_COUNT; ++i) {
        chunkup_noise_init_tables(&bundle->slots[i], chunkup_noise_salt(world_seed, i + 1u));
    }
}

static inline const ChunkupNoiseTables* chunkup_noise_slot(
    const ChunkupNoiseBundle* bundle,
    uint32_t slot
) {
    return &bundle->slots[slot % CHUNKUP_NOISE_SLOT_COUNT];
}

#ifdef __cplusplus
}
#endif

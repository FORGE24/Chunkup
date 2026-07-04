#include "chunkup_kernel_algo.h"

ChunkupNoiseBundle chunkup_active_bundle;

void chunkup_noise_prepare(uint32_t seed) {
    chunkup_noise_init_bundle(&chunkup_active_bundle, seed);
}

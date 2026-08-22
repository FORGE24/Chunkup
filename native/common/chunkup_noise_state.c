#include "chunkup_kernel_algo.h"
#include "chunkup_noise_state.h"

ChunkupNoiseBundle chunkup_active_bundle;

void chunkup_noise_prepare(uint32_t seed) {
    chunkup_noise_init_bundle(&chunkup_active_bundle, seed);
}

/* wg_eval 位精确世界：按 seed 缓存，避免每 chunk 重复派生噪声实例 */
static ChunkupWgWorld s_wg_world;
static uint64_t s_wg_world_seed = UINT64_MAX;

ChunkupWgWorld* chunkup_noise_wg_world(uint64_t world_seed) {
    if (s_wg_world_seed != world_seed || !s_wg_world.init_ok) {
        chunkup_wg_world_init(&s_wg_world, world_seed);
        s_wg_world_seed = world_seed;
    }
    return &s_wg_world;
}

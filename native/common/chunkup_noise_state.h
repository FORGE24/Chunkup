#pragma once

#include "chunkup_noise_bundle.h"
#include "chunkup_wg_eval.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void chunkup_noise_prepare(uint32_t seed);

/* wg_eval 位精确噪声世界（64 位 seed，vanilla RandomState 派生链）。
 * 按 seed 缓存：同 seed 重复调用零开销。 */
ChunkupWgWorld* chunkup_noise_wg_world(uint64_t world_seed);

#ifdef __cplusplus
}
#endif

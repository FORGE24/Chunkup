/**
 * chunkup_surface_rules.h 的 host 入口：
 * - ChunkupSrWorld 按 seed 缓存（初始化昂贵：15 噪声 + wg 世界 + clayBands）
 * - build_surface 单 chunk 调用包装
 *
 * 线程安全：调用方（Rust 侧 SR_WORLD_LOCK）串行化。
 */

#include "chunkup_surface_rules.h"

static ChunkupSrWorld g_sr_world;
static uint64_t g_sr_seed;
static int g_sr_ready;

int chunkup_sr_host_ensure_world(uint64_t world_seed) {
    if (g_sr_ready && g_sr_seed == world_seed) {
        return 1;
    }
    chunkup_sr_world_init(&g_sr_world, world_seed);
    g_sr_seed = world_seed;
    g_sr_ready = g_sr_world.init_ok;
    return g_sr_ready;
}

int chunkup_sr_host_build(
    int32_t chunk_x,
    int32_t chunk_z,
    int32_t min_y,
    int32_t height,
    uint16_t* blocks,
    const int32_t* heightmap,
    const uint8_t* biome_quart
) {
    if (!g_sr_ready || blocks == 0 || heightmap == 0 || biome_quart == 0) {
        return 0;
    }
    if (height <= 0 || (height & 3) != 0 || (min_y & 3) != 0) {
        return 0;
    }
    ChunkupSrChunkInput in;
    in.chunk_x = chunk_x;
    in.chunk_z = chunk_z;
    in.min_y = min_y;
    in.height = height;
    in.blocks = blocks;
    in.heightmap_ws_wg = heightmap;
    in.biome_quart = biome_quart;
    chunkup_sr_build_surface(&g_sr_world, &in);
    return 1;
}

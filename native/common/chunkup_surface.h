#pragma once

/**
 * GPU buildSurface 薄层：按列写回顶层 1–4 格 surface block ID。
 * 布局：surface_layers[(lz * 16 + lx) * CHUNKUP_SURFACE_LAYERS + layer]
 */

#include "chunkup_compat.h"
#include "chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_SURFACE_LAYERS 4u

typedef enum ChunkupSurfaceBlockId {
    CHUNKUP_SURFACE_SKIP = 0u,
    CHUNKUP_SURFACE_GRASS = 1u,
    CHUNKUP_SURFACE_DIRT = 2u,
    CHUNKUP_SURFACE_SAND = 3u,
    CHUNKUP_SURFACE_SNOW = 4u,
    CHUNKUP_SURFACE_STONE = 5u,
    CHUNKUP_SURFACE_GRAVEL = 6u,
} ChunkupSurfaceBlockId;

/** biome 粗分类（Kotlin 侧映射）。 */
typedef enum ChunkupSurfaceBiomeKind {
    CHUNKUP_BIOME_DEFAULT = 0u,
    CHUNKUP_BIOME_DESERT = 1u,
    CHUNKUP_BIOME_SNOW = 2u,
    CHUNKUP_BIOME_BEACH = 3u,
    CHUNKUP_BIOME_BADLANDS = 4u,
} ChunkupSurfaceBiomeKind;

CHUNKUP_FN int chunkup_surface_find_top_solid(
    const float* density,
    int height,
    uint32_t stride_y,
    int lx,
    int lz
) {
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
        if (density[idx] > 0.0f) {
            return ly;
        }
    }
    return -1;
}

CHUNKUP_FN void chunkup_surface_fill_layers_cpu(
    const float* density,
    const uint8_t* biome_kind,
    int min_y,
    int height,
    uint32_t stride_y,
    uint8_t* surface_layers
) {
    for (int lz = 0; lz < (int)CHUNKUP_CHUNK_SIZE; ++lz) {
        for (int lx = 0; lx < (int)CHUNKUP_CHUNK_SIZE; ++lx) {
            const int col = lz * (int)CHUNKUP_CHUNK_SIZE + lx;
            const uint32_t base = (uint32_t)col * CHUNKUP_SURFACE_LAYERS;
            surface_layers[base + 0] = CHUNKUP_SURFACE_SKIP;
            surface_layers[base + 1] = CHUNKUP_SURFACE_SKIP;
            surface_layers[base + 2] = CHUNKUP_SURFACE_SKIP;
            surface_layers[base + 3] = CHUNKUP_SURFACE_SKIP;

            const int ly = chunkup_surface_find_top_solid(density, height, stride_y, lx, lz);
            if (ly < 0) {
                continue;
            }

            const uint8_t kind = biome_kind ? biome_kind[col] : CHUNKUP_BIOME_DEFAULT;
            uint8_t top = CHUNKUP_SURFACE_GRASS;
            uint8_t mid = CHUNKUP_SURFACE_DIRT;
            uint8_t deep = CHUNKUP_SURFACE_DIRT;
            uint8_t bottom = CHUNKUP_SURFACE_STONE;

            switch (kind) {
                case CHUNKUP_BIOME_DESERT:
                case CHUNKUP_BIOME_BADLANDS:
                    top = CHUNKUP_SURFACE_SAND;
                    mid = CHUNKUP_SURFACE_SAND;
                    deep = CHUNKUP_SURFACE_SAND;
                    break;
                case CHUNKUP_BIOME_SNOW:
                    top = CHUNKUP_SURFACE_SNOW;
                    mid = CHUNKUP_SURFACE_DIRT;
                    break;
                case CHUNKUP_BIOME_BEACH:
                    top = CHUNKUP_SURFACE_SAND;
                    mid = CHUNKUP_SURFACE_SAND;
                    deep = CHUNKUP_SURFACE_GRAVEL;
                    break;
                default:
                    break;
            }

            (void)min_y;
            surface_layers[base + 0] = top;
            surface_layers[base + 1] = mid;
            surface_layers[base + 2] = deep;
            surface_layers[base + 3] = bottom;
        }
    }
}

#ifdef __cplusplus
}
#endif

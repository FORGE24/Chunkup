#include "chunkup_surface.h"
#include <stdio.h>
#include <assert.h>

#define TEST_HEIGHT   256
#define TEST_MIN_Y     0
#define STRIDE_Y     256

static float     density[TEST_HEIGHT * STRIDE_Y];
static uint8_t  biome[256];
static uint8_t  layers[256 * 4];

static void set_column_top(int lx, int lz, int topLy) {
    for (int ly = 0; ly < TEST_HEIGHT; ly++) {
        uint32_t idx = (uint32_t)ly * STRIDE_Y + lz * 16 + lx;
        density[idx] = (ly <= topLy) ? 1.0f : -1.0f;
    }
}

static void check_col(int col, int tx, int ty, int tz, int tw, const char* label) {
    uint32_t b = (uint32_t)col * 4;
    printf("  [col %d] layers: top=%d mid=%d deep=%d bottom=%d  (want=%d,%d,%d,%d)\n",
           col, layers[b], layers[b+1], layers[b+2], layers[b+3], tx, ty, tz, tw);
    assert(layers[b]   == tx);
    assert(layers[b+1] == ty);
    assert(layers[b+2] == tz);
    assert(layers[b+3] == tw);
    printf("  PASS: %s (col=%d)\n\n", label, col);
}

int main(void) {
    printf("=== Chunkup Surface Rule Integration Test ===\n\n");
    printf("SEA_LEVEL = %d, BEACH_TOP_ABOVE_SEA = %d => BEACH sift top = %d\n\n",
           CHUNKUP_SURFACE_SEA_LEVEL, CHUNKUP_SURFACE_BEACH_TOP_ABOVE_SEA,
           CHUNKUP_SURFACE_SEA_LEVEL + CHUNKUP_SURFACE_BEACH_TOP_ABOVE_SEA);

    set_column_top(0, 0, 65);  biome[0] = CHUNKUP_BIOME_BEACH;
    set_column_top(1, 0, 70);  biome[1] = CHUNKUP_BIOME_BEACH;
    set_column_top(2, 0, 71);  biome[2] = CHUNKUP_BIOME_BEACH;
    set_column_top(3, 0, 100); biome[3] = CHUNKUP_BIOME_DEFAULT;
    set_column_top(4, 0, 62);  biome[4] = CHUNKUP_BIOME_BEACH;
    set_column_top(5, 0, 69);  biome[5] = CHUNKUP_BIOME_BEACH;

    chunkup_surface_fill_layers_cpu(
        density, biome, TEST_MIN_Y, TEST_HEIGHT, STRIDE_Y, layers);

    printf("[1] BEACH top_y=65 (<70) -> SAND/SAND/GRAVEL/STONE\n");
    check_col(0, CHUNKUP_SURFACE_SAND, CHUNKUP_SURFACE_SAND,
              CHUNKUP_SURFACE_GRAVEL, CHUNKUP_SURFACE_STONE, "[1]");

    printf("[2] BEACH top_y=70 (boundary, NOT >70) -> SAND/SAND/GRAVEL/STONE\n");
    check_col(1, CHUNKUP_SURFACE_SAND, CHUNKUP_SURFACE_SAND,
              CHUNKUP_SURFACE_GRAVEL, CHUNKUP_SURFACE_STONE, "[2]");

    printf("[3] BEACH top_y=71 (>70) -> GRASS/DIRT/DIRT/STONE (beach raised)\n");
    check_col(2, CHUNKUP_SURFACE_GRASS, CHUNKUP_SURFACE_DIRT,
              CHUNKUP_SURFACE_DIRT, CHUNKUP_SURFACE_STONE, "[3]");

    printf("[4] DEFAULT biome top_y=100 -> GRASS/DIRT/DIRT/STONE\n");
    check_col(3, CHUNKUP_SURFACE_GRASS, CHUNKUP_SURFACE_DIRT,
              CHUNKUP_SURFACE_DIRT, CHUNKUP_SURFACE_STONE, "[4]");

    printf("[5] BEACH top_y=62 (below sea) -> SAND/SAND/GRAVEL/STONE\n");
    check_col(4, CHUNKUP_SURFACE_SAND, CHUNKUP_SURFACE_SAND,
              CHUNKUP_SURFACE_GRAVEL, CHUNKUP_SURFACE_STONE, "[5]");

    printf("[6] BEACH top_y=69 (just below 70) -> SAND/SAND/GRAVEL/STONE\n");
    check_col(5, CHUNKUP_SURFACE_SAND, CHUNKUP_SURFACE_SAND,
              CHUNKUP_SURFACE_GRAVEL, CHUNKUP_SURFACE_STONE, "[6]");

    printf("\n=== ALL 6 TESTS PASSED ===\n");
    return 0;
}

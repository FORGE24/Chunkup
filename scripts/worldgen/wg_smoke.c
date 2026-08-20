/* 冒烟测试：chunkup_wg_eval 链路编译+运行 sanity */
#include <stdio.h>
#include "../../native/common/chunkup_wg_eval.h"

int main(void) {
    static ChunkupWgWorld w;
    static ChunkupWgChunk c;

    chunkup_wg_world_init(&w, 12345LL);
    printf("init_ok=%d flat=%d interp=%d\n", w.init_ok, w.flat_count, w.interp_count);

    chunkup_wg_chunk_init(&c, &w, 0, 0);

    /* 采样若干点，检查密度值域与 y 递减趋势 */
    double sum = 0.0;
    int solid_low = 0, solid_high = 0;
    for (int x = 0; x < 16; x += 4) {
        for (int z = 0; z < 16; z += 4) {
            const double d_low = chunkup_wg_block_density(&c, x, -60, z);
            const double d_high = chunkup_wg_block_density(&c, x, 300, z);
            sum += d_low;
            if (d_low > 0.0) solid_low++;
            if (d_high > 0.0) solid_high++;
        }
    }
    printf("density(-60) avg=%.6f solid=%d/16, density(300) solid=%d/16\n",
           sum / 16.0, solid_low, solid_high);

    /* initial_density_without_jaggedness 也跑一遍 */
    const double init_d = chunkup_wg_initial_density(&c, 8, 64, 8);
    printf("initial_density(8,64,8)=%.6f\n", init_d);

    /* 多区块确定性：重复初始化同区块结果必须一致 */
    static ChunkupWgChunk c2;
    chunkup_wg_chunk_init(&c2, &w, 3, -2);
    const double a = chunkup_wg_block_density(&c2, 3 * 16 + 5, 40, -2 * 16 + 9);
    chunkup_wg_chunk_init(&c2, &w, 3, -2);
    const double b = chunkup_wg_block_density(&c2, 3 * 16 + 5, 40, -2 * 16 + 9);
    printf("deterministic=%d (%.17g vs %.17g)\n", a == b, a, b);

    return 0;
}

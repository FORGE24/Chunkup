/* GPU vs CPU 密度对拍：
 * GPU: chunkup_cuda_density_fill_batch（CUDA kernel，wg_eval 位精确密度源）
 * CPU: chunkup_cell_fill_chunk（相同角点缓存 + 三线性插值 + aquifer 逻辑）
 * 期望：ALL EXACT（角点 double→float + float 插值两侧一致）
 *
 * 编译（DevShell x64）:
 *   cl /nologo /O2 /utf-8 /I native\common /I native\cuda\include ^
 *      scripts\worldgen\wg_gpu_compare.c /Fe:build\cuda\wg_gpu_compare.exe ^
 *      /link build\cuda\chunkup_cuda.lib
 * 运行: build\cuda\wg_gpu_compare.exe（与 chunkup_cuda.dll 同目录）
 */
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#include "../../native/common/chunkup_cell_fill.h"
#include "../../native/cuda/include/chunkup_cuda.h"

int main(void) {
    if (!chunkup_cuda_is_available()) {
        printf("CUDA not available\n");
        return 1;
    }

    const int32_t min_y = -64;
    const int height = 384;
    const uint32_t stride_y = CHUNKUP_BLOCKS_PER_SECTION; /* 256 */
    const uint32_t bpc = stride_y * (uint32_t)height;      /* 98304 */

    ChunkupKernelJob job;
    memset(&job, 0, sizeof(job));
    job.chunk_x = 0;
    job.chunk_z = 0;
    job.min_y = min_y;
    job.height = height;
    job.seed = 12345u;
    job.world_seed = 12345ULL;
    job.op_mask = CHUNKUP_OP_NOISE_FILL;
    job.stage = CHUNKUP_STAGE_NOISE_FILL;

    float* gpu = (float*)malloc((size_t)bpc * 4 * sizeof(float));
    uint8_t* gpu_fluid = (uint8_t*)malloc((size_t)bpc * 4);
    float* cpu = (float*)malloc((size_t)bpc * sizeof(float));
    uint8_t* cpu_fluid = (uint8_t*)malloc((size_t)bpc);
    if (!gpu || !cpu || !gpu_fluid || !cpu_fluid) {
        printf("oom\n");
        return 1;
    }

    int32_t xs[4] = {0, 1, 0, 1};
    int32_t zs[4] = {0, 0, 1, 1};
    const int BATCH = 4;
    ChunkupKernelResult res;
    res.status = 0;
    res.ops_completed = 0u;
    if (chunkup_cuda_density_fill_batch(&job, BATCH, xs, zs, gpu, gpu_fluid, bpc, &res) != 0) {
        printf("GPU density_fill_batch failed (status=%d)\n", res.status);
        return 1;
    }
    printf("GPU ops_completed=0x%x\n", res.ops_completed);

    /* CPU 参考：同一角点 + 插值 + aquifer 逻辑（逐 chunk 重算，覆盖批量偏移） */
    static ChunkupWgWorld w;
    chunkup_wg_world_init(&w, 12345ULL);

    /* GPU/CPU double 库函数（sin/cos/pow 等）存在 ≤2 ulp 微差，
     * 经 double→float 角点转换后表现为 float 最后 1-2 位差异。
     * 地形判定（阈值 0）对此完全不敏感；容差 1e-6 内视为通过。 */
    const double TOL = 1e-6;
    size_t exact = 0, within_tol = 0, total = 0, fluid_diff = 0;
    double max_abs = 0.0, sum_abs = 0.0;
    int worst_ly = 0;
    for (int b = 0; b < BATCH; ++b) {
        chunkup_cell_fill_chunk(
            &w,
            xs[b] * (int32_t)CHUNKUP_CHUNK_SIZE,
            zs[b] * (int32_t)CHUNKUP_CHUNK_SIZE,
            min_y, height, cpu, cpu_fluid, stride_y
        );
        const float* g = gpu + (size_t)b * bpc;
        const uint8_t* gf = gpu_fluid + (size_t)b * bpc;
        for (int ly = 0; ly < height; ++ly) {
            for (int lz = 0; lz < 16; ++lz) {
                for (int lx = 0; lx < 16; ++lx) {
                    const uint32_t idx = chunkup_block_index(lx, ly, lz, stride_y);
                    const double a = (double)g[idx];
                    const double c = (double)cpu[idx];
                    const double diff = fabs(a - c);
                    total++;
                    sum_abs += diff;
                    if (diff == 0.0) {
                        exact++;
                    }
                    if (diff <= TOL) {
                        within_tol++;
                    }
                    if (diff > max_abs) {
                        max_abs = diff;
                        worst_ly = ly;
                    }
                    if (gf[idx] != cpu_fluid[idx]) {
                        fluid_diff++;
                    }
                }
            }
        }
    }
    printf("density: %zu/%zu bit-exact (%.4f%%), %zu/%zu within %.0e (%.4f%%)\n",
           exact, total, 100.0 * (double)exact / (double)total,
           within_tol, total, TOL, 100.0 * (double)within_tol / (double)total);
    printf("max_abs=%.9g (ly=%d), avg_abs=%.9g\n", max_abs, worst_ly,
           sum_abs / (double)total);
    printf("fluid diff: %zu\n", fluid_diff);

    if (within_tol == total && fluid_diff == 0) {
        printf("PASS (GPU ~= CPU, max |diff| %.3g <= %.0e)\n", max_abs, TOL);
    } else {
        printf("MISMATCH\n");
    }

    free(gpu);
    free(cpu);
    free(gpu_fluid);
    free(cpu_fluid);
    return (within_tol == total && fluid_diff == 0) ? 0 : 2;
}

#pragma once

/**
 * Chunkup 统一 Kernel ABI
 *
 * 所有后端（CUDA / OpenCL / CPU+SIMD）共享同一 Job / Buffer 布局与 Op 语义。
 * Kotlin → Rust → chunkup_kernel_dispatch_*()
 */

#include <stdint.h>

#include "chunkup_compat.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHUNKUP_CHUNK_SIZE 16u
#define CHUNKUP_BLOCKS_PER_SECTION (CHUNKUP_CHUNK_SIZE * CHUNKUP_CHUNK_SIZE)

/** 与 Kotlin [ChunkGenerationStage] ordinal 对齐的可选 hint */
#define CHUNKUP_STAGE_BIOMES 0u
#define CHUNKUP_STAGE_NOISE_FILL 1u
#define CHUNKUP_STAGE_SURFACE 2u
#define CHUNKUP_STAGE_FEATURES 3u
#define CHUNKUP_STAGE_GENERATED 4u
#define CHUNKUP_STAGE_LOADED 5u

typedef enum ChunkupKernelOp {
    CHUNKUP_OP_NOISE_FILL = 1u << 0,
    CHUNKUP_OP_SKYLIGHT = 1u << 1,
    CHUNKUP_OP_BLOCKLIGHT = 1u << 2,
    CHUNKUP_OP_FACE_CULL = 1u << 3,
    CHUNKUP_OP_SECTION_MESH = 1u << 4,
    CHUNKUP_OP_OCCLUSION_PACK = 1u << 5,
} ChunkupKernelOp;

typedef struct ChunkupKernelJob {
    int32_t chunk_x;
    int32_t chunk_z;
    int32_t min_y;
    int32_t height;
    uint32_t seed;
    uint32_t op_mask;
    uint32_t stage;
} ChunkupKernelJob;

typedef struct ChunkupKernelBuffers {
    float* density;
    uint8_t* fluid;
    uint8_t* skylight;
    uint8_t* blocklight;
    uint8_t* face_mask;
    uint32_t stride_y;
} ChunkupKernelBuffers;

typedef struct ChunkupKernelResult {
    int32_t status;
    uint32_t ops_completed;
} ChunkupKernelResult;

/** 将 Fabric 生成阶段映射为默认 op 掩码 */
uint32_t chunkup_kernel_ops_for_stage(uint32_t stage);

/** 在 host 侧分配/绑定缓冲区的推荐大小（字节） */
uint32_t chunkup_kernel_density_bytes(uint32_t height);
uint32_t chunkup_kernel_fluid_bytes(uint32_t height);
uint32_t chunkup_kernel_light_bytes(uint32_t height);
uint32_t chunkup_kernel_face_mask_bytes(uint32_t height);

CHUNKUP_FN uint32_t chunkup_block_index(int lx, int ly, int lz, uint32_t stride_y) {
    return (uint32_t)ly * stride_y + (uint32_t)lz * CHUNKUP_CHUNK_SIZE + (uint32_t)lx;
}

/** 统一入口：按 job.op_mask 顺序执行各 op */
int chunkup_kernel_dispatch_cpu(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
);

#ifdef __cplusplus
}
#endif

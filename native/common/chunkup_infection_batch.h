#ifndef CHUNKUP_INFECTION_BATCH_H
#define CHUNKUP_INFECTION_BATCH_H

#include "chunkup_export.h"
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 「第一次传染」批量 section mesh + GPU 上传。
 *
 * 输入：连续 section 记录流
 *   [int32 originX][int32 originY][int32 originZ][uint8 blockStates[4096]] × sectionCount
 *
 * 输出：合并顶点缓冲 + per-section draw indirect（Sodium CompactChunkVertex 兼容）
 *
 * Phase 2 实现：CUDA SECTION_MESH batch + GL SSBO upload
 */
typedef struct ChunkupInfectionBatchDesc {
    const uint8_t* sections;
    uint32_t section_count;
    int32_t min_origin_x;
    int32_t min_origin_y;
    int32_t min_origin_z;
} ChunkupInfectionBatchDesc;

typedef struct ChunkupInfectionBatchResult {
    void* device_vertex_buffer;
    size_t vertex_bytes;
    uint32_t draw_count;
    int ok;
} ChunkupInfectionBatchResult;

CHUNKUP_API int chunkup_infection_batch_mesh(
    const ChunkupInfectionBatchDesc* desc,
    ChunkupInfectionBatchResult* out
);

CHUNKUP_API void chunkup_infection_batch_release(ChunkupInfectionBatchResult* result);

#ifdef __cplusplus
}
#endif

#endif

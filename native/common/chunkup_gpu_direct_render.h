#pragma once

#include "../common/chunkup_export.h"
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

CHUNKUP_API uint64_t chunkup_cuda_upload_block_states(
    const uint8_t* host_data,
    uint32_t       total_bytes
);

CHUNKUP_API void chunkup_cuda_free_block_states(uint64_t device_ptr);

CHUNKUP_API int chunkup_gpu_mesh_count_only(
    const uint8_t* block_states_data,
    int32_t        device_block_states,
    uint32_t       section_count,
    uint32_t*      out_vertex_counts
);

CHUNKUP_API int chunkup_gpu_mesh_to_vbo(
    const uint8_t*   block_states_data,
    int32_t          device_block_states,
    uint32_t         section_count,
    uint32_t         vertex_stride,
    uint32_t         gl_vbo_id,
    const uint32_t*  vertex_offset_table,
    uint32_t*        out_draw_command_buffer
);

CHUNKUP_API int32_t chunkup_cuda_gl_register(uint32_t gl_vbo_id);
CHUNKUP_API void    chunkup_cuda_gl_unregister(uint32_t gl_vbo_id);

CHUNKUP_API int32_t chunkup_interop_is_available(void);

#ifdef __cplusplus
}
#endif
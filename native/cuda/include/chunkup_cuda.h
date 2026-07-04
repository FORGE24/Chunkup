#pragma once

#include "../common/chunkup_kernel.h"

#ifdef __cplusplus
extern "C" {
#endif

int chunkup_cuda_is_available(void);

int chunkup_cuda_kernel_dispatch(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
);

#ifdef __cplusplus
}
#endif

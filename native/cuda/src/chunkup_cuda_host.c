#include "../common/chunkup_cuda_host.h"

#include <cuda_runtime.h>
#include <string.h>

static int chunkup_cuda_host_check(cudaError_t err) {
    return err == cudaSuccess ? 0 : -10;
}

CHUNKUP_API int chunkup_cuda_pinned_ensure(ChunkupCudaPinnedChunkBuffers* buffers, size_t block_count) {
    if (!buffers || block_count == 0u) {
        return -1;
    }
    if (buffers->ready && buffers->block_count >= block_count) {
        return 0;
    }

    chunkup_cuda_pinned_release(buffers);

    const size_t density_bytes = block_count * sizeof(float);
    const size_t fluid_bytes = block_count;

    if (chunkup_cuda_host_check(cudaHostAlloc(&buffers->host_density, density_bytes, cudaHostAllocDefault)) != 0) {
        chunkup_cuda_pinned_release(buffers);
        return -10;
    }
    if (chunkup_cuda_host_check(cudaHostAlloc(&buffers->host_fluid, fluid_bytes, cudaHostAllocDefault)) != 0) {
        chunkup_cuda_pinned_release(buffers);
        return -10;
    }
    if (chunkup_cuda_host_check(cudaMalloc(&buffers->device_density, density_bytes)) != 0) {
        chunkup_cuda_pinned_release(buffers);
        return -10;
    }
    if (chunkup_cuda_host_check(cudaMalloc(&buffers->device_fluid, fluid_bytes)) != 0) {
        chunkup_cuda_pinned_release(buffers);
        return -10;
    }

    buffers->block_count = block_count;
    buffers->ready = 1;
    return 0;
}

CHUNKUP_API void chunkup_cuda_pinned_release(ChunkupCudaPinnedChunkBuffers* buffers) {
    if (!buffers) {
        return;
    }
    if (buffers->host_density) {
        cudaFreeHost(buffers->host_density);
    }
    if (buffers->host_fluid) {
        cudaFreeHost(buffers->host_fluid);
    }
    if (buffers->device_density) {
        cudaFree(buffers->device_density);
    }
    if (buffers->device_fluid) {
        cudaFree(buffers->device_fluid);
    }
    memset(buffers, 0, sizeof(*buffers));
}

CHUNKUP_API int chunkup_cuda_pinned_copy_density_d2h(
    ChunkupCudaPinnedChunkBuffers* buffers,
    size_t block_count
) {
    if (!buffers || !buffers->ready || !buffers->host_density || !buffers->device_density) {
        return -1;
    }
    if (block_count > buffers->block_count) {
        return -2;
    }
    return chunkup_cuda_host_check(cudaMemcpy(
        buffers->host_density,
        buffers->device_density,
        block_count * sizeof(float),
        cudaMemcpyDeviceToHost
    ));
}

CHUNKUP_API int chunkup_cuda_pinned_copy_fluid_d2h(
    ChunkupCudaPinnedChunkBuffers* buffers,
    size_t block_count
) {
    if (!buffers || !buffers->ready || !buffers->host_fluid || !buffers->device_fluid) {
        return -1;
    }
    if (block_count > buffers->block_count) {
        return -2;
    }
    return chunkup_cuda_host_check(cudaMemcpy(
        buffers->host_fluid,
        buffers->device_fluid,
        block_count,
        cudaMemcpyDeviceToHost
    ));
}

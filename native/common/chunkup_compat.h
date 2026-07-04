#pragma once

/**
 * CPU / CUDA 双端内联函数宏。
 * CUDA 编译单元中去掉 static，以便 __device__ 链接。
 */
#ifdef __CUDACC__
#define CHUNKUP_FN __host__ __device__ __forceinline__
#define CHUNKUP_ARRAY __device__ __constant__
#else
#define CHUNKUP_FN static inline
#define CHUNKUP_ARRAY static const
#endif

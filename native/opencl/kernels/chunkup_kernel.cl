// Chunkup unified OpenCL kernel — mirrors native/common/chunkup_kernel_algo.h

#define CHUNKUP_CHUNK_SIZE 16u
#define CHUNKUP_BLOCKS_PER_SECTION (CHUNKUP_CHUNK_SIZE * CHUNKUP_CHUNK_SIZE)

inline uint chunkup_hash_u32(uint x, uint y, uint z, uint seed) {
    uint h = seed ^ x * 374761393u ^ y * 668265263u ^ z * 2147483647u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}

inline float chunkup_density_at(int wx, int wy, int wz, uint seed) {
    float n = (float)(chunkup_hash_u32((uint)wx, (uint)wy, (uint)wz, seed) & 0xFFFFu) / 65535.0f;
    float height_bias = ((float)wy / 256.0f) - 0.45f;
    return n * 0.4f + height_bias;
}

inline int chunkup_is_solid(float density) {
    return density > 0.0f;
}

inline uint chunkup_block_index(int lx, int ly, int lz, uint stride_y) {
    return (uint)ly * stride_y + (uint)lz * CHUNKUP_CHUNK_SIZE + (uint)lx;
}

__kernel void chunkup_kernel_noise_fill(
    __global float* density,
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint seed,
    uint stride_y
) {
    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);
    const int ly = (int)get_global_id(2);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const int wy = min_y + ly;
    const uint idx = chunkup_block_index(lx, ly, lz, stride_y);
    density[idx] = chunkup_density_at(base_x + lx, wy, base_z + lz, seed);
}

__kernel void chunkup_kernel_skylight(
    __global const float* density,
    __global uchar* skylight,
    int height,
    uint stride_y
) {
    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint idx = chunkup_block_index(lx, ly, lz, stride_y);
        if (chunkup_is_solid(density[idx])) {
            light = 0;
        }
        skylight[idx] = (uchar)light;
        if (light > 0) {
            light -= 1;
        }
    }
}

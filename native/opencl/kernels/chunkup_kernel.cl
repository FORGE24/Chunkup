// Chunkup OpenCL kernel — 与 chunkup_overworld_density.h 同结构的简化实现

#define CHUNKUP_CHUNK_SIZE 16u
#define CHUNKUP_BLOCKS_PER_SECTION (CHUNKUP_CHUNK_SIZE * CHUNKUP_CHUNK_SIZE)

typedef struct {
    uchar perm[512];
    float xo;
    float yo;
    float zo;
} ChunkupNoiseTables;

inline uint chunkup_lcg(uint* state) {
    *state = (*state) * 1664525u + 1013904223u;
    return *state;
}

inline void chunkup_noise_init_tables(ChunkupNoiseTables* tables, uint seed) {
    uint rng = seed ^ 0xA341316Cu;
    for (int i = 0; i < 256; ++i) {
        tables->perm[i] = (uchar)i;
    }
    for (int i = 255; i > 0; --i) {
        uint r = chunkup_lcg(&rng);
        int j = (int)(r % (uint)(i + 1));
        uchar tmp = tables->perm[i];
        tables->perm[i] = tables->perm[j];
        tables->perm[j] = tmp;
    }
    for (int i = 0; i < 256; ++i) {
        tables->perm[i + 256] = tables->perm[i];
    }
    tables->xo = (float)chunkup_lcg(&rng) / 65536.0f * 256.0f;
    tables->yo = (float)chunkup_lcg(&rng) / 65536.0f * 256.0f;
    tables->zo = (float)chunkup_lcg(&rng) / 65536.0f * 256.0f;
}

inline int chunkup_perm_index(const ChunkupNoiseTables* t, int i) {
    return (int)t->perm[i & 255];
}

inline float chunkup_smoothstep(float x) {
    return x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
}

inline float chunkup_grad_dot(int h, float x, float y, float z) {
    switch (h & 15) {
        case 0: return x + y; case 1: return -x + y; case 2: return x - y; case 3: return -x - y;
        case 4: return x + z; case 5: return -x + z; case 6: return x - z; case 7: return -x - z;
        case 8: return y + z; case 9: return -y + z; case 10: return y - z; case 11: return -y - z;
        case 12: return y + x; case 13: return -y + z; case 14: return y - x; default: return -y - z;
    }
}

inline float chunkup_improved_noise3(const ChunkupNoiseTables* t, float x, float y, float z) {
    x += t->xo; y += t->yo; z += t->zo;
    int x0 = (int)floor(x), y0 = (int)floor(y), z0 = (int)floor(z);
    float fx = x - x0, fy = y - y0, fz = z - z0;
    int aa = chunkup_perm_index(t, chunkup_perm_index(t, x0) + y0);
    int ab = chunkup_perm_index(t, chunkup_perm_index(t, x0) + y0 + 1);
    int ba = chunkup_perm_index(t, chunkup_perm_index(t, x0 + 1) + y0);
    int bb = chunkup_perm_index(t, chunkup_perm_index(t, x0 + 1) + y0 + 1);
    float c000 = chunkup_grad_dot(chunkup_perm_index(t, aa + z0), fx, fy, fz);
    float c100 = chunkup_grad_dot(chunkup_perm_index(t, ba + z0), fx - 1, fy, fz);
    float c010 = chunkup_grad_dot(chunkup_perm_index(t, ab + z0), fx, fy - 1, fz);
    float c110 = chunkup_grad_dot(chunkup_perm_index(t, bb + z0), fx - 1, fy - 1, fz);
    float c001 = chunkup_grad_dot(chunkup_perm_index(t, aa + z0 + 1), fx, fy, fz - 1);
    float c101 = chunkup_grad_dot(chunkup_perm_index(t, ba + z0 + 1), fx - 1, fy, fz - 1);
    float c011 = chunkup_grad_dot(chunkup_perm_index(t, ab + z0 + 1), fx, fy - 1, fz - 1);
    float c111 = chunkup_grad_dot(chunkup_perm_index(t, bb + z0 + 1), fx - 1, fy - 1, fz - 1);
    float u = chunkup_smoothstep(fx), v = chunkup_smoothstep(fy), w = chunkup_smoothstep(fz);
    float a = mix(c000, c100, u), b = mix(c010, c110, u);
    float c = mix(c001, c101, u), d = mix(c011, c111, u);
    return mix(mix(a, b, v), mix(c, d, v), w);
}

inline float chunkup_fractal3(const ChunkupNoiseTables* t, float x, float y, float z, int oct, float lac, float pers) {
    float sum = 0, amp = 1, freq = 1, norm = 0;
    for (int i = 0; i < oct; ++i) {
        sum += chunkup_improved_noise3(t, x * freq, y * freq, z * freq) * amp;
        norm += amp; amp *= pers; freq *= lac;
    }
    return norm > 0 ? sum / norm : 0;
}

inline float chunkup_density_at_tables(const ChunkupNoiseTables* t, int wx, int wy, int wz) {
    float continental = chunkup_fractal3(t, wx * 0.00025f, 0, wz * 0.00025f, 4, 2, 0.5f);
    float erosion = chunkup_fractal3(t, wx * 0.0012f, 0, wz * 0.0012f, 3, 2, 0.55f);
    float ridge_src = chunkup_fractal3(t, wx * 0.0025f, 0, wz * 0.0025f, 4, 2, 0.5f);
    float ridges = 1.0f - fabs(ridge_src);
    float surface = 63.0f + continental * 52.0f + erosion * 18.0f + ridges * 28.0f;
    float density = (surface - (float)wy) * 0.09f;
    density += chunkup_fractal3(t, wx * 0.007f, wy * 0.007f, wz * 0.007f, 3, 2, 0.5f) * 6.0f;
    density += chunkup_fractal3(t, wx * 0.015f, wy * 0.015f, wz * 0.015f, 2, 2, 0.45f) * 2.5f;
    if (wy < surface - 6.0f) {
        float cheese = chunkup_fractal3(t, wx * 0.018f, wy * 0.018f, wz * 0.018f, 2, 2, 0.5f);
        if (cheese > 0.55f) density -= (cheese - 0.55f) * 22.0f;
    }
    return density;
}

inline int chunkup_is_solid(float density) { return density > 0.0f; }

inline uint chunkup_block_index(int lx, int ly, int lz, uint stride_y) {
    return (uint)ly * stride_y + (uint)lz * CHUNKUP_CHUNK_SIZE + (uint)lx;
}

__kernel void chunkup_kernel_noise_fill(
    __global float* density,
    __constant ChunkupNoiseTables* noise,
    int base_x,
    int base_z,
    int min_y,
    int height,
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
    density[idx] = chunkup_density_at_tables(noise, base_x + lx, wy, base_z + lz);
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

inline float chunkup_density_sample_cl(
    __global const float* density,
    int lx,
    int ly,
    int lz,
    int height,
    uint stride_y
) {
    if (lx < 0 || lz < 0 || lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return -1.0f;
    }
    if (ly < 0 || ly >= height) {
        return -1.0f;
    }
    return density[chunkup_block_index(lx, ly, lz, stride_y)];
}

__kernel void chunkup_kernel_face_cull(
    __global const float* density,
    __global uchar* face_mask,
    int height,
    uint stride_y
) {
    const int lx = (int)get_global_id(0);
    const int ly = (int)get_global_id(1);
    const int lz = (int)get_global_id(2);

    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const uint idx = chunkup_block_index(lx, ly, lz, stride_y);
    if (!chunkup_is_solid(density[idx])) {
        face_mask[idx] = 0;
        return;
    }

    uchar mask = 0;
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx, ly + 1, lz, height, stride_y))) {
        mask |= 1 << 0;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx, ly - 1, lz, height, stride_y))) {
        mask |= 1 << 1;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx, ly, lz - 1, height, stride_y))) {
        mask |= 1 << 2;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx, ly, lz + 1, height, stride_y))) {
        mask |= 1 << 3;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx - 1, ly, lz, height, stride_y))) {
        mask |= 1 << 4;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(density, lx + 1, ly, lz, height, stride_y))) {
        mask |= 1 << 5;
    }
    face_mask[idx] = mask;
}

__kernel void chunkup_kernel_skylight_batch(
    __global const float* density,
    __global uchar* skylight,
    int height,
    uint stride_y,
    uint blocks_per_chunk,
    int batch_count
) {
    const int chunk_idx = (int)get_global_id(2);
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE) {
        return;
    }

    const __global float* chunk_density = density + (uint)chunk_idx * blocks_per_chunk;
    __global uchar* chunk_light = skylight + (uint)chunk_idx * blocks_per_chunk;

    int light = 15;
    for (int ly = height - 1; ly >= 0; --ly) {
        const uint idx = chunkup_block_index(lx, ly, lz, stride_y);
        if (chunkup_is_solid(chunk_density[idx])) {
            light = 0;
        }
        chunk_light[idx] = (uchar)light;
        if (light > 0) {
            light -= 1;
        }
    }
}

__kernel void chunkup_kernel_face_cull_batch(
    __global const float* density,
    __global uchar* face_mask,
    int height,
    uint stride_y,
    uint blocks_per_chunk,
    int batch_count
) {
    const int flat_z = (int)get_global_id(2);
    const int chunk_idx = flat_z / height;
    const int ly = flat_z % height;
    if (chunk_idx >= batch_count) {
        return;
    }

    const int lx = (int)get_global_id(0);
    const int lz = (int)get_global_id(1);
    if (lx >= (int)CHUNKUP_CHUNK_SIZE || lz >= (int)CHUNKUP_CHUNK_SIZE || ly >= height) {
        return;
    }

    const __global float* chunk_density = density + (uint)chunk_idx * blocks_per_chunk;
    __global uchar* chunk_faces = face_mask + (uint)chunk_idx * blocks_per_chunk;

    const uint idx = chunkup_block_index(lx, ly, lz, stride_y);
    if (!chunkup_is_solid(chunk_density[idx])) {
        chunk_faces[idx] = 0;
        return;
    }

    uchar mask = 0;
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx, ly + 1, lz, height, stride_y))) {
        mask |= 1 << 0;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx, ly - 1, lz, height, stride_y))) {
        mask |= 1 << 1;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx, ly, lz - 1, height, stride_y))) {
        mask |= 1 << 2;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx, ly, lz + 1, height, stride_y))) {
        mask |= 1 << 3;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx - 1, ly, lz, height, stride_y))) {
        mask |= 1 << 4;
    }
    if (!chunkup_is_solid(chunkup_density_sample_cl(chunk_density, lx + 1, ly, lz, height, stride_y))) {
        mask |= 1 << 5;
    }
    chunk_faces[idx] = mask;
}

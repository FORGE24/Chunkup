#include "../common/chunkup_gpu_direct_render.h"
#include "../common/chunkup_sl_log.h"

#include <cuda_runtime.h>
#include <cuda_gl_interop.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#define BLK_PER_SECTION 4096u
#define VERTS_PER_QUAD  6u

struct FaceDef { int facing; int dx; int dy; int dz; };
static const FaceDef FACES[6] = {
    {3, -1,  0,  0},
    {0,  1,  0,  0},
    {4,  0, -1,  0},
    {1,  0,  1,  0},
    {5,  0,  0, -1},
    {2,  0,  0,  1},
};

#define MODEL_ORIGIN 8.0f
#define MODEL_RANGE  32.0f
#define POS_MAX      (1 << 20)

__device__ __forceinline__ int blk_idx(int x, int y, int z) {
    return x | (z << 4) | (y << 8);
}

__device__ __forceinline__ int face_visible(const uint8_t* s, int x, int y, int z,
                                             int dx, int dy, int dz) {
    int nx = x + dx, ny = y + dy, nz = z + dz;
    if (nx < 0 || ny < 0 || nz < 0 || nx >= 16 || ny >= 16 || nz >= 16) return 1;
    return s[blk_idx(nx, ny, nz)] != 1;
}

__device__ __forceinline__ int quantize_pos(float v) {
    float n = (MODEL_ORIGIN + v) / MODEL_RANGE;
    return ((int)(n * (float)POS_MAX)) & 0xFFFFF;
}

__device__ __forceinline__ unsigned int pack_hi(int x, int y, int z) {
    return ((unsigned int)((x >> 10) & 0x3FF))
         | (((unsigned int)((y >> 10) & 0x3FF)) << 10)
         | (((unsigned int)((z >> 10) & 0x3FF)) << 20);
}

__device__ __forceinline__ unsigned int pack_lo(int x, int y, int z) {
    return ((unsigned int)(x & 0x3FF))
         | (((unsigned int)(y & 0x3FF)) << 10)
         | (((unsigned int)(z & 0x3FF)) << 20);
}

__device__ __forceinline__ unsigned int ao_color(unsigned int base, int ao) {
    float s = (ao <= 0) ? 0.40f : (ao == 1) ? 0.60f : (ao == 2) ? 0.80f : 1.00f;
    unsigned int r = (unsigned int)((float)((base >> 16) & 0xFF) * s);
    unsigned int g = (unsigned int)((float)((base >>  8) & 0xFF) * s);
    unsigned int b = (unsigned int)((float)((base      ) & 0xFF) * s);
    return 0xFF000000u | (r << 16) | (g << 8) | b;
}

__device__ __forceinline__ unsigned int enc_light(unsigned char sky, unsigned char blk) {
    if (sky <  8) sky =  8; if (sky > 248) sky = 248;
    if (blk <  8) blk =  8; if (blk > 248) blk = 248;
    return (unsigned int)blk | ((unsigned int)sky << 8);
}

__device__ __forceinline__ void write_vert(
    unsigned int* out,
    float x, float y, float z,
    unsigned int color, unsigned int light,
    unsigned int mat
) {
    int px = quantize_pos(x), py = quantize_pos(y), pz = quantize_pos(z);
    out[0] = pack_hi(px, py, pz);
    out[1] = pack_lo(px, py, pz);
    out[2] = color;
    out[3] = 0;
    out[4] = (light & 0xFFFF) | ((mat & 0xFF) << 16) | ((0u & 0xFF) << 24);
}

__global__ void kernel_count_quads(
    const uint8_t* __restrict__ block_states,
    uint32_t*      out_counts,
    int            section_count
) {
    int sec = blockIdx.z;
    if (sec >= section_count) return;
    int lx = threadIdx.x, lz = threadIdx.y;
    int tid = lz * 16 + lx;

    const uint8_t* s = block_states + (size_t)sec * BLK_PER_SECTION;

    __shared__ uint32_t sm_counts[256];
    sm_counts[tid] = 0;
    __syncthreads();

    for (int ly = 0; ly < 16; ly++) {
        if (s[blk_idx(lx, ly, lz)] != 1) continue;
        for (int f = 0; f < 6; f++) {
            if (face_visible(s, lx, ly, lz, FACES[f].dx, FACES[f].dy, FACES[f].dz))
                sm_counts[tid]++;
        }
    }
    __syncthreads();

    for (int off = 128; off > 0; off >>= 1) {
        if (tid < off) sm_counts[tid] += sm_counts[tid + off];
        __syncthreads();
    }

    if (tid == 0) {
        out_counts[sec] = sm_counts[0] * VERTS_PER_QUAD;
    }
}

__device__ __forceinline__ void get_tri_idx(int tri[6]) {
    tri[0] = 0; tri[1] = 1; tri[2] = 2;
    tri[3] = 2; tri[4] = 3; tri[5] = 0;
}

__global__ void kernel_mesh_to_vbo(
    const uint8_t* __restrict__ block_states,
    unsigned int*  vbo_ptr,
    const uint32_t* __restrict__ vert_offsets,
    int            stride_u32,
    int            section_count
) {
    int sec = blockIdx.z;
    if (sec >= section_count) return;
    int lx = threadIdx.x, lz = threadIdx.y;
    int tid = lz * 16 + lx;

    const uint8_t* s = block_states + (size_t)sec * BLK_PER_SECTION;

    int my_quads = 0;
    for (int ly = 0; ly < 16; ly++) {
        if (s[blk_idx(lx, ly, lz)] != 1) continue;
        for (int f = 0; f < 6; f++) {
            if (face_visible(s, lx, ly, lz, FACES[f].dx, FACES[f].dy, FACES[f].dz))
                my_quads++;
        }
    }

    __shared__ uint32_t sm_offsets[256];
    sm_offsets[tid] = (uint32_t)my_quads;
    __syncthreads();

    int p = 1;
    while (p < 256) {
        int v = (tid >= p) ? (int)sm_offsets[tid - p] : 0;
        __syncthreads();
        sm_offsets[tid] += v;
        __syncthreads();
        p <<= 1;
    }
    uint32_t my_quad_start = sm_offsets[tid] - (uint32_t)my_quads;
    __syncthreads();

    uint32_t base_vtx = vert_offsets[sec];
    unsigned int* sec_vbo = vbo_ptr + (size_t)base_vtx * (size_t)stride_u32;

    uint32_t quad_idx = my_quad_start;
    int tri_idx[6];
    get_tri_idx(tri_idx);

    for (int ly = 0; ly < 16; ly++) {
        if (s[blk_idx(lx, ly, lz)] != 1) continue;
        float x0 = (float)lx, y0 = (float)ly, z0 = (float)lz;
        float x1 = x0 + 1.0f, y1 = y0 + 1.0f, z1 = z0 + 1.0f;

        for (int f = 0; f < 6; f++) {
            if (!face_visible(s, lx, ly, lz, FACES[f].dx, FACES[f].dy, FACES[f].dz))
                continue;

            int dx = FACES[f].dx, dy = FACES[f].dy, dz = FACES[f].dz;

            int ao = 0;
            {
                int ox[4] = {dx, dx,  0,  0};
                int oy[4] = {dy,  0, dy,  0};
                int oz[4] = {dz,  0,  0, dz};
                for (int a = 0; a < 4; a++) {
                    int sx_ = lx + ox[a], sy_ = ly + oy[a], sz_ = lz + oz[a];
                    if (sx_ >= 0 && sy_ >= 0 && sz_ >= 0 && sx_ < 16 && sy_ < 16 && sz_ < 16
                        && s[blk_idx(sx_, sy_, sz_)] == 1) ao++;
                }
                if (ao > 3) ao = 3;
            }

            unsigned int color = ao_color(0xFFC0C0C0u, ao);
            unsigned int light = enc_light(240, 0);

            float qx[4], qy[4], qz[4];
            switch (f) {
                case 0:
                    qx[0]=x0;qx[1]=x0;qx[2]=x0;qx[3]=x0;
                    qy[0]=y0;qy[1]=y1;qy[2]=y1;qy[3]=y0;
                    qz[0]=z1;qz[1]=z1;qz[2]=z0;qz[3]=z0;
                    break;
                case 1:
                    qx[0]=x1;qx[1]=x1;qx[2]=x1;qx[3]=x1;
                    qy[0]=y0;qy[1]=y1;qy[2]=y1;qy[3]=y0;
                    qz[0]=z0;qz[1]=z0;qz[2]=z1;qz[3]=z1;
                    break;
                case 2:
                    qx[0]=x0;qx[1]=x1;qx[2]=x1;qx[3]=x0;
                    qy[0]=y0;qy[1]=y0;qy[2]=y0;qy[3]=y0;
                    qz[0]=z0;qz[1]=z0;qz[2]=z1;qz[3]=z1;
                    break;
                case 3:
                    qx[0]=x0;qx[1]=x1;qx[2]=x1;qx[3]=x0;
                    qy[0]=y1;qy[1]=y1;qy[2]=y1;qy[3]=y1;
                    qz[0]=z1;qz[1]=z1;qz[2]=z0;qz[3]=z0;
                    break;
                case 4:
                    qx[0]=x0;qx[1]=x0;qx[2]=x1;qx[3]=x1;
                    qy[0]=y0;qy[1]=y1;qy[2]=y1;qy[3]=y0;
                    qz[0]=z0;qz[1]=z0;qz[2]=z0;qz[3]=z0;
                    break;
                default:
                    qx[0]=x1;qx[1]=x1;qx[2]=x0;qx[3]=x0;
                    qy[0]=y0;qy[1]=y1;qy[2]=y1;qy[3]=y0;
                    qz[0]=z1;qz[1]=z1;qz[2]=z1;qz[3]=z1;
                    break;
            }

            unsigned int* vp = sec_vbo + (size_t)(quad_idx * VERTS_PER_QUAD) * (size_t)stride_u32;
            for (int t = 0; t < 6; t++) {
                write_vert(
                    vp + (size_t)t * stride_u32,
                    qx[tri_idx[t]], qy[tri_idx[t]], qz[tri_idx[t]],
                    color, light, 0u
                );
            }
            quad_idx++;
        }
    }
}

static int ck(cudaError_t e) { return e == cudaSuccess ? 0 : -10; }

CHUNKUP_API uint64_t chunkup_cuda_upload_block_states(
    const uint8_t* host_data, uint32_t total_bytes
) {
    if (!host_data || total_bytes == 0) return 0;
    uint8_t* d_buf = NULL;
    if (ck(cudaMalloc(&d_buf, (size_t)total_bytes)) != 0) return 0;
    if (ck(cudaMemcpy(d_buf, host_data, (size_t)total_bytes, cudaMemcpyHostToDevice)) != 0) {
        cudaFree(d_buf); return 0;
    }
    CHUNKUP_SL_INFO_COMPLETE(
        "Chunkup-GPU-Direct-Render", "VRAM block_states uploaded",
        NULL
    );
    return (uint64_t)d_buf;
}

CHUNKUP_API void chunkup_cuda_free_block_states(uint64_t device_ptr) {
    if (device_ptr) cudaFree((void*)device_ptr);
}

CHUNKUP_API int chunkup_gpu_mesh_count_only(
    const uint8_t* block_states_data,
    int32_t        device_block_states,
    uint32_t       section_count,
    uint32_t*      out_vertex_counts
) {
    if (!block_states_data || !out_vertex_counts || section_count == 0) return -1;

    const uint8_t* d_blocks;
    int owns = 0;
    if (device_block_states) {
        d_blocks = block_states_data;
    } else {
        size_t bs = (size_t)section_count * BLK_PER_SECTION;
        uint8_t* tmp = NULL;
        if (ck(cudaMalloc(&tmp, bs)) != 0) return -10;
        if (ck(cudaMemcpy(tmp, block_states_data, bs, cudaMemcpyHostToDevice)) != 0) {
            cudaFree(tmp); return -10;
        }
        d_blocks = tmp; owns = 1;
    }

    uint32_t* d_counts = NULL;
    if (ck(cudaMalloc(&d_counts, section_count * sizeof(uint32_t))) != 0) {
        if (owns) cudaFree((void*)d_blocks);
        return -10;
    }

    dim3 block(16, 16, 1);
    dim3 grid(1, 1, section_count);
    kernel_count_quads<<<grid, block>>>(d_blocks, d_counts, (int)section_count);

    if (ck(cudaGetLastError()) != 0 || ck(cudaDeviceSynchronize()) != 0) {
        cudaFree(d_counts);
        if (owns) cudaFree((void*)d_blocks);
        return -10;
    }

    cudaMemcpy(out_vertex_counts, d_counts, section_count * sizeof(uint32_t), cudaMemcpyDeviceToHost);
    cudaFree(d_counts);
    if (owns) cudaFree((void*)d_blocks);
    return 0;
}

#define MAX_INTEROP 128
typedef struct { cudaGraphicsResource_t res; uint32_t gl_buf; int in_use; } InteropEntry;
static InteropEntry g_interop[MAX_INTEROP];

CHUNKUP_API int32_t chunkup_cuda_gl_register(uint32_t gl_vbo_id) {
    for (int i = 0; i < MAX_INTEROP; i++)
        if (g_interop[i].in_use && g_interop[i].gl_buf == gl_vbo_id) return 1;
    InteropEntry* e = NULL;
    for (int i = 0; i < MAX_INTEROP; i++)
        if (!g_interop[i].in_use) { e = &g_interop[i]; break; }
    if (!e) return 0;
    cudaError_t err = cudaGraphicsGLRegisterBuffer(
        &e->res, gl_vbo_id, cudaGraphicsMapFlagsWriteDiscard
    );
    if (err != cudaSuccess || !e->res) return 0;
    e->gl_buf = gl_vbo_id; e->in_use = 1;
    CHUNKUP_SL_INFO_COMPLETE(
        "Chunkup-GPU-Direct-Render", "GL buffer registered for CUDA interop",
        NULL
    );
    return 1;
}

CHUNKUP_API void chunkup_cuda_gl_unregister(uint32_t gl_vbo_id) {
    for (int i = 0; i < MAX_INTEROP; i++) {
        if (g_interop[i].in_use && g_interop[i].gl_buf == gl_vbo_id) {
            cudaGraphicsUnregisterResource(g_interop[i].res);
            g_interop[i].in_use = 0; g_interop[i].res = NULL;
            return;
        }
    }
}

static cudaGraphicsResource_t find_resource(uint32_t gl_vbo_id) {
    for (int i = 0; i < MAX_INTEROP; i++)
        if (g_interop[i].in_use && g_interop[i].gl_buf == gl_vbo_id) return g_interop[i].res;
    return NULL;
}

CHUNKUP_API int32_t chunkup_interop_is_available(void) {
    int count = 0;
    return (cudaGetDeviceCount(&count) == cudaSuccess && count > 0) ? 1 : 0;
}

CHUNKUP_API int chunkup_gpu_mesh_to_vbo(
    const uint8_t*   block_states_data,
    int32_t          device_block_states,
    uint32_t         section_count,
    uint32_t         vertex_stride,
    uint32_t         gl_vbo_id,
    const uint32_t*  vertex_offset_table,
    uint32_t*        out_draw_command_buffer
) {
    if (!block_states_data || !vertex_offset_table || !out_draw_command_buffer || section_count == 0)
        return -1;

    cudaGraphicsResource_t vbo_res = find_resource(gl_vbo_id);
    if (!vbo_res) return -2;

    if (ck(cudaGraphicsMapResources(1, &vbo_res, 0)) != 0) return -10;

    void* d_vbo = NULL;
    size_t vbo_size = 0;
    if (ck(cudaGraphicsResourceGetMappedPointer(&d_vbo, &vbo_size, vbo_res)) != 0) {
        cudaGraphicsUnmapResources(1, &vbo_res, 0);
        return -10;
    }

    const uint8_t* d_blocks;
    int owns = 0;
    if (device_block_states) {
        d_blocks = block_states_data;
    } else {
        size_t bs = (size_t)section_count * BLK_PER_SECTION;
        uint8_t* tmp = NULL;
        if (ck(cudaMalloc(&tmp, bs)) != 0) {
            cudaGraphicsUnmapResources(1, &vbo_res, 0);
            return -10;
        }
        if (ck(cudaMemcpy(tmp, block_states_data, bs, cudaMemcpyHostToDevice)) != 0) {
            cudaFree(tmp);
            cudaGraphicsUnmapResources(1, &vbo_res, 0);
            return -10;
        }
        d_blocks = tmp; owns = 1;
    }

    uint32_t* d_offsets = NULL;
    size_t off_bytes = (size_t)(section_count + 1) * sizeof(uint32_t);
    if (ck(cudaMalloc(&d_offsets, off_bytes)) != 0) {
        if (owns) cudaFree((void*)d_blocks);
        cudaGraphicsUnmapResources(1, &vbo_res, 0);
        return -10;
    }
    if (ck(cudaMemcpy(d_offsets, vertex_offset_table, off_bytes, cudaMemcpyHostToDevice)) != 0) {
        cudaFree(d_offsets);
        if (owns) cudaFree((void*)d_blocks);
        cudaGraphicsUnmapResources(1, &vbo_res, 0);
        return -10;
    }

    int stride_u32 = (int)(vertex_stride / sizeof(uint32_t));
    dim3 block(16, 16, 1);
    dim3 grid(1, 1, section_count);
    kernel_mesh_to_vbo<<<grid, block>>>(
        d_blocks, (unsigned int*)d_vbo, d_offsets, stride_u32, (int)section_count
    );

    cudaError_t kerr = cudaGetLastError();
    cudaError_t serr = cudaDeviceSynchronize();

    if (owns) cudaFree((void*)d_blocks);
    cudaFree(d_offsets);

    if (ck(cudaGraphicsUnmapResources(1, &vbo_res, 0)) != 0) return -10;
    if (kerr != cudaSuccess || serr != cudaSuccess) return -10;

    for (uint32_t i = 0; i < section_count; i++) {
        uint32_t vstart = vertex_offset_table[i];
        uint32_t vend   = vertex_offset_table[i + 1];
        out_draw_command_buffer[i * 4 + 0] = vend - vstart;
        out_draw_command_buffer[i * 4 + 1] = 1;
        out_draw_command_buffer[i * 4 + 2] = vstart;
        out_draw_command_buffer[i * 4 + 3] = i;
    }

    CHUNKUP_SL_INFO_COMPLETE(
        "Chunkup-GPU-Direct-Render", "Phase B mesh",
        NULL
    );
    return 0;
}
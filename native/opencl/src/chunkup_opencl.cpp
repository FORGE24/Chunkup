#include "chunkup_opencl.h"

#include "../common/chunkup_batch.h"
#include "../common/chunkup_kernel.h"
#include "../common/chunkup_kernel_algo.h"
#include "../common/chunkup_noise_bundle.h"
#include "../common/chunkup_noise_state.h"
#include "../common/chunkup_sl_log.h"

#include "../common/chunkup_sl_log.h"

#include <CL/cl.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct OpenClState {
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    cl_kernel density_fill_kernel = nullptr;
    cl_kernel density_fill_batch_kernel = nullptr;
    cl_kernel skylight_kernel = nullptr;
    cl_kernel face_cull_kernel = nullptr;
    cl_kernel skylight_batch_kernel = nullptr;
    cl_kernel face_cull_batch_kernel = nullptr;
    bool initialized = false;
};

OpenClState g_state;

std::string read_text_file(const char* path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        return {};
    }
    std::ostringstream ss;
    ss << file.rdbuf();
    return ss.str();
}

std::string read_kernel_source() {
#ifdef CHUNKUP_OPENCL_ROUTER_PATH
    const std::string router = read_text_file(CHUNKUP_OPENCL_ROUTER_PATH);
#else
    const std::string router = read_text_file("native/opencl/kernels/chunkup_router_codegen.clh");
#endif

#ifdef CHUNKUP_OPENCL_KERNEL_PATH
    const std::string kernel = read_text_file(CHUNKUP_OPENCL_KERNEL_PATH);
#else
    const std::string kernel = read_text_file("native/opencl/kernels/chunkup_kernel.cl");
#endif

    if (router.empty() || kernel.empty()) {
        return {};
    }
    return router + "\n" + kernel;
}

bool create_kernel(cl_kernel* out, const char* name) {
    cl_int err = CL_SUCCESS;
    *out = clCreateKernel(g_state.program, name, &err);
    return err == CL_SUCCESS && *out != nullptr;
}

bool ensure_opencl() {
    if (g_state.initialized) {
        return true;
    }

    cl_platform_id platform = nullptr;
    cl_device_id device = nullptr;
    if (clGetPlatformIDs(1, &platform, nullptr) != CL_SUCCESS) {
        return false;
    }
    if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, nullptr) != CL_SUCCESS) {
        return false;
    }

    cl_int err = CL_SUCCESS;
    g_state.context = clCreateContext(nullptr, 1, &device, nullptr, nullptr, &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.queue = clCreateCommandQueue(g_state.context, device, 0, &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    const std::string source = read_kernel_source();
    if (source.empty()) {
        return false;
    }

    const char* src_ptr = source.c_str();
    const size_t src_len = source.size();
    g_state.program = clCreateProgramWithSource(g_state.context, 1, &src_ptr, &src_len, &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    err = clBuildProgram(g_state.program, 1, &device, "-cl-fast-relaxed-math", nullptr, nullptr);
    if (err != CL_SUCCESS) {
        size_t log_len = 0;
        clGetProgramBuildInfo(g_state.program, device, CL_PROGRAM_BUILD_LOG, 0, nullptr, &log_len);
        if (log_len > 1) {
            std::vector<char> log(log_len);
            clGetProgramBuildInfo(
                g_state.program,
                device,
                CL_PROGRAM_BUILD_LOG,
                log_len,
                log.data(),
                nullptr);
            fprintf(stderr, "chunkup_opencl: kernel build failed:\n%s\n", log.data());
        }
        return false;
    }

    if (!create_kernel(&g_state.density_fill_kernel, "chunkup_kernel_density_fill") ||
        !create_kernel(&g_state.density_fill_batch_kernel, "chunkup_kernel_density_fill_batch") ||
        !create_kernel(&g_state.skylight_kernel, "chunkup_kernel_skylight") ||
        !create_kernel(&g_state.face_cull_kernel, "chunkup_kernel_face_cull") ||
        !create_kernel(&g_state.skylight_batch_kernel, "chunkup_kernel_skylight_batch") ||
        !create_kernel(&g_state.face_cull_batch_kernel, "chunkup_kernel_face_cull_batch")) {
        return false;
    }

    g_state.initialized = true;
    return true;
}

#define CHUNKUP_OPENCL_Y_TILE 4

cl_mem create_bundle_buffer(uint32_t seed, cl_int* err_out) {
    chunkup_noise_prepare(seed);
    return clCreateBuffer(
        g_state.context,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        sizeof(ChunkupNoiseBundle),
        &chunkup_active_bundle,
        err_out
    );
}

int launch_density_fill_gpu(
    int base_x,
    int base_z,
    int min_y,
    int height,
    uint32_t seed,
    uint32_t stride_y,
    cl_mem density_buf,
    cl_mem fluid_buf
) {
    cl_int err = CL_SUCCESS;
    cl_mem bundle_buf = create_bundle_buffer(seed, &err);
    if (err != CL_SUCCESS) {
        return -10;
    }

    clSetKernelArg(g_state.density_fill_kernel, 0, sizeof(cl_mem), &density_buf);
    clSetKernelArg(g_state.density_fill_kernel, 1, sizeof(cl_mem), &fluid_buf);
    clSetKernelArg(g_state.density_fill_kernel, 2, sizeof(cl_mem), &bundle_buf);
    clSetKernelArg(g_state.density_fill_kernel, 3, sizeof(int), &base_x);
    clSetKernelArg(g_state.density_fill_kernel, 4, sizeof(int), &base_z);
    clSetKernelArg(g_state.density_fill_kernel, 5, sizeof(int), &min_y);
    clSetKernelArg(g_state.density_fill_kernel, 6, sizeof(int), &height);
    clSetKernelArg(g_state.density_fill_kernel, 7, sizeof(uint32_t), &stride_y);

    const size_t local[3] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE, CHUNKUP_OPENCL_Y_TILE};
    const unsigned int z_slices =
        (unsigned int)((height + CHUNKUP_OPENCL_Y_TILE - 1) / CHUNKUP_OPENCL_Y_TILE);
    const size_t global[3] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE, z_slices};
    err = clEnqueueNDRangeKernel(
        g_state.queue,
        g_state.density_fill_kernel,
        3,
        nullptr,
        global,
        local,
        0,
        nullptr,
        nullptr
    );
    clReleaseMemObject(bundle_buf);
    return err == CL_SUCCESS ? 0 : -10;
}

bool ensure_density_buffer(
    cl_mem* density_buf,
    ChunkupKernelBuffers* buffers,
    size_t density_bytes,
    bool upload_from_host
) {
    if (*density_buf != nullptr) {
        return true;
    }

    cl_int err = CL_SUCCESS;
    if (upload_from_host) {
        *density_buf = clCreateBuffer(g_state.context, CL_MEM_READ_ONLY, density_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            return false;
        }
        err = clEnqueueWriteBuffer(
            g_state.queue,
            *density_buf,
            CL_TRUE,
            0,
            density_bytes,
            buffers->density,
            0,
            nullptr,
            nullptr
        );
        return err == CL_SUCCESS;
    }

    *density_buf = clCreateBuffer(
        g_state.context,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        density_bytes,
        buffers->density,
        &err
    );
    return err == CL_SUCCESS;
}

}  // namespace

extern "C" CHUNKUP_API int chunkup_opencl_is_available(void) {
    return ensure_opencl() ? 1 : 0;
}

extern "C" CHUNKUP_API int chunkup_opencl_kernel_dispatch(
    const ChunkupKernelJob* job,
    ChunkupKernelBuffers* buffers,
    ChunkupKernelResult* result
) {
    if (!ensure_opencl() || !job || !buffers || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const int base_x = job->chunk_x * (int)CHUNKUP_CHUNK_SIZE;
    const int base_z = job->chunk_z * (int)CHUNKUP_CHUNK_SIZE;
    const size_t density_bytes = chunkup_kernel_density_bytes((uint32_t)job->height);
    const size_t light_bytes = chunkup_kernel_light_bytes((uint32_t)job->height);

    cl_mem density_buf = nullptr;
    cl_mem fluid_buf = nullptr;
    cl_int err = CL_SUCCESS;
    bool upload_density_from_host = false;

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            return -2;
        }

        density_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, density_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            return -10;
        }

        if (buffers->fluid) {
            fluid_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);
            if (err != CL_SUCCESS) {
                clReleaseMemObject(density_buf);
                return -10;
            }
        } else {
            fluid_buf = nullptr;
        }

        const int fill_code = launch_density_fill_gpu(
            base_x,
            base_z,
            job->min_y,
            job->height,
            job->seed,
            buffers->stride_y,
            density_buf,
            fluid_buf
        );
        if (fill_code != 0) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            clReleaseMemObject(density_buf);
            return fill_code;
        }

        clEnqueueReadBuffer(
            g_state.queue,
            density_buf,
            CL_TRUE,
            0,
            density_bytes,
            buffers->density,
            0,
            nullptr,
            nullptr
        );
        if (fluid_buf && buffers->fluid) {
            clEnqueueReadBuffer(
                g_state.queue,
                fluid_buf,
                CL_TRUE,
                0,
                light_bytes,
                buffers->fluid,
                0,
                nullptr,
                nullptr
            );
        }

        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -3;
        }

        if (!density_buf) {
            upload_density_from_host = true;
            if (!ensure_density_buffer(&density_buf, buffers, density_bytes, true)) {
                return -20;
            }
        }

        cl_mem light_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -21;
        }

        clSetKernelArg(g_state.skylight_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.skylight_kernel, 1, sizeof(cl_mem), &light_buf);
        clSetKernelArg(g_state.skylight_kernel, 2, sizeof(int), &job->height);
        clSetKernelArg(g_state.skylight_kernel, 3, sizeof(uint32_t), &buffers->stride_y);

        const size_t global[2] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE};
        err = clEnqueueNDRangeKernel(
            g_state.queue,
            g_state.skylight_kernel,
            2,
            nullptr,
            global,
            nullptr,
            0,
            nullptr,
            nullptr
        );
        clEnqueueReadBuffer(g_state.queue, light_buf, CL_TRUE, 0, light_bytes, buffers->skylight, 0, nullptr, nullptr);
        clReleaseMemObject(light_buf);

        if (err != CL_SUCCESS) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -22;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {
        if (!buffers->blocklight) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -4;
        }
        std::memset(buffers->blocklight, 0, light_bytes);
        result->ops_completed |= CHUNKUP_OP_BLOCKLIGHT;
    }

    if (job->op_mask & CHUNKUP_OP_FACE_CULL) {
        if (!buffers->density || !buffers->face_mask) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -5;
        }

        if (!density_buf) {
            if (!ensure_density_buffer(&density_buf, buffers, density_bytes, upload_density_from_host)) {
                return -20;
            }
        }

        cl_mem face_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -23;
        }

        clSetKernelArg(g_state.face_cull_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.face_cull_kernel, 1, sizeof(cl_mem), &face_buf);
        clSetKernelArg(g_state.face_cull_kernel, 2, sizeof(int), &job->height);
        clSetKernelArg(g_state.face_cull_kernel, 3, sizeof(uint32_t), &buffers->stride_y);

        const size_t global[3] = {
            CHUNKUP_CHUNK_SIZE,
            (size_t)job->height,
            CHUNKUP_CHUNK_SIZE,
        };
        err = clEnqueueNDRangeKernel(
            g_state.queue,
            g_state.face_cull_kernel,
            3,
            nullptr,
            global,
            nullptr,
            0,
            nullptr,
            nullptr
        );
        clEnqueueReadBuffer(g_state.queue, face_buf, CL_TRUE, 0, light_bytes, buffers->face_mask, 0, nullptr, nullptr);
        clReleaseMemObject(face_buf);

        if (err != CL_SUCCESS) {
            if (fluid_buf) {
                clReleaseMemObject(fluid_buf);
            }
            if (density_buf) {
                clReleaseMemObject(density_buf);
            }
            return -24;
        }
        result->ops_completed |= CHUNKUP_OP_FACE_CULL;
    }

    if (fluid_buf) {
        clReleaseMemObject(fluid_buf);
    }
    if (density_buf) {
        clReleaseMemObject(density_buf);
    }

    return 0;
}

extern "C" CHUNKUP_API int chunkup_opencl_kernel_dispatch_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const float* host_density,
    uint8_t* host_skylight,
    uint8_t* host_face_mask,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!ensure_opencl() || !template_job || batch_count <= 0 || !host_density || !result || blocks_per_chunk == 0u) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const size_t density_bytes = (size_t)batch_count * blocks_per_chunk * sizeof(float);
    const size_t light_bytes = (size_t)batch_count * blocks_per_chunk;
    cl_int err = CL_SUCCESS;

    cl_mem density_buf = clCreateBuffer(
        g_state.context,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        density_bytes,
        const_cast<float*>(host_density),
        &err
    );
    if (err != CL_SUCCESS) {
        return -20;
    }

    if (template_job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!host_skylight) {
            clReleaseMemObject(density_buf);
            return -3;
        }

        cl_mem light_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            clReleaseMemObject(density_buf);
            return -21;
        }

        clSetKernelArg(g_state.skylight_batch_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.skylight_batch_kernel, 1, sizeof(cl_mem), &light_buf);
        clSetKernelArg(g_state.skylight_batch_kernel, 2, sizeof(int), &template_job->height);
        uint32_t stride_y = CHUNKUP_BLOCKS_PER_SECTION;
        clSetKernelArg(g_state.skylight_batch_kernel, 3, sizeof(uint32_t), &stride_y);
        clSetKernelArg(g_state.skylight_batch_kernel, 4, sizeof(uint32_t), &blocks_per_chunk);
        clSetKernelArg(g_state.skylight_batch_kernel, 5, sizeof(int), &batch_count);

        const size_t global[3] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE, (size_t)batch_count};
        err = clEnqueueNDRangeKernel(
            g_state.queue,
            g_state.skylight_batch_kernel,
            3,
            nullptr,
            global,
            nullptr,
            0,
            nullptr,
            nullptr
        );
        clEnqueueReadBuffer(g_state.queue, light_buf, CL_TRUE, 0, light_bytes, host_skylight, 0, nullptr, nullptr);
        clReleaseMemObject(light_buf);

        if (err != CL_SUCCESS) {
            clReleaseMemObject(density_buf);
            return -22;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (template_job->op_mask & CHUNKUP_OP_FACE_CULL) {
        if (!host_face_mask) {
            clReleaseMemObject(density_buf);
            return -5;
        }

        cl_mem face_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            clReleaseMemObject(density_buf);
            return -23;
        }

        clSetKernelArg(g_state.face_cull_batch_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.face_cull_batch_kernel, 1, sizeof(cl_mem), &face_buf);
        clSetKernelArg(g_state.face_cull_batch_kernel, 2, sizeof(int), &template_job->height);
        uint32_t stride_y = CHUNKUP_BLOCKS_PER_SECTION;
        clSetKernelArg(g_state.face_cull_batch_kernel, 3, sizeof(uint32_t), &stride_y);
        clSetKernelArg(g_state.face_cull_batch_kernel, 4, sizeof(uint32_t), &blocks_per_chunk);
        clSetKernelArg(g_state.face_cull_batch_kernel, 5, sizeof(int), &batch_count);

        const size_t global[3] = {
            CHUNKUP_CHUNK_SIZE,
            CHUNKUP_CHUNK_SIZE,
            (size_t)batch_count * (size_t)template_job->height,
        };
        err = clEnqueueNDRangeKernel(
            g_state.queue,
            g_state.face_cull_batch_kernel,
            3,
            nullptr,
            global,
            nullptr,
            0,
            nullptr,
            nullptr
        );
        clEnqueueReadBuffer(g_state.queue, face_buf, CL_TRUE, 0, light_bytes, host_face_mask, 0, nullptr, nullptr);
        clReleaseMemObject(face_buf);

        if (err != CL_SUCCESS) {
            clReleaseMemObject(density_buf);
            return -24;
        }
        result->ops_completed |= CHUNKUP_OP_FACE_CULL;
    }

    clReleaseMemObject(density_buf);
    return 0;
}

extern "C" CHUNKUP_API int chunkup_opencl_density_fill_batch(
    const ChunkupKernelJob* template_job,
    int batch_count,
    const int32_t* chunk_xs,
    const int32_t* chunk_zs,
    float* host_density,
    uint8_t* host_fluid,
    uint32_t blocks_per_chunk,
    ChunkupKernelResult* result
) {
    if (!ensure_opencl() || !template_job || batch_count <= 0 || !chunk_xs || !chunk_zs || !host_density || !result) {
        return -1;
    }

    result->status = 0;
    result->ops_completed = 0u;

    const size_t density_bytes = (size_t)batch_count * blocks_per_chunk * sizeof(float);
    const size_t fluid_bytes = (size_t)batch_count * blocks_per_chunk;
    cl_int err = CL_SUCCESS;

    cl_mem bundle_buf = create_bundle_buffer(template_job->seed, &err);
    if (err != CL_SUCCESS) {
        return chunkup_kernel_dispatch_density_batch(
            template_job,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result
        );
    }

    cl_mem density_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, density_bytes, nullptr, &err);
    if (err != CL_SUCCESS) {
        clReleaseMemObject(bundle_buf);
        return chunkup_kernel_dispatch_density_batch(
            template_job,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result
        );
    }

    cl_mem fluid_buf = nullptr;
    if (host_fluid) {
        fluid_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, fluid_bytes, nullptr, &err);
        if (err != CL_SUCCESS) {
            clReleaseMemObject(density_buf);
            clReleaseMemObject(bundle_buf);
            return chunkup_kernel_dispatch_density_batch(
                template_job,
                batch_count,
                chunk_xs,
                chunk_zs,
                host_density,
                host_fluid,
                blocks_per_chunk,
                result
            );
        }
    }

    cl_mem xs_buf = clCreateBuffer(
        g_state.context,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        (size_t)batch_count * sizeof(int32_t),
        const_cast<int32_t*>(chunk_xs),
        &err
    );
    if (err != CL_SUCCESS) {
        if (fluid_buf) {
            clReleaseMemObject(fluid_buf);
        }
        clReleaseMemObject(density_buf);
        clReleaseMemObject(bundle_buf);
        return chunkup_kernel_dispatch_density_batch(
            template_job,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result
        );
    }

    cl_mem zs_buf = clCreateBuffer(
        g_state.context,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        (size_t)batch_count * sizeof(int32_t),
        const_cast<int32_t*>(chunk_zs),
        &err
    );
    if (err != CL_SUCCESS) {
        clReleaseMemObject(xs_buf);
        if (fluid_buf) {
            clReleaseMemObject(fluid_buf);
        }
        clReleaseMemObject(density_buf);
        clReleaseMemObject(bundle_buf);
        return chunkup_kernel_dispatch_density_batch(
            template_job,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result
        );
    }

    const int min_y = template_job->min_y;
    const int height = template_job->height;
    uint32_t stride_y = CHUNKUP_BLOCKS_PER_SECTION;
    const int z_slices = (height + CHUNKUP_OPENCL_Y_TILE - 1) / CHUNKUP_OPENCL_Y_TILE;

    char params[192];
    snprintf(
        params,
        sizeof(params),
        "BatchCount=%d,Height=%d,ZSlices=%d,GridZ=%d",
        batch_count,
        height,
        z_slices,
        z_slices * batch_count
    );
    CHUNKUP_SL_INFO_START("OpenCL Density Batch Module", "Launching OpenCL density fill batch kernel", params);

    clSetKernelArg(g_state.density_fill_batch_kernel, 0, sizeof(cl_mem), &density_buf);
    clSetKernelArg(g_state.density_fill_batch_kernel, 1, sizeof(cl_mem), &fluid_buf);
    clSetKernelArg(g_state.density_fill_batch_kernel, 2, sizeof(cl_mem), &bundle_buf);
    clSetKernelArg(g_state.density_fill_batch_kernel, 3, sizeof(cl_mem), &xs_buf);
    clSetKernelArg(g_state.density_fill_batch_kernel, 4, sizeof(cl_mem), &zs_buf);
    clSetKernelArg(g_state.density_fill_batch_kernel, 5, sizeof(int), &min_y);
    clSetKernelArg(g_state.density_fill_batch_kernel, 6, sizeof(int), &height);
    clSetKernelArg(g_state.density_fill_batch_kernel, 7, sizeof(uint32_t), &stride_y);
    clSetKernelArg(g_state.density_fill_batch_kernel, 8, sizeof(uint32_t), &blocks_per_chunk);
    clSetKernelArg(g_state.density_fill_batch_kernel, 9, sizeof(int), &batch_count);

    const size_t local[3] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE, CHUNKUP_OPENCL_Y_TILE};
    const size_t global[3] = {
        CHUNKUP_CHUNK_SIZE,
        CHUNKUP_CHUNK_SIZE,
        (size_t)z_slices * (size_t)batch_count,
    };
    err = clEnqueueNDRangeKernel(
        g_state.queue,
        g_state.density_fill_batch_kernel,
        3,
        nullptr,
        global,
        local,
        0,
        nullptr,
        nullptr
    );

    if (err == CL_SUCCESS) {
        CHUNKUP_SL_INFO_COMPLETE(
            "OpenCL Density Batch Module",
            "OpenCL density fill batch kernel finished",
            params
        );
        clEnqueueReadBuffer(
            g_state.queue,
            density_buf,
            CL_TRUE,
            0,
            density_bytes,
            host_density,
            0,
            nullptr,
            nullptr
        );
        if (fluid_buf && host_fluid) {
            clEnqueueReadBuffer(
                g_state.queue,
                fluid_buf,
                CL_TRUE,
                0,
                fluid_bytes,
                host_fluid,
                0,
                nullptr,
                nullptr
            );
        }
        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    clReleaseMemObject(zs_buf);
    clReleaseMemObject(xs_buf);
    if (fluid_buf) {
        clReleaseMemObject(fluid_buf);
    }
    clReleaseMemObject(density_buf);
    clReleaseMemObject(bundle_buf);

    if (err != CL_SUCCESS) {
        return chunkup_kernel_dispatch_density_batch(
            template_job,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result
        );
    }

    return 0;
}

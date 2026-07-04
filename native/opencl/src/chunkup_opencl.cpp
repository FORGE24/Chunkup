#include "chunkup_opencl.h"

#include "../common/chunkup_kernel.h"

#include <CL/cl.h>

#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct OpenClState {
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    cl_kernel noise_kernel = nullptr;
    cl_kernel skylight_kernel = nullptr;
    bool initialized = false;
};

OpenClState g_state;

std::string read_kernel_source() {
#ifdef CHUNKUP_OPENCL_KERNEL_PATH
    std::ifstream file(CHUNKUP_OPENCL_KERNEL_PATH);
#else
    std::ifstream file("native/opencl/kernels/chunkup_kernel.cl");
#endif
    if (!file.is_open()) {
        return {};
    }
    std::ostringstream ss;
    ss << file.rdbuf();
    return ss.str();
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
        return false;
    }

    g_state.noise_kernel = clCreateKernel(g_state.program, "chunkup_kernel_noise_fill", &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.skylight_kernel = clCreateKernel(g_state.program, "chunkup_kernel_skylight", &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.initialized = true;
    return true;
}

}  // namespace

extern "C" int chunkup_opencl_is_available(void) {
    return ensure_opencl() ? 1 : 0;
}

extern "C" int chunkup_opencl_kernel_dispatch(
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

    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {
        if (!buffers->density) {
            return -2;
        }

        cl_int err = CL_SUCCESS;
        const size_t density_bytes = chunkup_kernel_density_bytes((uint32_t)job->height);
        cl_mem density_buf = clCreateBuffer(
            g_state.context,
            CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR,
            density_bytes,
            buffers->density,
            &err
        );
        if (err != CL_SUCCESS) {
            return -20;
        }

        clSetKernelArg(g_state.noise_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.noise_kernel, 1, sizeof(int), &base_x);
        clSetKernelArg(g_state.noise_kernel, 2, sizeof(int), &base_z);
        clSetKernelArg(g_state.noise_kernel, 3, sizeof(int), &job->min_y);
        clSetKernelArg(g_state.noise_kernel, 4, sizeof(int), &job->height);
        clSetKernelArg(g_state.noise_kernel, 5, sizeof(uint32_t), &job->seed);
        clSetKernelArg(g_state.noise_kernel, 6, sizeof(uint32_t), &buffers->stride_y);

        const size_t global[3] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE, (size_t)job->height};
        err = clEnqueueNDRangeKernel(g_state.queue, g_state.noise_kernel, 3, nullptr, global, nullptr, 0, nullptr, nullptr);
        clEnqueueReadBuffer(g_state.queue, density_buf, CL_TRUE, 0, density_bytes, buffers->density, 0, nullptr, nullptr);
        clReleaseMemObject(density_buf);

        if (err != CL_SUCCESS) {
            return -21;
        }
        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;
    }

    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {
        if (!buffers->density || !buffers->skylight) {
            return -3;
        }

        cl_int err = CL_SUCCESS;
        const size_t density_bytes = chunkup_kernel_density_bytes((uint32_t)job->height);
        const size_t light_bytes = chunkup_kernel_light_bytes((uint32_t)job->height);

        cl_mem density_buf = clCreateBuffer(
            g_state.context,
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            density_bytes,
            buffers->density,
            &err
        );
        cl_mem light_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);

        clSetKernelArg(g_state.skylight_kernel, 0, sizeof(cl_mem), &density_buf);
        clSetKernelArg(g_state.skylight_kernel, 1, sizeof(cl_mem), &light_buf);
        clSetKernelArg(g_state.skylight_kernel, 2, sizeof(int), &job->height);
        clSetKernelArg(g_state.skylight_kernel, 3, sizeof(uint32_t), &buffers->stride_y);

        const size_t global[2] = {CHUNKUP_CHUNK_SIZE, CHUNKUP_CHUNK_SIZE};
        err = clEnqueueNDRangeKernel(g_state.queue, g_state.skylight_kernel, 2, nullptr, global, nullptr, 0, nullptr, nullptr);
        clEnqueueReadBuffer(g_state.queue, light_buf, CL_TRUE, 0, light_bytes, buffers->skylight, 0, nullptr, nullptr);
        clReleaseMemObject(density_buf);
        clReleaseMemObject(light_buf);

        if (err != CL_SUCCESS) {
            return -22;
        }
        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;
    }

    if (job->op_mask & (CHUNKUP_OP_BLOCKLIGHT | CHUNKUP_OP_FACE_CULL)) {
        return chunkup_kernel_dispatch_cpu(job, buffers, result);
    }

    return 0;
}

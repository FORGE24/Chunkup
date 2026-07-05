#include "chunkup_opencl.h"



#include "../common/chunkup_kernel.h"

#include "../common/chunkup_cell_fill.h"

#include "../common/chunkup_kernel_algo.h"

#include "../common/chunkup_noise_state.h"



#include <CL/cl.h>



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

    cl_kernel skylight_kernel = nullptr;
    cl_kernel face_cull_kernel = nullptr;
    cl_kernel skylight_batch_kernel = nullptr;
    cl_kernel face_cull_batch_kernel = nullptr;
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



    g_state.skylight_kernel = clCreateKernel(g_state.program, "chunkup_kernel_skylight", &err);

    if (err != CL_SUCCESS) {

        return false;

    }



    g_state.face_cull_kernel = clCreateKernel(g_state.program, "chunkup_kernel_face_cull", &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.skylight_batch_kernel = clCreateKernel(g_state.program, "chunkup_kernel_skylight_batch", &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.face_cull_batch_kernel = clCreateKernel(g_state.program, "chunkup_kernel_face_cull_batch", &err);
    if (err != CL_SUCCESS) {
        return false;
    }

    g_state.initialized = true;

    return true;

}



bool ensure_density_buffer(

    cl_mem* density_buf,

    const ChunkupKernelJob* job,

    ChunkupKernelBuffers* buffers,

    size_t density_bytes,

    bool noise_fill_done

) {

    if (*density_buf != nullptr) {

        return true;

    }



    cl_int err = CL_SUCCESS;

    if (noise_fill_done) {

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

    cl_int err = CL_SUCCESS;

    bool noise_fill_done = false;



    if (job->op_mask & CHUNKUP_OP_NOISE_FILL) {

        if (!buffers->density) {

            return -2;

        }



        chunkup_noise_prepare(job->seed);

        chunkup_cell_fill_chunk(

            &chunkup_active_bundle,

            base_x,

            base_z,

            job->min_y,

            job->height,

            buffers->density,

            buffers->fluid,

            buffers->stride_y

        );

        result->ops_completed |= CHUNKUP_OP_NOISE_FILL;

        noise_fill_done = true;

    }



    if (job->op_mask & CHUNKUP_OP_SKYLIGHT) {

        if (!buffers->density || !buffers->skylight) {

            return -3;

        }

        if (!ensure_density_buffer(&density_buf, job, buffers, density_bytes, noise_fill_done)) {

            if (density_buf) {

                clReleaseMemObject(density_buf);

            }

            return -20;

        }



        cl_mem light_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);

        if (err != CL_SUCCESS) {

            clReleaseMemObject(density_buf);

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

            clReleaseMemObject(density_buf);

            return -22;

        }

        result->ops_completed |= CHUNKUP_OP_SKYLIGHT;

    }



    if (job->op_mask & CHUNKUP_OP_BLOCKLIGHT) {

        if (!buffers->blocklight) {

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

            if (density_buf) {

                clReleaseMemObject(density_buf);

            }

            return -5;

        }

        if (!ensure_density_buffer(&density_buf, job, buffers, density_bytes, noise_fill_done)) {

            if (density_buf) {

                clReleaseMemObject(density_buf);

            }

            return -20;

        }



        cl_mem face_buf = clCreateBuffer(g_state.context, CL_MEM_WRITE_ONLY, light_bytes, nullptr, &err);

        if (err != CL_SUCCESS) {

            clReleaseMemObject(density_buf);

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

            clReleaseMemObject(density_buf);

            return -24;

        }

        result->ops_completed |= CHUNKUP_OP_FACE_CULL;

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


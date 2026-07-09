use crate::backend::gpu_loader;
use crate::backend::BackendKind;
use crate::stats;

use super::types::{KernelBuffers, KernelJob, KernelResult};
use super::workspace::KernelWorkspace;

pub struct UnifiedKernel {
    backend: BackendKind,
}

impl UnifiedKernel {
    pub fn new(backend: BackendKind) -> Self {
        Self { backend }
    }

    pub fn dispatch(&self, job: &KernelJob) -> Result<KernelResult, i32> {
        if job.op_mask == 0 {
            return Ok(KernelResult {
                status: 0,
                ops_completed: 0,
            });
        }

        let mut workspace = KernelWorkspace::for_job(job);
        self.dispatch_with_workspace(job, &mut workspace)
    }

    pub fn dispatch_with_workspace(
        &self,
        job: &KernelJob,
        workspace: &mut KernelWorkspace,
    ) -> Result<KernelResult, i32> {
        if job.op_mask == 0 {
            return Ok(KernelResult {
                status: 0,
                ops_completed: 0,
            });
        }

        let mut buffers = KernelBuffers::from_workspace(workspace);
        let mut result = KernelResult::default();
        let force_gpu = stats::force_gpu();

        let code = unsafe {
            match self.backend {
                BackendKind::Cuda => {
                    if let Some(code) = gpu_loader::cuda_dispatch(job, &mut buffers, &mut result) {
                        stats::record_cuda_single(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_single();
                            super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                        }
                    } else if force_gpu {
                        stats::record_cuda_single(false);
                        return Err(-11);
                    } else {
                        stats::record_cpu_fallback_single();
                        stats::record_cpu_single();
                        super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                    }
                }
                BackendKind::OpenCl => {
                    if let Some(code) = gpu_loader::opencl_dispatch(job, &mut buffers, &mut result) {
                        stats::record_opencl_single(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_single();
                            super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                        }
                    } else if force_gpu {
                        stats::record_opencl_single(false);
                        return Err(-12);
                    } else {
                        stats::record_cpu_fallback_single();
                        stats::record_cpu_single();
                        super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                    }
                }
                _ => {
                    stats::record_cpu_single();
                    super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                }
            }
        };

        if code == 0 {
            stats::record_ops_completed(result.ops_completed);
            log::debug!(
                "chunkup kernel ok backend={} chunk=[{}, {}] ops=0x{:x}",
                self.backend.name(),
                job.chunk_x,
                job.chunk_z,
                result.ops_completed
            );
            Ok(result)
        } else {
            log::warn!(
                "chunkup kernel failed backend={} chunk=[{}, {}] code={}",
                self.backend.name(),
                job.chunk_x,
                job.chunk_z,
                code
            );
            Err(code)
        }
    }

    pub fn dispatch_batch(
        &self,
        template_job: &KernelJob,
        batch_count: i32,
        host_density: &[f32],
        host_skylight: &mut [u8],
        host_face_mask: &mut [u8],
        blocks_per_chunk: u32,
    ) -> Result<KernelResult, i32> {
        if batch_count <= 0 || template_job.op_mask == 0 {
            return Ok(KernelResult {
                status: 0,
                ops_completed: 0,
            });
        }

        let mut result = KernelResult::default();
        let force_gpu = stats::force_gpu();

        let code = unsafe {
            match self.backend {
                BackendKind::Cuda => {
                    if let Some(code) = gpu_loader::cuda_dispatch_batch(
                        template_job,
                        batch_count,
                        host_density.as_ptr(),
                        host_skylight.as_mut_ptr(),
                        host_face_mask.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result,
                    ) {
                        stats::record_cuda_batch(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_batch();
                            super::types::chunkup_kernel_dispatch_cpu_batch(
                                template_job as *const _,
                                batch_count,
                                host_density.as_ptr() as *mut f32,
                                host_skylight.as_mut_ptr(),
                                host_face_mask.as_mut_ptr(),
                                blocks_per_chunk,
                                &mut result as *mut _,
                            )
                        }
                    } else if force_gpu {
                        stats::record_cuda_batch(false);
                        return Err(-11);
                    } else {
                        stats::record_cpu_fallback_batch();
                        stats::record_cpu_batch();
                        super::types::chunkup_kernel_dispatch_cpu_batch(
                            template_job as *const _,
                            batch_count,
                            host_density.as_ptr() as *mut f32,
                            host_skylight.as_mut_ptr(),
                            host_face_mask.as_mut_ptr(),
                            blocks_per_chunk,
                            &mut result as *mut _,
                        )
                    }
                }
                BackendKind::OpenCl => {
                    if let Some(code) = gpu_loader::opencl_dispatch_batch(
                        template_job,
                        batch_count,
                        host_density.as_ptr(),
                        host_skylight.as_mut_ptr(),
                        host_face_mask.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result,
                    ) {
                        stats::record_opencl_batch(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_batch();
                            super::types::chunkup_kernel_dispatch_cpu_batch(
                                template_job as *const _,
                                batch_count,
                                host_density.as_ptr() as *mut f32,
                                host_skylight.as_mut_ptr(),
                                host_face_mask.as_mut_ptr(),
                                blocks_per_chunk,
                                &mut result as *mut _,
                            )
                        }
                    } else if force_gpu {
                        stats::record_opencl_batch(false);
                        return Err(-12);
                    } else {
                        stats::record_cpu_fallback_batch();
                        stats::record_cpu_batch();
                        super::types::chunkup_kernel_dispatch_cpu_batch(
                            template_job as *const _,
                            batch_count,
                            host_density.as_ptr() as *mut f32,
                            host_skylight.as_mut_ptr(),
                            host_face_mask.as_mut_ptr(),
                            blocks_per_chunk,
                            &mut result as *mut _,
                        )
                    }
                }
                _ => {
                    stats::record_cpu_batch();
                    super::types::chunkup_kernel_dispatch_cpu_batch(
                        template_job as *const _,
                        batch_count,
                        host_density.as_ptr() as *mut f32,
                        host_skylight.as_mut_ptr(),
                        host_face_mask.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result as *mut _,
                    )
                }
            }
        };

        if code == 0 {
            stats::record_ops_completed(result.ops_completed);
            log::debug!(
                "chunkup kernel batch ok backend={} count={} ops=0x{:x}",
                self.backend.name(),
                batch_count,
                result.ops_completed
            );
            Ok(result)
        } else {
            log::warn!(
                "chunkup kernel batch failed backend={} count={} code={}",
                self.backend.name(),
                batch_count,
                code
            );
            Err(code)
        }
    }

    pub fn dispatch_density_batch(
        &self,
        template_job: &KernelJob,
        batch_count: i32,
        chunk_xs: &[i32],
        chunk_zs: &[i32],
        host_density: &mut [f32],
        host_fluid: &mut [u8],
        blocks_per_chunk: u32,
    ) -> Result<KernelResult, i32> {
        if batch_count <= 0 || chunk_xs.len() < batch_count as usize || chunk_zs.len() < batch_count as usize {
            return Err(-1);
        }

        let mut result = KernelResult::default();
        let force_gpu = stats::force_gpu();

        crate::sl_log::info_start(
            "CUDA Density Batch Module",
            "Launching GPU density batch kernel",
            &format!(
                "Backend={},BatchCount={},MinY={},Height={},BlocksPerChunk={},ForceGpu={}",
                self.backend.name(),
                batch_count,
                template_job.min_y,
                template_job.height,
                blocks_per_chunk,
                force_gpu
            ),
        );

        let code = unsafe {
            match self.backend {
                BackendKind::Cuda => {
                    if let Some(code) = gpu_loader::cuda_dispatch_density_batch(
                        template_job,
                        batch_count,
                        chunk_xs.as_ptr(),
                        chunk_zs.as_ptr(),
                        host_density.as_mut_ptr(),
                        host_fluid.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result,
                    ) {
                        stats::record_cuda_batch(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_batch();
                            super::types::chunkup_kernel_dispatch_density_batch(
                                template_job as *const _,
                                batch_count,
                                chunk_xs.as_ptr(),
                                chunk_zs.as_ptr(),
                                host_density.as_mut_ptr(),
                                host_fluid.as_mut_ptr(),
                                blocks_per_chunk,
                                &mut result as *mut _,
                            )
                        }
                    } else if force_gpu {
                        stats::record_cuda_batch(false);
                        return Err(-11);
                    } else {
                        stats::record_cpu_fallback_batch();
                        stats::record_cpu_batch();
                        super::types::chunkup_kernel_dispatch_density_batch(
                            template_job as *const _,
                            batch_count,
                            chunk_xs.as_ptr(),
                            chunk_zs.as_ptr(),
                            host_density.as_mut_ptr(),
                            host_fluid.as_mut_ptr(),
                            blocks_per_chunk,
                            &mut result as *mut _,
                        )
                    }
                }
                BackendKind::OpenCl => {
                    if let Some(code) = gpu_loader::opencl_dispatch_density_batch(
                        template_job,
                        batch_count,
                        chunk_xs.as_ptr(),
                        chunk_zs.as_ptr(),
                        host_density.as_mut_ptr(),
                        host_fluid.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result,
                    ) {
                        stats::record_opencl_batch(code == 0);
                        if code == 0 {
                            code
                        } else if force_gpu {
                            return Err(code);
                        } else {
                            stats::record_cpu_fallback_batch();
                            super::types::chunkup_kernel_dispatch_density_batch(
                                template_job as *const _,
                                batch_count,
                                chunk_xs.as_ptr(),
                                chunk_zs.as_ptr(),
                                host_density.as_mut_ptr(),
                                host_fluid.as_mut_ptr(),
                                blocks_per_chunk,
                                &mut result as *mut _,
                            )
                        }
                    } else if force_gpu {
                        stats::record_opencl_batch(false);
                        return Err(-12);
                    } else {
                        stats::record_cpu_fallback_batch();
                        stats::record_cpu_batch();
                        super::types::chunkup_kernel_dispatch_density_batch(
                            template_job as *const _,
                            batch_count,
                            chunk_xs.as_ptr(),
                            chunk_zs.as_ptr(),
                            host_density.as_mut_ptr(),
                            host_fluid.as_mut_ptr(),
                            blocks_per_chunk,
                            &mut result as *mut _,
                        )
                    }
                }
                BackendKind::CpuSimd => {
                    stats::record_cpu_batch();
                    super::types::chunkup_kernel_dispatch_density_batch(
                        template_job as *const _,
                        batch_count,
                        chunk_xs.as_ptr(),
                        chunk_zs.as_ptr(),
                        host_density.as_mut_ptr(),
                        host_fluid.as_mut_ptr(),
                        blocks_per_chunk,
                        &mut result as *mut _,
                    )
                }
            }
        };

        if code == 0 {
            stats::record_ops_completed(result.ops_completed);
            log::debug!(
                "chunkup density batch ok backend={} count={} ops=0x{:x}",
                self.backend.name(),
                batch_count,
                result.ops_completed
            );
            Ok(result)
        } else {
            log::warn!(
                "chunkup density batch failed backend={} count={} code={}",
                self.backend.name(),
                batch_count,
                code
            );
            Err(code)
        }
    }
}

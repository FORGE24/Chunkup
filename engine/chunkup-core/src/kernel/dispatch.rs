use crate::backend::gpu_loader;
use crate::backend::BackendKind;

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

        let code = unsafe {
            match self.backend {
                BackendKind::Cuda => {
                    if let Some(code) = gpu_loader::cuda_dispatch(job, &mut buffers, &mut result) {
                        code
                    } else {
                        super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                    }
                }
                BackendKind::OpenCl => {
                    if let Some(code) = gpu_loader::opencl_dispatch(job, &mut buffers, &mut result) {
                        code
                    } else {
                        super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result)
                    }
                }
                _ => super::types::chunkup_kernel_dispatch_cpu(job, &mut buffers, &mut result),
            }
        };

        if code == 0 {
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
}

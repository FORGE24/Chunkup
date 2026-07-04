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
        let mut buffers = KernelBuffers::from_workspace(&mut workspace);
        let mut result = KernelResult::default();

        let code = unsafe {
            match self.backend {
                #[cfg(feature = "cuda")]
                BackendKind::Cuda => super::types::chunkup_cuda_kernel_dispatch(job, &mut buffers, &mut result),
                #[cfg(feature = "opencl")]
                BackendKind::OpenCl => super::types::chunkup_opencl_kernel_dispatch(job, &mut buffers, &mut result),
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

use super::{cuda::CudaBackend, cpu, opencl::OpenClBackend};
use crate::kernel::UnifiedKernel;
use crate::sl_log;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackendKind {
    Cuda,
    OpenCl,
    CpuSimd,
}

impl BackendKind {
    pub fn name(self) -> &'static str {
        match self {
            Self::Cuda => "cuda",
            Self::OpenCl => "opencl",
            Self::CpuSimd => "cpu-simd",
        }
    }
}

pub struct EngineContext {
    backend: BackendKind,
    kernel: UnifiedKernel,
}

impl EngineContext {
    /// 按优先级探测并绑定后端：CUDA → OpenCL → CPU/SIMD。
    pub fn bootstrap() -> Self {
        let backend = if CudaBackend::probe() {
            BackendKind::Cuda
        } else if OpenClBackend::probe() {
            BackendKind::OpenCl
        } else if cpu::probe() {
            BackendKind::CpuSimd
        } else {
            log::warn!("no accelerated backend found; defaulting to cpu-simd");
            BackendKind::CpuSimd
        };

        let kernel = UnifiedKernel::new(backend);
        sl_log::info_status(
            "Compute Backend Module",
            "Active compute backend selected",
            &format!("Backend={},CudaProbe={},OpenClProbe={}", backend.name(), CudaBackend::probe(), OpenClBackend::probe()),
        );
        Self { backend, kernel }
    }

    pub fn active_backend(&self) -> BackendKind {
        self.backend
    }

    pub fn kernel(&self) -> &UnifiedKernel {
        &self.kernel
    }

    pub fn shutdown(self) {
        log::info!("chunkup engine shutdown (backend={})", self.backend.name());
    }
}

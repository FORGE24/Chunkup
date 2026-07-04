use super::gpu_loader;

pub struct CudaBackend;

impl CudaBackend {
    pub fn probe() -> bool {
        gpu_loader::cuda_probe()
    }
}

use super::gpu_loader;

pub struct OpenClBackend;

impl OpenClBackend {
    pub fn probe() -> bool {
        gpu_loader::opencl_probe()
    }
}

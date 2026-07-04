pub struct CudaBackend;

impl CudaBackend {
    pub fn probe() -> bool {
        #[cfg(feature = "cuda")]
        {
            unsafe { chunkup_cuda_is_available() != 0 }
        }
        #[cfg(not(feature = "cuda"))]
        {
            false
        }
    }
}

#[cfg(feature = "cuda")]
extern "C" {
    fn chunkup_cuda_is_available() -> i32;
}

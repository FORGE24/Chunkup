pub struct OpenClBackend;

impl OpenClBackend {
    pub fn probe() -> bool {
        #[cfg(feature = "opencl")]
        {
            unsafe { chunkup_opencl_is_available() != 0 }
        }
        #[cfg(not(feature = "opencl"))]
        {
            false
        }
    }
}

#[cfg(feature = "opencl")]
extern "C" {
    fn chunkup_opencl_is_available() -> i32;
}

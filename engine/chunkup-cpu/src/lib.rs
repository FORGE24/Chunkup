//! CPU 回退后端：Rust + SIMD（AVX2/AVX-512 on x86, NEON on aarch64）。

pub struct CpuBackend;

impl CpuBackend {
    pub fn probe() -> bool {
        true
    }

    pub fn simd_level() -> &'static str {
        #[cfg(all(target_arch = "x86_64", feature = "simd"))]
        {
            if is_x86_feature_detected!("avx512f") {
                return "avx512";
            }
            if is_x86_feature_detected!("avx2") {
                return "avx2";
            }
            if is_x86_feature_detected!("sse4.2") {
                return "sse4.2";
            }
        }
        #[cfg(all(target_arch = "aarch64", feature = "simd"))]
        {
            return "neon";
        }
        "scalar"
    }
}

#[cfg(all(target_arch = "x86_64", feature = "simd"))]
use std::arch::is_x86_feature_detected;

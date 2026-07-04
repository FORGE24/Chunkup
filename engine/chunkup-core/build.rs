fn main() {
    let common = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../native/common");

    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel_algo.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel_host.c").display());

    cc::Build::new()
        .file(common.join("chunkup_kernel_host.c"))
        .include(&common)
        .warnings(false)
        .compile("chunkup_kernel_host");

    if cfg!(feature = "cuda") {
        println!("cargo:warning=CUDA feature enabled; link chunkup_cuda via native/cuda/CMakeLists.txt");
    }

    if cfg!(feature = "opencl") {
        println!("cargo:warning=OpenCL feature enabled; link chunkup_opencl via native/opencl/CMakeLists.txt");
    }
}

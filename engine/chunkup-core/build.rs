fn main() {
    let common = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../native/common");
    let router_header = common.join("chunkup_overworld_router.h");
    let is_debug = std::env::var("PROFILE").unwrap_or_default() == "debug";

    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel_algo.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_kernel_host.c").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_improved_noise.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_overworld_density.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_density_router.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_cell_fill.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_noise_bundle.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_normal_noise.h").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_spline.h").display());
    println!("cargo:rerun-if-changed={}", router_header.display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_factor_eval.h").display());
    println!("cargo:rerun-if-changed=../../scripts/codegen-factor-spline.py");
    println!("cargo:rerun-if-changed={}", common.join("chunkup_batch_host.c").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_batch_density_host.c").display());
    println!("cargo:rerun-if-changed={}", common.join("chunkup_noise_state.h").display());
    println!("cargo:rerun-if-changed=../../scripts/extract-overworld-router.py");

    let mut build = cc::Build::new();
    build
        .file(common.join("chunkup_kernel_host.c"))
        .file(common.join("chunkup_batch_host.c"))
        .file(common.join("chunkup_batch_density_host.c"))
        .file(common.join("chunkup_noise_state.c"))
        .include(&common)
        .warnings(false);

    if is_debug {
        build.debug(true);
        let target = std::env::var("TARGET").unwrap_or_default();
        if target.contains("msvc") {
            build.flag("/Zi").flag("/Od");
        }
    }

    build.compile("chunkup_kernel_host");
}

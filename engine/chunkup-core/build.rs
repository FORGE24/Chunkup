fn main() {

    let common = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../native/common");

    let router_header = common.join("chunkup_overworld_router.h");



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

    println!("cargo:rerun-if-changed={}", common.join("chunkup_batch_host.c").display());

    println!("cargo:rerun-if-changed={}", common.join("chunkup_noise_state.h").display());

    println!("cargo:rerun-if-changed=../../scripts/extract-overworld-router.py");



    cc::Build::new()

        .file(common.join("chunkup_kernel_host.c"))

        .file(common.join("chunkup_batch_host.c"))

        .file(common.join("chunkup_noise_state.c"))

        .include(&common)

        .warnings(false)

        .compile("chunkup_kernel_host");

}


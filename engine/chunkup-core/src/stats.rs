use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

static FORCE_GPU: AtomicBool = AtomicBool::new(true);

static CUDA_SINGLE_OK: AtomicU64 = AtomicU64::new(0);
static CUDA_SINGLE_FAIL: AtomicU64 = AtomicU64::new(0);
static CUDA_BATCH_OK: AtomicU64 = AtomicU64::new(0);
static CUDA_BATCH_FAIL: AtomicU64 = AtomicU64::new(0);
static OPENCL_SINGLE_OK: AtomicU64 = AtomicU64::new(0);
static OPENCL_SINGLE_FAIL: AtomicU64 = AtomicU64::new(0);
static OPENCL_BATCH_OK: AtomicU64 = AtomicU64::new(0);
static OPENCL_BATCH_FAIL: AtomicU64 = AtomicU64::new(0);
static CPU_SINGLE: AtomicU64 = AtomicU64::new(0);
static CPU_BATCH: AtomicU64 = AtomicU64::new(0);
static CPU_FALLBACK_SINGLE: AtomicU64 = AtomicU64::new(0);
static CPU_FALLBACK_BATCH: AtomicU64 = AtomicU64::new(0);

static OP_NOISE_FILL: AtomicU64 = AtomicU64::new(0);
static OP_SKYLIGHT: AtomicU64 = AtomicU64::new(0);
static OP_FACE_CULL: AtomicU64 = AtomicU64::new(0);
static OP_SECTION_MESH: AtomicU64 = AtomicU64::new(0);

pub fn set_force_gpu(enabled: bool) {
    FORCE_GPU.store(enabled, Ordering::Relaxed);
}

pub fn force_gpu() -> bool {
    FORCE_GPU.load(Ordering::Relaxed)
}

pub fn record_cuda_single(ok: bool) {
    if ok {
        CUDA_SINGLE_OK.fetch_add(1, Ordering::Relaxed);
    } else {
        CUDA_SINGLE_FAIL.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn record_cuda_batch(ok: bool) {
    if ok {
        CUDA_BATCH_OK.fetch_add(1, Ordering::Relaxed);
    } else {
        CUDA_BATCH_FAIL.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn record_opencl_single(ok: bool) {
    if ok {
        OPENCL_SINGLE_OK.fetch_add(1, Ordering::Relaxed);
    } else {
        OPENCL_SINGLE_FAIL.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn record_opencl_batch(ok: bool) {
    if ok {
        OPENCL_BATCH_OK.fetch_add(1, Ordering::Relaxed);
    } else {
        OPENCL_BATCH_FAIL.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn record_cpu_single() {
    CPU_SINGLE.fetch_add(1, Ordering::Relaxed);
}

pub fn record_cpu_batch() {
    CPU_BATCH.fetch_add(1, Ordering::Relaxed);
}

pub fn record_cpu_fallback_single() {
    CPU_FALLBACK_SINGLE.fetch_add(1, Ordering::Relaxed);
}

pub fn record_cpu_fallback_batch() {
    CPU_FALLBACK_BATCH.fetch_add(1, Ordering::Relaxed);
}

pub fn record_ops_completed(ops: u32) {
    use crate::kernel::types::KernelOp;
    if ops & KernelOp::NoiseFill as u32 != 0 {
        OP_NOISE_FILL.fetch_add(1, Ordering::Relaxed);
    }
    if ops & KernelOp::Skylight as u32 != 0 {
        OP_SKYLIGHT.fetch_add(1, Ordering::Relaxed);
    }
    if ops & KernelOp::FaceCull as u32 != 0 {
        OP_FACE_CULL.fetch_add(1, Ordering::Relaxed);
    }
    if ops & KernelOp::SectionMesh as u32 != 0 {
        OP_SECTION_MESH.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn debug_lines() -> Vec<String> {
    vec![
        format!("forceGpu={}", force_gpu()),
        format!(
            "cuda single ok/fail={}/{} batch ok/fail={}/{}",
            CUDA_SINGLE_OK.load(Ordering::Relaxed),
            CUDA_SINGLE_FAIL.load(Ordering::Relaxed),
            CUDA_BATCH_OK.load(Ordering::Relaxed),
            CUDA_BATCH_FAIL.load(Ordering::Relaxed),
        ),
        format!(
            "opencl single ok/fail={}/{} batch ok/fail={}/{}",
            OPENCL_SINGLE_OK.load(Ordering::Relaxed),
            OPENCL_SINGLE_FAIL.load(Ordering::Relaxed),
            OPENCL_BATCH_OK.load(Ordering::Relaxed),
            OPENCL_BATCH_FAIL.load(Ordering::Relaxed),
        ),
        format!(
            "cpu direct={}/{} fallback={}/{}",
            CPU_SINGLE.load(Ordering::Relaxed),
            CPU_BATCH.load(Ordering::Relaxed),
            CPU_FALLBACK_SINGLE.load(Ordering::Relaxed),
            CPU_FALLBACK_BATCH.load(Ordering::Relaxed),
        ),
        format!(
            "ops noise/skylight/cull/section={}/{}/{}/{}",
            OP_NOISE_FILL.load(Ordering::Relaxed),
            OP_SKYLIGHT.load(Ordering::Relaxed),
            OP_FACE_CULL.load(Ordering::Relaxed),
            OP_SECTION_MESH.load(Ordering::Relaxed),
        ),
        format!(
            "gpu libs cuda={} opencl={} cudaProbe={} openclProbe={}",
            crate::backend::gpu_loader::cuda_lib_loaded(),
            crate::backend::gpu_loader::opencl_lib_loaded(),
            crate::backend::gpu_loader::cuda_probe(),
            crate::backend::gpu_loader::opencl_probe(),
        ),
    ]
}

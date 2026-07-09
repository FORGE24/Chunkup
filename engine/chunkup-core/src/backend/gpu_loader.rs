use std::path::PathBuf;
use std::sync::OnceLock;

use libloading::Library;

use crate::kernel::types::{KernelBuffers, KernelJob, KernelResult};

static NATIVE_LIBRARY_DIR: OnceLock<PathBuf> = OnceLock::new();

type GpuIsAvailableFn = unsafe extern "C" fn() -> i32;
type GpuDispatchFn = unsafe extern "C" fn(
    *const KernelJob,
    *mut KernelBuffers,
    *mut KernelResult,
) -> i32;
type GpuBatchDispatchFn = unsafe extern "C" fn(
    *const KernelJob,
    i32,
    *const f32,
    *mut u8,
    *mut u8,
    u32,
    *mut KernelResult,
) -> i32;
type GpuDensityBatchFn = unsafe extern "C" fn(
    *const KernelJob,
    i32,
    *const i32,
    *const i32,
    *mut f32,
    *mut u8,
    u32,
    *mut KernelResult,
) -> i32;

struct GpuBackendLib {
    _library: Library,
    is_available: GpuIsAvailableFn,
    dispatch: GpuDispatchFn,
    dispatch_batch: Option<GpuBatchDispatchFn>,
    density_fill_batch: Option<GpuDensityBatchFn>,
}

// ── library candidate paths (FORGE24 Linux adapt) ──────────────────
//
// On Windows: just <base>.dll in working dir
// On macOS:   lib<base>.dylib or <base>.dylib
// On Linux:   lib<base>.so — searched in:
//   1. CHUNKUP_NATIVE_DIR env var (set by NativeLibraryLoader.kt)
//   2. LD_LIBRARY_PATH
//   3. Current working directory
fn library_candidates(base: &str) -> Vec<PathBuf> {
    get_search_dirs()
        .into_iter()
        .flat_map(|dir| {
            #[cfg(windows)]
            {
                vec![dir.join(format!("{base}.dll"))]
            }
            #[cfg(target_os = "macos")]
            {
                vec![
                    dir.join(format!("lib{base}.dylib")),
                    dir.join(format!("{base}.dylib")),
                ]
            }
            #[cfg(all(unix, not(target_os = "macos")))]
            {
                vec![dir.join(format!("lib{base}.so"))]
            }
        })
        .collect()
}

/// Collect directories to search for GPU backend libraries.
fn get_search_dirs() -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = Vec::new();

    if let Some(custom) = NATIVE_LIBRARY_DIR.get() {
        dirs.push(custom.clone());
    }

    // 1. CHUNKUP_NATIVE_DIR — set by NativeLibraryLoader.kt
    if let Ok(native_dir) = std::env::var("CHUNKUP_NATIVE_DIR") {
        if !native_dir.is_empty() {
            dirs.push(PathBuf::from(&native_dir));
        }
    }

    // 2. LD_LIBRARY_PATH — standard Linux loader search path
    #[cfg(target_os = "linux")]
    {
        if let Ok(ld_path) = std::env::var("LD_LIBRARY_PATH") {
            for p in ld_path.split(':') {
                let p = p.trim();
                if !p.is_empty() {
                    dirs.push(PathBuf::from(p));
                }
            }
        }
    }

    // 3. Current working directory (fallback for dev runs)
    if let Ok(cwd) = std::env::current_dir() {
        dirs.push(cwd);
    }

    // 4. Standard system library paths
    #[cfg(target_os = "linux")]
    {
        dirs.push(PathBuf::from("/usr/lib"));
        dirs.push(PathBuf::from("/usr/lib64"));
        dirs.push(PathBuf::from("/usr/local/lib"));
    }

    dirs
}

fn load_backend(
    base: &str,
    available_sym: &[u8],
    dispatch_sym: &[u8],
    batch_sym: &[u8],
    density_batch_sym: &[u8],
) -> Option<GpuBackendLib> {
    for path in library_candidates(base) {
        log::debug!("chunkup gpu_loader: trying {}", path.display());
        let library = match unsafe { Library::new(&path) } {
            Ok(lib) => lib,
            Err(_) => continue,
        };

        let is_available = match unsafe { library.get::<GpuIsAvailableFn>(available_sym) } {
            Ok(sym) => *sym,
            Err(_) => continue,
        };
        let dispatch = match unsafe { library.get::<GpuDispatchFn>(dispatch_sym) } {
            Ok(sym) => *sym,
            Err(_) => continue,
        };
        let dispatch_batch = unsafe { library.get::<GpuBatchDispatchFn>(batch_sym) }
            .ok()
            .map(|sym| *sym);
        let density_fill_batch = unsafe { library.get::<GpuDensityBatchFn>(density_batch_sym) }
            .ok()
            .map(|sym| *sym);

        log::info!(
            "chunkup gpu_loader: loaded {} from {} (batch={} densityBatch={})",
            base,
            path.display(),
            dispatch_batch.is_some(),
            density_fill_batch.is_some()
        );
        return Some(GpuBackendLib {
            _library: library,
            is_available,
            dispatch,
            dispatch_batch,
            density_fill_batch,
        });
    }

    None
}

static CUDA_LIB: OnceLock<Option<GpuBackendLib>> = OnceLock::new();
static OPENCL_LIB: OnceLock<Option<GpuBackendLib>> = OnceLock::new();

fn cuda_lib() -> Option<&'static GpuBackendLib> {
    CUDA_LIB
        .get_or_init(|| {
            load_backend(
                "chunkup_cuda",
                b"chunkup_cuda_is_available\0",
                b"chunkup_cuda_kernel_dispatch\0",
                b"chunkup_cuda_kernel_dispatch_batch\0",
                b"chunkup_cuda_density_fill_batch\0",
            )
        })
        .as_ref()
}

fn opencl_lib() -> Option<&'static GpuBackendLib> {
    OPENCL_LIB
        .get_or_init(|| {
            load_backend(
                "chunkup_opencl",
                b"chunkup_opencl_is_available\0",
                b"chunkup_opencl_kernel_dispatch\0",
                b"chunkup_opencl_kernel_dispatch_batch\0",
                b"chunkup_opencl_density_fill_batch\0",
            )
        })
        .as_ref()
}

pub fn cuda_lib_loaded() -> bool {
    cuda_lib().is_some()
}

pub fn opencl_lib_loaded() -> bool {
    opencl_lib().is_some()
}

pub fn cuda_probe() -> bool {
    cuda_lib().is_some_and(|lib| unsafe { (lib.is_available)() != 0 })
}

pub fn opencl_probe() -> bool {
    opencl_lib().is_some_and(|lib| unsafe { (lib.is_available)() != 0 })
}

pub fn cuda_dispatch(
    job: &KernelJob,
    buffers: &mut KernelBuffers,
    result: &mut KernelResult,
) -> Option<i32> {
    let lib = cuda_lib()?;
    Some(unsafe { (lib.dispatch)(job as *const _, buffers as *mut _, result as *mut _) })
}

pub fn opencl_dispatch(
    job: &KernelJob,
    buffers: &mut KernelBuffers,
    result: &mut KernelResult,
) -> Option<i32> {
    let lib = opencl_lib()?;
    Some(unsafe { (lib.dispatch)(job as *const _, buffers as *mut _, result as *mut _) })
}

/// Called from JNI bridge after native libs are extracted.
pub fn set_native_library_directory(dir: &str) {
    if dir.is_empty() {
        return;
    }
    let path = PathBuf::from(dir);
    let _ = NATIVE_LIBRARY_DIR.set(path);
    std::env::set_var("CHUNKUP_NATIVE_DIR", dir);
}

pub fn cuda_dispatch_batch(
    template_job: &KernelJob,
    batch_count: i32,
    host_density: *const f32,
    host_skylight: *mut u8,
    host_face_mask: *mut u8,
    blocks_per_chunk: u32,
    result: *mut KernelResult,
) -> Option<i32> {
    let lib = cuda_lib()?;
    let batch = lib.dispatch_batch?;
    Some(unsafe {
        batch(
            template_job as *const _,
            batch_count,
            host_density,
            host_skylight,
            host_face_mask,
            blocks_per_chunk,
            result,
        )
    })
}

pub fn cuda_dispatch_density_batch(
    template_job: &KernelJob,
    batch_count: i32,
    chunk_xs: *const i32,
    chunk_zs: *const i32,
    host_density: *mut f32,
    host_fluid: *mut u8,
    blocks_per_chunk: u32,
    result: *mut KernelResult,
) -> Option<i32> {
    let lib = cuda_lib()?;
    let batch = lib.density_fill_batch?;
    Some(unsafe {
        batch(
            template_job as *const _,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result,
        )
    })
}

pub fn opencl_dispatch_batch(
    template_job: &KernelJob,
    batch_count: i32,
    host_density: *const f32,
    host_skylight: *mut u8,
    host_face_mask: *mut u8,
    blocks_per_chunk: u32,
    result: *mut KernelResult,
) -> Option<i32> {
    let lib = opencl_lib()?;
    let batch = lib.dispatch_batch?;
    Some(unsafe {
        batch(
            template_job as *const _,
            batch_count,
            host_density,
            host_skylight,
            host_face_mask,
            blocks_per_chunk,
            result,
        )
    })
}

pub fn opencl_dispatch_density_batch(
    template_job: &KernelJob,
    batch_count: i32,
    chunk_xs: *const i32,
    chunk_zs: *const i32,
    host_density: *mut f32,
    host_fluid: *mut u8,
    blocks_per_chunk: u32,
    result: *mut KernelResult,
) -> Option<i32> {
    let lib = opencl_lib()?;
    let batch = lib.density_fill_batch?;
    Some(unsafe {
        batch(
            template_job as *const _,
            batch_count,
            chunk_xs,
            chunk_zs,
            host_density,
            host_fluid,
            blocks_per_chunk,
            result,
        )
    })
}

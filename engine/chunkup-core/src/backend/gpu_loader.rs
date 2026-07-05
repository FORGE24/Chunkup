use std::path::PathBuf;
use std::sync::OnceLock;

use libloading::Library;

use crate::kernel::types::{KernelBuffers, KernelJob, KernelResult};

type GpuIsAvailableFn = unsafe extern "C" fn() -> i32;
type GpuDispatchFn = unsafe extern "C" fn(
    *const KernelJob,
    *mut KernelBuffers,
    *mut KernelResult,
) -> i32;

struct GpuBackendLib {
    _library: Library,
    is_available: GpuIsAvailableFn,
    dispatch: GpuDispatchFn,
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

        log::info!(
            "chunkup gpu_loader: loaded {} from {}",
            base,
            path.display()
        );
        return Some(GpuBackendLib {
            _library: library,
            is_available,
            dispatch,
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
            )
        })
        .as_ref()
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

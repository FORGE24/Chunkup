use std::path::PathBuf;
use std::sync::OnceLock;

use libloading::Library;

use crate::kernel::types::{KernelBuffers, KernelJob, KernelResult};

static NATIVE_LIB_DIR: OnceLock<PathBuf> = OnceLock::new();

/// 由 Kotlin/JNI 在加载 chunkup_core 后设置（java.library.path 对 Rust dlopen 不可见）。
pub fn set_native_library_directory(path: &str) {
    if path.is_empty() {
        return;
    }
    let dir = PathBuf::from(path);
    if dir.is_dir() {
        let _ = NATIVE_LIB_DIR.set(dir);
    }
}

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

fn library_candidates(base: &str) -> Vec<PathBuf> {
    #[cfg(windows)]
    {
        vec![PathBuf::from(format!("{base}.dll"))]
    }
    #[cfg(target_os = "macos")]
    {
        vec![
            PathBuf::from(format!("lib{base}.dylib")),
            PathBuf::from(format!("{base}.dylib")),
        ]
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        vec![PathBuf::from(format!("lib{base}.so"))]
    }
}

fn library_search_paths() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Some(dir) = NATIVE_LIB_DIR.get() {
        paths.push(dir.clone());
    }
    if let Ok(dir) = std::env::var("CHUNKUP_NATIVE_DIR") {
        if !dir.is_empty() {
            paths.push(PathBuf::from(dir));
        }
    }
    paths
}

fn candidate_paths(base: &str) -> Vec<PathBuf> {
    let names = library_candidates(base);
    let mut paths = Vec::new();
    for dir in library_search_paths() {
        for name in &names {
            if let Some(file_name) = name.file_name() {
                paths.push(dir.join(file_name));
            }
        }
    }
    paths.extend(names);
    paths
}

fn load_backend(base: &str, available_sym: &[u8], dispatch_sym: &[u8]) -> Option<GpuBackendLib> {
    let mut last_err: Option<String> = None;

    for path in candidate_paths(base) {
        let library = match unsafe { Library::new(&path) } {
            Ok(lib) => lib,
            Err(err) => {
                last_err = Some(format!("{} ({})", path.display(), err));
                continue;
            }
        };

        let is_available = match unsafe { library.get::<GpuIsAvailableFn>(available_sym) } {
            Ok(sym) => *sym,
            Err(err) => {
                last_err = Some(format!("{} missing is_available ({})", path.display(), err));
                continue;
            }
        };
        let dispatch = match unsafe { library.get::<GpuDispatchFn>(dispatch_sym) } {
            Ok(sym) => *sym,
            Err(err) => {
                last_err = Some(format!("{} missing dispatch ({})", path.display(), err));
                continue;
            }
        };

        log::info!("chunkup gpu backend loaded: {} from {}", base, path.display());
        return Some(GpuBackendLib {
            _library: library,
            is_available,
            dispatch,
        });
    }

    if let Some(err) = last_err {
        log::warn!("chunkup gpu backend {} load failed: {}", base, err);
    } else {
        log::warn!("chunkup gpu backend {} not found", base);
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
    let Some(lib) = cuda_lib() else {
        log::info!("chunkup cuda probe: library not loaded");
        return false;
    };
    let ok = unsafe { (lib.is_available)() != 0 };
    if ok {
        log::info!("chunkup cuda probe: device available");
    } else {
        log::warn!("chunkup cuda probe: library loaded but no CUDA device");
    }
    ok
}

pub fn opencl_probe() -> bool {
    let Some(lib) = opencl_lib() else {
        log::info!("chunkup opencl probe: library not loaded");
        return false;
    };
    let ok = unsafe { (lib.is_available)() != 0 };
    if ok {
        log::info!("chunkup opencl probe: device available");
    } else {
        log::warn!("chunkup opencl probe: library loaded but no OpenCL device");
    }
    ok
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

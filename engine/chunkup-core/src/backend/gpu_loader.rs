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

fn load_backend(base: &str, available_sym: &[u8], dispatch_sym: &[u8]) -> Option<GpuBackendLib> {
    for path in library_candidates(base) {
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

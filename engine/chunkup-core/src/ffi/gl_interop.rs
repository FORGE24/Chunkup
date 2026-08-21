use std::path::PathBuf;
use std::sync::OnceLock;

use libloading::Library;

use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

type IsAvailableFn  = unsafe extern "C" fn() -> i32;
type UploadFn       = unsafe extern "C" fn(*const u8, u32) -> u64;
type FreeFn         = unsafe extern "C" fn(u64);
type MeshCountFn    = unsafe extern "C" fn(*const u8, i32, u32, *mut u32) -> i32;
type MeshToVboFn    = unsafe extern "C" fn(*const u8, i32, u32, u32, u32, *const u32, *mut u32) -> i32;
type GlRegisterFn   = unsafe extern "C" fn(u32) -> i32;
type GlUnregisterFn = unsafe extern "C" fn(u32);

struct GlInteropLib {
    _library: Library,
    is_available:         IsAvailableFn,
    upload_block_states: UploadFn,
    free_block_states:   FreeFn,
    mesh_count_only:     MeshCountFn,
    mesh_to_vbo:         MeshToVboFn,
    gl_register:         GlRegisterFn,
    gl_unregister:       GlUnregisterFn,
}

static INTEROP_LIB: OnceLock<Option<GlInteropLib>> = OnceLock::new();

fn library_candidates() -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = Vec::new();

    if let Ok(native_dir) = std::env::var("CHUNKUP_NATIVE_DIR") {
        if !native_dir.is_empty() { dirs.push(PathBuf::from(&native_dir)); }
    }

    #[cfg(target_os = "linux")]
    if let Ok(ld_path) = std::env::var("LD_LIBRARY_PATH") {
        for p in ld_path.split(':') {
            let p = p.trim();
            if !p.is_empty() { dirs.push(PathBuf::from(p)); }
        }
    }

    if let Ok(cwd) = std::current_dir() { dirs.push(cwd); }

    #[cfg(target_os = "linux")]
    dirs.extend([
        PathBuf::from("/usr/lib"), PathBuf::from("/usr/lib64"),
        PathBuf::from("/usr/local/lib"), PathBuf::from("/opt/cuda/lib64"),
    ]);

    dirs.into_iter().flat_map(|dir| {
        #[cfg(windows)]
        { vec![dir.join("chunkup_cuda_interop.dll")] }
        #[cfg(target_os = "macos")]
        { vec![dir.join("libchunkup_cuda_interop.dylib")] }
        #[cfg(all(unix, not(target_os = "macos")))]
        { vec![dir.join("libchunkup_cuda_interop.so")] }
    }).collect()
}

fn load_interop_lib() -> Option<GlInteropLib> {
    for path in library_candidates() {
        log::debug!("chunkup gl_interop: trying {}", path.display());

        let library = match unsafe { Library::new(&path) } {
            Ok(l) => l, Err(_) => continue,
        };

        let is_available         = match unsafe { library.get::<IsAvailableFn>(b"chunkup_interop_is_available\0") }         { Ok(s) => *s, Err(_) => continue };
        let upload_block_states  = match unsafe { library.get::<UploadFn>(b"chunkup_cuda_upload_block_states\0") }            { Ok(s) => *s, Err(_) => continue };
        let free_block_states    = match unsafe { library.get::<FreeFn>(b"chunkup_cuda_free_block_states\0") }                 { Ok(s) => *s, Err(_) => continue };
        let mesh_count_only      = match unsafe { library.get::<MeshCountFn>(b"chunkup_gpu_mesh_count_only\0") }               { Ok(s) => *s, Err(_) => continue };
        let mesh_to_vbo          = match unsafe { library.get::<MeshToVboFn>(b"chunkup_gpu_mesh_to_vbo\0") }                   { Ok(s) => *s, Err(_) => continue };
        let gl_register          = match unsafe { library.get::<GlRegisterFn>(b"chunkup_cuda_gl_register\0") }               { Ok(s) => *s, Err(_) => continue };
        let gl_unregister        = match unsafe { library.get::<GlUnregisterFn>(b"chunkup_cuda_gl_unregister\0") }             { Ok(s) => *s, Err(_) => continue };

        log::info!("chunkup gl_interop: loaded from {}", path.display());
        return Some(GlInteropLib {
            _library: library,
            is_available, upload_block_states, free_block_states,
            mesh_count_only, mesh_to_vbo, gl_register, gl_unregister,
        });
    }
    None
}

fn interop_lib() -> Option<&'static GlInteropLib> {
    INTEROP_LIB.get_or_init(load_interop_lib).as_ref()
}

pub fn available() -> bool {
    interop_lib().is_some_and(|lib| unsafe { (lib.is_available)() != 0 })
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropIsAvailable(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    if available() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropUploadBlockStates(
    mut env: JNIEnv, _class: JClass, block_states: JByteArray,
) -> jlong {
    let lib = match interop_lib() { Some(l) => l, None => return 0 };
    let data = match super::read_byte_array(&mut env, &block_states) {
        Ok(d) => d, Err(_) => return 0,
    };
    if data.is_empty() { return 0; }
    let ptr = unsafe { (lib.upload_block_states)(data.as_ptr(), data.len() as u32) };
    if ptr == 0 { log::warn!("chunkup gl_interop: upload_block_states failed"); }
    ptr as jlong
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropFreeBlockStates(
    _env: JNIEnv, _class: JClass, device_ptr: jlong,
) {
    let lib = match interop_lib() { Some(l) => l, None => return };
    unsafe { (lib.free_block_states)(device_ptr as u64); }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropMeshCountOnlyHost(
    mut env: JNIEnv, _class: JClass, block_states: JByteArray, section_count: jint,
) -> jni::sys::jintArray {
    let lib = match interop_lib() { Some(l) => l, None => return std::ptr::null_mut() };
    let data = match super::read_byte_array(&mut env, &block_states) {
        Ok(d) => d, Err(_) => return std::ptr::null_mut(),
    };
    if data.is_empty() || section_count <= 0 { return std::ptr::null_mut(); }

    let mut counts = vec![0u32; section_count as usize];
    let rc = unsafe {
        (lib.mesh_count_only)(data.as_ptr(), 0, section_count as u32, counts.as_mut_ptr())
    };
    pack_counts(env, rc, &counts)
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropMeshCountOnlyDevice(
    mut env: JNIEnv, _class: JClass, device_ptr: jlong, section_count: jint,
) -> jni::sys::jintArray {
    let lib = match interop_lib() { Some(l) => l, None => return std::ptr::null_mut() };
    if device_ptr == 0 || section_count <= 0 { return std::ptr::null_mut(); }

    let mut counts = vec![0u32; section_count as usize];
    let rc = unsafe {
        (lib.mesh_count_only)(device_ptr as *const u8, 1, section_count as u32, counts.as_mut_ptr())
    };
    pack_counts(env, rc, &counts)
}

fn pack_counts(mut env: JNIEnv, rc: i32, counts: &[u32]) -> jni::sys::jintArray {
    if rc != 0 {
        log::warn!("mesh_count_only rc={}", rc);
        return std::ptr::null_mut();
    }
    let counts_i32: Vec<i32> = counts.iter().map(|c| *c as i32).collect();
    match env.new_int_array(counts_i32.len() as i32) {
        Ok(arr) => {
            if env.set_int_array_region(&arr, 0, &counts_i32).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropGlRegister(
    _env: JNIEnv, _class: JClass, vbo_id: jint,
) -> jboolean {
    let lib = match interop_lib() { Some(l) => l, None => return JNI_FALSE };
    let r = unsafe { (lib.gl_register)(vbo_id as u32) };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropGlUnregister(
    _env: JNIEnv, _class: JClass, vbo_id: jint,
) {
    let lib = match interop_lib() { Some(l) => l, None => return };
    unsafe { (lib.gl_unregister)(vbo_id as u32); }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropMeshToVboHost(
    mut env: JNIEnv, _class: JClass,
    block_states: JByteArray,
    section_count: jint,
    vertex_stride: jint,
    vbo_id: jint,
    vertex_offsets: JIntArray,
    draw_command_buffer: JIntArray,
) -> jint {
    let lib = match interop_lib() { Some(l) => l, None => return -1 };
    let data = match super::read_byte_array(&mut env, &block_states) {
        Ok(d) => d, Err(_) => return -1,
    };
    mesh_to_vbo_dispatch(
        &mut env, lib, data.as_ptr(), 0,
        section_count, vertex_stride, vbo_id,
        &vertex_offsets, &draw_command_buffer,
    )
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInteropMeshToVboDevice(
    mut env: JNIEnv, _class: JClass,
    device_ptr: jlong,
    section_count: jint,
    vertex_stride: jint,
    vbo_id: jint,
    vertex_offsets: JIntArray,
    draw_command_buffer: JIntArray,
) -> jint {
    let lib = match interop_lib() { Some(l) => l, None => return -1 };
    if device_ptr == 0 { return -1; }
    mesh_to_vbo_dispatch(
        &mut env, lib, device_ptr as *const u8, 1,
        section_count, vertex_stride, vbo_id,
        &vertex_offsets, &draw_command_buffer,
    )
}

fn mesh_to_vbo_dispatch(
    env: &mut JNIEnv,
    lib: &GlInteropLib,
    block_states_ptr: *const u8,
    device_component: i32,
    section_count: jint,
    vertex_stride: jint,
    vbo_id: jint,
    vertex_offsets: &JIntArray,
    draw_command_buffer: &JIntArray,
) -> jint {
    if section_count <= 0 { return -1; }

    let offsets = match super::read_int_array(env, vertex_offsets) {
        Ok(o) => o, Err(_) => return -1,
    };
    if offsets.len() != (section_count + 1) as usize { return -1; }

    let mut cmds = vec![0i32; (section_count * 4) as usize];
    let r = unsafe {
        (lib.mesh_to_vbo)(
            block_states_ptr, device_component,
            section_count as u32, vertex_stride as u32, vbo_id as u32,
            offsets.as_ptr() as *const u32,
            cmds.as_mut_ptr() as *mut u32,
        )
    };
    if r == 0 {
        let _ = env.set_int_array_region(draw_command_buffer, 0, &cmds);
    } else {
        log::warn!("mesh_to_vbo rc={} (vbo_id={})", r, vbo_id);
    }
    r as jint
}
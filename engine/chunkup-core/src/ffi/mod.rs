use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use jni::objects::{JByteArray, JByteBuffer, JClass, JObject, JValue};
use jni::sys::{jboolean, jbyte, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::section::{self, SectionMeshResult};
use crate::{dispatch_chunk_stage, dispatch_section_build, generate_chunk_density, generate_chunk_density_batch, generate_surface_thin, initialize, is_available, process_chunk_load, process_chunk_load_batch, shutdown};
use crate::backend::set_native_library_directory;

static SECTION_BUFFERS: LazyLock<Mutex<HashMap<isize, Vec<u8>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn init_logging() {
    let _ = env_logger::try_init();
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeSetNativeLibraryDirectory(
    mut env: JNIEnv,
    _class: JClass,
    path: jni::objects::JString,
) {
    let Ok(dir) = env.get_string(&path) else {
        return;
    };
    let dir: String = dir.into();
    set_native_library_directory(&dir);
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if is_available() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeGetActiveBackend(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let name = crate::active_backend()
        .map(|b| b.name())
        .unwrap_or("none");
    match env.new_string(name) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInitialize(
    mut env: JNIEnv,
    _class: JClass,
    force_gpu: jboolean,
) -> jboolean {
    init_logging();
    crate::set_force_gpu(force_gpu != JNI_FALSE);
    if initialize() {
        JNI_TRUE
    } else {
        let _ = env.throw_new("java/lang/RuntimeException", "chunkup engine init failed");
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeSetForceGpu(
    _env: JNIEnv,
    _class: JClass,
    force_gpu: jboolean,
) {
    crate::set_force_gpu(force_gpu != JNI_FALSE);
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeGetDebugStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jobject {
    let lines = crate::debug_stats_lines();
    let array = match env.new_object_array(lines.len() as i32, "java/lang/String", JObject::null()) {
        Ok(arr) => arr,
        Err(_) => return JObject::null().into_raw(),
    };
    for (index, line) in lines.into_iter().enumerate() {
        let Ok(jline) = env.new_string(line) else {
            return JObject::null().into_raw();
        };
        if env.set_object_array_element(&array, index as i32, jline).is_err() {
            return JObject::null().into_raw();
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    shutdown();
    if let Ok(mut buffers) = SECTION_BUFFERS.lock() {
        buffers.clear();
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeOnChunkGeneration(
    _env: JNIEnv,
    _class: JClass,
    stage_ordinal: i32,
    chunk_x: i32,
    chunk_z: i32,
) -> jboolean {
    if dispatch_chunk_stage(chunk_x, chunk_z, stage_ordinal) {
        JNI_TRUE
    } else if is_available() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeGenerateChunkDensity(
    mut env: JNIEnv,
    _class: JClass,
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
) -> jni::sys::jobject {
    let Some((density, fluid)) = generate_chunk_density(chunk_x, chunk_z, min_y, height, world_seed) else {
        return JObject::null().into_raw();
    };

    let len = density.len();
    if len == 0 || fluid.len() != len {
        return JObject::null().into_raw();
    }

    let Ok(density_array) = env.new_float_array(len as i32) else {
        return JObject::null().into_raw();
    };
    if env.set_float_array_region(&density_array, 0, &density).is_err() {
        return JObject::null().into_raw();
    }

    let Ok(fluid_array) = env.new_byte_array(len as i32) else {
        return JObject::null().into_raw();
    };
    let fluid_i8: Vec<i8> = fluid.iter().map(|b| *b as i8).collect();
    if env.set_byte_array_region(&fluid_array, 0, &fluid_i8).is_err() {
        return JObject::null().into_raw();
    }

    let obj_array = match env.new_object_array(2, "java/lang/Object", JObject::null()) {
        Ok(arr) => arr,
        Err(_) => return JObject::null().into_raw(),
    };
    if env.set_object_array_element(&obj_array, 0, density_array).is_err() {
        return JObject::null().into_raw();
    }
    if env.set_object_array_element(&obj_array, 1, fluid_array).is_err() {
        return JObject::null().into_raw();
    }

    obj_array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeGenerateChunkDensityBatch(
    mut env: JNIEnv,
    _class: JClass,
    chunk_xs: jni::objects::JIntArray,
    chunk_zs: jni::objects::JIntArray,
    min_y: i32,
    height: i32,
    world_seed: i64,
) -> jni::sys::jobject {
    let xs = match read_int_array(&mut env, &chunk_xs) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };
    let zs = match read_int_array(&mut env, &chunk_zs) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };
    if xs.is_empty() || xs.len() != zs.len() {
        return JObject::null().into_raw();
    }

    let chunk_coords: Vec<(i32, i32)> = xs.into_iter().zip(zs).collect();
    let Some(outputs) = generate_chunk_density_batch(&chunk_coords, min_y, height, world_seed) else {
        return JObject::null().into_raw();
    };

    let outer = match env.new_object_array(outputs.len() as i32, "java/lang/Object", JObject::null()) {
        Ok(arr) => arr,
        Err(_) => return JObject::null().into_raw(),
    };

    for (index, (density, fluid)) in outputs.into_iter().enumerate() {
        let len = density.len();
        if len == 0 || fluid.len() != len {
            return JObject::null().into_raw();
        }

        let Ok(density_array) = env.new_float_array(len as i32) else {
            return JObject::null().into_raw();
        };
        if env.set_float_array_region(&density_array, 0, &density).is_err() {
            return JObject::null().into_raw();
        }

        let Ok(fluid_array) = env.new_byte_array(len as i32) else {
            return JObject::null().into_raw();
        };
        let fluid_i8: Vec<i8> = fluid.iter().map(|b| *b as i8).collect();
        if env.set_byte_array_region(&fluid_array, 0, &fluid_i8).is_err() {
            return JObject::null().into_raw();
        }

        let pair = match env.new_object_array(2, "java/lang/Object", JObject::null()) {
            Ok(arr) => arr,
            Err(_) => return JObject::null().into_raw(),
        };
        if env.set_object_array_element(&pair, 0, density_array).is_err() {
            return JObject::null().into_raw();
        }
        if env.set_object_array_element(&pair, 1, fluid_array).is_err() {
            return JObject::null().into_raw();
        }
        if env.set_object_array_element(&outer, index as i32, pair).is_err() {
            return JObject::null().into_raw();
        }
    }

    outer.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeGenerateSurfaceThin(
    mut env: JNIEnv,
    _class: JClass,
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    density: jni::objects::JFloatArray,
    biome_kind: jni::objects::JByteArray,
) -> jni::sys::jbyteArray {
    let density_vec = match read_float_array(&mut env, &density) {
        Ok(data) => data,
        Err(_) => return std::ptr::null_mut(),
    };
    let biome_vec = match read_byte_array(&mut env, &biome_kind) {
        Ok(data) => data,
        Err(_) => return std::ptr::null_mut(),
    };

    let Some(layers) = generate_surface_thin(
        chunk_x,
        chunk_z,
        min_y,
        height,
        world_seed,
        &density_vec,
        &biome_vec,
    ) else {
        return std::ptr::null_mut();
    };

    let Ok(array) = env.new_byte_array(layers.len() as i32) else {
        return std::ptr::null_mut();
    };
    let layers_i8: Vec<i8> = layers.iter().map(|b| *b as i8).collect();
    if env.set_byte_array_region(&array, 0, &layers_i8).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeProcessChunkLoad(
    mut env: JNIEnv,
    _class: JClass,
    stage_ordinal: i32,
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    density: jni::objects::JFloatArray,
) -> jni::sys::jobject {
    let density_vec = match read_float_array(&mut env, &density) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };

    let Some((skylight, face_mask)) = process_chunk_load(
        chunk_x,
        chunk_z,
        stage_ordinal,
        min_y,
        height,
        world_seed,
        &density_vec,
    ) else {
        return JObject::null().into_raw();
    };

    let len = skylight.len();
    if len == 0 || face_mask.len() != len {
        return JObject::null().into_raw();
    }

    let Ok(skylight_array) = env.new_byte_array(len as i32) else {
        return JObject::null().into_raw();
    };
    let skylight_i8: Vec<i8> = skylight.iter().map(|b| *b as i8).collect();
    if env.set_byte_array_region(&skylight_array, 0, &skylight_i8).is_err() {
        return JObject::null().into_raw();
    }

    let Ok(face_array) = env.new_byte_array(len as i32) else {
        return JObject::null().into_raw();
    };
    let face_i8: Vec<i8> = face_mask.iter().map(|b| *b as i8).collect();
    if env.set_byte_array_region(&face_array, 0, &face_i8).is_err() {
        return JObject::null().into_raw();
    }

    let obj_array = match env.new_object_array(2, "java/lang/Object", JObject::null()) {
        Ok(arr) => arr,
        Err(_) => return JObject::null().into_raw(),
    };
    if env.set_object_array_element(&obj_array, 0, skylight_array).is_err() {
        return JObject::null().into_raw();
    }
    if env.set_object_array_element(&obj_array, 1, face_array).is_err() {
        return JObject::null().into_raw();
    }

    obj_array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeProcessChunkLoadBatch(
    mut env: JNIEnv,
    _class: JClass,
    stage_ordinal: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    chunk_xs: jni::objects::JIntArray,
    chunk_zs: jni::objects::JIntArray,
    densities: jni::objects::JFloatArray,
) -> jni::sys::jobject {
    let xs = match read_int_array(&mut env, &chunk_xs) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };
    let zs = match read_int_array(&mut env, &chunk_zs) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };
    if xs.is_empty() || xs.len() != zs.len() {
        return JObject::null().into_raw();
    }

    let density_vec = match read_float_array(&mut env, &densities) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };

    let chunk_coords: Vec<(i32, i32)> = xs.into_iter().zip(zs).collect();
    let Some(outputs) = process_chunk_load_batch(
        stage_ordinal,
        min_y,
        height,
        world_seed,
        &chunk_coords,
        &density_vec,
    ) else {
        return JObject::null().into_raw();
    };

    if outputs.len() != chunk_coords.len() {
        return JObject::null().into_raw();
    }

    let outer = match env.new_object_array(outputs.len() as i32, "java/lang/Object", JObject::null()) {
        Ok(arr) => arr,
        Err(_) => return JObject::null().into_raw(),
    };

    for (index, (skylight, face_mask)) in outputs.into_iter().enumerate() {
        let pair = match build_chunk_load_pair_array(&mut env, &skylight, &face_mask) {
            Ok(obj) => obj,
            Err(_) => return JObject::null().into_raw(),
        };
        if env.set_object_array_element(&outer, index as i32, pair).is_err() {
            return JObject::null().into_raw();
        }
    }

    outer.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeOnSectionBuild(
    mut env: JNIEnv,
    _class: JClass,
    section_x: i32,
    section_y: i32,
    section_z: i32,
    block_states: JByteArray,
) -> jni::sys::jobject {
    let states = match read_byte_array(&mut env, &block_states) {
        Ok(data) => data,
        Err(_) => return JObject::null().into_raw(),
    };

    let result = dispatch_section_build(section_x, section_y, section_z, &states);
    match build_section_payload_array(&mut env, result) {
        Ok(array) => array.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_client_bridge_ClientEngineBridge_nativeReleaseSectionBuffer(
    mut env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) {
    let byte_buffer: JByteBuffer = buffer.into();
    let Ok(address) = env.get_direct_buffer_address(&byte_buffer) else {
        return;
    };
    if address.is_null() {
        return;
    }
    if let Ok(mut buffers) = SECTION_BUFFERS.lock() {
        if let Some(vec) = buffers.remove(&(address as isize)) {
            drop(vec);
        }
    }
}

#[no_mangle]
pub extern "C" fn chunkup_engine_initialize() -> i32 {
    init_logging();
    if initialize() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "C" fn chunkup_engine_shutdown() {
    shutdown();
}

#[no_mangle]
pub extern "C" fn chunkup_engine_is_available() -> i32 {
    if is_available() {
        1
    } else {
        0
    }
}

#[repr(C)]
pub struct ChunkupSectionBuildResult {
    pub kind: u8,
    pub vertex_len: u32,
    pub vertex_data: *mut u8,
    pub vertex_segments: [i32; section::VERTEX_SEGMENTS_LEN],
    pub visibility: [u64; 4],
    pub ready: u8,
}

#[no_mangle]
pub extern "C" fn chunkup_engine_build_section(
    _section_x: i32,
    _section_y: i32,
    _section_z: i32,
    block_states: *const u8,
    block_states_len: u32,
    out: *mut ChunkupSectionBuildResult,
) -> i32 {
    if block_states.is_null() || out.is_null() || block_states_len as usize != section::BLOCKS_PER_SECTION {
        return 0;
    }

    let states = unsafe { std::slice::from_raw_parts(block_states, block_states_len as usize) };
    let mesh = dispatch_section_build(0, 0, 0, states);
    let mut vertex_data = mesh.vertex_data;
    let vertex_ptr = if vertex_data.is_empty() {
        std::ptr::null_mut()
    } else {
        vertex_data.as_mut_ptr()
    };
    let vertex_len = vertex_data.len() as u32;
    std::mem::forget(vertex_data);

    unsafe {
        (*out).kind = mesh.kind as u8;
        (*out).vertex_len = vertex_len;
        (*out).vertex_data = vertex_ptr;
        (*out).vertex_segments = mesh.vertex_segments;
        (*out).visibility = mesh.visibility;
        (*out).ready = mesh.ready as u8;
    }
    1
}

fn read_byte_array(env: &mut JNIEnv, array: &JByteArray) -> jni::errors::Result<Vec<u8>> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0u8; len];
    env.get_byte_array_region(array, 0, unsafe {
        std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut jbyte, len)
    })?;
    Ok(buf)
}

fn read_float_array(env: &mut JNIEnv, array: &jni::objects::JFloatArray) -> jni::errors::Result<Vec<f32>> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0f32; len];
    env.get_float_array_region(array, 0, &mut buf)?;
    Ok(buf)
}

fn read_int_array(env: &mut JNIEnv, array: &jni::objects::JIntArray) -> jni::errors::Result<Vec<i32>> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0i32; len];
    env.get_int_array_region(array, 0, &mut buf)?;
    Ok(buf)
}

fn build_chunk_load_pair_array<'local>(
    env: &mut JNIEnv<'local>,
    skylight: &[u8],
    face_mask: &[u8],
) -> Result<JObject<'local>, ()> {
    let len = skylight.len();
    if len == 0 || face_mask.len() != len {
        return Err(());
    }

    let skylight_array = env.new_byte_array(len as i32).map_err(|_| ())?;
    let skylight_i8: Vec<i8> = skylight.iter().map(|b| *b as i8).collect();
    env.set_byte_array_region(&skylight_array, 0, &skylight_i8)
        .map_err(|_| ())?;

    let face_array = env.new_byte_array(len as i32).map_err(|_| ())?;
    let face_i8: Vec<i8> = face_mask.iter().map(|b| *b as i8).collect();
    env.set_byte_array_region(&face_array, 0, &face_i8)
        .map_err(|_| ())?;

    let obj_array = env
        .new_object_array(2, "java/lang/Object", JObject::null())
        .map_err(|_| ())?;
    env.set_object_array_element(&obj_array, 0, skylight_array)
        .map_err(|_| ())?;
    env.set_object_array_element(&obj_array, 1, face_array)
        .map_err(|_| ())?;

    Ok(obj_array.into())
}

fn build_section_payload_array<'local>(
    env: &mut JNIEnv<'local>,
    result: SectionMeshResult,
) -> jni::errors::Result<JObject<'local>> {
    let kind = env.new_object(
        "java/lang/Integer",
        "(I)V",
        &[JValue::Int(result.kind as i32)],
    )?;

    let vertex_buffer = if result.vertex_data.is_empty() {
        let empty: *mut u8 = std::ptr::null_mut();
        unsafe { env.new_direct_byte_buffer(empty, 0)? }
    } else {
        let mut data = result.vertex_data;
        let ptr = data.as_mut_ptr();
        let len = data.len();
        if let Ok(mut buffers) = SECTION_BUFFERS.lock() {
            buffers.insert(ptr as isize, data);
        }
        unsafe { env.new_direct_byte_buffer(ptr, len)? }
    };

    let segments = env.new_int_array(section::VERTEX_SEGMENTS_LEN as i32)?;
    env.set_int_array_region(&segments, 0, &result.vertex_segments)?;

    let visibility = env.new_long_array(4)?;
    let visibility_i64: [i64; 4] = result.visibility.map(|v| v as i64);
    env.set_long_array_region(&visibility, 0, &visibility_i64)?;

    let ready = env.new_object(
        "java/lang/Boolean",
        "(Z)V",
        &[JValue::Bool(result.ready as u8)],
    )?;

    let obj_array = env.new_object_array(5, "java/lang/Object", JObject::null())?;
    env.set_object_array_element(&obj_array, 0, kind)?;
    env.set_object_array_element(&obj_array, 1, vertex_buffer)?;
    env.set_object_array_element(&obj_array, 2, segments)?;
    env.set_object_array_element(&obj_array, 3, visibility)?;
    env.set_object_array_element(&obj_array, 4, ready)?;

    Ok(obj_array.into())
}

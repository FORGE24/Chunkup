use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use jni::objects::{JByteArray, JByteBuffer, JClass, JObject, JValue};
use jni::sys::{jboolean, jbyte, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::section::{self, SectionMeshResult};
use crate::{dispatch_chunk_stage, dispatch_section_build, generate_chunk_density, initialize, is_available, shutdown};

static SECTION_BUFFERS: LazyLock<Mutex<HashMap<isize, Vec<u8>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn init_logging() {
    let _ = env_logger::try_init();
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
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeInitialize(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    init_logging();
    if initialize() {
        JNI_TRUE
    } else {
        let _ = env.throw_new("java/lang/RuntimeException", "chunkup engine init failed");
        JNI_FALSE
    }
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

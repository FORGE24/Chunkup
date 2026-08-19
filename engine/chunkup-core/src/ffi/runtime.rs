//! ChunkRuntime 的 JNI 导出层。
//!
//! 通过全局单例 `Mutex<Option<ChunkRuntime>>` 暴露 runtime 状态机给 Kotlin/Java。
//! Minecraft 客户端主线程调用,非并发安全场景。
//!
//! ## 函数映射
//!
//! | Kotlin (JniBridge.nativeRuntimeXxx) | Rust (ChunkRuntime) |
//! |--------------------------------------|---------------------|
//! | `nativeRuntimeCreate()` | `ChunkRuntime::new()` |
//! | `nativeRuntimeShutdown()` | drop 全局单例 |
//! | `nativeRuntimeRegisterArchived(dim,x,z)` | `register_archived(ChunkId)` |
//! | `nativeRuntimeBeginCpuLoad(dim,x,z)` | `begin_cpu_load(ChunkId)` |
//! | `nativeRuntimeFinishCpuLoad(dim,x,z,payload)` | `finish_cpu_load(ChunkId, Box<[u8]>)` |
//! | `nativeRuntimeBeginGpuStage(dim,x,z)` | `begin_gpu_stage(ChunkId)` |
//! | `nativeRuntimeFinishGpuStage(dim,x,z,gpuId,size)` | `finish_gpu_stage(ChunkId, GpuBufferHandle)` |
//! | `nativeRuntimeChunkDataLocation(dim,x,z)` | `chunk_data_location(ChunkId) -> DataLocation` |
//! | `nativeRuntimeStats()` | `[slot_count, cpu_bytes, gpu_bytes]` |

use std::sync::{LazyLock, Mutex};

use jni::objects::{JClass, JByteArray};
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use chunkup_cwa::id::ChunkId;
use chunkup_runtime::{ChunkRuntime, DataLocation, GpuBufferHandle};

/// 全局 runtime 单例。
static RUNTIME: LazyLock<Mutex<Option<ChunkRuntime>>> = LazyLock::new(|| Mutex::new(None));

/// 从 dim/x/z 构造 ChunkId。
fn chunk_id(dim: jint, x: jint, z: jint) -> ChunkId {
    ChunkId::new(dim as u8, x, z)
}

/// 锁定 runtime 并执行闭包。若 runtime 未创建或锁中毒返回 None。
fn with_runtime<R>(f: impl FnOnce(&mut ChunkRuntime) -> R) -> Option<R> {
    let mut guard = RUNTIME.lock().ok()?;
    let rt = guard.as_mut()?;
    Some(f(rt))
}

// =========================================================================
// JNI 导出函数
// =========================================================================

/// 创建全局 ChunkRuntime 单例。返回 true 表示成功(重复调用安全)。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let mut guard = match RUNTIME.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    if guard.is_none() {
        *guard = Some(ChunkRuntime::new());
    }
    JNI_TRUE
}

/// 销毁全局 runtime。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut guard) = RUNTIME.lock() {
        *guard = None;
    }
}

/// 注册一个 Archived chunk(磁盘有,内存无)。返回 true 表示新增。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeRegisterArchived(
    _env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
) -> jboolean {
    let id = chunk_id(dim, x, z);
    match with_runtime(|rt| rt.register_archived(id)) {
        Some(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Archived → CpuLoading:开始从磁盘加载 payload。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeBeginCpuLoad(
    _env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
) -> jboolean {
    let id = chunk_id(dim, x, z);
    match with_runtime(|rt| rt.begin_cpu_load(id)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// CpuLoading → CpuResident:完成 CPU 加载,写入 payload。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeFinishCpuLoad(
    mut env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
    payload: JByteArray,
) -> jboolean {
    let id = chunk_id(dim, x, z);
    let data = match super::read_byte_array(&mut env, &payload) {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };
    let boxed: Box<[u8]> = data.into_boxed_slice();
    match with_runtime(|rt| rt.finish_cpu_load(id, boxed)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// CpuResident → GpuStaging:开始上传到 GPU。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeBeginGpuStage(
    _env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
) -> jboolean {
    let id = chunk_id(dim, x, z);
    match with_runtime(|rt| rt.begin_gpu_stage(id)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// GpuStaging → GpuResident:完成 GPU 上传,记录 handle。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeFinishGpuStage(
    _env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
    gpu_id: jlong,
    size: jint,
) -> jboolean {
    let id = chunk_id(dim, x, z);
    let handle = GpuBufferHandle::new(gpu_id as u64, size as u32);
    match with_runtime(|rt| rt.finish_gpu_stage(id, handle)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// 查询 chunk 数据所在地:0=Absent, 1=Cpu, 2=Gpu。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeChunkDataLocation(
    _env: JNIEnv,
    _class: JClass,
    dim: jint,
    x: jint,
    z: jint,
) -> jint {
    let id = chunk_id(dim, x, z);
    match with_runtime(|rt| rt.chunk_data_location(id)) {
        Some(DataLocation::Absent) => 0,
        Some(DataLocation::Cpu) => 1,
        Some(DataLocation::Gpu) => 2,
        None => 0,
    }
}

/// 返回 runtime 统计:[slot_count, cpu_resident_bytes, gpu_resident_bytes]。
/// runtime 未创建时返回 null。
#[no_mangle]
pub extern "system" fn Java_cn_sanrolnet_chunkup_bridge_JniBridge_nativeRuntimeStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jlongArray {
    let stats = match with_runtime(|rt| {
        (
            rt.len() as jlong,
            rt.cpu_resident_bytes() as jlong,
            rt.gpu_resident_bytes() as jlong,
        )
    }) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let values: [jlong; 3] = [stats.0, stats.1, stats.2];
    match env.new_long_array(3) {
        Ok(arr) => {
            if env.set_long_array_region(&arr, 0, &values).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

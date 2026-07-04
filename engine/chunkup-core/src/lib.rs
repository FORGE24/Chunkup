//! Chunkup 核心引擎

pub mod backend;
pub mod compression;
pub mod culling;
pub mod ffi;
pub mod io;
pub mod kernel;
pub mod lighting;
pub mod memory;
pub mod noise;
pub mod section;

use std::sync::Mutex;

use backend::{BackendKind, EngineContext};
use kernel::{KernelJob, Stage};

static ENGINE: Mutex<Option<EngineContext>> = Mutex::new(None);

const DEFAULT_WORLD_SEED: u32 = 0x10F0_0001;

pub fn initialize() -> bool {
    let ctx = EngineContext::bootstrap();
    log::info!(
        "chunkup engine online: backend={}",
        ctx.active_backend().name()
    );
    match ENGINE.lock() {
        Ok(mut slot) => {
            *slot = Some(ctx);
            true
        }
        Err(_) => false,
    }
}

pub fn shutdown() {
    if let Ok(mut slot) = ENGINE.lock() {
        if let Some(ctx) = slot.take() {
            ctx.shutdown();
        }
    }
}

pub fn is_available() -> bool {
    ENGINE
        .lock()
        .ok()
        .is_some_and(|slot| slot.is_some())
}

pub fn active_backend() -> Option<BackendKind> {
    ENGINE
        .lock()
        .ok()
        .and_then(|slot| slot.as_ref().map(|ctx| ctx.active_backend()))
}

pub fn dispatch_chunk_stage(chunk_x: i32, chunk_z: i32, stage_ordinal: i32) -> bool {
    let Some(stage) = Stage::from_ordinal(stage_ordinal) else {
        return false;
    };

    let Ok(slot) = ENGINE.lock() else {
        return false;
    };
    let Some(ctx) = slot.as_ref() else {
        return false;
    };

    let job = KernelJob::for_chunk_stage(chunk_x, chunk_z, stage, DEFAULT_WORLD_SEED);
    if job.op_mask == 0 {
        return true;
    }

    ctx.kernel().dispatch(&job).is_ok()
}

pub fn dispatch_section_build(
    section_x: i32,
    section_y: i32,
    section_z: i32,
    block_states: &[u8],
) -> section::SectionMeshResult {
    let _ = (section_x, section_y, section_z);
    section::build_section_mesh(block_states)
}

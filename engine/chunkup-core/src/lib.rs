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
pub mod sl_log;
pub mod stats;

use std::sync::Mutex;

use backend::{BackendKind, EngineContext};
use kernel::{KernelJob, Stage};
use kernel::types::BLOCKS_PER_SECTION;
use kernel::workspace::KernelWorkspace;

static ENGINE: Mutex<Option<EngineContext>> = Mutex::new(None);

const DEFAULT_WORLD_SEED: u32 = 0x10F0_0001;

pub fn initialize() -> bool {
    let ctx = EngineContext::bootstrap();
    let backend = ctx.active_backend().name();
    sl_log::info_init(
        "Engine Bootstrap Module",
        "Chunkup compute engine initialized",
        &format!("Backend={backend},ForceGpu={}", stats::force_gpu()),
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

pub fn set_force_gpu(enabled: bool) {
    stats::set_force_gpu(enabled);
}

pub fn debug_stats_lines() -> Vec<String> {
    stats::debug_lines()
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

    ctx.kernel().dispatch(&job).map(|result| {
        log::debug!(
            "chunkup generation backend={} stage={:?} chunk=[{}, {}] ops=0x{:x}",
            ctx.active_backend().name(),
            stage,
            chunk_x,
            chunk_z,
            result.ops_completed
        );
        result
    }).is_ok()
}

pub fn generate_chunk_density(
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
) -> Option<(Vec<f32>, Vec<u8>)> {
    if height <= 0 {
        return None;
    }

    let seed = mix_world_seed(world_seed);
    let job = KernelJob::for_density_fill(chunk_x, chunk_z, min_y, height, seed);

    let Ok(slot) = ENGINE.lock() else {
        return None;
    };
    let ctx = slot.as_ref()?;

    let mut workspace = KernelWorkspace::for_job(&job);
    let expected_len = workspace.density.len();
    ctx.kernel().dispatch_with_workspace(&job, &mut workspace).ok()?;

    if workspace.density.len() != expected_len || workspace.fluid.len() != expected_len {
        return None;
    }

    log::debug!(
        "chunkup density fill backend={} chunk=[{}, {}] min_y={} height={}",
        ctx.active_backend().name(),
        chunk_x,
        chunk_z,
        min_y,
        height
    );

    Some((workspace.density, workspace.fluid))
}

pub fn generate_chunk_density_batch(
    chunk_coords: &[(i32, i32)],
    min_y: i32,
    height: i32,
    world_seed: i64,
) -> Option<Vec<(Vec<f32>, Vec<u8>)>> {
    if height <= 0 || chunk_coords.is_empty() {
        return None;
    }

    let seed = mix_world_seed(world_seed);
    let template_job = KernelJob::for_density_fill(0, 0, min_y, height, seed);
    let batch_count = chunk_coords.len() as i32;
    let blocks_per_chunk = BLOCKS_PER_SECTION as usize * height as usize;
    let mut host_density = vec![0f32; blocks_per_chunk * chunk_coords.len()];
    let mut host_fluid = vec![0u8; blocks_per_chunk * chunk_coords.len()];

    let Ok(slot) = ENGINE.lock() else {
        return None;
    };
    let ctx = slot.as_ref()?;

    let chunk_xs: Vec<i32> = chunk_coords.iter().map(|(x, _)| *x).collect();
    let chunk_zs: Vec<i32> = chunk_coords.iter().map(|(_, z)| *z).collect();

    ctx.kernel()
        .dispatch_density_batch(
            &template_job,
            batch_count,
            &chunk_xs,
            &chunk_zs,
            &mut host_density,
            &mut host_fluid,
            BLOCKS_PER_SECTION * height as u32,
        )
        .ok()?;

    let backend = ctx.active_backend().name();
    sl_log::info_complete(
        "CUDA Density Batch Module",
        "GPU density batch dispatch finished",
        &format!(
            "Backend={backend},BatchCount={batch_count},MinY={min_y},Height={height},BlocksPerChunk={}",
            BLOCKS_PER_SECTION * height as u32
        ),
    );

    let mut outputs = Vec::with_capacity(chunk_coords.len());
    for i in 0..chunk_coords.len() {
        let start = i * blocks_per_chunk;
        let end = start + blocks_per_chunk;
        outputs.push((
            host_density[start..end].to_vec(),
            host_fluid[start..end].to_vec(),
        ));
    }
    Some(outputs)
}

pub fn generate_surface_thin(
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    density: &[f32],
    biome_kind: &[u8],
) -> Option<Vec<u8>> {
    if height <= 0 {
        return None;
    }

    let expected_density = BLOCKS_PER_SECTION as usize * height as usize;
    if density.len() != expected_density || biome_kind.len() != crate::kernel::workspace::SURFACE_COLUMNS {
        return None;
    }

    let seed = mix_world_seed(world_seed);
    let job = KernelJob::for_surface_thin(chunk_x, chunk_z, min_y, height, seed);

    let Ok(slot) = ENGINE.lock() else {
        return None;
    };
    let ctx = slot.as_ref()?;

    let mut workspace = KernelWorkspace::for_job(&job);
    workspace.density.copy_from_slice(density);
    workspace.biome_kind.copy_from_slice(biome_kind);

    ctx.kernel().dispatch_with_workspace(&job, &mut workspace).ok()?;

    log::debug!(
        "chunkup surface thin backend={} chunk=[{}, {}]",
        ctx.active_backend().name(),
        chunk_x,
        chunk_z
    );

    Some(workspace.surface_layers)
}

pub fn process_chunk_load(
    chunk_x: i32,
    chunk_z: i32,
    stage_ordinal: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    density: &[f32],
) -> Option<(Vec<u8>, Vec<u8>)> {
    if height <= 0 {
        return None;
    }

    let stage = Stage::from_ordinal(stage_ordinal)?;
    let seed = mix_world_seed(world_seed);
    let mut job = KernelJob::for_chunk_stage(chunk_x, chunk_z, stage, seed);
    job.min_y = min_y;
    job.height = height;

    if job.op_mask == 0 {
        return Some((Vec::new(), Vec::new()));
    }

    let mut workspace = KernelWorkspace::for_job(&job);
    if density.len() != workspace.density.len() {
        log::warn!(
            "chunkup chunk load density size mismatch: got {} expected {}",
            density.len(),
            workspace.density.len()
        );
        return None;
    }
    workspace.density.copy_from_slice(density);

    let Ok(slot) = ENGINE.lock() else {
        return None;
    };
    let ctx = slot.as_ref()?;

    ctx.kernel().dispatch_with_workspace(&job, &mut workspace).ok()?;

    log::debug!(
        "chunkup chunk load backend={} stage={:?} chunk=[{}, {}] min_y={} height={}",
        ctx.active_backend().name(),
        stage,
        chunk_x,
        chunk_z,
        min_y,
        height
    );

    Some((workspace.skylight, workspace.face_mask))
}

pub fn process_chunk_load_batch(
    stage_ordinal: i32,
    min_y: i32,
    height: i32,
    world_seed: i64,
    chunk_coords: &[(i32, i32)],
    densities: &[f32],
) -> Option<Vec<(Vec<u8>, Vec<u8>)>> {
    if height <= 0 || chunk_coords.is_empty() {
        return None;
    }

    let stage = Stage::from_ordinal(stage_ordinal)?;
    let seed = mix_world_seed(world_seed);
    let mut template_job = KernelJob::for_chunk_stage(0, 0, stage, seed);
    template_job.min_y = min_y;
    template_job.height = height;

    if template_job.op_mask == 0 {
        return Some(vec![(Vec::new(), Vec::new()); chunk_coords.len()]);
    }

    let batch_count = chunk_coords.len() as i32;
    let blocks_per_chunk = BLOCKS_PER_SECTION as usize * height as usize;
    let expected_density = blocks_per_chunk * chunk_coords.len();
    if densities.len() != expected_density {
        log::warn!(
            "chunkup batch density size mismatch: got {} expected {}",
            densities.len(),
            expected_density
        );
        return None;
    }

    let mut host_skylight = vec![0u8; blocks_per_chunk * chunk_coords.len()];
    let mut host_face_mask = vec![0u8; blocks_per_chunk * chunk_coords.len()];

    let Ok(slot) = ENGINE.lock() else {
        return None;
    };
    let ctx = slot.as_ref()?;

    ctx.kernel()
        .dispatch_batch(
            &template_job,
            batch_count,
            densities,
            &mut host_skylight,
            &mut host_face_mask,
            BLOCKS_PER_SECTION * height as u32,
        )
        .ok()?;

    log::debug!(
        "chunkup chunk load batch backend={} stage={:?} count={} min_y={} height={}",
        ctx.active_backend().name(),
        stage,
        batch_count,
        min_y,
        height
    );

    let mut outputs = Vec::with_capacity(chunk_coords.len());
    for i in 0..chunk_coords.len() {
        let start = i * blocks_per_chunk;
        let end = start + blocks_per_chunk;
        outputs.push((
            host_skylight[start..end].to_vec(),
            host_face_mask[start..end].to_vec(),
        ));
    }
    Some(outputs)
}

fn mix_world_seed(world_seed: i64) -> u32 {
    let lo = world_seed as u32;
    let hi = (world_seed >> 32) as u32;
    lo ^ hi.rotate_left(1)
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

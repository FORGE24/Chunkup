use std::os::raw::c_int;

pub const CHUNK_SIZE: u32 = 16;
pub const BLOCKS_PER_SECTION: u32 = CHUNK_SIZE * CHUNK_SIZE;

#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    Biomes = 0,
    NoiseFill = 1,
    Surface = 2,
    Features = 3,
    Generated = 4,
    Loaded = 5,
}

impl Stage {
    pub fn from_ordinal(value: i32) -> Option<Self> {
        match value {
            0 => Some(Self::Biomes),
            1 => Some(Self::NoiseFill),
            2 => Some(Self::Surface),
            3 => Some(Self::Features),
            4 => Some(Self::Generated),
            5 => Some(Self::Loaded),
            _ => None,
        }
    }
}

#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KernelOp {
    NoiseFill = 1 << 0,
    Skylight = 1 << 1,
    Blocklight = 1 << 2,
    FaceCull = 1 << 3,
    SectionMesh = 1 << 4,
    OcclusionPack = 1 << 5,
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct KernelJob {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_y: i32,
    pub height: i32,
    pub seed: u32,
    pub op_mask: u32,
    pub stage: u32,
}

impl KernelJob {
    pub fn for_chunk_stage(chunk_x: i32, chunk_z: i32, stage: Stage, seed: u32) -> Self {
        let stage_u = stage as u32;
        Self {
            chunk_x,
            chunk_z,
            min_y: -64,
            height: 384,
            seed,
            op_mask: unsafe { chunkup_kernel_ops_for_stage(stage_u) },
            stage: stage_u,
        }
    }

    pub fn for_density_fill(
        chunk_x: i32,
        chunk_z: i32,
        min_y: i32,
        height: i32,
        seed: u32,
    ) -> Self {
        Self {
            chunk_x,
            chunk_z,
            min_y,
            height,
            seed,
            op_mask: KernelOp::NoiseFill as u32,
            stage: Stage::NoiseFill as u32,
        }
    }
}

#[repr(C)]
pub struct KernelBuffers {
    pub density: *mut f32,
    pub fluid: *mut u8,
    pub skylight: *mut u8,
    pub blocklight: *mut u8,
    pub face_mask: *mut u8,
    pub stride_y: u32,
}

impl KernelBuffers {
    pub fn from_workspace(workspace: &mut super::workspace::KernelWorkspace) -> Self {
        Self {
            density: workspace.density.as_mut_ptr(),
            fluid: workspace.fluid.as_mut_ptr(),
            skylight: workspace.skylight.as_mut_ptr(),
            blocklight: workspace.blocklight.as_mut_ptr(),
            face_mask: workspace.face_mask.as_mut_ptr(),
            stride_y: workspace.stride_y,
        }
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct KernelResult {
    pub status: c_int,
    pub ops_completed: u32,
}

extern "C" {
    pub fn chunkup_kernel_ops_for_stage(stage: u32) -> u32;
    pub fn chunkup_kernel_density_bytes(height: u32) -> u32;
    pub fn chunkup_kernel_light_bytes(height: u32) -> u32;
    pub fn chunkup_kernel_face_mask_bytes(height: u32) -> u32;
    pub fn chunkup_kernel_dispatch_cpu(
        job: *const KernelJob,
        buffers: *mut KernelBuffers,
        result: *mut KernelResult,
    ) -> c_int;
}

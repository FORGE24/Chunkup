use super::types::{KernelJob, BLOCKS_PER_SECTION};

pub struct KernelWorkspace {
    pub density: Vec<f32>,
    pub fluid: Vec<u8>,
    pub skylight: Vec<u8>,
    pub blocklight: Vec<u8>,
    pub face_mask: Vec<u8>,
    pub stride_y: u32,
}

impl KernelWorkspace {
    pub fn new(height: i32) -> Self {
        let height = height.max(1) as usize;
        let block_count = BLOCKS_PER_SECTION as usize * height;
        Self {
            density: vec![0.0; block_count],
            fluid: vec![0; block_count],
            skylight: vec![0; block_count],
            blocklight: vec![0; block_count],
            face_mask: vec![0; block_count],
            stride_y: BLOCKS_PER_SECTION,
        }
    }

    pub fn for_job(job: &KernelJob) -> Self {
        Self::new(job.height)
    }
}

//! Section mesh generation and occlusion visibility packing.

mod mesh;
mod mesher;
mod occlusion;
mod vertex;

pub use mesh::{build_section_mesh, SectionKind, SectionMeshResult};
pub use occlusion::pack_occlusion;

pub const BLOCKS_PER_SECTION: usize = 16 * 16 * 16;
pub const VERTEX_STRIDE: usize = 20;
pub const VERTEX_SEGMENTS_LEN: usize = 14;

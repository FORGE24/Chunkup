#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SectionKind {
    AirOnly = 0,
    SolidUniform = 1,
    FluidHeavy = 2,
    Mixed = 3,
}

pub struct SectionMeshResult {
    pub kind: SectionKind,
    pub vertex_data: Vec<u8>,
    pub vertex_segments: [i32; super::VERTEX_SEGMENTS_LEN],
    pub visibility: [u64; 4],
    pub ready: bool,
}

pub fn build_section_mesh(block_states: &[u8]) -> SectionMeshResult {
    if block_states.len() != super::BLOCKS_PER_SECTION {
        return empty_result(SectionKind::Mixed);
    }

    let mut first_non_air = None;
    let mut all_same_opaque = true;
    let mut fluid_count = 0u32;
    let mut opaque_count = 0u32;

    for &state in block_states {
        match state {
            0 => {}
            1 => opaque_count += 1,
            2 => fluid_count += 1,
            _ => {}
        }
        if state == 0 {
            continue;
        }
        match first_non_air {
            None => first_non_air = Some(state),
            Some(first) if first != state => all_same_opaque = false,
            _ => {}
        }
    }

    if first_non_air.is_none() {
        return SectionMeshResult {
            kind: SectionKind::AirOnly,
            vertex_data: Vec::new(),
            vertex_segments: [0; super::VERTEX_SEGMENTS_LEN],
            visibility: super::occlusion::fully_visible(),
            ready: true,
        };
    }

    if fluid_count > opaque_count {
        return SectionMeshResult {
            kind: SectionKind::FluidHeavy,
            vertex_data: Vec::new(),
            vertex_segments: [0; super::VERTEX_SEGMENTS_LEN],
            visibility: super::occlusion::pack_occlusion(block_states),
            ready: false,
        };
    }

    let uniform_shell = all_same_opaque && opaque_count > 0 && fluid_count == 0;
    let mesh = super::mesher::build_culled_mesh(block_states, uniform_shell);
    let has_vertices = !mesh.vertices.is_empty();

    SectionMeshResult {
        kind: if uniform_shell {
            SectionKind::SolidUniform
        } else {
            SectionKind::Mixed
        },
        vertex_data: mesh.vertices,
        vertex_segments: mesh.segments,
        visibility: super::occlusion::pack_occlusion(block_states),
        ready: has_vertices || uniform_shell,
    }
}

fn empty_result(kind: SectionKind) -> SectionMeshResult {
    SectionMeshResult {
        kind,
        vertex_data: Vec::new(),
        vertex_segments: [0; super::VERTEX_SEGMENTS_LEN],
        visibility: super::occlusion::fully_visible(),
        ready: false,
    }
}

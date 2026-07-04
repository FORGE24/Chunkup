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
    let mut all_same = true;
    let mut fluid_count = 0u32;
    let mut opaque_count = 0u32;

    for &state in block_states {
        if state == 0 {
            continue;
        }
        if state == 2 {
            fluid_count += 1;
        } else {
            opaque_count += 1;
        }
        match first_non_air {
            None => first_non_air = Some(state),
            Some(first) if first != state => all_same = false,
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

    if all_same && opaque_count > 0 && fluid_count == 0 {
        return SectionMeshResult {
            kind: SectionKind::SolidUniform,
            vertex_data: stub_vertices(),
            vertex_segments: stub_segments(),
            visibility: super::occlusion::pack_occlusion(block_states),
            ready: true,
        };
    }

    if fluid_count > opaque_count {
        return SectionMeshResult {
            kind: SectionKind::FluidHeavy,
            vertex_data: stub_vertices(),
            vertex_segments: stub_segments(),
            visibility: super::occlusion::pack_occlusion(block_states),
            ready: true,
        };
    }

    SectionMeshResult {
        kind: SectionKind::Mixed,
        vertex_data: stub_vertices(),
        vertex_segments: stub_segments(),
        visibility: super::occlusion::pack_occlusion(block_states),
        ready: true,
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

fn stub_vertices() -> Vec<u8> {
    vec![0u8; super::VERTEX_STRIDE * 4]
}

fn stub_segments() -> [i32; super::VERTEX_SEGMENTS_LEN] {
    let mut segments = [0i32; super::VERTEX_SEGMENTS_LEN];
    segments[0] = 4;
    segments[1] = 2;
    segments
}

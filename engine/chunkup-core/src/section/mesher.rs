//! 6-face culling + 简易 AO，输出 Sodium 兼容 quad 顶点流（按 ModelQuadFacing 分组）。

use super::vertex::{self, write_vertex};

const FACING_COUNT: usize = 7;

// ModelQuadFacing: POS_X=0, POS_Y=1, POS_Z=2, NEG_X=3, NEG_Y=4, NEG_Z=5
const FACE_DEFS: [(usize, i32, i32, i32); 6] = [
    (3, -1, 0, 0), // NEG_X
    (0, 1, 0, 0),  // POS_X
    (4, 0, -1, 0), // NEG_Y
    (1, 0, 1, 0),  // POS_Y
    (5, 0, 0, -1), // NEG_Z
    (2, 0, 0, 1),  // POS_Z
];

pub struct MeshBuild {
    pub vertices: Vec<u8>,
    pub segments: [i32; super::VERTEX_SEGMENTS_LEN],
}

pub fn build_culled_mesh(block_states: &[u8], uniform_shell: bool) -> MeshBuild {
    let mut buckets = [const { Vec::new() }; FACING_COUNT];

    if uniform_shell {
        emit_uniform_shell(&mut buckets);
    } else {
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let state = block_states[block_index(x, y, z)];
                    if state != 1 {
                        continue;
                    }
                    for &(facing, dx, dy, dz) in &FACE_DEFS {
                        if !face_visible(block_states, x, y, z, dx, dy, dz) {
                            continue;
                        }
                        let ao = vertex_ao(block_states, x, y, z, dx, dy, dz);
                        let color = vertex::ao_color(0xFF_C0_C0_C0, ao);
                        emit_block_face(&mut buckets[facing], x, y, z, dx, dy, dz, color);
                    }
                }
            }
        }
    }

    merge_facing_buckets(&buckets)
}

fn merge_facing_buckets(buckets: &[Vec<u8>; FACING_COUNT]) -> MeshBuild {
    let mut vertices = Vec::new();
    let mut counts = [0i32; FACING_COUNT];

    for (facing, bucket) in buckets.iter().enumerate() {
        if bucket.is_empty() {
            continue;
        }
        counts[facing] = (bucket.len() / super::VERTEX_STRIDE) as i32;
        vertices.extend_from_slice(bucket);
    }

    MeshBuild {
        vertices,
        segments: build_segments(&counts),
    }
}

fn emit_uniform_shell(buckets: &mut [Vec<u8>; FACING_COUNT]) {
    let color = vertex::ao_color(0xFF_90_90_90, 3);
    let light = vertex::encode_light(240, 0);
    let material = 0u32;
    let section = 0u32;

    for &(facing, dx, dy, dz) in &FACE_DEFS {
        let quad = shell_quad(dx, dy, dz);
        for (x, y, z) in quad {
            write_vertex(&mut buckets[facing], x, y, z, color, 0, 0, light, material, section);
        }
    }
}

fn shell_quad(dx: i32, dy: i32, dz: i32) -> [(f32, f32, f32); 4] {
    match (dx, dy, dz) {
        (1, 0, 0) => [(16.0, 0.0, 0.0), (16.0, 16.0, 0.0), (16.0, 16.0, 16.0), (16.0, 0.0, 16.0)],
        (-1, 0, 0) => [(0.0, 0.0, 16.0), (0.0, 16.0, 16.0), (0.0, 16.0, 0.0), (0.0, 0.0, 0.0)],
        (0, 1, 0) => [(0.0, 16.0, 16.0), (16.0, 16.0, 16.0), (16.0, 16.0, 0.0), (0.0, 16.0, 0.0)],
        (0, -1, 0) => [(0.0, 0.0, 0.0), (16.0, 0.0, 0.0), (16.0, 0.0, 16.0), (0.0, 0.0, 16.0)],
        (0, 0, 1) => [(16.0, 0.0, 16.0), (16.0, 16.0, 16.0), (0.0, 16.0, 16.0), (0.0, 0.0, 16.0)],
        _ => [(0.0, 0.0, 0.0), (0.0, 16.0, 0.0), (16.0, 16.0, 0.0), (16.0, 0.0, 0.0)],
    }
}

fn emit_block_face(
    vertices: &mut Vec<u8>,
    bx: i32,
    by: i32,
    bz: i32,
    dx: i32,
    dy: i32,
    dz: i32,
    color: u32,
) {
    let light = vertex::encode_light(240, 0);
    let material = 0u32;
    let section = 0u32;
    let (x0, y0, z0) = (bx as f32, by as f32, bz as f32);
    let (x1, y1, z1) = (x0 + 1.0, y0 + 1.0, z0 + 1.0);

    let quad: [(f32, f32, f32); 4] = match (dx, dy, dz) {
        (1, 0, 0) => [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)],
        (-1, 0, 0) => [(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)],
        (0, 1, 0) => [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
        (0, -1, 0) => [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
        (0, 0, 1) => [(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)],
        _ => [(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)],
    };

    for (x, y, z) in quad {
        write_vertex(vertices, x, y, z, color, 0, 0, light, material, section);
    }
}

fn face_visible(blocks: &[u8], x: i32, y: i32, z: i32, dx: i32, dy: i32, dz: i32) -> bool {
    let nx = x + dx;
    let ny = y + dy;
    let nz = z + dz;
    if nx < 0 || ny < 0 || nz < 0 || nx >= 16 || ny >= 16 || nz >= 16 {
        return true;
    }
    blocks[block_index(nx, ny, nz)] != 1
}

fn vertex_ao(blocks: &[u8], x: i32, y: i32, z: i32, dx: i32, dy: i32, dz: i32) -> u32 {
    let mut solid = 0u32;
    for &(ox, oy, oz) in &[(dx, dy, dz), (dx, 0, 0), (0, dy, 0), (0, 0, dz)] {
        let sx = x + ox;
        let sy = y + oy;
        let sz = z + oz;
        if sx >= 0 && sy >= 0 && sz >= 0 && sx < 16 && sy < 16 && sz < 16
            && blocks[block_index(sx, sy, sz)] == 1
        {
            solid += 1;
        }
    }
    solid.min(3)
}

fn block_index(x: i32, y: i32, z: i32) -> usize {
    (x | (z << 4) | (y << 8)) as usize
}

fn build_segments(counts: &[i32; FACING_COUNT]) -> [i32; super::VERTEX_SEGMENTS_LEN] {
    let mut segments = [0i32; super::VERTEX_SEGMENTS_LEN];
    let mut slot = 0usize;
    for (facing, &count) in counts.iter().enumerate() {
        if count <= 0 {
            continue;
        }
        if slot + 1 >= segments.len() {
            break;
        }
        segments[slot] = count;
        segments[slot + 1] = facing as i32;
        slot += 2;
    }
    segments
}

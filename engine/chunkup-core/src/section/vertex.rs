//! Sodium [CompactChunkVertex] 兼容顶点编码（stride = 20）。

pub const STRIDE: usize = super::VERTEX_STRIDE;
pub const POSITION_MAX: i32 = 1 << 20;
pub const TEXTURE_MAX: i32 = 1 << 15;

const MODEL_ORIGIN: f32 = 8.0;
const MODEL_RANGE: f32 = 32.0;

pub fn write_vertex(
    out: &mut Vec<u8>,
    x: f32,
    y: f32,
    z: f32,
    color: u32,
    tex_u: i32,
    tex_v: i32,
    light: u32,
    material: u32,
    section_index: u32,
) {
    let px = quantize_position(x);
    let py = quantize_position(y);
    let pz = quantize_position(z);

    append_u32(out, pack_position_hi(px, py, pz));
    append_u32(out, pack_position_lo(px, py, pz));
    append_u32(out, color);
    append_u32(out, pack_texture(tex_u, tex_v));
    append_u32(out, pack_light_and_data(light, material, section_index));
}

fn quantize_position(v: f32) -> i32 {
    let normalized = (MODEL_ORIGIN + v) / MODEL_RANGE;
    ((normalized * POSITION_MAX as f32) as i32) & 0xFFFFF
}

fn pack_position_hi(x: i32, y: i32, z: i32) -> u32 {
    (((x >> 10) & 0x3FF) as u32)
        | ((((y >> 10) & 0x3FF) as u32) << 10)
        | ((((z >> 10) & 0x3FF) as u32) << 20)
}

fn pack_position_lo(x: i32, y: i32, z: i32) -> u32 {
    ((x & 0x3FF) as u32) | (((y & 0x3FF) as u32) << 10) | (((z & 0x3FF) as u32) << 20)
}

fn pack_texture(u: i32, v: i32) -> u32 {
    ((u as u32) & 0xFFFF) | (((v as u32) & 0xFFFF) << 16)
}

fn pack_light_and_data(light: u32, material: u32, section: u32) -> u32 {
    (light & 0xFFFF) | ((material & 0xFF) << 16) | ((section & 0xFF) << 24)
}

fn append_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

/// 简易顶点 AO：0..3，越大越暗。
pub fn ao_color(base_rgb: u32, ao: u32) -> u32 {
    let scale = match ao {
        0 => 0.40,
        1 => 0.60,
        2 => 0.80,
        _ => 1.00,
    };
    let r = (((base_rgb >> 16) & 0xFF) as f32 * scale) as u32;
    let g = (((base_rgb >> 8) & 0xFF) as f32 * scale) as u32;
    let b = ((base_rgb & 0xFF) as f32 * scale) as u32;
    0xFF000000 | (r << 16) | (g << 8) | b
}

pub fn encode_light(sky: u8, block: u8) -> u32 {
    let sky = sky.clamp(8, 248) as u32;
    let block = block.clamp(8, 248) as u32;
    (block << 0) | (sky << 8)
}

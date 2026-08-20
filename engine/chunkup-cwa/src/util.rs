//! Little-endian 字节读写辅助。
//!
//! CWA 协议要求 byte-exact 偏移且固定 LE 字节序。
//! 不依赖 `#[repr(C)]` 内存布局(设计偏移如 `world_seed @ 0x014=20` 不等于 u64 自然对齐),
//! 而是手动按偏移读写 LE 字节,保证跨平台一致。

#[inline]
pub fn write_u8(buf: &mut [u8], offset: usize, v: u8) {
    buf[offset] = v;
}

#[inline]
pub fn read_u8(buf: &[u8], offset: usize) -> u8 {
    buf[offset]
}

#[inline]
pub fn write_u16(buf: &mut [u8], offset: usize, v: u16) {
    buf[offset..offset + 2].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn read_u16(buf: &[u8], offset: usize) -> u16 {
    let mut a = [0u8; 2];
    a.copy_from_slice(&buf[offset..offset + 2]);
    u16::from_le_bytes(a)
}

#[inline]
pub fn write_u32(buf: &mut [u8], offset: usize, v: u32) {
    buf[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn read_u32(buf: &[u8], offset: usize) -> u32 {
    let mut a = [0u8; 4];
    a.copy_from_slice(&buf[offset..offset + 4]);
    u32::from_le_bytes(a)
}

#[inline]
pub fn write_u64(buf: &mut [u8], offset: usize, v: u64) {
    buf[offset..offset + 8].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn read_u64(buf: &[u8], offset: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&buf[offset..offset + 8]);
    u64::from_le_bytes(a)
}

#[inline]
pub fn write_i32(buf: &mut [u8], offset: usize, v: i32) {
    write_u32(buf, offset, v as u32);
}

#[inline]
pub fn read_i32(buf: &[u8], offset: usize) -> i32 {
    read_u32(buf, offset) as i32
}

#[inline]
pub fn write_bytes(buf: &mut [u8], offset: usize, src: &[u8]) {
    buf[offset..offset + src.len()].copy_from_slice(src);
}

#[inline]
pub fn read_bytes<'a>(buf: &'a [u8], offset: usize, len: usize) -> &'a [u8] {
    &buf[offset..offset + len]
}

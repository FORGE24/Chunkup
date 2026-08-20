//! crc32c 校验封装。
//!
//! 设计 §19:每 4KB payload 一个 crc32c;Descriptor 内嵌 crc32c(payload);
//! Header/RegionIndex 独立 crc32c。

use crc32c::crc32c;

/// 计算数据的 crc32c。
#[inline]
pub fn compute(data: &[u8]) -> u32 {
    crc32c(data)
}

/// 校验数据与预期 crc32c 是否一致。
#[inline]
pub fn verify(data: &[u8], expected: u32) -> bool {
    compute(data) == expected
}

/// 默认校验块大小(4KB)。
pub const DEFAULT_CHECKSUM_BLOCK_SIZE: u32 = 4096;

/// 计算分块校验表。
///
/// 每 `block_size` 字节一个 crc32c,返回所有块的校验值。
pub fn compute_block_table(data: &[u8], block_size: usize) -> Vec<u32> {
    let mut out = Vec::with_capacity((data.len() + block_size - 1) / block_size);
    let mut start = 0;
    while start < data.len() {
        let end = (start + block_size).min(data.len());
        out.push(crc32c(&data[start..end]));
        start = end;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn block_table_basic() {
        let data = vec![0u8; 10_000];
        let table = compute_block_table(&data, 4096);
        assert_eq!(table.len(), 3);
        assert_eq!(table[0], compute(&data[..4096]));
    }
}

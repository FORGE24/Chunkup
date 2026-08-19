//! CWA 压缩后端:zstd(整体 payload) + RLE(block 位图 / air bitmap)。
//!
//! 设计:
//! - chunk payload 整体走 zstd(高压缩比,GPU 解压友好)
//! - block 位图 / SectionFace air_bitmap 用 RLE(单 section 内同值连续,极快)
//! - Header flags 中 [COMPRESSED_PAYLOAD](crate::header::flags::COMPRESSED_PAYLOAD)
//!   控制 chunk payload 是否压缩
//!
//! RLE 格式(变长):
//! ```text
//! [repeat_count: u16 LE][value: u8]
//! ```
//! 末尾不足 16-bit 计数时按字节对展开。解码时 `repeat_count` 0 表示结束。
//! 单段最大长度 65535,超出分段。

use crate::error::{CwaError, CwaResult};
use std::io::Read;

/// zstd 默认压缩级别(1..22,推荐 3-19,3 快速,19 慢但比最高)。
pub const ZSTD_DEFAULT_LEVEL: i32 = 3;

/// zstd 魔数(用于识别是否已被 zstd 编码)。
pub const ZSTD_MAGIC: [u8; 4] = [0x28, 0xB5, 0x2F, 0xFD];

// =========================================================================
// zstd 整体 payload 压缩
// =========================================================================

/// 用 zstd 压缩数据。
///
/// `level` 取值 1..=22,推荐 [ZSTD_DEFAULT_LEVEL]。
pub fn zstd_compress(data: &[u8], level: i32) -> CwaResult<Vec<u8>> {
    zstd::encode_all(data, level).map_err(CwaError::Io)
}

/// 用 zstd 解压数据,指定期望大小用于预分配。
pub fn zstd_decompress(data: &[u8], expected_size: usize) -> CwaResult<Vec<u8>> {
    let mut out = Vec::with_capacity(expected_size);
    zstd::Decoder::new(data)
        .map_err(CwaError::Io)?
        .read_to_end(&mut out)
        .map_err(CwaError::Io)?;
    Ok(out)
}

/// 判断数据是否为 zstd 编码(检查魔数)。
pub fn is_zstd_encoded(data: &[u8]) -> bool {
    data.len() >= 4 && data[..4] == ZSTD_MAGIC
}

// =========================================================================
// RLE 块位图 / air bitmap 压缩
// =========================================================================

/// RLE 单段最大重复数(u16::MAX = 65535)。
pub const RLE_MAX_RUN: usize = u16::MAX as usize;

/// RLE 编码。
///
/// 输入:`data`(任意字节序列,典型为 block palette indices 或 air bitmap)。
/// 输出:紧凑的 `[count_le: u16][value: u8]` 段数组。
pub fn rle_compress(data: &[u8]) -> Vec<u8> {
    if data.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(data.len());
    let mut run_value = data[0];
    let mut run_len: usize = 1;
    for &v in &data[1..] {
        if v == run_value && run_len < RLE_MAX_RUN {
            run_len += 1;
        } else {
            push_rle_segment(&mut out, run_value, run_len);
            run_value = v;
            run_len = 1;
        }
    }
    push_rle_segment(&mut out, run_value, run_len);
    out
}

/// RLE 解码。
///
/// `expected_size` 仅用于预分配容量,实际解码到所有段用完为止。
pub fn rle_decompress(data: &[u8], expected_size: usize) -> CwaResult<Vec<u8>> {
    if data.len() % 3 != 0 {
        return Err(CwaError::SizeInvariant {
            what: "rle_segment_len",
            expected: 0,
            got: data.len() % 3,
        });
    }
    let mut out = Vec::with_capacity(expected_size);
    let mut i = 0;
    while i + 3 <= data.len() {
        let count = u16::from_le_bytes([data[i], data[i + 1]]) as usize;
        let value = data[i + 2];
        out.resize(out.len() + count, value);
        i += 3;
    }
    Ok(out)
}

#[inline]
fn push_rle_segment(out: &mut Vec<u8>, value: u8, len: usize) {
    let mut remaining = len;
    while remaining > 0 {
        let chunk = remaining.min(RLE_MAX_RUN);
        let chunk_le = (chunk as u16).to_le_bytes();
        out.push(chunk_le[0]);
        out.push(chunk_le[1]);
        out.push(value);
        remaining -= chunk;
    }
}

// =========================================================================
// CompressionBackend 抽象(可选后端,默认 zstd)
// =========================================================================

/// 压缩后端。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CompressionBackend {
    /// 不压缩,直接存原始字节。
    None,
    /// zstd(GPU 友好,默认)。
    Zstd,
}

impl CompressionBackend {
    /// 压缩数据。返回 (压缩后字节, 是否实际压缩)。
    ///
    /// 若压缩后比原始大(短数据),返回原始字节 + `false`。
    pub fn compress(&self, data: &[u8], level: i32) -> CwaResult<(Vec<u8>, bool)> {
        match self {
            CompressionBackend::None => Ok((data.to_vec(), false)),
            CompressionBackend::Zstd => {
                if data.len() < 32 {
                    // 太短不压缩
                    return Ok((data.to_vec(), false));
                }
                let compressed = zstd_compress(data, level)?;
                if compressed.len() >= data.len() {
                    Ok((data.to_vec(), false))
                } else {
                    Ok((compressed, true))
                }
            }
        }
    }

    /// 解压数据。`expected_size` 为原始大小(从 descriptor 读)。
    pub fn decompress(&self, data: &[u8], expected_size: usize) -> CwaResult<Vec<u8>> {
        match self {
            CompressionBackend::None => Ok(data.to_vec()),
            CompressionBackend::Zstd => {
                if is_zstd_encoded(data) {
                    zstd_decompress(data, expected_size)
                } else {
                    // 兼容:写入时压缩未生效,直接返回
                    Ok(data.to_vec())
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zstd_roundtrip_small() {
        let data = b"hello chunkup world! The quick brown fox jumps over the lazy dog.";
        let comp = zstd_compress(data, ZSTD_DEFAULT_LEVEL).unwrap();
        assert!(is_zstd_encoded(&comp));
        let decomp = zstd_decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn zstd_roundtrip_large() {
        // 4KB 类似 chunk payload 大小
        let data: Vec<u8> = (0..4096).map(|i| (i % 7) as u8).collect();
        let comp = zstd_compress(&data, ZSTD_DEFAULT_LEVEL).unwrap();
        assert!(comp.len() < data.len(), "应当显著压缩");
        let decomp = zstd_decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn zstd_magic_detection() {
        let data = b"some data here that is at least 32 bytes long for testing magic detection";
        let comp = zstd_compress(data, 3).unwrap();
        assert!(is_zstd_encoded(&comp));
        assert!(!is_zstd_encoded(data));
    }

    #[test]
    fn rle_roundtrip_all_same() {
        // 全 0x42(典型 block palette)
        let data = vec![0x42u8; 4096];
        let comp = rle_compress(&data);
        // 1 段 * 3 字节
        assert_eq!(comp.len(), 3);
        assert_eq!(comp[2], 0x42);
        let decomp = rle_decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn rle_roundtrip_mixed() {
        // 0xAA * 100 + 0xBB * 200 + 0xCC * 50
        let mut data = vec![0xAA; 100];
        data.extend_from_slice(&vec![0xBB; 200]);
        data.extend_from_slice(&vec![0xCC; 50]);
        let comp = rle_compress(&data);
        assert_eq!(comp.len(), 9); // 3 段
        let decomp = rle_decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn rle_max_run_split() {
        // 超过 65535 必须分段
        let data = vec![0x77u8; 70_000];
        let comp = rle_compress(&data);
        // 2 段:65535 + 4465
        assert_eq!(comp.len(), 6);
        let decomp = rle_decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp.len(), 70_000);
        assert_eq!(decomp, data);
    }

    #[test]
    fn rle_empty() {
        let comp = rle_compress(&[]);
        assert!(comp.is_empty());
        let decomp = rle_decompress(&comp, 0).unwrap();
        assert!(decomp.is_empty());
    }

    #[test]
    fn rle_invalid_segment_len_rejected() {
        // 长度不是 3 的倍数
        let bad = [0u8; 4];
        assert!(rle_decompress(&bad, 10).is_err());
    }

    #[test]
    fn backend_zstd_compresses_large() {
        let data: Vec<u8> = (0..2048).map(|i| (i % 3) as u8).collect();
        let (comp, did_compress) = CompressionBackend::Zstd.compress(&data, 3).unwrap();
        assert!(did_compress);
        assert!(comp.len() < data.len());
        let decomp = CompressionBackend::Zstd.decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn backend_zstd_skips_short() {
        let data = b"short data";
        let (comp, did_compress) = CompressionBackend::Zstd.compress(data, 3).unwrap();
        assert!(!did_compress);
        assert_eq!(comp, data);
        // 解压未压缩数据应直接返回
        let decomp = CompressionBackend::Zstd.decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn backend_none_passthrough() {
        let data = b"raw bytes here";
        let (comp, did_compress) = CompressionBackend::None.compress(data, 3).unwrap();
        assert!(!did_compress);
        assert_eq!(comp, data);
        let decomp = CompressionBackend::None.decompress(&comp, data.len()).unwrap();
        assert_eq!(decomp, data);
    }

    #[test]
    fn backend_zstd_block_bitmap_pattern() {
        // 模拟 section 内 block palette indices:大量 0 + 少量非零
        let mut data = vec![0u8; 4096];
        for i in (0..4096).step_by(64) {
            data[i] = 1;
        }
        // 用 RLE 压缩 block 位图
        let rle = rle_compress(&data);
        assert!(rle.len() < data.len() / 4, "RLE 应当显著压缩");
        let decomp = rle_decompress(&rle, data.len()).unwrap();
        assert_eq!(decomp, data);

        // 再用 zstd 压缩,确保整体路径通
        let (zcomp, _) = CompressionBackend::Zstd.compress(&data, 3).unwrap();
        let zdecomp = CompressionBackend::Zstd.decompress(&zcomp, data.len()).unwrap();
        assert_eq!(zdecomp, data);
    }
}

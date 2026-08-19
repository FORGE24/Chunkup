//! server → client face_mask 网络包(设计 §6:面预计算下发)。
//!
//! ## 目的
//!
//! 服务端预先计算好每个 (chunk, section_y, face_dir) 的 256-bit air 位图,
//! 通过此包下发给客户端,避免客户端重复扫描 block 数据做 air 判定。
//! 客户端收包后直接写入 [SectionFace::air_bitmap] 并标 `BITMAP_STALE=0`。
//!
//! ## 二进制布局(LE 字节,复用 CWA 序列化风格)
//!
//! ### Packet Header (16 字节)
//! 偏移  字段               说明
//! 0x00  magic: u32         = `0x46414345`("FACE" ASCII LE)
//! 0x04  version: u16        协议版本(当前 = 1)
//! 0x06  entry_count: u16    FaceMaskEntry 条目数
//! 0x08  section_y_start: u8 本包覆盖的最小 section_y
//! 0x09  section_y_end: u8   本包覆盖的最大 section_y
//! 0x0A  reserved: u16       = 0
//! 0x0C  checksum: u32       crc32c over header(不含本字段) + 全部 entry
//!
//! ### FaceMaskEntry (44 字节,重复 entry_count 次)
//! 偏移  字段               说明
//! 0x00  chunk_id: u64       目标 chunk 全局 ID
//! 0x08  section_y: u8       目标 section_y
//! 0x09  face_dir: u8        [FaceDir] as u8 (0..5)
//! 0x0A  flags: u8           见 [face_mask_flags]
//! 0x0B  reserved: u8        = 0
//! 0x0C  air_bitmap: [u8;32] 256-bit air 位图(与 [SectionFace::air_bitmap] 同语义)

use crate::checksum;
use crate::face::{FaceDir, AIR_BITMAP_SIZE};
use crate::util;

/// FaceMask 包 magic("FACE" ASCII LE)。
pub const FACE_MASK_MAGIC: u32 = 0x46414345;

/// FaceMask 包协议版本。
pub const FACE_MASK_VERSION: u16 = 1;

/// Packet header 大小。
pub const FACE_MASK_HEADER_SIZE: usize = 16;

/// 单个 entry 大小(8 + 1 + 1 + 1 + 1 + 32 = 44 字节)。
pub const FACE_MASK_ENTRY_SIZE: usize = 44;

/// FaceMaskEntry flags。
pub mod face_mask_flags {
    /// 该面全 air(air_bitmap 全 1)。
    pub const ALL_AIR: u8 = 1 << 0;
    /// 邻居 chunk 缺失,按边界空气面处理。
    pub const NEIGHBOR_MISSING: u8 = 1 << 1;
    /// 该面跨 chunk 边界。
    pub const CROSS_CHUNK: u8 = 1 << 2;
}

/// 单个 face_mask 条目(40 字节)。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FaceMaskEntry {
    /// 目标 chunk 全局 ID。
    pub chunk_id: u64,
    /// 目标 section_y。
    pub section_y: u8,
    /// 目标面方向(FaceDir as u8)。
    pub face_dir: u8,
    /// flags,见 [face_mask_flags]。
    pub flags: u8,
    /// 256-bit air 位图。
    pub air_bitmap: [u8; AIR_BITMAP_SIZE],
}

impl FaceMaskEntry {
    /// Entry 字节大小(常量)。
    pub const SIZE: usize = FACE_MASK_ENTRY_SIZE;

    /// 构造全 air 条目。
    pub fn all_air(chunk_id: u64, section_y: u8, face_dir: FaceDir) -> Self {
        FaceMaskEntry {
            chunk_id,
            section_y,
            face_dir: face_dir as u8,
            flags: face_mask_flags::ALL_AIR,
            air_bitmap: [0xFF; AIR_BITMAP_SIZE],
        }
    }

    /// 是否全 air。
    pub fn is_all_air(&self) -> bool {
        self.flags & face_mask_flags::ALL_AIR != 0
    }

    /// 取位图中 (row, col) 处的 air 状态。
    pub fn cell_is_air(&self, row: usize, col: usize) -> bool {
        debug_assert!(row < 16 && col < 16);
        let idx = row * 16 + col;
        self.air_bitmap[idx >> 3] & (1 << (idx & 7)) != 0
    }

    /// 序列化为 LE 字节(44 字节)。
    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u64(&mut buf, 0x00, self.chunk_id);
        util::write_u8(&mut buf, 0x08, self.section_y);
        util::write_u8(&mut buf, 0x09, self.face_dir);
        util::write_u8(&mut buf, 0x0A, self.flags);
        // 0x0B reserved = 0
        buf[0x0C..0x0C + AIR_BITMAP_SIZE].copy_from_slice(&self.air_bitmap);
        buf
    }

    /// 从 LE 字节反序列化(44 字节)。
    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        let mut air_bitmap = [0u8; AIR_BITMAP_SIZE];
        air_bitmap.copy_from_slice(&buf[0x0C..0x0C + AIR_BITMAP_SIZE]);
        FaceMaskEntry {
            chunk_id: util::read_u64(buf, 0x00),
            section_y: util::read_u8(buf, 0x08),
            face_dir: util::read_u8(buf, 0x09),
            flags: util::read_u8(buf, 0x0A),
            air_bitmap,
        }
    }
}

/// FaceMask 包(可序列化/反序列化)。
#[derive(Clone, Debug)]
pub struct FaceMaskPacket {
    /// 覆盖的最小 section_y。
    pub section_y_start: u8,
    /// 覆盖的最大 section_y。
    pub section_y_end: u8,
    /// 条目列表。
    pub entries: Vec<FaceMaskEntry>,
}

impl FaceMaskPacket {
    /// 构造空包。
    pub fn new(section_y_start: u8, section_y_end: u8) -> Self {
        FaceMaskPacket {
            section_y_start,
            section_y_end,
            entries: Vec::new(),
        }
    }

    /// 追加一个条目。
    pub fn push(&mut self, entry: FaceMaskEntry) {
        self.entries.push(entry);
    }

    /// 条目数。
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// 是否为空。
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// 序列化为字节(`header + entries`)。
    ///
    /// checksum = crc32c(header[0..0x0C] + 全部 entry 字节)。
    pub fn to_bytes(&self) -> Vec<u8> {
        let total = FACE_MASK_HEADER_SIZE + self.entries.len() * FACE_MASK_ENTRY_SIZE;
        let mut buf = Vec::with_capacity(total);

        // header(先写除 checksum 外的字段)
        let mut hdr = [0u8; FACE_MASK_HEADER_SIZE];
        util::write_u32(&mut hdr, 0x00, FACE_MASK_MAGIC);
        util::write_u16(&mut hdr, 0x04, FACE_MASK_VERSION);
        util::write_u16(&mut hdr, 0x06, self.entries.len() as u16);
        util::write_u8(&mut hdr, 0x08, self.section_y_start);
        util::write_u8(&mut hdr, 0x09, self.section_y_end);
        // 0x0A reserved = 0
        // checksum 稍后填

        // entries
        for e in &self.entries {
            buf.extend_from_slice(&e.to_bytes());
        }

        // checksum over header[0..0x0C] + entries
        let mut cksum_input = Vec::with_capacity(0x0C + buf.len());
        cksum_input.extend_from_slice(&hdr[0..0x0C]);
        cksum_input.extend_from_slice(&buf);
        let cksum = checksum::compute(&cksum_input);
        util::write_u32(&mut hdr, 0x0C, cksum);

        // 组装:header + entries
        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(&hdr);
        out.extend_from_slice(&buf);
        out
    }

    /// 从字节反序列化。
    ///
    /// 校验 magic / version / checksum,失败返回错误。
    pub fn from_bytes(data: &[u8]) -> Result<Self, FaceMaskError> {
        if data.len() < FACE_MASK_HEADER_SIZE {
            return Err(FaceMaskError::TooShort {
                need: FACE_MASK_HEADER_SIZE,
                have: data.len(),
            });
        }

        let magic = util::read_u32(data, 0x00);
        if magic != FACE_MASK_MAGIC {
            return Err(FaceMaskError::BadMagic { got: magic });
        }

        let version = util::read_u16(data, 0x04);
        if version != FACE_MASK_VERSION {
            return Err(FaceMaskError::UnsupportedVersion { got: version });
        }

        let entry_count = util::read_u16(data, 0x06) as usize;
        let section_y_start = util::read_u8(data, 0x08);
        let section_y_end = util::read_u8(data, 0x09);
        let expected_checksum = util::read_u32(data, 0x0C);

        let entries_start = FACE_MASK_HEADER_SIZE;
        let entries_end = entries_start + entry_count * FACE_MASK_ENTRY_SIZE;
        if data.len() < entries_end {
            return Err(FaceMaskError::TooShort {
                need: entries_end,
                have: data.len(),
            });
        }

        // 校验 checksum:header[0..0x0C] + entries
        let mut cksum_buf = Vec::with_capacity(0x0C + (entries_end - entries_start));
        cksum_buf.extend_from_slice(&data[0..0x0C]);
        cksum_buf.extend_from_slice(&data[entries_start..entries_end]);
        let real_checksum = checksum::compute(&cksum_buf);
        if real_checksum != expected_checksum {
            return Err(FaceMaskError::ChecksumMismatch {
                expected: expected_checksum,
                got: real_checksum,
            });
        }

        // 解析 entries
        let mut entries = Vec::with_capacity(entry_count);
        for i in 0..entry_count {
            let off = entries_start + i * FACE_MASK_ENTRY_SIZE;
            let mut buf = [0u8; FACE_MASK_ENTRY_SIZE];
            buf.copy_from_slice(&data[off..off + FACE_MASK_ENTRY_SIZE]);
            entries.push(FaceMaskEntry::from_bytes(&buf));
        }

        Ok(FaceMaskPacket {
            section_y_start,
            section_y_end,
            entries,
        })
    }
}

/// FaceMask 包错误。
#[derive(Debug, PartialEq, Eq)]
pub enum FaceMaskError {
    /// 数据过短。
    TooShort {
        /// 期望字节数。
        need: usize,
        /// 实际字节数。
        have: usize,
    },
    /// magic 不匹配。
    BadMagic {
        /// 实际 magic 值。
        got: u32,
    },
    /// 不支持的版本。
    UnsupportedVersion {
        /// 实际版本号。
        got: u16,
    },
    /// checksum 不匹配。
    ChecksumMismatch {
        /// 期望 checksum。
        expected: u32,
        /// 实际 checksum。
        got: u32,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cid(x: i32, z: i32) -> u64 {
        ChunkId::new(0, x, z).0
    }

    // 引入 ChunkId 用于测试
    use crate::id::ChunkId;

    #[test]
    fn entry_roundtrip() {
        let mut bm = [0u8; AIR_BITMAP_SIZE];
        bm[0] = 0b10101010;
        bm[31] = 0xFF;
        let e = FaceMaskEntry {
            chunk_id: cid(3, 5),
            section_y: 7,
            face_dir: FaceDir::PosY as u8,
            flags: face_mask_flags::CROSS_CHUNK,
            air_bitmap: bm,
        };
        let b = e.to_bytes();
        assert_eq!(b.len(), FaceMaskEntry::SIZE);
        assert_eq!(b.len(), 44);
        // reserved 必须补零
        assert_eq!(b[0x0B], 0);

        let e2 = FaceMaskEntry::from_bytes(&b);
        assert_eq!(e2, e);
        assert!(e2.cell_is_air(0, 1)); // bit 1
        assert!(!e2.cell_is_air(0, 0));
    }

    #[test]
    fn all_air_entry() {
        let e = FaceMaskEntry::all_air(cid(0, 0), 0, FaceDir::NegY);
        assert!(e.is_all_air());
        for b in &e.air_bitmap {
            assert_eq!(*b, 0xFF);
        }
    }

    #[test]
    fn packet_roundtrip() {
        let mut pkt = FaceMaskPacket::new(3, 7);
        pkt.push(FaceMaskEntry::all_air(cid(0, 0), 3, FaceDir::PosY));
        pkt.push(FaceMaskEntry::all_air(cid(1, 0), 5, FaceDir::NegY));

        let bytes = pkt.to_bytes();
        assert!(bytes.len() >= FACE_MASK_HEADER_SIZE + 2 * FACE_MASK_ENTRY_SIZE);

        let pkt2 = FaceMaskPacket::from_bytes(&bytes).unwrap();
        assert_eq!(pkt2.section_y_start, 3);
        assert_eq!(pkt2.section_y_end, 7);
        assert_eq!(pkt2.entries.len(), 2);
        assert_eq!(pkt2.entries[0], pkt.entries[0]);
        assert_eq!(pkt2.entries[1], pkt.entries[1]);
    }

    #[test]
    fn bad_magic_rejected() {
        let pkt = FaceMaskPacket::new(0, 0);
        let mut bytes = pkt.to_bytes();
        bytes[0] = 0x00; // 破坏 magic
        let err = FaceMaskPacket::from_bytes(&bytes).unwrap_err();
        assert!(matches!(err, FaceMaskError::BadMagic { .. }));
    }

    #[test]
    fn bad_checksum_rejected() {
        let mut pkt = FaceMaskPacket::new(0, 0);
        pkt.push(FaceMaskEntry::all_air(cid(0, 0), 0, FaceDir::PosY));
        let mut bytes = pkt.to_bytes();
        // 翻转 entry 数据中的一个字节(在 header 之后)
        bytes[FACE_MASK_HEADER_SIZE] ^= 0xFF;
        let err = FaceMaskPacket::from_bytes(&bytes).unwrap_err();
        assert!(matches!(err, FaceMaskError::ChecksumMismatch { .. }));
    }

    #[test]
    fn too_short_rejected() {
        let err = FaceMaskPacket::from_bytes(&[0u8; 8]).unwrap_err();
        assert!(matches!(err, FaceMaskError::TooShort { .. }));
    }

    #[test]
    fn empty_packet_roundtrip() {
        let pkt = FaceMaskPacket::new(0, 0);
        let bytes = pkt.to_bytes();
        let pkt2 = FaceMaskPacket::from_bytes(&bytes).unwrap();
        assert!(pkt2.entries.is_empty());
        assert_eq!(pkt2.len(), 0);
    }

    #[test]
    fn mixed_air_and_solid_entries() {
        let mut pkt = FaceMaskPacket::new(0, 1);
        pkt.push(FaceMaskEntry::all_air(cid(0, 0), 0, FaceDir::NegY));
        pkt.push(FaceMaskEntry {
            chunk_id: cid(1, 0),
            section_y: 1,
            face_dir: FaceDir::PosY as u8,
            flags: 0,
            air_bitmap: [0x00; AIR_BITMAP_SIZE], // 全 solid
        });

        let bytes = pkt.to_bytes();
        let pkt2 = FaceMaskPacket::from_bytes(&bytes).unwrap();
        assert!(pkt2.entries[0].is_all_air());
        assert!(!pkt2.entries[1].is_all_air());
    }
}

//! Region / Chunk / Section Descriptor 及 SoA Hot View。
//!
//! 所有结构手写 LE 序列化,不依赖 `#[repr(C)]` 内存布局(设计偏移不等于自然对齐)。

use crate::error::{CwaError, CwaResult};
use crate::util;

// =========================================================================
// RegionIndexEntry (32 字节,设计 §5.1)
// =========================================================================

/// RegionIndexEntry 大小。
pub const REGION_INDEX_ENTRY_SIZE: usize = 32;

/// Region flags(设计 §5.1)。
pub mod region_flags {
    /// region payload 已压缩。
    pub const COMPRESSED: u16 = 1 << 0;
    /// region 已被预读。
    pub const PREFETCHED: u16 = 1 << 1;
}

/// Region 索引项(32 字节)。
#[derive(Clone, Debug, Default)]
pub struct RegionIndexEntry {
    pub region_id: u64,           // 0x00
    pub region_offset: u64,       // 0x08
    pub chunk_count: u16,         // 0x10
    pub section_count: u32,       // 0x12
    pub region_checksum: u32,     // 0x16
    pub flags: u16,               // 0x1A
    // 0x1C reserved u32 = 0
}

impl RegionIndexEntry {
    pub const SIZE: usize = REGION_INDEX_ENTRY_SIZE;

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u64(&mut buf, 0x00, self.region_id);
        util::write_u64(&mut buf, 0x08, self.region_offset);
        util::write_u16(&mut buf, 0x10, self.chunk_count);
        util::write_u32(&mut buf, 0x12, self.section_count);
        util::write_u32(&mut buf, 0x16, self.region_checksum);
        util::write_u16(&mut buf, 0x1A, self.flags);
        // 0x1C reserved = 0
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        RegionIndexEntry {
            region_id: util::read_u64(buf, 0x00),
            region_offset: util::read_u64(buf, 0x08),
            chunk_count: util::read_u16(buf, 0x10),
            section_count: util::read_u32(buf, 0x12),
            region_checksum: util::read_u32(buf, 0x16),
            flags: util::read_u16(buf, 0x1A),
        }
    }
}

// =========================================================================
// ChunkDescriptor (80 字节,设计 §6)
// =========================================================================

/// ChunkDescriptor 大小。
///
/// 设计 §6 标注 64B,但字段总和 80B(0x00..0x50),此处修正为 80B,对齐 16。
/// cache line 对齐在 SoA Hot View(24B)中实现,GPU coalesced 访问目标为 Hot View。
pub const CHUNK_DESC_ENTRY_SIZE: usize = 80;

/// ChunkDescriptor(80 字节)。
///
/// 0x48 处原 reserved u64 拆为 `face_off: u32` + reserved u32,
/// 用于指向 chunk payload 内 SectionFace 表的字节偏移(相对 `payload_offset`)。
///
/// SectionFace 表布局:6 个 [SectionFace](crate::face::SectionFace) 连续存放,
/// 共 `6 * SectionFace::SIZE = 288` 字节,按 FaceDir 顺序 (NegX/PosX/NegY/PosY/NegZ/PosZ)。
#[derive(Clone, Debug)]
pub struct ChunkDescriptor {
    pub chunk_id: u64,            // 0x00
    pub region_idx: u32,          // 0x08
    pub chunk_local_idx: u16,     // 0x0C
    pub section_count: u16,       // 0x0E
    pub payload_offset: u64,      // 0x10
    pub payload_size_comp: u32,   // 0x18
    pub payload_size_raw: u32,    // 0x1C
    pub block_off: u32,            // 0x20
    pub biome_off: u32,            // 0x24
    pub density_off: u32,          // 0x28
    pub light_off: u32,            // 0x2C
    pub mesh_off: u32,             // 0x30
    pub metadata_off: u32,         // 0x34
    pub state_flags: u32,          // 0x38
    pub epoch: u32,                // 0x3C
    pub version: u16,              // 0x40
    pub priority_hint: u16,        // 0x42
    pub checksum: u32,             // 0x44
    pub face_off: u32,             // 0x48 (相对 payload_offset,指向 6 个 SectionFace)
    // 0x4C reserved u32 = 0  (到 0x50)
}

impl Default for ChunkDescriptor {
    fn default() -> Self {
        ChunkDescriptor {
            chunk_id: 0,
            region_idx: 0,
            chunk_local_idx: 0,
            section_count: 0,
            payload_offset: 0,
            payload_size_comp: 0,
            payload_size_raw: 0,
            block_off: 0,
            biome_off: 0,
            density_off: 0,
            light_off: 0,
            mesh_off: 0,
            metadata_off: 0,
            state_flags: 0,
            epoch: 0,
            version: 1,
            priority_hint: 0,
            checksum: 0,
            face_off: 0,
        }
    }
}

impl ChunkDescriptor {
    pub const SIZE: usize = CHUNK_DESC_ENTRY_SIZE;

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u64(&mut buf, 0x00, self.chunk_id);
        util::write_u32(&mut buf, 0x08, self.region_idx);
        util::write_u16(&mut buf, 0x0C, self.chunk_local_idx);
        util::write_u16(&mut buf, 0x0E, self.section_count);
        util::write_u64(&mut buf, 0x10, self.payload_offset);
        util::write_u32(&mut buf, 0x18, self.payload_size_comp);
        util::write_u32(&mut buf, 0x1C, self.payload_size_raw);
        util::write_u32(&mut buf, 0x20, self.block_off);
        util::write_u32(&mut buf, 0x24, self.biome_off);
        util::write_u32(&mut buf, 0x28, self.density_off);
        util::write_u32(&mut buf, 0x2C, self.light_off);
        util::write_u32(&mut buf, 0x30, self.mesh_off);
        util::write_u32(&mut buf, 0x34, self.metadata_off);
        util::write_u32(&mut buf, 0x38, self.state_flags);
        util::write_u32(&mut buf, 0x3C, self.epoch);
        util::write_u16(&mut buf, 0x40, self.version);
        util::write_u16(&mut buf, 0x42, self.priority_hint);
        util::write_u32(&mut buf, 0x44, self.checksum);
        util::write_u32(&mut buf, 0x48, self.face_off);
        // 0x4C reserved = 0
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        ChunkDescriptor {
            chunk_id: util::read_u64(buf, 0x00),
            region_idx: util::read_u32(buf, 0x08),
            chunk_local_idx: util::read_u16(buf, 0x0C),
            section_count: util::read_u16(buf, 0x0E),
            payload_offset: util::read_u64(buf, 0x10),
            payload_size_comp: util::read_u32(buf, 0x18),
            payload_size_raw: util::read_u32(buf, 0x1C),
            block_off: util::read_u32(buf, 0x20),
            biome_off: util::read_u32(buf, 0x24),
            density_off: util::read_u32(buf, 0x28),
            light_off: util::read_u32(buf, 0x2C),
            mesh_off: util::read_u32(buf, 0x30),
            metadata_off: util::read_u32(buf, 0x34),
            state_flags: util::read_u32(buf, 0x38),
            epoch: util::read_u32(buf, 0x3C),
            version: util::read_u16(buf, 0x40),
            priority_hint: util::read_u16(buf, 0x42),
            checksum: util::read_u32(buf, 0x44),
            face_off: util::read_u32(buf, 0x48),
        }
    }
}

// =========================================================================
// ChunkDescHotView (24 字节,SoA,设计 §8.1)
// =========================================================================

/// SoA Hot View(24 字节,设计 §8.1)。
///
/// GPU/CPU 热路径使用,跨 chunk 单字段连续访问(coalesced)。
/// 字段自然对齐(u64@0, u32@8/12/16/20),`#[repr(C)]` 内存布局 == 设计偏移。
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct ChunkDescHotView {
    pub payload_offset: u64,     // 0
    pub payload_size_raw: u32,   // 8
    pub block_off: u32,           // 12
    pub epoch: u32,               // 16
    pub state_flags: u32,         // 20
}

impl ChunkDescHotView {
    pub const SIZE: usize = 24;

    pub fn from_descriptor(d: &ChunkDescriptor) -> Self {
        ChunkDescHotView {
            payload_offset: d.payload_offset,
            payload_size_raw: d.payload_size_raw,
            block_off: d.block_off,
            epoch: d.epoch,
            state_flags: d.state_flags,
        }
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u64(&mut buf, 0, self.payload_offset);
        util::write_u32(&mut buf, 8, self.payload_size_raw);
        util::write_u32(&mut buf, 12, self.block_off);
        util::write_u32(&mut buf, 16, self.epoch);
        util::write_u32(&mut buf, 20, self.state_flags);
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        ChunkDescHotView {
            payload_offset: util::read_u64(buf, 0),
            payload_size_raw: util::read_u32(buf, 8),
            block_off: util::read_u32(buf, 12),
            epoch: util::read_u32(buf, 16),
            state_flags: util::read_u32(buf, 20),
        }
    }
}

/// 构建跨 chunk 的 SoA Hot View 数组(批量上传 GPU 用)。
pub fn build_hot_view_array(descs: &[ChunkDescriptor]) -> Vec<u8> {
    let mut out = Vec::with_capacity(descs.len() * ChunkDescHotView::SIZE);
    for d in descs {
        out.extend_from_slice(&ChunkDescHotView::from_descriptor(d).to_bytes());
    }
    out
}

// =========================================================================
// SectionDescriptor (40 字节,设计 §7,扩展 §7.x face_off)
// =========================================================================

/// SectionDescriptor 大小。
///
/// v0.1 原 32B 已占满,扩展到 40B 以容纳 `face_off`。
/// 0x20 处新增 `face_off: u32`,0x24 reserved u32 对齐到 0x28(8 字节边界)。
pub const SECTION_DESC_ENTRY_SIZE: usize = 40;

/// SectionKind(设计 §7)。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum SectionKind {
    Air = 0,
    Uniform = 1,
    Mixed = 2,
    Fluid = 3,
}

impl SectionKind {
    pub fn from_u8(v: u8) -> CwaResult<Self> {
        Ok(match v {
            0 => Self::Air,
            1 => Self::Uniform,
            2 => Self::Mixed,
            3 => Self::Fluid,
            other => {
                return Err(CwaError::OutOfRange {
                    what: "SectionKind",
                    value: other as u64,
                    max: 3,
                })
            }
        })
    }
}

/// Section flags(设计 §7)。
pub mod section_flags {
    pub const DIRTY: u16 = 1 << 0;
    pub const MESHED: u16 = 1 << 1;
    pub const LIGHTED: u16 = 1 << 2;
}

/// SectionDescriptor(40 字节)。
///
/// 字段布局:
/// - 0x00..0x1F: 原 v0.1 字段(不变)
/// - 0x20: `face_off: u32` (新增,相对 chunk payload_offset 指向该 section 的 face 数据)
///
/// section 级 face_off 暂用于定位该 section 在 chunk face payload 中的子段(预留),
/// 当前主流程使用 chunk 级 [ChunkDescriptor::face_off] 定位 6 个 SectionFace。
#[derive(Clone, Debug, Default)]
pub struct SectionDescriptor {
    pub chunk_desc_idx: u32,    // 0x00
    pub section_y: u8,           // 0x04
    pub kind: u8,                // 0x05 (SectionKind as u8)
    pub flags: u16,              // 0x06
    pub block_off: u32,          // 0x08
    pub palette_off: u32,        // 0x0C
    pub size_raw: u32,            // 0x10
    pub size_comp: u32,           // 0x14
    pub mesh_off: u32,            // 0x18
    pub checksum: u32,            // 0x1C
    pub face_off: u32,            // 0x20 (相对 chunk payload_offset,section 级 face 子段)
    // 0x24 reserved u32 = 0  (到 0x28)
}

impl SectionDescriptor {
    pub const SIZE: usize = SECTION_DESC_ENTRY_SIZE;

    pub fn kind_enum(&self) -> CwaResult<SectionKind> {
        SectionKind::from_u8(self.kind)
    }

    pub fn with_kind(mut self, kind: SectionKind) -> Self {
        self.kind = kind as u8;
        self
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u32(&mut buf, 0x00, self.chunk_desc_idx);
        util::write_u8(&mut buf, 0x04, self.section_y);
        util::write_u8(&mut buf, 0x05, self.kind);
        util::write_u16(&mut buf, 0x06, self.flags);
        util::write_u32(&mut buf, 0x08, self.block_off);
        util::write_u32(&mut buf, 0x0C, self.palette_off);
        util::write_u32(&mut buf, 0x10, self.size_raw);
        util::write_u32(&mut buf, 0x14, self.size_comp);
        util::write_u32(&mut buf, 0x18, self.mesh_off);
        util::write_u32(&mut buf, 0x1C, self.checksum);
        util::write_u32(&mut buf, 0x20, self.face_off);
        // 0x24 reserved = 0
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        SectionDescriptor {
            chunk_desc_idx: util::read_u32(buf, 0x00),
            section_y: util::read_u8(buf, 0x04),
            kind: util::read_u8(buf, 0x05),
            flags: util::read_u16(buf, 0x06),
            block_off: util::read_u32(buf, 0x08),
            palette_off: util::read_u32(buf, 0x0C),
            size_raw: util::read_u32(buf, 0x10),
            size_comp: util::read_u32(buf, 0x14),
            mesh_off: util::read_u32(buf, 0x18),
            checksum: util::read_u32(buf, 0x1C),
            face_off: util::read_u32(buf, 0x20),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::id::{ChunkId, RegionId};

    #[test]
    fn region_index_roundtrip() {
        let e = RegionIndexEntry {
            region_id: RegionId::new(0, 5, -3).0,
            region_offset: 0x10000,
            chunk_count: 1024,
            section_count: 24576,
            region_checksum: 0xCAFE_BABE,
            flags: region_flags::COMPRESSED,
        };
        let b = e.to_bytes();
        let e2 = RegionIndexEntry::from_bytes(&b);
        assert_eq!(e2.region_id, e.region_id);
        assert_eq!(e2.region_offset, e.region_offset);
        assert_eq!(e2.chunk_count, e.chunk_count);
        assert_eq!(e2.region_checksum, e.region_checksum);
        assert_eq!(e2.flags, e.flags);
    }

    #[test]
    fn chunk_desc_roundtrip() {
        let d = ChunkDescriptor {
            chunk_id: ChunkId::new(1, 100, -200).0,
            region_idx: 7,
            chunk_local_idx: 42,
            section_count: 24,
            payload_offset: 0x100000,
            payload_size_comp: 1024,
            payload_size_raw: 4096,
            block_off: 0,
            biome_off: 4096,
            density_off: 8192,
            light_off: 16384,
            mesh_off: 20480,
            metadata_off: 24576,
            state_flags: 0xDEAD_BEEF,
            epoch: 42,
            version: 1,
            priority_hint: 128,
            checksum: 0x1234_5678,
            face_off: 0x8000,
        };
        let b = d.to_bytes();
        let d2 = ChunkDescriptor::from_bytes(&b);
        assert_eq!(d2.chunk_id, d.chunk_id);
        assert_eq!(d2.payload_offset, d.payload_offset);
        assert_eq!(d2.state_flags, d.state_flags);
        assert_eq!(d2.epoch, d.epoch);
        assert_eq!(d2.checksum, d.checksum);
        assert_eq!(d2.face_off, d.face_off);
    }

    #[test]
    fn hot_view_from_descriptor() {
        let mut d = ChunkDescriptor::default();
        d.payload_offset = 0xABCD;
        d.payload_size_raw = 4096;
        d.block_off = 16;
        d.state_flags = 0xFF;
        d.epoch = 7;
        let hv = ChunkDescHotView::from_descriptor(&d);
        assert_eq!(hv.payload_offset, 0xABCD);
        assert_eq!(hv.payload_size_raw, 4096);
        assert_eq!(hv.block_off, 16);
        assert_eq!(hv.epoch, 7);
        assert_eq!(hv.state_flags, 0xFF);
    }

    #[test]
    fn section_desc_roundtrip() {
        let s = SectionDescriptor {
            chunk_desc_idx: 3,
            section_y: 5,
            kind: SectionKind::Mixed as u8,
            flags: section_flags::DIRTY,
            block_off: 128,
            palette_off: 256,
            size_raw: 4096,
            size_comp: 512,
            mesh_off: 1024,
            checksum: 0xABCDEF12,
            face_off: 2048,
        };
        let b = s.to_bytes();
        assert_eq!(b.len(), SectionDescriptor::SIZE);
        assert_eq!(b.len(), 40);
        let s2 = SectionDescriptor::from_bytes(&b);
        assert_eq!(s2.chunk_desc_idx, s.chunk_desc_idx);
        assert_eq!(s2.kind, s.kind);
        assert_eq!(s2.flags, s.flags);
        assert_eq!(s2.checksum, s.checksum);
        assert_eq!(s2.face_off, s.face_off);
        assert_eq!(s2.kind_enum().unwrap(), SectionKind::Mixed);
    }

    #[test]
    fn section_desc_size_invariant() {
        // v0.1 扩展后必须为 40B,不允许回归
        assert_eq!(SECTION_DESC_ENTRY_SIZE, 40);
        assert_eq!(SectionDescriptor::SIZE, 40);
    }

    #[test]
    fn chunk_desc_face_off_roundtrip() {
        let mut d = ChunkDescriptor::default();
        d.face_off = 0xDEAD_BEEF;
        let b = d.to_bytes();
        // 0x48 处 4 字节应为 0xEFBEADDE(LE)
        assert_eq!(&b[0x48..0x4C], &[0xEF, 0xBE, 0xAD, 0xDE]);
        let d2 = ChunkDescriptor::from_bytes(&b);
        assert_eq!(d2.face_off, 0xDEAD_BEEF);
        // 0x4C reserved 必须为 0
        assert_eq!(&b[0x4C..0x50], &[0, 0, 0, 0]);
        // 80B 不变
        assert_eq!(b.len(), 80);
    }

    #[test]
    fn hot_view_array_layout() {
        let mut d1 = ChunkDescriptor::default();
        d1.epoch = 1;
        let mut d2 = ChunkDescriptor::default();
        d2.epoch = 2;
        let arr = build_hot_view_array(&[d1.clone(), d2.clone()]);
        assert_eq!(arr.len(), 24 * 2);
        let hv1 = ChunkDescHotView::from_bytes(arr[0..24].try_into().unwrap());
        let hv2 = ChunkDescHotView::from_bytes(arr[24..48].try_into().unwrap());
        assert_eq!(hv1.epoch, 1);
        assert_eq!(hv2.epoch, 2);
    }
}

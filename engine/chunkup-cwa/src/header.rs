//! CWA 文件头(512 字节,固定大小)。
//!
//! 字段偏移严格对齐设计文档 §4。手写 LE 序列化,不依赖 `#[repr(C)]` 内存布局
//! (设计偏移如 `world_seed @ 0x014=20` 不等于 u64 自然对齐 24)。

use crate::checksum;
use crate::error::{CwaError, CwaResult};
use crate::util;

/// CWA magic 标识(8 字节): `C W A \0 v 0 . 1`。
pub const MAGIC: [u8; 8] = *b"CWA\x00v0.1";

/// Header 固定大小。
pub const HEADER_SIZE: usize = 512;

/// Header 校验覆盖范围(0x000..0x09C,共 156 字节)。
pub const HEADER_CHECKSUM_RANGE: usize = 0x09C;

/// Header flags 位定义。
pub mod flags {
    /// 字节序为 LE(始终置位)。
    pub const LITTLE_ENDIAN: u32 = 1 << 0;
    /// 使用 64-bit offset(始终置位)。
    pub const OFFSET_64BIT: u32 = 1 << 1;
    /// 包含 checksum 表。
    pub const HAS_CHECKSUM: u32 = 1 << 2;
    /// payload 已压缩。
    pub const COMPRESSED_PAYLOAD: u32 = 1 << 3;
}

/// 默认 entry 大小(与设计 §3-7 对齐)。
///
/// 注意:`DEFAULT_SECTION_DESC_ENTRY_SIZE` 在 v0.1 扩展中由 32 提升到 40
/// 以容纳 `face_off` 字段(详见 [SectionDescriptor](crate::descriptor::SectionDescriptor))。
pub const DEFAULT_REGION_INDEX_ENTRY_SIZE: u32 = 32;
pub const DEFAULT_CHUNK_DESC_ENTRY_SIZE: u32 = 80;
pub const DEFAULT_SECTION_DESC_ENTRY_SIZE: u32 = 40;
pub const DEFAULT_STATE_ENTRY_SIZE: u32 = 16;
pub const DEFAULT_CHECKSUM_BLOCK_SIZE: u32 = 4096;

/// CWA v0.1 文件头。
#[derive(Clone, Debug)]
pub struct Header {
    pub magic: [u8; 8],                 // 0x000
    pub version_major: u16,             // 0x008
    pub version_minor: u16,             // 0x00A
    pub flags: u32,                     // 0x00C
    pub header_size: u32,               // 0x010
    pub world_seed: u64,                // 0x014
    pub dimension_count: u32,           // 0x01C
    pub region_count: u32,              // 0x020
    pub chunk_count: u32,               // 0x024
    pub section_count: u64,              // 0x028
    pub world_min_y: i32,                // 0x030
    pub world_height: u32,               // 0x034
    pub region_size_shift: u8,           // 0x038
    pub chunk_size_shift: u8,            // 0x039
    pub section_size_shift: u8,          // 0x03A
    pub region_index_offset: u64,        // 0x03C
    pub region_index_entry_size: u32,    // 0x044
    pub chunk_desc_offset: u64,          // 0x048
    pub chunk_desc_entry_size: u32,      // 0x050
    pub section_desc_offset: u64,        // 0x054
    pub section_desc_entry_size: u32,     // 0x05C
    pub state_table_offset: u64,         // 0x060
    pub compression_meta_offset: u64,     // 0x068
    pub payload_offset: u64,             // 0x070
    pub payload_size: u64,               // 0x078
    pub checksum_table_offset: u64,       // 0x080
    pub checksum_block_size: u32,         // 0x088
    pub creation_time: u64,               // 0x08C
    pub last_modified: u64,               // 0x094
    pub header_checksum: u32,             // 0x09C
    // 0x0A0..0x200 reserved(352 字节,补零;设计文档笔误为 384,按 512B 总大小修正)
}

impl Header {
    /// 计算头部校验和(crc32c of 0x000..0x09C)。
    pub fn compute_checksum(&self) -> u32 {
        let mut buf = [0u8; HEADER_SIZE];
        self.write_fields(&mut buf);
        checksum::compute(&buf[..HEADER_CHECKSUM_RANGE])
    }

    /// 验证 magic 与 checksum。
    pub fn validate(&self) -> CwaResult<()> {
        if self.magic != MAGIC {
            return Err(CwaError::InvalidMagic {
                expected: MAGIC,
                got: self.magic,
            });
        }
        let computed = self.compute_checksum();
        if computed != self.header_checksum {
            return Err(CwaError::ChecksumMismatch {
                offset: 0x09C as u64,
                expected: self.header_checksum,
                got: computed,
            });
        }
        Ok(())
    }

    /// 写入字段到缓冲(不含 checksum 自身,reserved 补零)。
    fn write_fields(&self, buf: &mut [u8; HEADER_SIZE]) {
        *buf = [0u8; HEADER_SIZE];
        util::write_bytes(buf, 0x000, &self.magic);
        util::write_u16(buf, 0x008, self.version_major);
        util::write_u16(buf, 0x00A, self.version_minor);
        util::write_u32(buf, 0x00C, self.flags);
        util::write_u32(buf, 0x010, self.header_size);
        util::write_u64(buf, 0x014, self.world_seed);
        util::write_u32(buf, 0x01C, self.dimension_count);
        util::write_u32(buf, 0x020, self.region_count);
        util::write_u32(buf, 0x024, self.chunk_count);
        util::write_u64(buf, 0x028, self.section_count);
        util::write_i32(buf, 0x030, self.world_min_y);
        util::write_u32(buf, 0x034, self.world_height);
        util::write_u8(buf, 0x038, self.region_size_shift);
        util::write_u8(buf, 0x039, self.chunk_size_shift);
        util::write_u8(buf, 0x03A, self.section_size_shift);
        // 0x03B reserved_0 = 0
        util::write_u64(buf, 0x03C, self.region_index_offset);
        util::write_u32(buf, 0x044, self.region_index_entry_size);
        util::write_u64(buf, 0x048, self.chunk_desc_offset);
        util::write_u32(buf, 0x050, self.chunk_desc_entry_size);
        util::write_u64(buf, 0x054, self.section_desc_offset);
        util::write_u32(buf, 0x05C, self.section_desc_entry_size);
        util::write_u64(buf, 0x060, self.state_table_offset);
        util::write_u64(buf, 0x068, self.compression_meta_offset);
        util::write_u64(buf, 0x070, self.payload_offset);
        util::write_u64(buf, 0x078, self.payload_size);
        util::write_u64(buf, 0x080, self.checksum_table_offset);
        util::write_u32(buf, 0x088, self.checksum_block_size);
        util::write_u64(buf, 0x08C, self.creation_time);
        util::write_u64(buf, 0x094, self.last_modified);
    }

    /// 序列化为 512 字节(包含 checksum,reserved 补零)。
    pub fn to_bytes(&self) -> [u8; HEADER_SIZE] {
        let mut buf = [0u8; HEADER_SIZE];
        self.write_fields(&mut buf);
        util::write_u32(&mut buf, 0x09C, self.header_checksum);
        buf
    }

    /// 从 512 字节反序列化(验证 magic 与 checksum)。
    pub fn from_bytes(buf: &[u8; HEADER_SIZE]) -> CwaResult<Self> {
        let mut magic = [0u8; 8];
        magic.copy_from_slice(util::read_bytes(buf, 0x000, 8));

        let header = Header {
            magic,
            version_major: util::read_u16(buf, 0x008),
            version_minor: util::read_u16(buf, 0x00A),
            flags: util::read_u32(buf, 0x00C),
            header_size: util::read_u32(buf, 0x010),
            world_seed: util::read_u64(buf, 0x014),
            dimension_count: util::read_u32(buf, 0x01C),
            region_count: util::read_u32(buf, 0x020),
            chunk_count: util::read_u32(buf, 0x024),
            section_count: util::read_u64(buf, 0x028),
            world_min_y: util::read_i32(buf, 0x030),
            world_height: util::read_u32(buf, 0x034),
            region_size_shift: util::read_u8(buf, 0x038),
            chunk_size_shift: util::read_u8(buf, 0x039),
            section_size_shift: util::read_u8(buf, 0x03A),
            region_index_offset: util::read_u64(buf, 0x03C),
            region_index_entry_size: util::read_u32(buf, 0x044),
            chunk_desc_offset: util::read_u64(buf, 0x048),
            chunk_desc_entry_size: util::read_u32(buf, 0x050),
            section_desc_offset: util::read_u64(buf, 0x054),
            section_desc_entry_size: util::read_u32(buf, 0x05C),
            state_table_offset: util::read_u64(buf, 0x060),
            compression_meta_offset: util::read_u64(buf, 0x068),
            payload_offset: util::read_u64(buf, 0x070),
            payload_size: util::read_u64(buf, 0x078),
            checksum_table_offset: util::read_u64(buf, 0x080),
            checksum_block_size: util::read_u32(buf, 0x088),
            creation_time: util::read_u64(buf, 0x08C),
            last_modified: util::read_u64(buf, 0x094),
            header_checksum: util::read_u32(buf, 0x09C),
        };
        header.validate()?;
        Ok(header)
    }
}

impl Default for Header {
    fn default() -> Self {
        let mut h = Header {
            magic: MAGIC,
            version_major: 0,
            version_minor: 1,
            flags: flags::LITTLE_ENDIAN | flags::OFFSET_64BIT | flags::HAS_CHECKSUM,
            header_size: HEADER_SIZE as u32,
            world_seed: 0,
            dimension_count: 1,
            region_count: 0,
            chunk_count: 0,
            section_count: 0,
            world_min_y: -64,
            world_height: 384,
            region_size_shift: 5,
            chunk_size_shift: 4,
            section_size_shift: 4,
            region_index_offset: 0x1200,
            region_index_entry_size: DEFAULT_REGION_INDEX_ENTRY_SIZE,
            chunk_desc_offset: 0,
            chunk_desc_entry_size: DEFAULT_CHUNK_DESC_ENTRY_SIZE,
            section_desc_offset: 0,
            section_desc_entry_size: DEFAULT_SECTION_DESC_ENTRY_SIZE,
            state_table_offset: 0,
            compression_meta_offset: 0,
            payload_offset: 0,
            payload_size: 0,
            checksum_table_offset: 0,
            checksum_block_size: DEFAULT_CHECKSUM_BLOCK_SIZE,
            creation_time: 0,
            last_modified: 0,
            header_checksum: 0,
        };
        h.header_checksum = h.compute_checksum();
        h
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip() {
        let mut h = Header::default();
        h.world_seed = 0xDEAD_BEEF_CAFE_BABE;
        h.region_count = 42;
        h.chunk_count = 1024;
        h.header_checksum = h.compute_checksum();
        let bytes = h.to_bytes();
        let h2 = Header::from_bytes(&bytes).expect("roundtrip");
        assert_eq!(h2.world_seed, h.world_seed);
        assert_eq!(h2.region_count, h.region_count);
        assert_eq!(h2.chunk_count, h.chunk_count);
        assert_eq!(h2.magic, MAGIC);
    }

    #[test]
    fn bad_magic_rejected() {
        let mut bytes = Header::default().to_bytes();
        bytes[0] = b'X';
        assert!(Header::from_bytes(&bytes).is_err());
    }

    #[test]
    fn bad_checksum_rejected() {
        let mut bytes = Header::default().to_bytes();
        bytes[0x09C] ^= 0xFF;
        assert!(Header::from_bytes(&bytes).is_err());
    }
}

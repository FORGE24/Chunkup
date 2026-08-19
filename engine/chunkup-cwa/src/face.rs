//! Section face 数据格式与跨 section 面聚合索引。
//!
//! 设计目标:
//! - 按面方向(6 种)独立存储,服务 GPU face-batch mesh 与跨 chunk 边界协同 culling
//! - 「多 chunks 同 section_y 同 face 必须同顶底」约束由 [FaceSection] 聚合索引承载
//! - air 位图(256-bit)用 RLE 在 [compress](crate::compress) 层处理,这里只存原始位图
//!
//! ## 二进制布局
//!
//! ### [SectionFace] (48 字节,单面单 section)
//! 偏移  字段                说明
//! 0x00  face_dir: u8        [FaceDir] as u8 (0..5)
//! 0x01  flags: u8           [section_face_flags]
//! 0x02  reserved: u16       = 0
//! 0x04  air_bitmap: [u8;32] 256-bit 位图,bit i = (row*16+col) 处 cell 是否 air
//! 0x24  palette_off: u32    相对 chunk payload_offset 的调色板偏移
//! 0x28  epoch: u32          face 新鲜度
//! 0x2C  checksum: u32       crc32c over air_bitmap + palette_off
//!
//! ### [FaceSection] (32 字节,跨 section 聚合)
//! 偏移  字段                  说明
//! 0x00  section_y: u8         聚合组的 section_y
//! 0x01  face_dir: u8          聚合组的面方向
//! 0x02  chunk_count: u16      组内 chunk 数
//! 0x04  top_height_max: u8    组内最高 top_height
//! 0x05  top_height_min: u8    组内最低 top_height
//! 0x06  bottom_height_max: u8 组内最高 bottom_height
//! 0x07  bottom_height_min: u8 组内最低 bottom_height
//! 0x08  flags: u16            [face_section_flags]
//! 0x0A  reserved: u16         = 0
//! 0x0C  chunk_desc_idx_first: u32  组内首 chunk 在描述符表中的索引(连续存放)
//! 0x10  epoch: u32            聚合新鲜度
//! 0x14  checksum: u32         crc32c
//! 0x18  reserved: [u8;8]      = 0
//!
//! 一致性约束:`top_height_max == top_height_min && bottom_height_max == bottom_height_min`,
//! 即组内所有 chunk 的 top_height 相同,bottom_height 相同。

use crate::util;

// =========================================================================
// FaceDir: 6 面方向枚举
// =========================================================================

/// 6 面方向(与 MC `Direction` 一致)。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum FaceDir {
    /// -X
    NegX = 0,
    /// +X
    PosX = 1,
    /// -Y (bottom)
    NegY = 2,
    /// +Y (top)
    PosY = 3,
    /// -Z
    NegZ = 4,
    /// +Z
    PosZ = 5,
}

impl FaceDir {
    /// 方向数。
    pub const COUNT: usize = 6;

    pub fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0 => Self::NegX,
            1 => Self::PosX,
            2 => Self::NegY,
            3 => Self::PosY,
            4 => Self::NegZ,
            5 => Self::PosZ,
            _ => return None,
        })
    }

    /// 是否为水平方向(X/Z 面)。
    pub const fn is_horizontal(self) -> bool {
        matches!(self, Self::NegX | Self::PosX | Self::NegZ | Self::PosZ)
    }

    /// 是否为垂直方向(Y 面,顶/底)。
    pub const fn is_vertical(self) -> bool {
        matches!(self, Self::NegY | Self::PosY)
    }
}

// =========================================================================
// SectionFace (48 字节,单 section 单面)
// =========================================================================

/// SectionFace 大小。
pub const SECTION_FACE_ENTRY_SIZE: usize = 48;

/// SectionFace flags。
pub mod section_face_flags {
    /// 该面跨 chunk 边界(需要邻接 chunk 协同 culling)。
    pub const CROSS_CHUNK: u8 = 1 << 0;
    /// 该面全 air(air_bitmap 全 1)。
    pub const ALL_AIR: u8 = 1 << 1;
    /// air 位图已修改未同步到 GPU。
    pub const BITMAP_STALE: u8 = 1 << 2;
    /// palette 已修改未同步。
    pub const PALETTE_STALE: u8 = 1 << 3;
    /// 邻居 chunk 缺失,按边界空气面处理。
    pub const NEIGHBOR_MISSING: u8 = 1 << 4;
}

/// 16×16 面的 cell 数。
pub const FACE_CELLS: usize = 16 * 16;

/// 256-bit air 位图字节数。
pub const AIR_BITMAP_SIZE: usize = FACE_CELLS / 8; // 32

/// SectionFace:单个 section 单个面方向的元数据(48 字节)。
///
/// 一个 section 有 6 个 SectionFace(NegX/PosX/NegY/PosY/NegZ/PosZ)。
/// 在 chunk payload 中由 [ChunkDescriptor::face_off](crate::descriptor::ChunkDescriptor::face_off)
/// 定位起始,6 个 entry 连续存放,共 `6 * 48 = 288` 字节。
#[derive(Clone, Debug)]
pub struct SectionFace {
    /// 面方向(FaceDir as u8, 0..5)。
    pub face_dir: u8,
    /// flags,见 [section_face_flags]。
    pub flags: u8,
    /// 256-bit air 位图,bit i = (row*16+col) 处 cell 是否 air。
    pub air_bitmap: [u8; AIR_BITMAP_SIZE],
    /// 相对 chunk payload_offset 的调色板偏移。
    pub palette_off: u32,
    /// face 新鲜度。
    pub epoch: u32,
    /// crc32c over air_bitmap + palette_off。
    pub checksum: u32,
}

impl Default for SectionFace {
    fn default() -> Self {
        SectionFace {
            face_dir: 0,
            flags: section_face_flags::ALL_AIR,
            air_bitmap: [0xFF; AIR_BITMAP_SIZE], // 全 air
            palette_off: 0,
            epoch: 0,
            checksum: 0,
        }
    }
}

impl SectionFace {
    pub const SIZE: usize = SECTION_FACE_ENTRY_SIZE;

    /// 构造指定方向的全 air SectionFace。
    pub fn all_air(dir: FaceDir) -> Self {
        SectionFace {
            face_dir: dir as u8,
            ..Default::default()
        }
    }

    /// 构造指定方向的全实心 SectionFace。
    pub fn all_solid(dir: FaceDir) -> Self {
        SectionFace {
            face_dir: dir as u8,
            flags: 0,
            air_bitmap: [0x00; AIR_BITMAP_SIZE], // 全实心
            palette_off: 0,
            epoch: 0,
            checksum: 0,
        }
    }

    /// 是否全 air。
    pub fn is_all_air(&self) -> bool {
        self.flags & section_face_flags::ALL_AIR != 0
    }

    /// 取位图中 (row, col) 处的 air 状态。
    pub fn cell_is_air(&self, row: usize, col: usize) -> bool {
        debug_assert!(row < 16 && col < 16);
        let idx = row * 16 + col;
        self.air_bitmap[idx >> 3] & (1 << (idx & 7)) != 0
    }

    /// 设置位图中 (row, col) 处的 air 状态。
    pub fn set_cell_air(&mut self, row: usize, col: usize, is_air: bool) {
        debug_assert!(row < 16 && col < 16);
        let idx = row * 16 + col;
        if is_air {
            self.air_bitmap[idx >> 3] |= 1 << (idx & 7);
        } else {
            self.air_bitmap[idx >> 3] &= !(1 << (idx & 7));
        }
    }

    /// 统计非 air cell 数。
    pub fn solid_count(&self) -> usize {
        (0..16)
            .flat_map(|r| (0..16).map(move |c| !self.cell_is_air(r, c)))
            .filter(|&x| x)
            .count()
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u8(&mut buf, 0x00, self.face_dir);
        util::write_u8(&mut buf, 0x01, self.flags);
        // 0x02 reserved = 0
        buf[0x04..0x04 + AIR_BITMAP_SIZE].copy_from_slice(&self.air_bitmap);
        util::write_u32(&mut buf, 0x24, self.palette_off);
        util::write_u32(&mut buf, 0x28, self.epoch);
        util::write_u32(&mut buf, 0x2C, self.checksum);
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        let mut air_bitmap = [0u8; AIR_BITMAP_SIZE];
        air_bitmap.copy_from_slice(&buf[0x04..0x04 + AIR_BITMAP_SIZE]);
        SectionFace {
            face_dir: util::read_u8(buf, 0x00),
            flags: util::read_u8(buf, 0x01),
            air_bitmap,
            palette_off: util::read_u32(buf, 0x24),
            epoch: util::read_u32(buf, 0x28),
            checksum: util::read_u32(buf, 0x2C),
        }
    }
}

// =========================================================================
// FaceSection (32 字节,跨 section 聚合)
// =========================================================================

/// FaceSection 大小。
pub const FACE_SECTION_ENTRY_SIZE: usize = 32;

/// FaceSection flags。
pub mod face_section_flags {
    /// 组内所有 chunk 的 top/bottom 一致(满足约束)。
    pub const CONSISTENT: u16 = 1 << 0;
    /// 组内 chunk 的 face 已全部上传 GPU。
    pub const ALL_UPLOADED: u16 = 1 << 1;
    /// 组内 chunk 不连续(需要查 chunk_idx 数组)。
    pub const NON_CONTIGUOUS: u16 = 1 << 2;
    /// 聚合过期,需要重算。
    pub const STALE: u16 = 1 << 3;
}

/// 全 air 标记值,用于高度字段(0xFF 表示无效/未设置)。
pub const HEIGHT_ALL_AIR: u8 = 0xFF;

/// FaceSection:跨 section 面聚合索引项(32 字节)。
///
/// 服务于「多 chunks 同 section_y 同 face 必须同顶底」约束。
/// 一个 (section_y, face_dir) 组对应一个 FaceSection,组内 chunk
/// 在描述符表中连续存放(由 [chunk_desc_idx_first] + [chunk_count] 圈定)。
///
/// 一致性验证由 [check_consistency](FaceSection::check_consistency) 完成:
/// `top_height_max == top_height_min && bottom_height_max == bottom_height_min`。
#[derive(Clone, Debug)]
pub struct FaceSection {
    /// 聚合组 section_y。
    pub section_y: u8,
    /// 聚合组面方向。
    pub face_dir: u8,
    /// 组内 chunk 数。
    pub chunk_count: u16,
    /// 组内最高 top_height(组内 chunk 的 top_height 最大值)。
    pub top_height_max: u8,
    /// 组内最低 top_height(组内 chunk 的 top_height 最小值)。
    pub top_height_min: u8,
    /// 组内最高 bottom_height。
    pub bottom_height_max: u8,
    /// 组内最低 bottom_height。
    pub bottom_height_min: u8,
    /// flags,见 [face_section_flags]。
    pub flags: u16,
    /// 组内首 chunk 在描述符表中的索引(连续存放)。
    pub chunk_desc_idx_first: u32,
    /// 聚合新鲜度。
    pub epoch: u32,
    /// crc32c。
    pub checksum: u32,
}

impl Default for FaceSection {
    fn default() -> Self {
        FaceSection {
            section_y: 0,
            face_dir: 0,
            chunk_count: 0,
            top_height_max: 0,
            top_height_min: HEIGHT_ALL_AIR,
            bottom_height_max: 0,
            bottom_height_min: HEIGHT_ALL_AIR,
            flags: 0,
            chunk_desc_idx_first: 0,
            epoch: 0,
            checksum: 0,
        }
    }
}

impl FaceSection {
    pub const SIZE: usize = FACE_SECTION_ENTRY_SIZE;

    /// 构造聚合组(初始极值待 merge)。
    pub fn new(section_y: u8, face_dir: FaceDir, chunk_count: u16) -> Self {
        FaceSection {
            section_y,
            face_dir: face_dir as u8,
            chunk_count,
            ..Default::default()
        }
    }

    /// 把一个 (top, bottom) 对并入聚合组的极值。
    ///
    /// `top` / `bottom` 来自 chunk 在该 face 方向上的高度极值(由外部计算)。
    pub fn merge_heights(&mut self, top: u8, bottom: u8) {
        if top != HEIGHT_ALL_AIR {
            if self.top_height_min == HEIGHT_ALL_AIR || top < self.top_height_min {
                self.top_height_min = top;
            }
            if self.top_height_max == 0 || top > self.top_height_max {
                self.top_height_max = top;
            }
        }
        if bottom != HEIGHT_ALL_AIR {
            if self.bottom_height_min == HEIGHT_ALL_AIR || bottom < self.bottom_height_min {
                self.bottom_height_min = bottom;
            }
            if self.bottom_height_max == 0 || bottom > self.bottom_height_max {
                self.bottom_height_max = bottom;
            }
        }
    }

    /// 校验约束:组内所有 chunk 的 top/bottom 必须一致。
    ///
    /// 满足条件:`top_height_max == top_height_min && bottom_height_max == bottom_height_min`。
    /// 满足时设置 [face_section_flags::CONSISTENT]。
    pub fn check_consistency(&mut self) {
        let consistent = self.top_height_max == self.top_height_min
            && self.bottom_height_max == self.bottom_height_min
            && self.top_height_min != HEIGHT_ALL_AIR;
        if consistent {
            self.flags |= face_section_flags::CONSISTENT;
        } else {
            self.flags &= !face_section_flags::CONSISTENT;
        }
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u8(&mut buf, 0x00, self.section_y);
        util::write_u8(&mut buf, 0x01, self.face_dir);
        util::write_u16(&mut buf, 0x02, self.chunk_count);
        util::write_u8(&mut buf, 0x04, self.top_height_max);
        util::write_u8(&mut buf, 0x05, self.top_height_min);
        util::write_u8(&mut buf, 0x06, self.bottom_height_max);
        util::write_u8(&mut buf, 0x07, self.bottom_height_min);
        util::write_u16(&mut buf, 0x08, self.flags);
        // 0x0A reserved = 0
        util::write_u32(&mut buf, 0x0C, self.chunk_desc_idx_first);
        util::write_u32(&mut buf, 0x10, self.epoch);
        util::write_u32(&mut buf, 0x14, self.checksum);
        // 0x18..0x20 reserved = 0
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        FaceSection {
            section_y: util::read_u8(buf, 0x00),
            face_dir: util::read_u8(buf, 0x01),
            chunk_count: util::read_u16(buf, 0x02),
            top_height_max: util::read_u8(buf, 0x04),
            top_height_min: util::read_u8(buf, 0x05),
            bottom_height_max: util::read_u8(buf, 0x06),
            bottom_height_min: util::read_u8(buf, 0x07),
            flags: util::read_u16(buf, 0x08),
            chunk_desc_idx_first: util::read_u32(buf, 0x0C),
            epoch: util::read_u32(buf, 0x10),
            checksum: util::read_u32(buf, 0x14),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn face_dir_classify() {
        assert!(FaceDir::NegX.is_horizontal());
        assert!(FaceDir::PosX.is_horizontal());
        assert!(!FaceDir::NegY.is_horizontal());
        assert!(FaceDir::PosY.is_vertical());
        for v in 0..6u8 {
            assert_eq!(FaceDir::from_u8(v).unwrap() as u8, v);
        }
        assert!(FaceDir::from_u8(6).is_none());
    }

    #[test]
    fn section_face_default_is_all_air() {
        let sf = SectionFace::default();
        assert!(sf.is_all_air());
        // 位图全 1
        for b in &sf.air_bitmap {
            assert_eq!(*b, 0xFF);
        }
        assert_eq!(sf.solid_count(), 0);
    }

    #[test]
    fn section_face_all_solid() {
        let sf = SectionFace::all_solid(FaceDir::PosX);
        assert_eq!(sf.face_dir, FaceDir::PosX as u8);
        assert!(!sf.is_all_air());
        assert_eq!(sf.solid_count(), 256);
    }

    #[test]
    fn section_face_bitmap_set_get() {
        let mut sf = SectionFace::all_solid(FaceDir::NegY);
        // 把 (3, 5) 设为 air
        sf.set_cell_air(3, 5, true);
        assert!(sf.cell_is_air(3, 5));
        assert!(!sf.cell_is_air(3, 4));
        assert_eq!(sf.solid_count(), 255);

        // 清回去
        sf.set_cell_air(3, 5, false);
        assert!(!sf.cell_is_air(3, 5));
        assert_eq!(sf.solid_count(), 256);
    }

    #[test]
    fn section_face_roundtrip() {
        let mut sf = SectionFace::all_solid(FaceDir::PosZ);
        sf.set_cell_air(0, 0, true);
        sf.set_cell_air(15, 15, true);
        sf.palette_off = 0x1000;
        sf.epoch = 42;
        sf.checksum = 0xCAFEBABE;
        sf.flags = section_face_flags::CROSS_CHUNK | section_face_flags::PALETTE_STALE;

        let b = sf.to_bytes();
        assert_eq!(b.len(), SectionFace::SIZE);
        assert_eq!(b.len(), 48);
        // reserved 必须补零
        assert_eq!(&b[0x02..0x04], &[0, 0]);

        let sf2 = SectionFace::from_bytes(&b);
        assert_eq!(sf2.face_dir, sf.face_dir);
        assert_eq!(sf2.flags, sf.flags);
        assert_eq!(sf2.air_bitmap, sf.air_bitmap);
        assert_eq!(sf2.palette_off, sf.palette_off);
        assert_eq!(sf2.epoch, sf.epoch);
        assert_eq!(sf2.checksum, sf.checksum);
        assert!(sf2.cell_is_air(0, 0));
        assert!(sf2.cell_is_air(15, 15));
    }

    #[test]
    fn face_section_default_extremes() {
        let fs = FaceSection::default();
        assert_eq!(fs.chunk_count, 0);
        assert_eq!(fs.top_height_max, 0);
        assert_eq!(fs.top_height_min, HEIGHT_ALL_AIR);
        assert_eq!(fs.bottom_height_max, 0);
        assert_eq!(fs.bottom_height_min, HEIGHT_ALL_AIR);
    }

    #[test]
    fn face_section_merge_and_consistency() {
        let mut fs = FaceSection::new(7, FaceDir::PosY, 3);
        // 模拟 3 个 chunk 同 section_y=7 同 face=PosY
        // 约束:三个 chunk 的 (top, bottom) 必须相同
        fs.merge_heights(12, 4);
        fs.merge_heights(12, 4);
        fs.merge_heights(12, 4);
        fs.check_consistency();
        assert!(fs.flags & face_section_flags::CONSISTENT != 0, "应一致");
        assert_eq!(fs.top_height_max, 12);
        assert_eq!(fs.top_height_min, 12);
        assert_eq!(fs.bottom_height_max, 4);
        assert_eq!(fs.bottom_height_min, 4);
    }

    #[test]
    fn face_section_inconsistent_top() {
        let mut fs = FaceSection::new(7, FaceDir::PosY, 2);
        fs.merge_heights(12, 4);
        fs.merge_heights(10, 4); // top 不一致
        fs.check_consistency();
        assert_eq!(fs.flags & face_section_flags::CONSISTENT, 0);
    }

    #[test]
    fn face_section_inconsistent_bottom() {
        let mut fs = FaceSection::new(7, FaceDir::PosY, 2);
        fs.merge_heights(12, 4);
        fs.merge_heights(12, 6); // bottom 不一致
        fs.check_consistency();
        assert_eq!(fs.flags & face_section_flags::CONSISTENT, 0);
    }

    #[test]
    fn face_section_all_air_rejects_consistency() {
        // 组内全 air(top=0xFF)不能算一致
        let mut fs = FaceSection::new(7, FaceDir::NegY, 2);
        fs.merge_heights(HEIGHT_ALL_AIR, HEIGHT_ALL_AIR);
        fs.merge_heights(HEIGHT_ALL_AIR, HEIGHT_ALL_AIR);
        fs.check_consistency();
        assert_eq!(fs.flags & face_section_flags::CONSISTENT, 0);
    }

    #[test]
    fn face_section_roundtrip() {
        let mut fs = FaceSection::new(15, FaceDir::NegZ, 8);
        fs.merge_heights(14, 2);
        fs.merge_heights(14, 2);
        fs.flags = face_section_flags::CONSISTENT | face_section_flags::ALL_UPLOADED;
        fs.chunk_desc_idx_first = 0x100;
        fs.epoch = 99;
        fs.checksum = 0xDEADBEEF;

        let b = fs.to_bytes();
        assert_eq!(b.len(), FaceSection::SIZE);
        assert_eq!(b.len(), 32);
        // reserved 必须补零
        assert_eq!(&b[0x0A..0x0C], &[0, 0]);
        assert_eq!(&b[0x18..0x20], &[0u8; 8]);

        let fs2 = FaceSection::from_bytes(&b);
        assert_eq!(fs2.section_y, 15);
        assert_eq!(fs2.face_dir, FaceDir::NegZ as u8);
        assert_eq!(fs2.chunk_count, 8);
        assert_eq!(fs2.top_height_max, 14);
        assert_eq!(fs2.top_height_min, 14);
        assert_eq!(fs2.bottom_height_max, 2);
        assert_eq!(fs2.bottom_height_min, 2);
        assert_eq!(fs2.flags, fs.flags);
        assert_eq!(fs2.chunk_desc_idx_first, 0x100);
        assert_eq!(fs2.epoch, 99);
        assert_eq!(fs2.checksum, 0xDEADBEEF);
    }
}

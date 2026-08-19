//! ChunkId / RegionId 编码与 Morton 序。

/// Chunk 全局逻辑 ID。
///
/// bit 布局(64 位):
/// ```text
/// bit 63..56  55..28  27..0
///      dim     x       z
/// ```
/// - dim: 维度索引(0=overworld, 1=nether, 2=end, 3+ 自定义)
/// - x/z: 各 28 bit,范围 ±134,217,728 chunks = ±2.1B blocks
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(transparent)]
pub struct ChunkId(pub u64);

impl ChunkId {
    /// 维度位宽。
    pub const DIM_BITS: u32 = 8;
    /// X/Z 位宽。
    pub const AXIS_BITS: u32 = 28;
    /// 维度偏移。
    pub const DIM_SHIFT: u32 = Self::AXIS_BITS * 2; // 56
    /// X 偏移。
    pub const X_SHIFT: u32 = Self::AXIS_BITS; // 28

    /// 维度掩码。
    pub const DIM_MASK: u64 = (1u64 << Self::DIM_BITS) - 1;
    /// X/Z 掩码。
    pub const AXIS_MASK: u64 = (1u64 << Self::AXIS_BITS) - 1;

    /// 构造 ChunkId。
    pub const fn new(dim: u8, x: i32, z: i32) -> Self {
        let dim_u = (dim as u64) & Self::DIM_MASK;
        let x_u = (x as u32 as u64) & Self::AXIS_MASK;
        let z_u = (z as u32 as u64) & Self::AXIS_MASK;
        Self((dim_u << Self::DIM_SHIFT) | (x_u << Self::X_SHIFT) | z_u)
    }

    /// 维度。
    pub const fn dim(self) -> u8 {
        ((self.0 >> Self::DIM_SHIFT) & Self::DIM_MASK) as u8
    }

    /// chunk X。
    pub const fn x(self) -> i32 {
        sign_extend_28((self.0 >> Self::X_SHIFT) & Self::AXIS_MASK)
    }

    /// chunk Z。
    pub const fn z(self) -> i32 {
        sign_extend_28(self.0 & Self::AXIS_MASK)
    }

    /// 该 chunk 所属 region 的 RegionId。
    pub const fn region(self, region_size_shift: u32) -> RegionId {
        let rx = self.x() >> region_size_shift;
        let rz = self.z() >> region_size_shift;
        RegionId::new(self.dim(), rx, rz)
    }

    /// chunk 在所属 region 内的 Morton 序。
    pub fn morton_local(self, region_size_shift: u32) -> u16 {
        let mask = (1u32 << region_size_shift) - 1;
        let lx = (self.x() as u32) & mask;
        let lz = (self.z() as u32) & mask;
        morton_encode_2d(lx, lz) as u16
    }
}

impl std::fmt::Debug for ChunkId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ChunkId")
            .field("dim", &self.dim())
            .field("x", &self.x())
            .field("z", &self.z())
            .finish()
    }
}

impl std::fmt::Display for ChunkId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "chunk[{},{}](dim {})", self.x(), self.z(), self.dim())
    }
}

/// Region 全局逻辑 ID。
///
/// bit 布局同 ChunkId,但 x/z 为 region 坐标。
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(transparent)]
pub struct RegionId(pub u64);

impl RegionId {
    pub const fn new(dim: u8, x: i32, z: i32) -> Self {
        let dim_u = (dim as u64) & ChunkId::DIM_MASK;
        let x_u = (x as u32 as u64) & ChunkId::AXIS_MASK;
        let z_u = (z as u32 as u64) & ChunkId::AXIS_MASK;
        Self((dim_u << ChunkId::DIM_SHIFT) | (x_u << ChunkId::X_SHIFT) | z_u)
    }

    pub const fn dim(self) -> u8 {
        ((self.0 >> ChunkId::DIM_SHIFT) & ChunkId::DIM_MASK) as u8
    }

    pub const fn x(self) -> i32 {
        sign_extend_28((self.0 >> ChunkId::X_SHIFT) & ChunkId::AXIS_MASK)
    }

    pub const fn z(self) -> i32 {
        sign_extend_28(self.0 & ChunkId::AXIS_MASK)
    }
}

impl std::fmt::Debug for RegionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RegionId")
            .field("dim", &self.dim())
            .field("x", &self.x())
            .field("z", &self.z())
            .finish()
    }
}

impl std::fmt::Display for RegionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "region[{},{}](dim {})", self.x(), self.z(), self.dim())
    }
}

/// 2D Morton(Z-order)编码,用于 region 内 chunk 排序。
///
/// 支持最大 16 bit 输入(32 位输出)。32x32 region 实际只用到 5 bit 各轴。
#[inline]
pub fn morton_encode_2d(x: u32, z: u32) -> u32 {
    debug_assert!(x < (1 << 16) && z < (1 << 16), "morton input overflow");
    let mut result = 0u32;
    let mut bits = x | z;
    let mut shift = 0;
    while bits != 0 {
        let bit_x = (x >> shift) & 1;
        let bit_z = (z >> shift) & 1;
        result |= bit_x << (shift * 2);
        result |= bit_z << (shift * 2 + 1);
        bits >>= 1;
        shift += 1;
    }
    result
}

/// 2D Morton 解码。
#[inline]
pub fn morton_decode_2d(morton: u32) -> (u32, u32) {
    let mut x = 0u32;
    let mut z = 0u32;
    let mut m = morton;
    let mut shift = 0;
    while m != 0 {
        x |= (m & 1) << shift;
        z |= ((m >> 1) & 1) << shift;
        m >>= 2;
        shift += 1;
    }
    (x, z)
}

/// 将 28 位有符号数符号扩展到 i32(设计 §2:axis 为 28-bit signed)。
#[inline]
const fn sign_extend_28(v: u64) -> i32 {
    let v = (v as u32) & (ChunkId::AXIS_MASK as u32);
    if v & (1u32 << (ChunkId::AXIS_BITS - 1)) != 0 {
        (v | !(ChunkId::AXIS_MASK as u32)) as i32
    } else {
        v as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_id_roundtrip() {
        for (dim, x, z) in [(0, 0, 0), (1, -100, 100), (2, 1_000_000, -1_000_000)] {
            let id = ChunkId::new(dim, x, z);
            assert_eq!(id.dim(), dim);
            assert_eq!(id.x(), x);
            assert_eq!(id.z(), z);
        }
    }

    #[test]
    fn morton_roundtrip() {
        for shift in 0..=5 {
            let size = 1u32 << shift;
            for x in 0..size {
                for z in 0..size {
                    let m = morton_encode_2d(x, z);
                    let (dx, dz) = morton_decode_2d(m);
                    assert_eq!((dx, dz), (x, z), "morton roundtrip ({},{})", x, z);
                }
            }
        }
    }
}

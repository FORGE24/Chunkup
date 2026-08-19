//! Chunk 状态机:bit flags + lifecycle(设计 §10-11)。

use crate::util;

/// StateEntry 大小(16 字节,设计 §3.1)。
pub const STATE_ENTRY_SIZE: usize = 16;

/// Lifecycle 状态(低 4 位,互斥,设计 §10.1)。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Lifecycle {
    Absent = 0,
    Archived = 1,
    CpuLoading = 2,
    CpuResident = 3,
    GpuStaging = 4,
    GpuResident = 5,
    GpuActive = 6,
    GpuDirty = 7,
    CpuSync = 8,
    Evicting = 9,
}

impl Lifecycle {
    pub fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0 => Self::Absent,
            1 => Self::Archived,
            2 => Self::CpuLoading,
            3 => Self::CpuResident,
            4 => Self::GpuStaging,
            5 => Self::GpuResident,
            6 => Self::GpuActive,
            7 => Self::GpuDirty,
            8 => Self::CpuSync,
            9 => Self::Evicting,
            _ => return None,
        })
    }
}

/// State flags 位掩码(设计 §10.2)。
pub mod state_flags {
    /// lifecycle 占低 4 位。
    pub const LIFECYCLE_MASK: u32 = 0x0F;

    /// block 数据已修改未同步。
    pub const DIRTY_BLOCK: u32 = 1 << 4;
    /// 光照已修改未同步。
    pub const DIRTY_LIGHT: u32 = 1 << 5;
    /// mesh 已修改未同步。
    pub const DIRTY_MESH: u32 = 1 << 6;
    /// density 已修改未同步。
    pub const DIRTY_DENSITY: u32 = 1 << 7;
    /// 不可驱逐(玩家所在 chunk / 进行中任务)。
    pub const PINNED: u32 = 1 << 8;
    /// 最近 N tick 访问过。
    pub const HOT: u32 = 1 << 9;
    /// 入 GPU 上传队列。
    pub const UPLOAD_PENDING: u32 = 1 << 10;
    /// 入 GPU->CPU 回写队列。
    pub const DOWNLOAD_PENDING: u32 = 1 << 11;
    /// worldgen 待跑。
    pub const GEN_PENDING: u32 = 1 << 12;
    /// mesh 待跑。
    pub const MESH_PENDING: u32 = 1 << 13;
    /// light 待跑。
    pub const LIGHT_PENDING: u32 = 1 << 14;
    /// checksum 失败。
    pub const CORRUPT: u32 = 1 << 15;
    /// 只读快照。
    pub const READONLY: u32 = 1 << 16;
    /// region 首 chunk,prefetch 锚点。
    pub const REGION_HEAD: u32 = 1 << 17;
    /// GPU 持有最新副本(权威)。
    pub const GPU_OWNED: u32 = 1 << 18;
    /// CPU 持有最新副本(权威)。
    pub const CPU_OWNED: u32 = 1 << 19;

    /// 所有 dirty 位。
    pub const DIRTY_ANY: u32 = DIRTY_BLOCK | DIRTY_LIGHT | DIRTY_MESH | DIRTY_DENSITY;
}

/// StateEntry(16 字节,热数据,可独立于 Descriptor 表存储)。
#[derive(Clone, Debug, Default)]
pub struct StateEntry {
    pub state_flags: u32,         // 0x00
    pub cpu_epoch: u32,            // 0x04
    pub gpu_epoch: u32,            // 0x08
    pub last_access_tick: u32,     // 0x0C
}

impl StateEntry {
    pub const SIZE: usize = STATE_ENTRY_SIZE;

    /// 当前 lifecycle。
    pub fn lifecycle(&self) -> Lifecycle {
        let v = (self.state_flags & state_flags::LIFECYCLE_MASK) as u8;
        Lifecycle::from_u8(v).unwrap_or(Lifecycle::Absent)
    }

    /// 设置 lifecycle(保留 flags)。
    pub fn set_lifecycle(&mut self, lc: Lifecycle) {
        self.state_flags = (self.state_flags & !state_flags::LIFECYCLE_MASK) | (lc as u32);
    }

    /// 是否含任一 dirty 位。
    pub fn is_dirty(&self) -> bool {
        self.state_flags & state_flags::DIRTY_ANY != 0
    }

    /// 标记某资源 dirty。
    pub fn mark_dirty(&mut self, flag: u32) {
        self.state_flags |= flag;
    }

    /// 清除 dirty。
    pub fn clear_dirty(&mut self, flag: u32) {
        self.state_flags &= !flag;
    }

    /// GPU 持有权威副本。
    pub fn is_gpu_owned(&self) -> bool {
        self.state_flags & state_flags::GPU_OWNED != 0
    }

    /// CPU 持有权威副本。
    pub fn is_cpu_owned(&self) -> bool {
        self.state_flags & state_flags::CPU_OWNED != 0
    }

    /// 是否被钉住(不可驱逐)。
    pub fn is_pinned(&self) -> bool {
        self.state_flags & state_flags::PINNED != 0
    }

    /// GPU 数据是否过期(设计 §11:gpu_epoch < cpu_epoch)。
    pub fn is_gpu_stale(&self) -> bool {
        self.gpu_epoch < self.cpu_epoch
    }

    /// 完成 CPU->GPU 同步:gpu_epoch 追平 cpu_epoch。
    pub fn sync_gpu_epoch(&mut self) {
        self.gpu_epoch = self.cpu_epoch;
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        util::write_u32(&mut buf, 0x00, self.state_flags);
        util::write_u32(&mut buf, 0x04, self.cpu_epoch);
        util::write_u32(&mut buf, 0x08, self.gpu_epoch);
        util::write_u32(&mut buf, 0x0C, self.last_access_tick);
        buf
    }

    pub fn from_bytes(buf: &[u8; Self::SIZE]) -> Self {
        StateEntry {
            state_flags: util::read_u32(buf, 0x00),
            cpu_epoch: util::read_u32(buf, 0x04),
            gpu_epoch: util::read_u32(buf, 0x08),
            last_access_tick: util::read_u32(buf, 0x0C),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_roundtrip() {
        let mut s = StateEntry::default();
        s.set_lifecycle(Lifecycle::GpuResident);
        s.mark_dirty(state_flags::DIRTY_BLOCK);
        s.cpu_epoch = 42;
        s.gpu_epoch = 41;
        s.last_access_tick = 1000;
        let b = s.to_bytes();
        let s2 = StateEntry::from_bytes(&b);
        assert_eq!(s2.lifecycle(), Lifecycle::GpuResident);
        assert!(s2.is_dirty());
        assert!(s2.is_gpu_stale());
        assert_eq!(s2.cpu_epoch, 42);
    }

    #[test]
    fn epoch_sync() {
        let mut s = StateEntry::default();
        s.cpu_epoch = 10;
        s.gpu_epoch = 5;
        assert!(s.is_gpu_stale());
        s.sync_gpu_epoch();
        assert!(!s.is_gpu_stale());
        assert_eq!(s.gpu_epoch, 10);
    }

    #[test]
    fn ownership() {
        let mut s = StateEntry::default();
        s.state_flags |= state_flags::GPU_OWNED;
        assert!(s.is_gpu_owned());
        assert!(!s.is_cpu_owned());
    }

    #[test]
    fn set_lifecycle_preserves_flags() {
        let mut s = StateEntry::default();
        s.mark_dirty(state_flags::DIRTY_MESH);
        s.state_flags |= state_flags::PINNED;
        s.set_lifecycle(Lifecycle::CpuResident);
        assert!(s.is_dirty());
        assert!(s.is_pinned());
        assert_eq!(s.lifecycle(), Lifecycle::CpuResident);
    }
}

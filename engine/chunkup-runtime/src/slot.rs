//! ChunkSlot:单个 chunk 在运行时内存中的状态容器。
//!
//! 一个 [ChunkSlot] 持有:
//! - [StateEntry]:CWA state 表镜像(epoch / flags / lifecycle)
//! - `cpu_payload`:Option<Box<[u8]>>,CPU RAM 中的解压 payload(可能为空)
//! - `gpu_handle`:Option<GpuBufferHandle>,GPU VRAM 中的 buffer 句柄(可能为空)
//! - `resident_size`:当前驻留字节数(CPU + GPU 合计,用于统计)
//!
//! 状态机转移见 [state_machine](crate::state_machine)。

use chunkup_cwa::state::{state_flags, Lifecycle, StateEntry};

use crate::gpu_handle::GpuBufferHandle;

/// 单个 chunk 的运行时槽位。
#[derive(Clone, Debug)]
pub struct ChunkSlot {
    /// CWA state 表镜像。
    pub state: StateEntry,
    /// CPU RAM payload(若 [Lifecycle::CpuResident] / [Lifecycle::CpuSync] 等持有)。
    pub cpu_payload: Option<Box<[u8]>>,
    /// GPU VRAM buffer 句柄(若 [Lifecycle::GpuResident] / [Lifecycle::GpuActive] 等持有)。
    pub gpu_handle: Option<GpuBufferHandle>,
    /// 当前驻留总字节数(CPU + GPU 合计;为避免重复统计,
    /// CpuResident→GpuStaging 期间 cpu_payload 仍计,GpuResident 完成后 cpu_payload 不计)。
    pub resident_size: u32,
    /// 调度优先级(镜像自 [ChunkDescriptor::priority_hint](chunkup_cwa::descriptor::ChunkDescriptor::priority_hint))。
    /// 越高越应保留;调度器评分时从驱逐分中扣减。
    pub priority_hint: u16,
}

impl Default for ChunkSlot {
    fn default() -> Self {
        ChunkSlot {
            state: StateEntry::default(),
            cpu_payload: None,
            gpu_handle: None,
            resident_size: 0,
            priority_hint: 0,
        }
    }
}

impl ChunkSlot {
    /// 构造 Absent 状态的空槽。
    pub fn absent() -> Self {
        let mut slot = ChunkSlot::default();
        slot.state.set_lifecycle(Lifecycle::Absent);
        slot
    }

    /// 构造 Archived 状态的空槽(磁盘上有,内存中无)。
    pub fn archived() -> Self {
        let mut slot = ChunkSlot::default();
        slot.state.set_lifecycle(Lifecycle::Archived);
        slot
    }

    /// 当前 lifecycle。
    pub fn lifecycle(&self) -> Lifecycle {
        self.state.lifecycle()
    }

    /// 是否持有 CPU payload。
    pub fn has_cpu_payload(&self) -> bool {
        self.cpu_payload.is_some()
    }

    /// 是否持有 GPU handle。
    pub fn has_gpu_handle(&self) -> bool {
        self.gpu_handle.is_some()
    }

    /// CPU payload 字节数(0 表示未驻留)。
    pub fn cpu_payload_size(&self) -> u32 {
        self.cpu_payload
            .as_ref()
            .map(|p| p.len() as u32)
            .unwrap_or(0)
    }

    /// GPU buffer 字节数(0 表示未驻留)。
    pub fn gpu_buffer_size(&self) -> u32 {
        self.gpu_handle.as_ref().map(|h| h.size).unwrap_or(0)
    }

    /// 释放 CPU payload(并返回其大小用于统计扣减)。
    pub fn drop_cpu_payload(&mut self) -> u32 {
        let size = self.cpu_payload_size();
        self.cpu_payload = None;
        size
    }

    /// 释放 GPU handle(并返回其大小用于统计扣减)。
    pub fn drop_gpu_handle(&mut self) -> u32 {
        let size = self.gpu_buffer_size();
        self.gpu_handle = None;
        size
    }

    /// 是否被钉住不可驱逐。
    pub fn is_pinned(&self) -> bool {
        self.state.is_pinned()
    }

    /// 是否任一 dirty 位。
    pub fn is_dirty(&self) -> bool {
        self.state.is_dirty()
    }

    /// 标记某资源 dirty。
    pub fn mark_dirty(&mut self, flag: u32) {
        self.state.mark_dirty(flag);
    }

    /// 清除 dirty。
    pub fn clear_dirty(&mut self, flag: u32) {
        self.state.clear_dirty(flag);
    }

    /// 设置 PINNED(防驱逐)。
    pub fn pin(&mut self) {
        self.state.state_flags |= state_flags::PINNED;
    }

    /// 清除 PINNED。
    pub fn unpin(&mut self) {
        self.state.state_flags &= !state_flags::PINNED;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slot_default_is_absent() {
        let s = ChunkSlot::default();
        assert_eq!(s.lifecycle(), Lifecycle::Absent);
        assert!(!s.has_cpu_payload());
        assert!(!s.has_gpu_handle());
        assert_eq!(s.resident_size, 0);
    }

    #[test]
    fn slot_archived_state() {
        let s = ChunkSlot::archived();
        assert_eq!(s.lifecycle(), Lifecycle::Archived);
        assert!(!s.has_cpu_payload());
    }

    #[test]
    fn slot_pin_unpin() {
        let mut s = ChunkSlot::absent();
        assert!(!s.is_pinned());
        s.pin();
        assert!(s.is_pinned());
        s.unpin();
        assert!(!s.is_pinned());
    }

    #[test]
    fn slot_drop_payload_returns_size() {
        let mut s = ChunkSlot::absent();
        s.cpu_payload = Some(vec![0u8; 1024].into_boxed_slice());
        s.gpu_handle = Some(GpuBufferHandle::new(42, 2048));
        assert_eq!(s.drop_cpu_payload(), 1024);
        assert!(!s.has_cpu_payload());
        assert_eq!(s.drop_gpu_handle(), 2048);
        assert!(!s.has_gpu_handle());
        // 二次 drop 返回 0
        assert_eq!(s.drop_cpu_payload(), 0);
        assert_eq!(s.drop_gpu_handle(), 0);
    }

    #[test]
    fn slot_dirty_tracking() {
        let mut s = ChunkSlot::absent();
        assert!(!s.is_dirty());
        s.mark_dirty(state_flags::DIRTY_BLOCK | state_flags::DIRTY_MESH);
        assert!(s.is_dirty());
        s.clear_dirty(state_flags::DIRTY_BLOCK);
        assert!(s.is_dirty()); // 仍有 DIRTY_MESH
        s.clear_dirty(state_flags::DIRTY_MESH);
        assert!(!s.is_dirty());
    }
}

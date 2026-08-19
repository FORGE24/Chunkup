//! ChunkRuntime:持有所有 chunk 的内存侧状态。
//!
//! 设计 §8:ChunkRuntime 是 CWA 磁盘格式与 CPU/GPU 异构执行之间的运行时壳。
//!
//! 职责:
//! - 维护 [HashMap](std::collections::HashMap)<ChunkId, [ChunkSlot]>
//! - 暴露状态机转移入口(委托给 [state_machine](crate::state_machine))
//! - 统计 CPU/GPU 驻留字节数,供调度器查询
//! - 触发 epoch 推进(每次 CPU/GPU 副本变化时)

use std::collections::HashMap;

use chunkup_cwa::id::ChunkId;
use chunkup_cwa::state::Lifecycle;

use crate::coordinator::DataLocation;
use crate::gpu_handle::GpuBufferHandle;
use crate::slot::ChunkSlot;
use crate::state_machine::{
    self, TransitionError, TransitionSideEffect,
};

/// ChunkRuntime:chunk 内存侧状态机容器。
pub struct ChunkRuntime {
    /// chunk_id -> slot。
    slots: HashMap<u64, ChunkSlot>,
    /// 当前 CPU RAM 总驻留字节(所有 CpuResident / CpuLoading / CpuSync 等)。
    cpu_resident_bytes: u64,
    /// 当前 GPU VRAM 总驻留字节(所有 GpuResident / GpuActive 等)。
    gpu_resident_bytes: u64,
    /// 全局 epoch 单调计数器。
    global_epoch: u32,
}

impl Default for ChunkRuntime {
    fn default() -> Self {
        ChunkRuntime {
            slots: HashMap::new(),
            cpu_resident_bytes: 0,
            gpu_resident_bytes: 0,
            global_epoch: 0,
        }
    }
}

impl ChunkRuntime {
    /// 创建空 runtime。
    pub fn new() -> Self {
        ChunkRuntime::default()
    }

    /// 创建指定初始容量的 runtime。
    pub fn with_capacity(cap: usize) -> Self {
        ChunkRuntime {
            slots: HashMap::with_capacity(cap),
            ..Default::default()
        }
    }

    /// 当前注册的 chunk 数。
    pub fn len(&self) -> usize {
        self.slots.len()
    }

    /// 是否为空。
    pub fn is_empty(&self) -> bool {
        self.slots.is_empty()
    }

    /// CPU RAM 驻留字节。
    pub fn cpu_resident_bytes(&self) -> u64 {
        self.cpu_resident_bytes
    }

    /// GPU VRAM 驻留字节。
    pub fn gpu_resident_bytes(&self) -> u64 {
        self.gpu_resident_bytes
    }

    /// 全局 epoch。
    pub fn global_epoch(&self) -> u32 {
        self.global_epoch
    }

    /// 推进全局 epoch(通常由主 tick 调用)。
    pub fn advance_epoch(&mut self) {
        self.global_epoch = self.global_epoch.wrapping_add(1);
    }

    /// 注册一个新 chunk(初始 [Lifecycle::Archived])。
    ///
    /// 若已存在,返回 false 但不覆盖。
    pub fn register_archived(&mut self, chunk_id: ChunkId) -> bool {
        if self.slots.contains_key(&chunk_id.0) {
            return false;
        }
        self.slots.insert(chunk_id.0, ChunkSlot::archived());
        true
    }

    /// 强制移除一个 chunk(忽略 lifecycle,内部用)。
    pub fn remove(&mut self, chunk_id: ChunkId) -> Option<ChunkSlot> {
        if let Some(slot) = self.slots.remove(&chunk_id.0) {
            self.cpu_resident_bytes =
                self.cpu_resident_bytes.saturating_sub(slot.cpu_payload_size() as u64);
            self.gpu_resident_bytes =
                self.gpu_resident_bytes.saturating_sub(slot.gpu_buffer_size() as u64);
            Some(slot)
        } else {
            None
        }
    }

    /// 获取 slot 只读引用。
    pub fn get(&self, chunk_id: ChunkId) -> Option<&ChunkSlot> {
        self.slots.get(&chunk_id.0)
    }

    /// 获取 slot 可变引用。
    pub fn get_mut(&mut self, chunk_id: ChunkId) -> Option<&mut ChunkSlot> {
        self.slots.get_mut(&chunk_id.0)
    }

    // =====================================================================
    // 状态机转移入口(委托给 state_machine)
    // =====================================================================

    /// Archived → CpuLoading。
    pub fn begin_cpu_load(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::begin_cpu_load(slot)?;
        Ok(())
    }

    /// CpuLoading → CpuResident。
    ///
    /// 调用方提供 `cpu_payload`,本函数负责放入 slot 并推进状态。
    pub fn finish_cpu_load(
        &mut self,
        chunk_id: ChunkId,
        cpu_payload: Box<[u8]>,
    ) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        let size = cpu_payload.len() as u32;
        slot.cpu_payload = Some(cpu_payload);
        state_machine::finish_cpu_load(slot)?;
        self.cpu_resident_bytes += size as u64;
        Ok(())
    }

    /// CpuResident → GpuStaging。
    pub fn begin_gpu_stage(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::begin_gpu_stage(slot)?;
        Ok(())
    }

    /// GpuStaging → GpuResident。
    ///
    /// 调用方提供 `gpu_handle`,本函数负责放入 slot 并推进状态。
    pub fn finish_gpu_stage(
        &mut self,
        chunk_id: ChunkId,
        gpu_handle: GpuBufferHandle,
    ) -> Result<(), TransitionError> {
        let size = gpu_handle.size;
        let slot = self.slot_or_err(chunk_id)?;
        slot.gpu_handle = Some(gpu_handle);
        state_machine::finish_gpu_stage(slot)?;
        self.gpu_resident_bytes += size as u64;
        Ok(())
    }

    /// GpuResident → GpuActive。
    pub fn activate(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::activate(slot)?;
        Ok(())
    }

    /// GpuActive/GpuResident → GpuDirty。
    pub fn mark_gpu_dirty(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::mark_gpu_dirty(slot)?;
        Ok(())
    }

    /// GpuDirty → CpuSync。
    pub fn begin_cpu_sync(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::begin_cpu_sync(slot)?;
        Ok(())
    }

    /// CpuSync → CpuResident。
    ///
    /// 调用方提供同步后的新 `cpu_payload`。
    pub fn finish_cpu_sync(
        &mut self,
        chunk_id: ChunkId,
        new_cpu_payload: Box<[u8]>,
    ) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        let old_size = slot.cpu_payload_size();
        slot.cpu_payload = Some(new_cpu_payload);
        let new_size = slot.cpu_payload_size();
        state_machine::finish_cpu_sync(slot)?;
        // 调整 CPU 统计(可能 payload 大小变化)
        if new_size > old_size {
            self.cpu_resident_bytes += (new_size - old_size) as u64;
        } else {
            self.cpu_resident_bytes =
                self.cpu_resident_bytes.saturating_sub((old_size - new_size) as u64);
        }
        Ok(())
    }

    /// 触发驱逐(从 CpuResident / GpuResident / GpuActive / Archived 进入 Evicting)。
    pub fn begin_evict(&mut self, chunk_id: ChunkId) -> Result<(), TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        state_machine::begin_evict(slot)?;
        Ok(())
    }

    /// 完成驱逐:释放所有 payload,lifecycle → Absent。
    pub fn finish_evict(&mut self, chunk_id: ChunkId) -> Result<TransitionSideEffect, TransitionError> {
        let slot = self.slot_or_err(chunk_id)?;
        let effect = state_machine::finish_evict(slot)?;
        self.cpu_resident_bytes =
            self.cpu_resident_bytes.saturating_sub(effect.freed_cpu_bytes as u64);
        self.gpu_resident_bytes =
            self.gpu_resident_bytes.saturating_sub(effect.freed_gpu_bytes as u64);
        Ok(effect)
    }

    /// 钉住某 chunk(防驱逐)。
    pub fn pin(&mut self, chunk_id: ChunkId) -> bool {
        if let Some(slot) = self.slots.get_mut(&chunk_id.0) {
            slot.pin();
            true
        } else {
            false
        }
    }

    /// 解除钉住。
    pub fn unpin(&mut self, chunk_id: ChunkId) -> bool {
        if let Some(slot) = self.slots.get_mut(&chunk_id.0) {
            slot.unpin();
            true
        } else {
            false
        }
    }

    /// 遍历所有 slot(只读)。
    pub fn iter(&self) -> impl Iterator<Item = (ChunkId, &ChunkSlot)> {
        self.slots.iter().map(|(&id, slot)| (ChunkId(id), slot))
    }

    /// 收集所有 PINNED chunk_id。
    pub fn pinned_chunks(&self) -> Vec<ChunkId> {
        self.slots
            .iter()
            .filter(|(_, s)| s.is_pinned())
            .map(|(&id, _)| ChunkId(id))
            .collect()
    }

    /// 收集所有 GPU dirty(chunk 需要回写 CPU)。
    pub fn gpu_dirty_chunks(&self) -> Vec<ChunkId> {
        self.slots
            .iter()
            .filter(|(_, s)| s.lifecycle() == Lifecycle::GpuDirty)
            .map(|(&id, _)| ChunkId(id))
            .collect()
    }

    /// 查询 chunk 的 block/density 数据当前所在地。
    ///
    /// 用于 [LoadCoordinator::plan_next](crate::coordinator::LoadCoordinator::plan_next)
    /// 分派 air 判定到 CPU 或 GPU kernel:
    /// - `GPU_OWNED` 且持有 `gpu_handle` → [DataLocation::Gpu](crate::coordinator::DataLocation::Gpu)
    ///   (数据全程留 VRAM,不回拉 CPU)
    /// - `CPU_OWNED` 且持有 `cpu_payload` → [DataLocation::Cpu](crate::coordinator::DataLocation::Cpu)
    /// - 其余(未注册 / 未驻留 / Evicting 中) → [DataLocation::Absent](crate::coordinator::DataLocation::Absent)
    pub fn chunk_data_location(&self, chunk_id: ChunkId) -> DataLocation {
        match self.slots.get(&chunk_id.0) {
            None => DataLocation::Absent,
            Some(slot) => {
                if slot.state.is_gpu_owned() && slot.has_gpu_handle() {
                    DataLocation::Gpu
                } else if slot.state.is_cpu_owned() && slot.has_cpu_payload() {
                    DataLocation::Cpu
                } else if slot.has_gpu_handle() {
                    // 有 GPU handle 但非 GPU_OWNED(例如 CpuResident 阶段保留的旧 GPU 副本)
                    DataLocation::Gpu
                } else if slot.has_cpu_payload() {
                    DataLocation::Cpu
                } else {
                    DataLocation::Absent
                }
            }
        }
    }

    fn slot_or_err(&mut self, chunk_id: ChunkId) -> Result<&mut ChunkSlot, TransitionError> {
        self.slots
            .get_mut(&chunk_id.0)
            .ok_or(TransitionError::Unsupported {
                from: Lifecycle::Absent,
                to: Lifecycle::Absent,
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chunkup_cwa::id::ChunkId;

    fn cid(x: i32, z: i32) -> ChunkId {
        ChunkId::new(0, x, z)
    }

    #[test]
    fn register_and_lookup() {
        let mut rt = ChunkRuntime::new();
        let id = cid(0, 0);
        assert!(rt.register_archived(id));
        assert!(!rt.register_archived(id)); // 重复
        assert_eq!(rt.len(), 1);

        let slot = rt.get(id).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::Archived);
    }

    #[test]
    fn full_lifecycle_via_runtime() {
        let mut rt = ChunkRuntime::new();
        let id = cid(1, 1);
        rt.register_archived(id);

        // CPU 加载
        rt.begin_cpu_load(id).unwrap();
        let payload = vec![0u8; 4096].into_boxed_slice();
        rt.finish_cpu_load(id, payload).unwrap();
        assert_eq!(rt.cpu_resident_bytes(), 4096);
        assert_eq!(rt.get(id).unwrap().lifecycle(), Lifecycle::CpuResident);

        // GPU 上传
        rt.begin_gpu_stage(id).unwrap();
        let handle = GpuBufferHandle::new(id.0, 4096);
        rt.finish_gpu_stage(id, handle).unwrap();
        assert_eq!(rt.gpu_resident_bytes(), 4096);
        assert_eq!(rt.get(id).unwrap().lifecycle(), Lifecycle::GpuResident);

        // 激活
        rt.activate(id).unwrap();

        // dirty
        rt.mark_gpu_dirty(id).unwrap();
        let dirty = rt.gpu_dirty_chunks();
        assert_eq!(dirty.len(), 1);
        assert_eq!(dirty[0], id);

        // CPU 同步
        rt.begin_cpu_sync(id).unwrap();
        let new_payload = vec![1u8; 4096].into_boxed_slice();
        rt.finish_cpu_sync(id, new_payload).unwrap();
        assert!(!rt.get(id).unwrap().is_dirty());

        // 驱逐
        rt.begin_evict(id).unwrap();
        let effect = rt.finish_evict(id).unwrap();
        assert_eq!(effect.freed_cpu_bytes, 4096);
        assert_eq!(effect.freed_gpu_bytes, 4096);
        assert_eq!(rt.cpu_resident_bytes(), 0);
        assert_eq!(rt.gpu_resident_bytes(), 0);
        assert_eq!(rt.get(id).unwrap().lifecycle(), Lifecycle::Absent);
    }

    #[test]
    fn pin_blocks_evict() {
        let mut rt = ChunkRuntime::new();
        let id = cid(2, 2);
        rt.register_archived(id);
        rt.begin_cpu_load(id).unwrap();
        rt.finish_cpu_load(id, vec![0u8; 100].into_boxed_slice()).unwrap();
        rt.pin(id);

        // PINNED 拒绝驱逐
        let err = rt.begin_evict(id);
        assert_eq!(err, Err(TransitionError::Pinned));

        // unpin 后可驱逐
        rt.unpin(id);
        rt.begin_evict(id).unwrap();
        rt.finish_evict(id).unwrap();
    }

    #[test]
    fn stats_track_payload_changes() {
        let mut rt = ChunkRuntime::new();
        let id = cid(3, 3);
        rt.register_archived(id);
        rt.begin_cpu_load(id).unwrap();
        rt.finish_cpu_load(id, vec![0u8; 100].into_boxed_slice()).unwrap();
        assert_eq!(rt.cpu_resident_bytes(), 100);

        // mark dirty -> sync 后 payload 大小变化
        rt.begin_gpu_stage(id).unwrap();
        rt.finish_gpu_stage(id, GpuBufferHandle::new(id.0, 200)).unwrap();
        rt.activate(id).unwrap();
        rt.mark_gpu_dirty(id).unwrap();
        rt.begin_cpu_sync(id).unwrap();
        rt.finish_cpu_sync(id, vec![0u8; 150].into_boxed_slice()).unwrap();
        assert_eq!(rt.cpu_resident_bytes(), 150);
    }

    #[test]
    fn remove_drops_stats() {
        let mut rt = ChunkRuntime::new();
        let id = cid(4, 4);
        rt.register_archived(id);
        rt.begin_cpu_load(id).unwrap();
        rt.finish_cpu_load(id, vec![0u8; 256].into_boxed_slice()).unwrap();
        assert_eq!(rt.cpu_resident_bytes(), 256);

        rt.remove(id);
        assert_eq!(rt.cpu_resident_bytes(), 0);
        assert_eq!(rt.len(), 0);
    }

    #[test]
    fn iter_visits_all_slots() {
        let mut rt = ChunkRuntime::new();
        rt.register_archived(cid(0, 0));
        rt.register_archived(cid(1, 0));
        rt.register_archived(cid(0, 1));

        let count = rt.iter().count();
        assert_eq!(count, 3);
    }

    #[test]
    fn pinned_chunks_list() {
        let mut rt = ChunkRuntime::new();
        rt.register_archived(cid(0, 0));
        rt.register_archived(cid(1, 0));
        rt.register_archived(cid(2, 0));
        rt.pin(cid(1, 0));

        let pinned = rt.pinned_chunks();
        assert_eq!(pinned.len(), 1);
    }

    #[test]
    fn epoch_advances() {
        let mut rt = ChunkRuntime::new();
        assert_eq!(rt.global_epoch(), 0);
        rt.advance_epoch();
        rt.advance_epoch();
        assert_eq!(rt.global_epoch(), 2);
    }

    #[test]
    fn transition_on_missing_chunk_errors() {
        let mut rt = ChunkRuntime::new();
        let id = cid(99, 99);
        let err = rt.begin_cpu_load(id);
        assert!(err.is_err());
    }

    #[test]
    fn chunk_data_location_tracks_lifecycle() {
        let mut rt = ChunkRuntime::new();
        let id = cid(5, 5);

        // 未注册 → Absent
        assert_eq!(rt.chunk_data_location(id), DataLocation::Absent);

        // Archived → Absent(磁盘有,内存无)
        rt.register_archived(id);
        assert_eq!(rt.chunk_data_location(id), DataLocation::Absent);

        // CpuResident → Cpu
        rt.begin_cpu_load(id).unwrap();
        rt.finish_cpu_load(id, vec![0u8; 100].into_boxed_slice()).unwrap();
        assert_eq!(rt.chunk_data_location(id), DataLocation::Cpu);

        // GpuResident → Gpu(数据搬到 VRAM,CPU_OWNED 清除)
        rt.begin_gpu_stage(id).unwrap();
        rt.finish_gpu_stage(id, GpuBufferHandle::new(id.0, 100)).unwrap();
        assert_eq!(rt.chunk_data_location(id), DataLocation::Gpu);

        // GpuActive → 仍 Gpu
        rt.activate(id).unwrap();
        assert_eq!(rt.chunk_data_location(id), DataLocation::Gpu);

        // 回写后 → Cpu(GPU_OWNED 清除,CPU_OWNED 置位)
        rt.mark_gpu_dirty(id).unwrap();
        rt.begin_cpu_sync(id).unwrap();
        rt.finish_cpu_sync(id, vec![0u8; 100].into_boxed_slice()).unwrap();
        assert_eq!(rt.chunk_data_location(id), DataLocation::Cpu);

        // 驱逐后 → Absent
        rt.begin_evict(id).unwrap();
        rt.finish_evict(id).unwrap();
        assert_eq!(rt.chunk_data_location(id), DataLocation::Absent);
    }

    #[test]
    fn chunk_data_location_absent_for_unknown() {
        let rt = ChunkRuntime::new();
        assert_eq!(rt.chunk_data_location(cid(0, 0)), DataLocation::Absent);
    }
}

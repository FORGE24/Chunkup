//! Chunk 状态机转移函数(设计 §8-11)。
//!
//! ## 完整状态图
//!
//! ```text
//!                          ┌─── (player join) ───┐
//!                          ▼                     │
//!  Absent ──load_meta──▶ Archived ──load_cpu──▶ CpuLoading
//!                                                  │
//!                                                  ▼ (cpu_payload ready)
//!                                              CpuResident ◀──────┐
//!                                                  │               │
//!                                                  ├──stage_to_gpu─┤
//!                                                  ▼               │
//!                                              GpuStaging          │
//!                                                  │               │
//!                                                  ▼ (upload done) │
//!                                              GpuResident         │
//!                                                  │               │
//!                                                  ├──activate─────┤
//!                                                  ▼               │
//!                                              GpuActive           │
//!                                                  │               │
//!                                                  ├──mark_dirty───┤
//!                                                  ▼               │
//!                                              GpuDirty            │
//!                                                  │               │
//!                                                  ├──sync_to_cpu──┤
//!                                                  ▼               │
//!                                              CpuSync             │
//!                                                  │               │
//!                                                  ▼ (cpu write back)│
//!                                              CpuResident ────────┘
//!                                                  │
//!                                                  ├──evict──▶ Evicting ──▶ Absent
//! ```
//!
//! ## 转移合法性
//!
//! 每个转移都校验 `from` lifecycle,不匹配返回 [TransitionError]。
//! 调用方应捕获错误并触发回退路径(例如 GPU 上传失败回退到 CpuResident)。

use chunkup_cwa::state::{state_flags, Lifecycle};

use crate::slot::ChunkSlot;

/// 状态机转移错误。
#[derive(Debug, PartialEq, Eq)]
pub enum TransitionError {
    /// 起始 lifecycle 与预期不符。
    UnexpectedFrom {
        expected: Lifecycle,
        actual: Lifecycle,
    },
    /// slot 被钉住,无法驱逐。
    Pinned,
    /// 试图在未持有 CPU payload 时执行 GPU 上传 / CPU 同步。
    MissingCpuPayload,
    /// 试图在未持有 GPU handle 时执行 GPU->CPU 同步。
    MissingGpuHandle,
    /// 不支持的转移路径。
    Unsupported {
        from: Lifecycle,
        to: Lifecycle,
    },
}

/// 转移结果:返回更新后的 slot 与(可能的)副作用描述。
#[derive(Debug, PartialEq, Eq)]
pub struct TransitionSideEffect {
    /// 已释放的 CPU payload 字节数(若发生)。
    pub freed_cpu_bytes: u32,
    /// 已释放的 GPU buffer 字节数(若发生)。
    pub freed_gpu_bytes: u32,
    /// 新设置的 lifecycle。
    pub new_lifecycle: Lifecycle,
}

impl Default for TransitionSideEffect {
    fn default() -> Self {
        TransitionSideEffect {
            freed_cpu_bytes: 0,
            freed_gpu_bytes: 0,
            new_lifecycle: Lifecycle::Absent,
        }
    }
}

// =========================================================================
// 各转移函数
// =========================================================================

/// Archived → CpuLoading:开始从磁盘加载 chunk payload 到 CPU RAM。
pub fn begin_cpu_load(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::Archived)?;
    slot.state.set_lifecycle(Lifecycle::CpuLoading);
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::CpuLoading,
        ..Default::default()
    })
}

/// CpuLoading → CpuResident:CPU payload 已就绪。
///
/// 调用方应先设置 `slot.cpu_payload`,再调用此函数。
pub fn finish_cpu_load(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::CpuLoading)?;
    if !slot.has_cpu_payload() {
        return Err(TransitionError::MissingCpuPayload);
    }
    slot.state.set_lifecycle(Lifecycle::CpuResident);
    // CPU 持有权威副本
    slot.state.state_flags |= state_flags::CPU_OWNED;
    slot.state.state_flags &= !state_flags::GPU_OWNED;
    slot.resident_size = slot.cpu_payload_size();
    slot.state.cpu_epoch = slot.state.cpu_epoch.wrapping_add(1);
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::CpuResident,
        ..Default::default()
    })
}

/// CpuResident → GpuStaging:开始把 payload 上传到 GPU。
pub fn begin_gpu_stage(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::CpuResident)?;
    if !slot.has_cpu_payload() {
        return Err(TransitionError::MissingCpuPayload);
    }
    slot.state.set_lifecycle(Lifecycle::GpuStaging);
    slot.state.state_flags |= state_flags::UPLOAD_PENDING;
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::GpuStaging,
        ..Default::default()
    })
}

/// GpuStaging → GpuResident:GPU 上传完成。
///
/// 调用方应先设置 `slot.gpu_handle`,再调用此函数。
pub fn finish_gpu_stage(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::GpuStaging)?;
    if !slot.has_gpu_handle() {
        return Err(TransitionError::MissingGpuHandle);
    }
    slot.state.set_lifecycle(Lifecycle::GpuResident);
    slot.state.state_flags &= !state_flags::UPLOAD_PENDING;
    // GPU 持有权威副本(可选保留 CPU 副本用于回读)
    slot.state.state_flags |= state_flags::GPU_OWNED;
    slot.state.state_flags &= !state_flags::CPU_OWNED;
    slot.state.sync_gpu_epoch();
    slot.resident_size = slot.cpu_payload_size() + slot.gpu_buffer_size();
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::GpuResident,
        ..Default::default()
    })
}

/// GpuResident → GpuActive:GPU buffer 进入活跃渲染状态。
pub fn activate(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::GpuResident)?;
    if !slot.has_gpu_handle() {
        return Err(TransitionError::MissingGpuHandle);
    }
    slot.state.set_lifecycle(Lifecycle::GpuActive);
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::GpuActive,
        ..Default::default()
    })
}

/// GpuActive → GpuDirty:GPU 数据被修改,需要回写到 CPU。
pub fn mark_gpu_dirty(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    // 也允许 GpuResident 直接 mark dirty(尚未 active)
    let lc = slot.lifecycle();
    if lc != Lifecycle::GpuActive && lc != Lifecycle::GpuResident {
        return Err(TransitionError::UnexpectedFrom {
            expected: Lifecycle::GpuActive,
            actual: lc,
        });
    }
    slot.state.set_lifecycle(Lifecycle::GpuDirty);
    slot.state.mark_dirty(state_flags::DIRTY_BLOCK);
    slot.state.cpu_epoch = slot.state.cpu_epoch.wrapping_add(1);
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::GpuDirty,
        ..Default::default()
    })
}

/// GpuDirty → CpuSync:开始把 GPU 数据回写到 CPU。
pub fn begin_cpu_sync(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::GpuDirty)?;
    if !slot.has_gpu_handle() {
        return Err(TransitionError::MissingGpuHandle);
    }
    slot.state.set_lifecycle(Lifecycle::CpuSync);
    slot.state.state_flags |= state_flags::DOWNLOAD_PENDING;
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::CpuSync,
        ..Default::default()
    })
}

/// CpuSync → CpuResident:CPU 回写完成。
///
/// 调用方应先更新 `slot.cpu_payload`(从 GPU 读取的最新数据),再调用此函数。
pub fn finish_cpu_sync(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::CpuSync)?;
    if !slot.has_cpu_payload() {
        return Err(TransitionError::MissingCpuPayload);
    }
    slot.state.set_lifecycle(Lifecycle::CpuResident);
    slot.state.state_flags &= !state_flags::DOWNLOAD_PENDING;
    slot.state.clear_dirty(state_flags::DIRTY_ANY);
    slot.state.state_flags |= state_flags::CPU_OWNED;
    slot.state.state_flags &= !state_flags::GPU_OWNED;
    slot.resident_size = slot.cpu_payload_size();
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::CpuResident,
        ..Default::default()
    })
}

/// CpuResident / GpuResident / GpuActive → Evicting:开始驱逐。
///
/// PINNED 的 chunk 不可驱逐。
pub fn begin_evict(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    let lc = slot.lifecycle();
    if lc != Lifecycle::CpuResident
        && lc != Lifecycle::GpuResident
        && lc != Lifecycle::GpuActive
        && lc != Lifecycle::Archived
    {
        return Err(TransitionError::Unsupported {
            from: lc,
            to: Lifecycle::Evicting,
        });
    }
    if slot.is_pinned() {
        return Err(TransitionError::Pinned);
    }
    slot.state.set_lifecycle(Lifecycle::Evicting);
    Ok(TransitionSideEffect {
        new_lifecycle: Lifecycle::Evicting,
        ..Default::default()
    })
}

/// Evicting → Absent:驱逐完成,释放所有 payload。
pub fn finish_evict(slot: &mut ChunkSlot) -> Result<TransitionSideEffect, TransitionError> {
    expect_from(slot, Lifecycle::Evicting)?;
    let freed_cpu = slot.drop_cpu_payload();
    let freed_gpu = slot.drop_gpu_handle();
    slot.state.set_lifecycle(Lifecycle::Absent);
    slot.state.state_flags &= !(state_flags::CPU_OWNED | state_flags::GPU_OWNED);
    slot.resident_size = 0;
    Ok(TransitionSideEffect {
        freed_cpu_bytes: freed_cpu,
        freed_gpu_bytes: freed_gpu,
        new_lifecycle: Lifecycle::Absent,
    })
}

// =========================================================================
// 工具
// =========================================================================

fn expect_from(slot: &ChunkSlot, expected: Lifecycle) -> Result<(), TransitionError> {
    let actual = slot.lifecycle();
    if actual != expected {
        Err(TransitionError::UnexpectedFrom { expected, actual })
    } else {
        Ok(())
    }
}

/// 通用 transition 入口:根据 from/to 选择对应函数。
///
/// 用于调度器或网络事件驱动场景,根据目标状态自动选择路径。
///
/// **路由顺序**:先按 `(from, to)` 分派到具体转移函数,再由该函数内部校验
/// slot 的实际 lifecycle。这保证不支持的 `(from, to)` 组合一定返回
/// [TransitionError::Unsupported],而不是被实际状态校验拦截成
/// [TransitionError::UnexpectedFrom]。
pub fn transition(
    slot: &mut ChunkSlot,
    from: Lifecycle,
    to: Lifecycle,
) -> Result<TransitionSideEffect, TransitionError> {
    match (from, to) {
        (Lifecycle::Archived, Lifecycle::CpuLoading) => begin_cpu_load(slot),
        (Lifecycle::CpuLoading, Lifecycle::CpuResident) => finish_cpu_load(slot),
        (Lifecycle::CpuResident, Lifecycle::GpuStaging) => begin_gpu_stage(slot),
        (Lifecycle::GpuStaging, Lifecycle::GpuResident) => finish_gpu_stage(slot),
        (Lifecycle::GpuResident, Lifecycle::GpuActive) => activate(slot),
        (Lifecycle::GpuActive, Lifecycle::GpuDirty)
        | (Lifecycle::GpuResident, Lifecycle::GpuDirty) => mark_gpu_dirty(slot),
        (Lifecycle::GpuDirty, Lifecycle::CpuSync) => begin_cpu_sync(slot),
        (Lifecycle::CpuSync, Lifecycle::CpuResident) => finish_cpu_sync(slot),
        (Lifecycle::CpuResident, Lifecycle::Evicting)
        | (Lifecycle::GpuResident, Lifecycle::Evicting)
        | (Lifecycle::GpuActive, Lifecycle::Evicting)
        | (Lifecycle::Archived, Lifecycle::Evicting) => begin_evict(slot),
        (Lifecycle::Evicting, Lifecycle::Absent) => finish_evict(slot),
        _ => Err(TransitionError::Unsupported { from, to }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpu_handle::GpuBufferHandle;
    use chunkup_cwa::state::StateEntry;

    fn slot_with_state(lc: Lifecycle) -> ChunkSlot {
        let mut s = StateEntry::default();
        s.set_lifecycle(lc);
        ChunkSlot {
            state: s,
            ..ChunkSlot::default()
        }
    }

    #[test]
    fn full_lifecycle_archived_to_active_to_absent() {
        let mut slot = slot_with_state(Lifecycle::Archived);

        // Archived -> CpuLoading
        begin_cpu_load(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuLoading);

        // CpuLoading -> CpuResident (需要先放 cpu_payload)
        slot.cpu_payload = Some(vec![0u8; 4096].into_boxed_slice());
        finish_cpu_load(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuResident);
        assert!(slot.state.is_cpu_owned());
        assert!(!slot.state.is_gpu_owned());
        assert_eq!(slot.resident_size, 4096);

        // CpuResident -> GpuStaging
        begin_gpu_stage(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::GpuStaging);

        // GpuStaging -> GpuResident
        slot.gpu_handle = Some(GpuBufferHandle::new(42, 4096));
        finish_gpu_stage(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::GpuResident);
        assert!(slot.state.is_gpu_owned());
        assert!(!slot.state.is_cpu_owned());

        // GpuResident -> GpuActive
        activate(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::GpuActive);

        // GpuActive -> GpuDirty
        mark_gpu_dirty(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::GpuDirty);
        assert!(slot.is_dirty());

        // GpuDirty -> CpuSync
        begin_cpu_sync(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuSync);

        // CpuSync -> CpuResident (cpu_payload 应已有,假设已同步更新)
        finish_cpu_sync(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuResident);
        assert!(!slot.is_dirty());

        // CpuResident -> Evicting
        begin_evict(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::Evicting);

        // Evicting -> Absent
        let eff = finish_evict(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::Absent);
        assert_eq!(eff.freed_cpu_bytes, 4096);
        assert_eq!(eff.freed_gpu_bytes, 4096);
        assert_eq!(slot.resident_size, 0);
    }

    #[test]
    fn begin_cpu_load_requires_archived() {
        let mut slot = slot_with_state(Lifecycle::CpuResident);
        assert_eq!(
            begin_cpu_load(&mut slot),
            Err(TransitionError::UnexpectedFrom {
                expected: Lifecycle::Archived,
                actual: Lifecycle::CpuResident
            })
        );
    }

    #[test]
    fn finish_cpu_load_requires_payload() {
        let mut slot = slot_with_state(Lifecycle::CpuLoading);
        // 未设置 cpu_payload
        assert_eq!(
            finish_cpu_load(&mut slot),
            Err(TransitionError::MissingCpuPayload)
        );

        // 设置后再转移
        slot.cpu_payload = Some(vec![0u8; 100].into_boxed_slice());
        finish_cpu_load(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuResident);
    }

    #[test]
    fn finish_gpu_stage_requires_handle() {
        let mut slot = slot_with_state(Lifecycle::GpuStaging);
        assert_eq!(
            finish_gpu_stage(&mut slot),
            Err(TransitionError::MissingGpuHandle)
        );

        slot.gpu_handle = Some(GpuBufferHandle::new(1, 100));
        finish_gpu_stage(&mut slot).unwrap();
    }

    #[test]
    fn evict_pinned_rejected() {
        let mut slot = slot_with_state(Lifecycle::CpuResident);
        slot.cpu_payload = Some(vec![0u8; 100].into_boxed_slice());
        slot.pin();
        assert_eq!(begin_evict(&mut slot), Err(TransitionError::Pinned));

        // unpin 后可以驱逐
        slot.unpin();
        begin_evict(&mut slot).unwrap();
    }

    #[test]
    fn transition_dispatch_routes_correctly() {
        let mut slot = slot_with_state(Lifecycle::Archived);
        transition(&mut slot, Lifecycle::Archived, Lifecycle::CpuLoading).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::CpuLoading);

        // 不支持的路径
        let err = transition(&mut slot, Lifecycle::Absent, Lifecycle::GpuActive);
        assert!(matches!(err, Err(TransitionError::Unsupported { .. })));
    }

    #[test]
    fn transition_validates_from() {
        let mut slot = slot_with_state(Lifecycle::CpuResident);
        // 当前 CpuResident,但谎称 from=Archived
        let err = transition(&mut slot, Lifecycle::Archived, Lifecycle::CpuLoading);
        assert_eq!(
            err,
            Err(TransitionError::UnexpectedFrom {
                expected: Lifecycle::Archived,
                actual: Lifecycle::CpuResident
            })
        );
        // 状态不应被改
        assert_eq!(slot.lifecycle(), Lifecycle::CpuResident);
    }

    #[test]
    fn mark_gpu_dirty_allows_gpu_resident() {
        let mut slot = slot_with_state(Lifecycle::GpuResident);
        slot.gpu_handle = Some(GpuBufferHandle::new(1, 100));
        mark_gpu_dirty(&mut slot).unwrap();
        assert_eq!(slot.lifecycle(), Lifecycle::GpuDirty);
    }

    #[test]
    fn epoch_advances_on_dirty() {
        let mut slot = slot_with_state(Lifecycle::GpuActive);
        slot.gpu_handle = Some(GpuBufferHandle::new(1, 100));
        let before = slot.state.cpu_epoch;
        mark_gpu_dirty(&mut slot).unwrap();
        assert_eq!(slot.state.cpu_epoch, before.wrapping_add(1));
    }
}

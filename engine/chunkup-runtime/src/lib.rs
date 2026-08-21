//! Chunkup Runtime:CPU/GPU 异构运行时壳。
//!
//! 把 CWA 磁盘格式与 CPU/GPU 异构执行桥接起来。
//!
//! ## 模块组成
//!
//! - [slot]: [ChunkSlot] 单个 chunk 内存侧状态容器
//! - [state_machine]: lifecycle 转移函数(Archived → CpuResident → GpuActive → Absent)
//! - [runtime]: [ChunkRuntime] 持有所有 chunk 的 HashMap,暴露状态机入口
//! - [gpu_handle]: [GpuBufferHandle] GPU buffer 句柄占位
//! - [scheduler]: [ResidencyScheduler] Disk/CPU/GPU 三层缓存 LRU+Pin 驱逐
//! - [coordinator]: [LoadCoordinator] 按 SectionY 自上而下编排 CPU/GPU 面加载
//!
//! ## 状态图
//!
//! ```text
//!  Absent ──register──▶ Archived ──load_cpu──▶ CpuLoading ──▶ CpuResident
//!                                                              │
//!                                                              ▼
//!                                                          GpuStaging ──▶ GpuResident ──▶ GpuActive
//!                                                              ▲                │
//!                                                              │                ▼
//!                                                          CpuSync ◀── GpuDirty
//!                                                              │
//!                                                              ▼
//!                                                          CpuResident ──evict──▶ Evicting ──▶ Absent
//! ```

#![deny(rust_2018_idioms)]
#![warn(missing_docs)]

pub mod coordinator;
pub mod dual_coordinator;
pub mod gpu_handle;
pub mod runtime;
pub mod scheduler;
pub mod slot;
pub mod state_machine;

pub use coordinator::{CpuTask, DataLocation, GpuTask, LoadCoordinator, SectionLoadPlan};
pub use dual_coordinator::{BackendLane, CoordinatorStats, DispatchResult, DispatchStatus, DispatchTask, DualBackendCoordinator, TaskStage};
pub use gpu_handle::GpuBufferHandle;
pub use runtime::ChunkRuntime;
pub use scheduler::{EvictionCandidate, EvictionContext, ResidencyScheduler};
pub use slot::ChunkSlot;
pub use state_machine::{TransitionError, TransitionSideEffect, transition};

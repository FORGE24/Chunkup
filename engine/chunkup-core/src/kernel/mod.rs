//! Chunkup 统一 Kernel：Job / Buffer / Op 与 `native/common/chunkup_kernel.h` 对齐。

pub mod dispatch;
pub mod types;
pub mod workspace;

pub use dispatch::UnifiedKernel;
pub use types::{KernelJob, KernelOp, KernelResult, Stage};

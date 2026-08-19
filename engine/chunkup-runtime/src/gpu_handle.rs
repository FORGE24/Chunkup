//! GPU buffer handle 占位(placeholder)。
//!
//! 当前仅作为 ID 标识;后续接入真实 CUDA/OpenCL backend 时
//! 替换为具体 buffer 句柄(如 `cuMemAlloc` 返回的 `CUdeviceptr`)。

use std::sync::atomic::{AtomicU64, Ordering};

/// 全局 handle 计数器(用于生成唯一 ID)。
static NEXT_HANDLE_ID: AtomicU64 = AtomicU64::new(1);

/// GPU buffer handle。
///
/// 由 `id` 标识一块已上传到 GPU 的 chunk payload buffer。
/// `size` 记录分配的字节数,便于 residency 调度器统计 VRAM 占用。
#[derive(Clone, Debug)]
pub struct GpuBufferHandle {
    /// 全局唯一 ID(0 表示无效)。
    pub id: u64,
    /// buffer 字节大小。
    pub size: u32,
    /// 关联的 chunk_id(便于反向查找)。
    pub chunk_id: u64,
}

impl Default for GpuBufferHandle {
    fn default() -> Self {
        GpuBufferHandle {
            id: 0,
            size: 0,
            chunk_id: 0,
        }
    }
}

impl GpuBufferHandle {
    /// 是否为无效 handle(id == 0)。
    pub fn is_invalid(&self) -> bool {
        self.id == 0
    }

    /// 生成新的唯一 handle ID。
    pub fn new_id() -> u64 {
        NEXT_HANDLE_ID.fetch_add(1, Ordering::Relaxed)
    }

    /// 构造有效 handle。
    pub fn new(chunk_id: u64, size: u32) -> Self {
        GpuBufferHandle {
            id: Self::new_id(),
            size,
            chunk_id,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_default_invalid() {
        let h = GpuBufferHandle::default();
        assert!(h.is_invalid());
        assert_eq!(h.id, 0);
    }

    #[test]
    fn handle_new_unique() {
        let h1 = GpuBufferHandle::new(100, 4096);
        let h2 = GpuBufferHandle::new(200, 8192);
        assert!(!h1.is_invalid());
        assert!(!h2.is_invalid());
        assert_ne!(h1.id, h2.id);
        assert_eq!(h1.chunk_id, 100);
        assert_eq!(h1.size, 4096);
        assert_eq!(h2.size, 8192);
    }
}

//! 零拷贝内存池与跨语言缓冲区共享（JNI DirectByteBuffer / FFM MemorySegment）。

pub struct MemoryArena;

impl MemoryArena {
    pub fn new(capacity: usize) -> Self {
        let _ = capacity;
        Self
    }
}

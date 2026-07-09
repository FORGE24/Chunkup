# 区块加载性能分析报告

## 问题概述
区块加载延迟主要由以下几个因素共同影响：
1. **CPU 密度读取开销** - `ChunkDensityReader.read()` 遍历整个区块
2. **同步锁竞争** - 多 worker 线程竞争批处理器锁
3. **批处理策略** - 批次大小和刷新间隔配置
4. **GPU kernel 调度** - JNI 跨语言调用和 GPU dispatch
5. **天空光写回** - 可选的 `ChunkSkylightApplier.apply()`

---

## 瓶颈详解

### 1. ChunkDensityReader.read() - 密度读取 ⚠️ 主要瓶颈

**位置**: `src/main/kotlin/cn/sanrolnet/chunkup/minecraft/generation/ChunkDensityReader.kt`

**问题分析**:
```kotlin
// 对每个方块都要：
// 1. 获取方块状态 section.getBlockState(lx, relY, lz)
// 2. 调用 state.getLightBlock(chunk, pos) - 可能触发复杂计算
for (sectionIndex in chunk.sections.indices) {
    for (localY in 0 until 16) {
        for (lz in 0 until CHUNK_SIZE) {
            for (lx in 0 until CHUNK_SIZE) {
                val state = section.getBlockState(lx, relY, lz)
                out[rowBase + lx] = densityForSkylight(state, chunk, pos)
            }
        }
    }
}
```

**复杂度**: O(16×16×height)
- 对于 1.18+ 世界（minY=-64, height=384）：**98,304 次迭代**
- 每次迭代包含 `getLightBlock()` 调用，可能涉及方块实体查询、流体状态检查等

**优化建议**:
1. **缓存优化**: 利用 `ChunkDensityCache` 缓存 GENERATED 阶段的密度
2. **提前跳过空气 section**: 已有 `section.hasOnlyAir()` 检查 ✓
3. **批量获取方块状态**: 减少间接调用
4. **并行处理**: 考虑使用多线程处理不同 section

---

### 2. 同步锁竞争 🔒 潜在瓶颈

**位置**: `ChunkLoadBatcher.kt` 和 `ChunkDensityBatcher.kt`

**问题分析**:
```kotlin
// ChunkLoadBatcher.kt
private val lock = Any()

@JvmStatic
fun enqueue(context: ChunkGenerationContext, engine: EngineBridge): Boolean {
    synchronized(lock) {  // <- 多个 chunk worker 竞争此锁
        // ... 入队逻辑
        if (batchKey != null && batchKey != key && pending.isNotEmpty()) {
            flushLocked(engine)  // <- 可能触发同步 GPU 调用
        }
    }
}

@JvmStatic
fun flushDue(engine: EngineBridge, force: Boolean, allowPartial: Boolean): Boolean {
    synchronized(lock) {  // <- 与 enqueue 竞争
        // ...
        flushLocked(engine)  // <- GPU dispatch + 结果处理
    }
}
```

**影响**:
- Minecraft 区块生成使用多线程 worker 池
- 每个 worker 在入队时都会持有锁
- `flushLocked()` 内的 GPU 调用是阻塞操作
- 其他 worker 必须等待 GPU 完成

**优化建议**:
1. **分离锁粒度**: 使用读写锁或分段锁
2. **异步 flush**: 将 GPU dispatch 移到单独线程
3. **无锁队列**: 使用 `ConcurrentLinkedQueue` 或 `Disruptor`

---

### 3. 批处理策略配置 ⚙️ 可调优

**当前配置** (`ChunkupConfig.kt`):

| 配置项 | 默认值 | 说明 | 建议 |
|--------|--------|------|------|
| `gpuChunkLoadBatchSize` | 64 | GPU 批次大小 | 可增至 96-128（GPU 性能允许时） |
| `gpuChunkLoadFlushInterval` | 20 tick | 部分 flush 间隔 | 可减至 10-15 tick |
| `gpuChunkLoadMinFlushBatch` | 16 | 最小部分 flush 数量 | 可减至 8 |
| `gpuDensityBatchSize` | 32 | 密度批次大小 | 可增至 64 |

**关键问题**: `instantLoad=true` 时强制间隔为 1 tick：
```kotlin
val gpuChunkLoadFlushInterval: Int
    get() = if (instantLoad) {
        1  // <- 每 tick 都可能触发 flush
    } else {
        // ...
    }
```

**优化建议**:
1. 调整批次大小以匹配 GPU 性能
2. 根据实际区块生成速率调整刷新间隔
3. 监控 `pendingLoad` 和 `pendingDensity` 队列长度

---

### 4. GPU Kernel 调度开销 🖥️

**调用链**:
```
ChunkLoadBatcher.flushLocked()
  → EngineBridge.processChunkLoadBatch()
    → JniBridge.nativeProcessChunkLoadBatch() [JNI 边界]
      → chunkup_core (Rust)
        → gpu_loader::cuda_dispatch_batch() [CUDA dispatch]
```

**开销来源**:
1. **JNI 调用**: 每次批次都有跨语言开销
2. **内存拷贝**: 
   - `densities: FloatArray` → native buffer
   - `skylight: ByteArray` ← native buffer
   - `faceMask: ByteArray` ← native buffer
3. **GPU 启动延迟**: CUDA kernel launch 有固定开销
4. **同步等待**: 当前实现是同步调用

**探针数据** (`ChunkupDebugProbe`):
```
gpu.batch avg=Xms last=Yms count=Z
```

**优化建议**:
1. **Pinned Memory**: 已有 `gpuPinnedHost` 配置 ✓
2. **异步 Stream**: 使用 CUDA streams 实现异步拷贝
3. **Double Buffering**: 准备下一批数据时，GPU 同时处理当前批次

---

### 5. ChunkSkylightApplier.apply() - 天空光写回 🌤️ 可选

**位置**: `ChunkSkylight.kt`

**当前状态**: 默认关闭 (`gpuSkylightApply=false`)

**原因**: GPU 天空光算法是简化列传播，与原版 flood-fill 不兼容，准确率仅约 35%

**如果启用**:
```kotlin
// 对每个非空 section：
// 1. 创建 DataLayer
// 2. 写入 4096 字节光照数据
// 3. 提交到 lightEngine
lightEngine.queueSectionData(LightLayer.SKY, sectionPos, layer)
```

**复杂度**: O(sections × 16×16×16)

**优化建议**: 保持关闭，让原版光照引擎处理

---

## 性能监控

### F3 调试面板

启用 `-Dchunkup.f3Debug=true` 可看到：

```
Chunkup Engine
 backend: cuda
 density.read avg=15ms last=12ms count=128
 gpu.batch avg=8ms last=6ms count=16
 skylight.apply off (vanilla lighting; chunk-load compute only)
 chunksProcessed=128 pendingLoad=0 pendingDensity=0
```

### 关键指标（Phase 2 统一 F3 首行）

F3 右侧 `── Chunkup Perf ──` 汇总：

```
pendingLoad=0 pendingDensity=0 preRender.pending=12 density.read=8ms gpu.batch=5ms
```

详细探针需开启设置 UI「性能探针日志」或 `-Dchunkup.debug.probe=true`。

| 指标 | 正常范围 | 警告阈值 |
|------|----------|----------|
| `pendingLoad` | 0（instantLoad） | > 64 |
| `preRender.pending` | < 50 静止 | > 500 |
| `density.read` avg | < 10ms | > 20ms |
| `gpu.batch` avg | < 5ms | > 15ms |
| `pendingDensity` | 0-32 | > 128 |

---

## 优化路线图

### 短期优化（1-2 天）

1. **调整批处理参数**:
   ```bash
   -Dchunkup.gpuChunkLoad.batchSize=96
   -Dchunkup.gpuChunkLoad.flushInterval=10
   -Dchunkup.gpuChunkLoad.minFlushBatch=8
   ```

2. **启用密度缓存**:
   - 确保 `ChunkDensityCache` 在 GENERATED 阶段被正确使用
   - 检查缓存命中率

3. **监控统计**:
   - 启用探针日志：`-Dchunkup.debug.probe=true`
   - 分析哪些阶段耗时最长

### 中期优化（1 周）

1. **异步 GPU 调度**:
   ```kotlin
   // 将 flush 移到专用线程
   object GpuDispatcher {
       private val queue = ConcurrentLinkedQueue<BatchJob>()
       private val thread = Thread { /* GPU worker loop */ }
   }
   ```

2. **锁优化**:
   ```kotlin
   // 使用读写锁
   private val rwLock = ReentrantReadWriteLock()
   
   fun enqueue() = rwLock.writeLock().withLock { ... }
   fun pendingCount() = rwLock.readLock().withLock { ... }
   ```

3. **密度读取优化**:
   - 考虑使用 Unsafe 或 VarHandle 直接访问内存
   - 批量预取方块状态

### 长期优化（2-4 周）

1. **GPU Direct**: 使用 CUDA managed memory 减少拷贝
2. **Pipeline 并行**: CPU 准备下一批数据时，GPU 处理当前批次
3. **自适应批处理**: 根据负载动态调整批次大小

---

## 快速诊断命令

### 检查当前性能状态
```bash
# 在游戏内 F3 界面查看 Chunkup 统计
# 或查看日志中的 PROBE 输出
grep "PROBE" logs/latest.log
```

### 测试不同配置
```bash
# 极速模式（跳过 GPU 处理）
-Dchunkup.instantLoad=true

# GPU 世界生成模式
-Dchunkup.gpuWorldGen=true

# 调试模式
-Dchunkup.debug.probe=true -Dchunkup.f3Debug=true
```

---

## 相关源码文件

- `ChunkDensityReader.kt` - 密度读取（主要瓶颈）
- `ChunkLoadBatcher.kt` - 批处理调度
- `ChunkLoadPipeline.kt` - 管线协调
- `ChunkupConfig.kt` - 配置项
- `ChunkupDebugProbe.kt` - 性能探针
- `engine/chunkup-core/src/kernel/dispatch.rs` - GPU dispatch
- `native/common/chunkup_batch.h` - 批处理接口
# Phase 1 验收标准：默认游玩路径 Baseline

**目标**：不开任何实验开关，进游戏后区块加载、mesh 构建、FPS 均正常，可作为后续 GPU 开关的对比基线。

**时间盒**：1–2 周  
**范围**：Sodium 协同三件套 + 默认配置；**不包含** Infection 旁路、完整 GPU 世界生成、`gpuSections=true` 大规模启用。

---

## 默认配置（零 JVM 参数即可运行）

| 开关 | 默认值 | 说明 |
|------|--------|------|
| `chunkup.instantLoad` | `true` | 生成 worker 不阻塞在 CUDA |
| `chunkup.gpuWorldGen` | `false` | 无 GPU 程序化生成 |
| `chunkup.gpuSections` | `false` | Sodium CPU mesh |
| `chunkup.preRenderOnLoad` | `true` | 加载时距离优先预渲染 |
| `chunkup.preRender.budget` | `8` | 每帧预渲染上限 |
| `chunkup.layeredSections` | `true` | 地表先 mesh |
| `chunkup.layeredSections.rate` | `3` | 每 tick 向下解锁层数 |
| `chunkup.infectionRender` | `false` | 感染旁路关闭 |
| `chunkup.debug.probe` | `false` | 探针日志默认关 |
| `chunkup.f3Debug` | `true` | F3 显示 Chunkup 行 |

Gradle `runClient` 仅注入 `native.dir` 与 `f3Debug`，其余依赖代码默认值。

---

## Sodium 协同三件套

### 1. SectionLoadPreRenderer

| 行为 | 实现要点 |
|------|----------|
| 距离优先 | 每帧 flush 前按当前玩家位置重算 `distanceSq` |
| Budget 控制 | `min(preRender.budget, builder.schedulingBudget)`，与 Sodium worker 共享队列 |
| 忙碌重入队 | `buildCancellationToken != null` 时放回队列，不丢任务 |
| 分层延迟 | 未解锁深度的 section 放回队列（`deferred` 计数） |
| 传送清理 | 水平位移 > 48 格清空 pending，重新锚定分层 |

### 2. LayeredSectionPolicy

| 行为 | 实现要点 |
|------|----------|
| 地表先出 | `allowSectionMesh` 限制 Y 窗口；未解锁层不进 mesh |
| 向下扩展 | 每 tick `depthBelow += rate`，直至世界底部 |
| 锚点重置 | 玩家 Y 变化 > 4 section 或传送时 `resetAnchor` |
| 队列排序 | `submitRebuildTasks` HEAD 按 `chunkY` 降序 |

### 3. RenderSectionManagerMixin

| 注入点 | 作用 |
|--------|------|
| `updateChunks` HEAD | flush 预渲染队列（在可见性遍历之前） |
| `onSectionAdded` TAIL | 新区块 section 入预渲染队列 |
| `submitRebuildTasks` HEAD | rebuild 队列地表优先 |

---

## Acceptance Criteria（必须通过）

### AC-1：快速飞行 / 传送

| 场景 | 步骤 | 通过标准 |
|------|------|----------|
| 创造模式飞行 | 以 3+ 速度沿一个方向飞行 30 秒 | 视野内无明显 mesh 空洞；黑块 < 1 秒自愈 |
| `/tp` 传送 | 同维度传送 500+ 格，重复 5 次 | 落点周围 5 秒内地表可见；无持续黑块 |
| 下界传送门 | 主世界 ↔ 下界各 3 次 | 切换后 mesh 正常恢复 |

### AC-2：Sodium Worker 不枯竭

| 指标 | 通过标准 |
|------|----------|
| 线程状态 | `Chunk Render Task Executor` 无长期 100% 占满（观察 5 分钟正常游玩） |
| FPS | 1080p 中端 GPU：稳定场景 ≥ 60 FPS；加载高峰 ≥ 45 FPS |
| 队列积压 | F3 `preRender pending` 飞行后 10 秒内回落至 < 200 |
| 无 gpuSections | `gpuSections=false` 下以上标准仍成立 |

### AC-3：F3 指标合理

打开 F3 → 右侧 Chunkup 区域应包含：

```
preRender on=true pending=<N> queued=... submitted=... deferred=... skipped=... budget=8
layered anchor=<Y> depthBelow=<D>/rate=3 headroom=2
sectionMesh airOnly=... sodiumFallback=... (gpuSections=false 时 rustFast≈0)
pendingLoad=0 (instantLoad=true 时)
client gpuSections=false sodium=true
```

| 指标 | 正常范围 | 异常信号 |
|------|----------|----------|
| `preRender pending` | 静止 < 50；加载高峰 < 500 | 持续 > 1000 且不下降 |
| `preRender deferred` | 分层解锁时短期增长后回落 | 只增不减 |
| `preRender skipped` | 缓慢增长 | 每秒 +100 以上 |
| `layered depthBelow` | 随时间增至世界深度 | 长期停在 initialDepth |
| `pendingLoad` | instantLoad 下为 0 | > 0 且持续增长 |

---

## 对比场景（Phase 2 沿用）

同一 seed、同一坐标，记录以下变体：

| 变体 | JVM 参数 |
|------|----------|
| vanilla baseline | 上述默认（无额外参数） |
| preRender off | `-Dchunkup.preRenderOnLoad=false` |
| layered off | `-Dchunkup.layeredSections=false` |
| instantLoad off | `-Dchunkup.instantLoad=false`（GPU 实验，非 Phase 1） |

记录项：首屏可见时间、视野内区块 FULL 时间、5 分钟平均 FPS。

---

## 测试清单

```
[ ] 新存档出生点站立 30 秒 — 无黑块、FPS 稳定
[ ] 创造模式飞行 30 秒 — AC-1 通过
[ ] /tp 远距传送 ×5 — AC-1 通过
[ ] F3 检查 preRender / layered 行 — AC-3 通过
[ ] 观察 Chunk Render Task Executor — AC-2 通过
[ ] 关闭 preRenderOnLoad 对比 — 确认预渲染有正向效果
[ ] 关闭 layeredSections 对比 — 确认分层有正向效果
```

---

## 明确不做（本阶段）

- Infection 旁路（`infectionRender`）
- 完整 GPU 世界生成（`gpuWorldGen`）
- `gpuSections=true` 大规模启用
- Qt 设置 UI 暴露（Phase 2）
- `chunkup.debug.probe` 默认开启

---

## 相关文件

| 组件 | 路径 |
|------|------|
| 预渲染 | `src/client/kotlin/.../pipeline/SectionLoadPreRenderer.kt` |
| 分层策略 | `src/client/kotlin/.../sodium/LayeredSectionPolicy.kt` |
| 分层引导 | `src/client/kotlin/.../sodium/LayeredSectionBootstrap.kt` |
| Sodium Mixin | `src/client/java/.../mixin/sodium/RenderSectionManagerMixin.java` |
| 可见性过滤 | `src/client/java/.../mixin/sodium/VisibleChunkCollectorMixin.java` |
| F3 调试 | `src/client/kotlin/.../debug/ChunkupF3Debug.kt` |
| 配置 | `src/main/kotlin/.../ChunkupConfig.kt` |

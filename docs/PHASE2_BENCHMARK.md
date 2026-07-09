# Phase 2 对比场景与记录模板

**目标**：在同一 seed、同一坐标下对比不同配置，用 F3 统一指标替代盲调 JVM 参数。

---

## 固定测试环境

| 项 | 建议值 |
|----|--------|
| Seed | 固定（如 `12345`） |
| 坐标 | 出生点或 `/tp 0 80 0` |
| 渲染距离 | 12（与日常一致） |
| Sodium | 0.5.11 |
| 对比时长 | 每场景 5 分钟 |

---

## 对比变体

| 编号 | 名称 | 配置 | 用途 |
|------|------|------|------|
| A | baseline | 默认（无额外 JVM 参数） | Phase 1 基线 |
| B | preRender off | 设置 UI 关闭「加载时预渲染」 | 验证预渲染收益 |
| C | layered off | 设置 UI 关闭「分层 section mesh」 | 验证分层收益 |
| D | instantLoad off | 设置 UI 关闭「极速加载」 | GPU 生成路径（实验） |
| E | probe on | 设置 UI 开启「性能探针日志」 | 采集 density.read / gpu.batch |

也可通过 `%APPDATA%\Chunkup\settings.json` 切换，无需改 launch 配置。

---

## 记录指标

### 主观 / 计时

| 指标 | 定义 | 记录方式 |
|------|------|----------|
| 首屏可见时间 | 进入世界到地表 mesh 基本完整 | 秒表或录屏回放 |
| 区块 FULL 时间 | 出生点 3×3 chunk 全部 FULL | F3 区块边界 + 日志 |
| 平均 FPS | 静止 30s + 飞行 30s 均值 | F3 右上角 |

### F3 统一指标（`── Chunkup Perf ──`）

```
pendingLoad=0 pendingDensity=0 preRender.pending=12 density.read=8ms gpu.batch=5ms
```

| 指标 | 正常（baseline） | 异常信号 |
|------|------------------|----------|
| `pendingLoad` | instantLoad 下 ≈ 0 | > 64 持续 |
| `preRender.pending` | 静止 < 50 | > 500 不回落 |
| `density.read` | probe on 时 < 15ms avg | > 25ms |
| `gpu.batch` | probe on 时 < 10ms avg | > 20ms |

探针关闭时显示 `probe=off`；需要详细分位数时开启探针或查 `logs/latest.log` 中 `[PROBE]` 行。

---

## 记录表模板

```
日期: ____
Seed: ____  坐标: ____  变体: ____

首屏可见: ___s
3×3 FULL: ___s
FPS 静止: ___
FPS 飞行: ___

F3 Perf 行（静止）:
  pendingLoad=  preRender.pending=  density.read=  gpu.batch=

F3 Perf 行（飞行高峰）:
  pendingLoad=  preRender.pending=  density.read=  gpu.batch=

备注:
```

---

## 快速操作

1. **游戏内设置**：按 `,` 打开 Chunkup 设置（Qt 或 Fabric 回退界面）
2. **F3**：确认 `── Chunkup Perf ──` 首行存在
3. **探针日志**：设置 UI 勾选「性能探针日志」，或 `-Dchunkup.debug.probe=true`
4. **grep 探针**：`grep "[PROBE]" logs/latest.log`

---

## 相关文档

- [PHASE1_ACCEPTANCE.md](./PHASE1_ACCEPTANCE.md) — 默认路径验收
- [PERFORMANCE_ANALYSIS.md](./PERFORMANCE_ANALYSIS.md) — 瓶颈分析与阈值

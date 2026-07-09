# Chunkup

Chunkup 是一个面向 Minecraft 1.20.1（Fabric）的 GPU 区块生成优化模组。  
它将区块噪声密度计算迁移到 CUDA / OpenCL，并通过批处理与缓存机制减少 CPU 端生成压力，在探索新区块时获得更稳定的吞吐表现。

## 核心能力

- GPU 密度生成：支持 CUDA 优先，OpenCL 作为回退。
- 批量生成调度：将多个区块请求合并后统一下发，降低单次调度与 JNI 往返开销。
- 地表构建可选加速：在兼容条件满足时可启用 GPU 薄层地表构建。
- 兼容性保护：遇到旧区块混合（Blender）等场景会自动回退原版路径，优先保证世界一致性。
- Sodium 协同：保留与渲染侧优化链路的协作能力。

## 运行环境

- Minecraft: `1.20.1`
- Loader: `Fabric Loader >= 0.19.3`
- Java: `17+`
- 必需依赖：
  - `fabric-api`
  - `fabric-language-kotlin`
- 推荐依赖：
  - `sodium`
  - `modmenu`

## 快速开始

1. 克隆项目并确保本机安装 JDK 17。
2. 在项目根目录执行：

```powershell
.\gradlew.bat runClient
```

3. 首次运行建议在新存档中验证基础稳定性，再测试旧存档外扩区块场景。

## 常用参数

可通过 JVM 参数（`-D`）或 Gradle 属性（`-P` 映射）覆盖运行时行为。以下为常见项：

- `chunkup.gpuWorldGen`：总开关，启用 GPU 世界生成（默认启用）。
- `chunkup.gpuNoiseFill`：启用 NOISE_FILL 阶段 GPU 密度路径。
- `chunkup.gpuDensityBatch`：启用密度批处理。
- `chunkup.gpuDensityBatch.size`：批处理目标大小。
- `chunkup.gpuDensityBatch.coalesceMs`：聚合窗口毫秒数。
- `chunkup.gpuDensityBatch.maxWaitMs`：批次最长等待时间。
- `chunkup.gpuDensityBatch.minFlush`：最小触发 flush 阈值。
- `chunkup.gpuSurfaceBuild`：启用可选 GPU 地表构建。

示例：

```powershell
.\gradlew.bat runClient -PchunkupDensityMinFlush=1 -PchunkupDensityCoalesceMs=0
```

## 兼容性说明

- 为避免旧存档边界地形断层，Chunkup 会在检测到 Blender 混合需求时自动回退原版生成。
- 对于不满足 GPU 路径前置条件的场景（如特定区块包装状态），同样会回退到原版逻辑。
- 建议在每次更新后进行以下验证：
  - 新世界连续探索是否正常；
  - 旧世界向外扩张时新区块是否无错生成；
  - 关键维度下是否存在异常地表或流体分布。

## 排障建议

- 若启动时报 native 库占用，请先关闭正在运行的 `runClient` 进程后重试构建。
- 若 OpenCL 编译失败，请检查显卡驱动、OpenCL Runtime 与日志中的 kernel build 输出。
- 若性能未达预期，建议先调低 `coalesceMs` 并设置 `minFlush=1` 验证延迟路径，再逐步扩大 batch。

## 许可证

Copyright 2026 FORGE24

Licensed under the Apache License, Version 2.0.  
See [LICENSE](LICENSE) for the full text.

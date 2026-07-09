# Chunkup 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  Mod 壳 (Kotlin)          Fabric API / 事件 / MC 对接        │
│  src/main/kotlin/.../bridge/   ← JNI / FFM 边界             │
│  src/main/kotlin/.../minecraft/ ← 生命周期与游戏事件         │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI / FFM
┌──────────────────────────▼──────────────────────────────────┐
│  核心引擎 (Rust)          engine/chunkup-core/               │
│  noise · lighting · culling · compression · io · memory     │
│  backend/scheduler → 多后端调度                              │
└──────────┬─────────────────┬─────────────────┬────────────────┘
           │                 │                 │
    ┌──────▼──────┐   ┌──────▼──────┐   ┌──────▼──────┐
    │ CUDA (C++)  │   │ OpenCL      │   │ CPU+SIMD    │
    │ native/cuda │   │ native/opencl│  │ chunkup-cpu │
    └─────────────┘   └─────────────┘   └─────────────┘
```

## 层级职责

| 层级 | 语言 | 目录 | 职责 |
|------|------|------|------|
| Mod 壳 | Kotlin | `src/main/kotlin/...` | Fabric 接口、事件调度、Minecraft API 对接 |
| 核心引擎 | Rust | `engine/chunkup-core/` | 噪声、光照、剔除、压缩、IO、零拷贝内存、后端调度 |
| GPU 后端 | CUDA C++ | `native/cuda/` | NVIDIA 并行计算 |
| GPU 回退 | OpenCL | `native/opencl/` | AMD/Intel 等通用 GPU |
| CPU 回退 | Rust + SIMD | `engine/chunkup-cpu/` | AVX/NEON 向量化，无 GPU 时仍超原版 |

## 调用链

```
Kotlin Mod 壳
  → EngineBridge (JniBridge | FfmBridge)
    → chunkup_core (Rust FFI)
      → BackendKind: CUDA → OpenCL → CPU/SIMD
```

## 构建

```powershell
# Windows
.\scripts\build-engine.ps1
.\gradlew build

# Linux / macOS
./scripts/build-engine.sh
./gradlew build
```

Gradle 任务：

- `buildNativeEngine` — 编译 Rust 核心
- `copyNativeLibraries` — 复制 `.dll/.so/.dylib` 到 `build/native/`

## 区块生成 Hook（1.20.1）

Fabric API 0.92.9 无 `CHUNK_GENERATE`，通过 Mixin 覆盖完整管线：

| 阶段 | 注入点 | Mixin |
|------|--------|-------|
| BIOMES | `ChunkAccess.fillBiomesFromNoise` TAIL | ChunkAccessMixin |
| NOISE_FILL | `NoiseBasedChunkGenerator.doFill` TAIL | NoiseBasedChunkGeneratorMixin |
| SURFACE | `ChunkGenerator.buildSurface` TAIL | ChunkGeneratorMixin |
| FEATURES | `ChunkGenerator.applyBiomeDecoration` TAIL | ChunkGeneratorMixin |
| GENERATED | `ChunkMap.protoChunkToFullChunk` TAIL（排除 ImposterProtoChunk） | ChunkMapMixin |
| LOADED | `ServerChunkEvents.CHUNK_LOAD` | ChunkupEvents |

Kotlin 侧注册自定义监听器：

```kotlin
ChunkGenerationHooks.register { ctx ->
    // ctx.stage, ctx.chunkX, ctx.chunkZ, ctx.newlyGenerated
}
```


## 统一 Kernel

所有后端共享 `native/common/chunkup_kernel.h` 中的 Job / Buffer / Op 定义：

| Op | 掩码 | 说明 |
|----|------|------|
| `NOISE_FILL` | `1<<0` | 3D 密度场 |
| `SKYLIGHT` | `1<<1` | 天空光传播 |
| `BLOCKLIGHT` | `1<<2` | 方块光（占位） |
| `FACE_CULL` | `1<<3` | 面剔除掩码 |
| `SECTION_MESH` | `1<<4` | Section 网格（CompactChunkVertex） |
| `OCCLUSION_PACK` | `1<<5` | Sodium visibility 打包 |

## Sodium 0.5.11 客户端集成

目标包名：`me.jellysquid.mods.sodium`（Modrinth `mc1.20.1-0.5.11`）

```
ClientChunkEvents → ClientSectionPipeline（距离优先级）
  → ClientEngineBridge / JNI → Rust section/mesher
  → SectionBuildCache
  → ChunkBuilderMeshingTaskMixin（HEAD 短路）
  → SodiumBuildFactory → ChunkBuildOutput + BuiltSectionInfo
```

| SectionKind | 行为 |
|-------------|------|
| `AIR_ONLY` | 返回 `BuiltSectionInfo.EMPTY`，跳过 mesh |
| `SOLID_UNIFORM` | 外表面 shell mesh（6 面） |
| `MIXED` | 逐 block 6-face culling + 简易 AO |
| `FLUID_HEAVY` | `ready=false`，回退 Sodium CPU mesh |

```
ChunkupKernelJob → UnifiedKernel::dispatch (Rust)
  → chunkup_kernel_dispatch_cpu   (native/common/chunkup_kernel_host.c)
  → chunkup_cuda_kernel_dispatch  (native/cuda/chunkup_cuda.cu)
  → chunkup_opencl_kernel_dispatch (native/opencl/kernels/chunkup_kernel.cl)
```

## 「第一次传染」渲染（实验）

以玩家为中心的 32×32 chunk 区域全部 `FULL` 后，一次性打包全部 section → GPU batch mesh → 旁路 Sodium。

```
ClientTick → InfectionCoordinator
  ACCUMULATING  等待 1024 chunk FULL，区内 Sodium mesh/draw 门控（可见空洞）
  PACKING       InfectionBatchPackager 采集 4096×N 字节 → native（待接 CUDA）
  INFECTED      Sodium 旁路，ChunkupGpuWorldRenderer 绘制（Phase 2）
```

| 开关 | 默认 | 说明 |
|------|------|------|
| `chunkup.infectionRender` | `false` | 总开关 |
| `chunkup.infectionRender.radius` | `16` | 半宽 → 32×32 区域 |

客户端代码：`src/client/kotlin/.../infection/`

Phase 2 待实现：
- `CHUNKUP_OP_SECTION_MESH` CUDA batch kernel
- `chunkup_infection_upload.cu` — pinned host → VBO/SSBO
- `SodiumWorldRenderer` mixin — 区内跳过 terrain draw
- `ChunkupGpuWorldRenderer` — 单次 draw call 全区域


## 后端探测顺序

1. **CUDA** — `chunkup_cuda_is_available()`
2. **OpenCL** — `chunkup_opencl_is_available()`
3. **CPU** — `chunkup_kernel_dispatch_cpu()`（默认，随 Rust 静态链接）

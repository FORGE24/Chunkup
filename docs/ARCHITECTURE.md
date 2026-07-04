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

```
ChunkupKernelJob → UnifiedKernel::dispatch (Rust)
  → chunkup_kernel_dispatch_cpu   (native/common/chunkup_kernel_host.c)
  → chunkup_cuda_kernel_dispatch  (native/cuda/chunkup_cuda.cu)
  → chunkup_opencl_kernel_dispatch (native/opencl/kernels/chunkup_kernel.cl)
```

Fabric 阶段 → Op：`NOISE_FILL` → 密度；`GENERATED` → 光照 + 剔除。

## 后端探测顺序

1. **CUDA** — `chunkup_cuda_is_available()`
2. **OpenCL** — `chunkup_opencl_is_available()`
3. **CPU** — `chunkup_kernel_dispatch_cpu()`（默认，随 Rust 静态链接）

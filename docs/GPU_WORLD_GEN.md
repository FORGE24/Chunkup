# GPU 世界生成：CUDA → OpenCL → CPU/SIMD

## 总开关

| 属性 | 默认 | 说明 |
|------|------|------|
| `chunkup.gpuWorldGen` | `true` | 启用 NOISE_FILL GPU 密度替换 + 默认攒批 |
| `chunkup.instantLoad` | `false`（gpuWorldGen 时强制） | `true` 时跳过全部生成 mixin |
| `chunkup.forceGpu` | `false` | `true` 时 GPU 失败 **不回退** CPU |

`gpuWorldGen=true` 时自动：`instantLoad=false`、`gpuNoiseFill=true`、`gpuDensityBatch=true`、`gpuSurfaceBuild=true`。

## 后端探测链（启动时一次绑定）

```
EngineContext::bootstrap()
  1. cuda_probe()     → chunkup_cuda.dll + NVIDIA 设备
  2. opencl_probe()   → chunkup_opencl.dll + OpenCL 设备
  3. cpu::probe()     → cpu-simd（恒 true）
```

## 单次 dispatch 回退（forceGpu=false 时）

```
UnifiedKernel::dispatch / dispatch_density_batch
  Cuda/OpenCl 调用失败或符号缺失
    → chunkup_kernel_dispatch_cpu / chunkup_kernel_dispatch_density_batch
```

| 后端 | NOISE_FILL 单 chunk | NOISE_FILL 攒批 |
|------|---------------------|-----------------|
| CUDA | GPU kernel | `chunkup_cuda_density_fill_batch` |
| OpenCL | **GPU** `chunkup_kernel_density_fill`（router + cell，与 CUDA 同算法） | **GPU** `chunkup_kernel_density_fill_batch` |
| CPU/SIMD | CPU cell-fill | CPU batch |

OpenCL 的 NOISE_FILL 暂走 CPU cell-fill（`.cl` kernel 待接入）；skylight/face_cull 走 OpenCL GPU。

## 生成钩子

| 阶段 | 入口 |
|------|------|
| NOISE_FILL | `NoiseBasedChunkGeneratorMixin` → `ChunkDensityGeneration.tryReplaceNoiseFill` |
| SURFACE | `ChunkSurfaceGeneration.tryReplaceBuildSurface`（gpuSurfaceBuild） |
| GENERATED/LOADED | `ChunkLoadPipeline`（默认关，实验） |

## 构建 native

```powershell
.\scripts\build-engine.ps1
.\gradlew.bat copyNativeLibraries
```

产物：`build/native/chunkup_core.dll`、`chunkup_cuda.dll`、`chunkup_opencl.dll`

CUDA 构建需 nvcc + MSVC 14.29。`build-engine.ps1` nvcc 直编已包含 `chunkup_cuda_host.c`（pinned memory）。若仍失败可试 `-CudaViaCmake`。

## F3 / 日志验证

```
Chunkup engine initialized via jni (compute backend=opencl, gpuWorldGen=true, instantLoad=false, ...)
backend: opencl
density.read / gpu.batch  （探针开启时）
```

## 旧 settings.json 迁移

若 `%APPDATA%\Chunkup\settings.json` 含 `"instantLoad": true`，会覆盖代码默认值。请：

- 设置 UI 开启「GPU 世界生成」，关闭「极速加载」；或
- 删除 settings.json 让新默认生效

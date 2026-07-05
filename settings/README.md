# Chunkup Qt 设置 DLL（JNI 深度集成）

`chunkup_settings.dll` 由 Mod 客户端通过 JNI 直接加载，进程内弹出 Qt 模态对话框，**无 IPC、无独立 exe**。

## 架构

```
游戏内按 ',' 键
  → ChunkupSettingsUi (Kotlin, 客户端主线程)
  → SettingsNative JNI
  → chunkup_settings.dll (Qt Widgets 模态对话框)
  → 保存 JSON + ChunkupConfigFile.applyRuntime() 写 System Property
```

与 `chunkup_core.dll` / CUDA / OpenCL 同目录打包进 jar `assets/chunkup/native/windows-x86_64/`。

## 构建

```powershell
.\scripts\build-settings.ps1 -Toolchain MinGW
```

产物自动复制到 `build\native\`（含 Qt 运行时 DLL 与 `platforms/qwindows.dll`）。

Gradle 集成：`./gradlew copyNativeLibraries` 会自动调用 settings 构建（Windows）。

## 依赖

- Qt 6.11.1 **MinGW 64-bit**（`D:\Qt\6.11.1\mingw_64`，含 Qt Widgets）
- MinGW 13.1（`D:\Qt\Tools\mingw1310_64`）
- JDK（JNI 头文件，`JAVA_HOME`）

## 游戏内使用

- 默认快捷键：**`,`**（可在控制设置中修改）
- 保存后立即写入 `%APPDATA%\Chunkup\settings.json` 并更新 JVM 属性

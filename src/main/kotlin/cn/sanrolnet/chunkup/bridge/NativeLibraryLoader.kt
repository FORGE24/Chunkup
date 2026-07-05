package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.Chunkup
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 从 mod jar 内嵌资源解压 native 库，或回退到 [java.library.path]。
 *
 * 资源路径：`assets/chunkup/native/<platform>/chunkup_*.{dll,so,dylib}`
 *
 * Linux 适配 (FORGE24):
 *   - 设置 CHUNKUP_NATIVE_DIR 环境变量，供 Rust dlopen 搜索 .so 文件
 *   - CUDA/OpenCL .so 按优先级自动尝试加载
 *   - 支持 LD_LIBRARY_PATH 回退
 */
object NativeLibraryLoader {
    private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.native")

    private val LIBRARY_BASE_NAMES = listOf(
        "chunkup_cuda",
        "chunkup_opencl",
        "chunkup_core",
        "chunkup_settings",
    )

    /** 实际加载 native 库的目录，供 Rust dlopen GPU 后端时使用。 */
    @Volatile
    private var nativeLibraryDir: java.nio.file.Path? = null

    fun nativeLibraryDirectory(): String? =
        nativeLibraryDir?.toAbsolutePath()?.toString()

    private val WINDOWS_DEPENDENCY_DLLS = listOf(
        "libwinpthread-1.dll",
        "libgcc_s_seh-1.dll",
        "libstdc++-6.dll",
        "Qt6Core.dll",
        "Qt6Gui.dll",
        "Qt6Widgets.dll",
        "Qt6Network.dll",
        "Qt6Svg.dll",
    )

    /** 让 Windows 能解析同目录 Qt / MinGW 依赖 DLL。 */
    @JvmStatic
    fun prepareNativeDirectory(dir: Path) {
        val absolute = dir.toAbsolutePath().toString()
        System.setProperty("chunkup.native.dir", absolute)
        prependPathEntry(absolute)
        try {
            val envField = Class.forName("java.lang.ProcessEnvironment")
                .getDeclaredField("theUnmodifiableEnvironment")
            envField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = envField.get(null) as MutableMap<String, String>
            env["CHUNKUP_NATIVE_DIR"] = absolute
            if (System.getProperty("os.name").lowercase().contains("win")) {
                env["QT_PLUGIN_PATH"] = absolute
            }
        } catch (_: Exception) {
            // 非致命；Rust 侧仍可通过 JNI 收到目录
        }
    }

    private fun preloadWindowsDependencies(dir: Path) {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            return
        }
        for (name in WINDOWS_DEPENDENCY_DLLS) {
            val path = dir.resolve(name)
            if (!Files.isRegularFile(path)) {
                continue
            }
            try {
                System.load(path.toAbsolutePath().toString())
            } catch (_: UnsatisfiedLinkError) {
                // Optional; chunkup_settings load may still succeed.
            }
        }
    }

    private fun prependPathEntry(entry: String) {
        val key = "PATH"
        val existing = System.getenv(key) ?: ""
        if (existing.split(';').any { it.equals(entry, ignoreCase = true) }) {
            return
        }
        val updated = if (existing.isEmpty()) entry else "$entry;$existing"
        try {
            val envField = Class.forName("java.lang.ProcessEnvironment")
                .getDeclaredField("theUnmodifiableEnvironment")
            envField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = envField.get(null) as MutableMap<String, String>
            env[key] = updated
        } catch (_: Exception) {
            // ignore
        }
    }

    fun loadEngineLibraries(): Boolean {
        if (tryLoadFromLibraryPath()) {
            return true
        }

        val bundledDir = extractBundledLibraries() ?: return false
        return loadFromDirectory(bundledDir)
    }

    private fun tryLoadFromLibraryPath(): Boolean {
        try {
            val coreFileName = System.mapLibraryName("chunkup_core")
            val libPath = System.getProperty("java.library.path")
                ?.split(System.getProperty("path.separator") ?: ";")
                ?.firstOrNull { entry ->
                    Files.isRegularFile(java.nio.file.Path.of(entry, coreFileName))
                }
            if (libPath != null) {
                nativeLibraryDir = java.nio.file.Path.of(libPath)
            } else {
                System.getProperty("chunkup.native.dir")?.takeIf { it.isNotEmpty() }?.let { dir ->
                    nativeLibraryDir = java.nio.file.Path.of(dir)
                }
            }

            nativeLibraryDir?.let {
                prepareNativeDirectory(it)
                preloadWindowsDependencies(it)
            }

            // 先加载 GPU 库，Rust 侧 dlopen 才能复用已映射模块。
            tryLoadOptional("chunkup_cuda")
            tryLoadOptional("chunkup_opencl")
            System.loadLibrary("chunkup_core")

            // ── Linux: 设置 CHUNKUP_NATIVE_DIR 供 gpu_loader.rs dlopen ──
            setNativeDirEnv()

            LOGGER.info("Loaded chunkup_core from java.library.path ({})", nativeLibraryDir)
            return true
        } catch (_: UnsatisfiedLinkError) {
            return false
        }
    }

    private fun loadFromDirectory(dir: Path): Boolean {
        nativeLibraryDir = dir
        prepareNativeDirectory(dir)
        preloadWindowsDependencies(dir)
        for (baseName in LIBRARY_BASE_NAMES) {
            val fileName = System.mapLibraryName(baseName)
            val path = dir.resolve(fileName)
            if (!Files.isRegularFile(path)) {
                if (baseName == "chunkup_cuda" || baseName == "chunkup_opencl") {
                    LOGGER.info("GPU backend {} not bundled (OK on CL-only distros)", baseName)
                }
                continue
            }
            try {
                System.load(path.toAbsolutePath().toString())
                LOGGER.info("Loaded native library from jar: {}", fileName)
            } catch (e: UnsatisfiedLinkError) {
                if (baseName == "chunkup_core") {
                    LOGGER.warn("Failed to load {}", fileName, e)
                    return false
                }
                LOGGER.warn("Optional native library not loaded: {} ({})", fileName, e.message)
            }
        }

        // ── Linux: 设置 CHUNKUP_NATIVE_DIR 供 gpu_loader.rs dlopen ──
        setNativeDirEnv()

        return true
    }

    /**
     * 在 Linux 上设置 `CHUNKUP_NATIVE_DIR` 环境变量，
     * 让 Rust 的 `gpu_loader.rs` 能找到 libchunkup_cuda.so / libchunkup_opencl.so。
     * 同时也把这些目录追加到 `LD_LIBRARY_PATH` 以便 dlopen 解析依赖。
     */
    private fun setNativeDirEnv() {
        val dir = nativeLibraryDir?.toAbsolutePath()?.toString() ?: return
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        if (isLinux) {
            try {
                // 设置系统属性，Java 侧也可见
                System.setProperty("chunkup.native.dir", dir)

                // 修正 LD_LIBRARY_PATH — 用反射避免 Java 9+ module 限制
                val envField = Class.forName("java.lang.ProcessEnvironment")
                    .getDeclaredField("theUnmodifiableEnvironment")
                envField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val env = envField.get(null) as MutableMap<String, String>
                val existing = env["LD_LIBRARY_PATH"] ?: ""
                val updated = if (existing.isEmpty()) dir else "$dir:$existing"
                env["LD_LIBRARY_PATH"] = updated

                LOGGER.debug("LD_LIBRARY_PATH updated with native dir: {}", dir)
            } catch (e: Exception) {
                LOGGER.debug("Could not update LD_LIBRARY_PATH (non-fatal): {}", e.message)
            }
        }
    }

    private fun tryLoadOptional(baseName: String) {
        try {
            System.loadLibrary(baseName)
            LOGGER.info("Loaded {} from java.library.path", baseName)
        } catch (e: UnsatisfiedLinkError) {
            LOGGER.warn("Optional GPU library {} not loaded from java.library.path: {}",
                baseName, e.message)
        }
    }

    private fun extractBundledLibraries(): Path? {
        val platform = platformDirectory()
        val classLoader = NativeLibraryLoader::class.java.classLoader
        val extractDir = Files.createTempDirectory("chunkup-native-$platform")
        extractDir.toFile().deleteOnExit()

        val resourceRoot = "assets/chunkup/native/$platform"
        val resourceUrls = classLoader.getResources(resourceRoot)
            .toList()
            .ifEmpty {
                classLoader.getResource(resourceRoot)?.let { listOf(it) } ?: emptyList()
            }

        var extracted = 0
        for (url in resourceUrls) {
            extracted += when (url.protocol) {
                "jar" -> extractFromJarUrl(url, resourceRoot, extractDir)
                "file" -> extractFromFileUrl(url, resourceRoot, extractDir)
                else -> 0
            }
        }

        if (extracted == 0) {
            for (baseName in LIBRARY_BASE_NAMES) {
                val fileName = System.mapLibraryName(baseName)
                val resourcePath = "$resourceRoot/$fileName"
                val stream = classLoader.getResourceAsStream(resourcePath) ?: continue
                stream.use { input ->
                    val target = extractDir.resolve(fileName)
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    target.toFile().deleteOnExit()
                    extracted++
                }
            }
        }

        if (extracted == 0) {
            LOGGER.warn("No bundled native libraries for platform {}", platform)
            return null
        }
        return extractDir
    }

    private fun extractFromJarUrl(url: java.net.URL, resourceRoot: String, extractDir: Path): Int {
        val connection = url.openConnection() as java.net.JarURLConnection
        var extracted = 0
        connection.jarFile.use { jar ->
            val prefix = connection.entryName?.trimEnd('/')?.plus("/") ?: "$resourceRoot/"
            for (entry in jar.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) {
                    continue
                }
                val relative = entry.name.removePrefix(prefix)
                if (relative.isEmpty() || relative.contains("..")) {
                    continue
                }
                jar.getInputStream(entry).use { input ->
                    val target = extractDir.resolve(relative)
                    Files.createDirectories(target.parent)
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    target.toFile().deleteOnExit()
                    extracted++
                }
            }
        }
        return extracted
    }

    private fun extractFromFileUrl(url: java.net.URL, resourceRoot: String, extractDir: Path): Int {
        val sourceDir = java.nio.file.Path.of(url.toURI())
        if (!Files.isDirectory(sourceDir)) {
            return 0
        }
        var extracted = 0
        Files.walk(sourceDir).use { paths ->
            for (path in paths) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val relative = sourceDir.relativize(path).toString().replace('\\', '/')
                val target = extractDir.resolve(relative)
                Files.createDirectories(target.parent)
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                target.toFile().deleteOnExit()
                extracted++
            }
        }
        return extracted
    }

    private fun platformDirectory(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("win") -> "windows-x86_64"
            os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm64"))
                "macos-aarch64" else "macos-x86_64"
            else -> if (arch.contains("aarch64") || arch.contains("arm64"))
                "linux-aarch64" else "linux-x86_64"
        }
    }
}

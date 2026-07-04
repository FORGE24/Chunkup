package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.Chunkup
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 从 mod jar 内嵌资源解压 native 库，或回退到 [java.library.path]。
 *
 * 资源路径：`assets/chunkup/native/<platform>/chunkup_*.dll`
 */
object NativeLibraryLoader {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.native")

	private val LIBRARY_BASE_NAMES = listOf(
		"chunkup_cuda",
		"chunkup_opencl",
		"chunkup_core",
	)

	/** 实际加载 native 库的目录，供 Rust dlopen GPU 后端时使用。 */
	@Volatile
	private var nativeLibraryDir: java.nio.file.Path? = null

	fun nativeLibraryDirectory(): String? =
		nativeLibraryDir?.toAbsolutePath()?.toString()

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
			// java.library.path 场景下需先加载 GPU 库，Rust 侧 dlopen 才能复用已映射模块。
			tryLoadOptional("chunkup_cuda")
			tryLoadOptional("chunkup_opencl")
			System.loadLibrary("chunkup_core")
			LOGGER.info("Loaded chunkup_core from java.library.path ({})", nativeLibraryDir)
			return true
		} catch (_: UnsatisfiedLinkError) {
			return false
		}
	}

	private fun loadFromDirectory(dir: Path): Boolean {
		nativeLibraryDir = dir
		for (baseName in LIBRARY_BASE_NAMES) {
			val fileName = System.mapLibraryName(baseName)
			val path = dir.resolve(fileName)
			if (!Files.isRegularFile(path)) {
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
		return true
	}

	private fun tryLoadOptional(baseName: String) {
		try {
			System.loadLibrary(baseName)
			LOGGER.info("Loaded {} from java.library.path", baseName)
		} catch (e: UnsatisfiedLinkError) {
			LOGGER.warn("Optional GPU library {} not loaded from java.library.path: {}", baseName, e.message)
		}
	}

	private fun extractBundledLibraries(): Path? {
		val platform = platformDirectory()
		val classLoader = NativeLibraryLoader::class.java.classLoader
		val extractDir = Files.createTempDirectory("chunkup-native-$platform")
		extractDir.toFile().deleteOnExit()

		var extracted = 0
		for (baseName in LIBRARY_BASE_NAMES) {
			val fileName = System.mapLibraryName(baseName)
			val resourcePath = "assets/chunkup/native/$platform/$fileName"
			val stream = classLoader.getResourceAsStream(resourcePath)
			if (stream == null) {
				continue
			}
			stream.use { input ->
				val target = extractDir.resolve(fileName)
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
				target.toFile().deleteOnExit()
				extracted++
			}
		}

		if (extracted == 0) {
			LOGGER.warn("No bundled native libraries for platform {}", platform)
			return null
		}
		return extractDir
	}

	private fun platformDirectory(): String {
		val os = System.getProperty("os.name").lowercase()
		val arch = System.getProperty("os.arch").lowercase()
		return when {
			os.contains("win") -> "windows-x86_64"
			os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm64")) "macos-aarch64" else "macos-x86_64"
			else -> if (arch.contains("aarch64") || arch.contains("arm64")) "linux-aarch64" else "linux-x86_64"
		}
	}
}

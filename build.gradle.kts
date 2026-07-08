// Fabric mod (Kotlin/Java) + native engine build hooks

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.0"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

val lwjglVersion = "3.3.1"

configurations.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.lwjgl") {
			useVersion(lwjglVersion)
			because("Lock LWJGL to $lwjglVersion")
		}
	}
}

repositories {
	maven {
		name = "Modrinth"
		url = uri("https://api.modrinth.com/maven")
		content {
			includeGroup("maven.modrinth")
		}
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("chunkup") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}

	runs {
		named("client") {
			val nativeDir = layout.buildDirectory.dir("native").get().asFile.absolutePath
			vmArg("-Djava.library.path=$nativeDir")
			vmArg("-Dchunkup.native.dir=$nativeDir")
			vmArg("-Dchunkup.instantLoad=true")
			vmArg("-Dchunkup.gpuWorldGen=false")
			vmArg("-Dchunkup.gpuDensityBatch=false")
			vmArg("-Dchunkup.layeredSections=true")
			vmArg("-Dchunkup.layeredSections.rate=4")
			vmArg("-Dchunkup.gpuSections=false")
			vmArg("-Dchunkup.forceGpu=true")
			vmArg("-Dchunkup.gpuChunkLoad.generated=false")
			vmArg("-Dchunkup.gpuChunkLoad.loaded=false")
			vmArg("-Dchunkup.gpuSkylightApply=false")
			vmArg("-Dchunkup.gpuChunkLoad.batchSize=64")
			vmArg("-Dchunkup.gpuChunkLoad.flushInterval=20")
			vmArg("-Dchunkup.gpuChunkLoad.minFlushBatch=16")
			vmArg("-Dchunkup.f3Debug=true")
			vmArg("-Dchunkup.debug.probe=true")
			vmArg("-DRUST_LOG=warn,chunkup_core=info")
		}
	}
}

fabricApi {
	configureDataGeneration {
		client = true
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

	// Sodium：compileOnly 用于 Mixin 类型；runClient 可选加载
	modCompileOnly("maven.modrinth:sodium:mc1.20.1-0.5.11")
	modRuntimeOnly("maven.modrinth:sodium:mc1.20.1-0.5.11")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}

// Java Mixin 引用 Kotlin 类，必须先编译 Kotlin
tasks.named("compileJava") {
	dependsOn(tasks.named("compileKotlin"))
}
tasks.named("compileClientJava") {
	dependsOn(tasks.named("compileClientKotlin"))
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// ---------------------------------------------------------------------------
// Native engine: Kotlin → JNI/FFM → Rust → CUDA/OpenCL/CPU
// ---------------------------------------------------------------------------

tasks.register<Exec>("codegenFactorSpline") {
	group = "chunkup"
	description = "Generate chunkup_factor_eval.h from factor.json"
	workingDir = layout.projectDirectory.asFile
	val python = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
	commandLine(python, layout.projectDirectory.file("scripts/codegen-factor-spline.py").asFile.absolutePath)
}

tasks.register<Exec>("extractOverworldRouter") {
	group = "chunkup"
	description = "Parse overworld.json noise_router into chunkup_overworld_router.h"
	dependsOn("codegenFactorSpline")
	workingDir = layout.projectDirectory.asFile
	val python = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
	commandLine(python, layout.projectDirectory.file("scripts/extract-overworld-router.py").asFile.absolutePath)
}

tasks.register<Exec>("buildGpuNativeEngine") {
	group = "chunkup"
	description = "Build CUDA/OpenCL backends via scripts/build-engine.ps1"
	dependsOn("extractOverworldRouter")
	workingDir = layout.projectDirectory.asFile
	val script = layout.projectDirectory.file("scripts/build-engine.ps1").asFile
	if (System.getProperty("os.name").lowercase().contains("windows")) {
		commandLine(
			"powershell",
			"-NoProfile",
			"-ExecutionPolicy", "Bypass",
			"-File", script.absolutePath,
		)
	} else {
		commandLine("bash", layout.projectDirectory.file("scripts/build-engine.sh").asFile.absolutePath)
	}
}

tasks.register<Exec>("buildGpuNativeEngineDebug") {
	group = "chunkup"
	description = "Build CUDA/OpenCL + Rust backends (Debug, with debug symbols)"
	dependsOn("extractOverworldRouter")
	workingDir = layout.projectDirectory.asFile
	val script = layout.projectDirectory.file("scripts/build-engine.ps1").asFile
	if (System.getProperty("os.name").lowercase().contains("windows")) {
		commandLine(
			"powershell",
			"-NoProfile",
			"-ExecutionPolicy", "Bypass",
			"-File", script.absolutePath,
			"-Configuration", "Debug",
		)
	} else {
		commandLine("bash", layout.projectDirectory.file("scripts/build-engine.sh").asFile.absolutePath)
		environment("CHUNKUP_BUILD_CONFIG", "Debug")
	}
}

tasks.register<Exec>("buildNativeEngine") {
	group = "chunkup"
	description = "Build Rust core via cargo (release)"
	dependsOn("buildGpuNativeEngine")
	workingDir = layout.projectDirectory.dir("engine").asFile
	commandLine("cargo", "build", "--release")
}

fun nativePlatformDirectory(): String {
	val os = System.getProperty("os.name").lowercase()
	val arch = System.getProperty("os.arch").lowercase()
	return when {
		os.contains("win") -> "windows-x86_64"
		os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm64")) "macos-aarch64" else "macos-x86_64"
		else -> if (arch.contains("aarch64") || arch.contains("arm64")) "linux-aarch64" else "linux-x86_64"
	}
}

tasks.register<Exec>("buildSettingsNative") {
	group = "chunkup"
	description = "Build Qt settings JNI DLL (chunkup_settings.dll)"
	onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
	commandLine(
		"powershell",
		"-NoProfile",
		"-ExecutionPolicy", "Bypass",
		"-File",
		project.file("scripts/build-settings.ps1").absolutePath,
		"-Toolchain", "MinGW",
	)
}

val nativeOutputDir = layout.buildDirectory.dir("native")
val rustReleaseDir = layout.projectDirectory.dir("engine/target/release")
val rustDebugDir = layout.projectDirectory.dir("engine/target/debug")
val nativeGpuDir = layout.projectDirectory.dir("build/native-gpu")

tasks.register<Exec>("buildSettingsNativeDebug") {
	group = "chunkup"
	description = "Build Qt settings JNI DLL (debug, chunkup_settings.dll)"
	onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
	commandLine(
		"powershell",
		"-NoProfile",
		"-ExecutionPolicy", "Bypass",
		"-File",
		project.file("scripts/build-settings.ps1").absolutePath,
		"-Toolchain", "MinGW",
		"-Configuration", "Debug",
	)
}

tasks.register<Copy>("copyNativeLibraries") {
	group = "chunkup"
	description = "Assemble native libraries into build/native and stage for jar embedding"
	dependsOn("buildNativeEngine")
	if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
		dependsOn("buildSettingsNative")
	}

	from(rustReleaseDir) {
		include("chunkup_core.dll", "libchunkup_core.so", "libchunkup_core.dylib")
	}

	// CUDA / OpenCL：build-engine 脚本产出（新路径 build/native-gpu）
	from(nativeGpuDir) {
		include("*.dll", "*.so", "*.dylib")
	}

	// 兼容旧脚本直接写入 build/native 的 GPU 库
	from(layout.projectDirectory.dir("build/native")) {
		include("chunkup_cuda.dll", "chunkup_opencl.dll", "libchunkup_cuda.so", "libchunkup_opencl.so")
	}

	into(nativeOutputDir)
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("copyNativeLibrariesDebug") {
	group = "chunkup"
	description = "Assemble debug native libraries (with PDB) into build/native"
	dependsOn("buildGpuNativeEngineDebug")
	if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
		dependsOn("buildSettingsNativeDebug")
	}

	from(rustDebugDir) {
		include(
			"chunkup_core.dll",
			"chunkup_core.pdb",
			"libchunkup_core.so",
			"libchunkup_core.dylib",
			"*.pdb",
		)
	}

	from(layout.projectDirectory.dir("engine/target/debug/deps")) {
		include("*.pdb")
	}

	from(nativeGpuDir) {
		include("*.dll", "*.pdb", "*.so", "*.dylib")
	}

	from(layout.projectDirectory.dir("build/native")) {
		include("chunkup_cuda.dll", "chunkup_cuda.pdb", "chunkup_opencl.dll", "chunkup_opencl.pdb")
		include("libchunkup_cuda.so", "libchunkup_opencl.so")
	}

	from(layout.projectDirectory.dir("build/cuda/Debug")) {
		include("*.dll", "*.pdb", "*.so")
	}

	from(layout.projectDirectory.dir("build/cuda")) {
		include("chunkup_cuda.dll", "chunkup_cuda.pdb", "libchunkup_cuda.so")
	}

	from(layout.projectDirectory.dir("build/opencl/Debug")) {
		include("*.dll", "*.pdb", "*.so")
	}

	from(layout.projectDirectory.dir("build/opencl")) {
		include("chunkup_opencl.dll", "chunkup_opencl.pdb", "libchunkup_opencl.so")
	}

	from(layout.projectDirectory.dir("build/settings/Debug")) {
		include("chunkup_settings.dll", "chunkup_settings.pdb")
	}

	into(nativeOutputDir)
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("stageNativeForJar") {
	group = "chunkup"
	description = "Copy build/native into resources path for jar packaging"
	dependsOn("copyNativeLibraries")
	from(nativeOutputDir) {
		include("**/*")
	}
	into(layout.buildDirectory.dir("generated/native/${nativePlatformDirectory()}"))
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Cross-platform: embed pre-built Windows DLLs alongside CI-built Linux .so
tasks.register<Copy>("stageWindowsNativeForJar") {
	group = "chunkup"
	description = "Copy pre-built Windows DLLs for cross-platform jar"
	dependsOn("copyNativeLibraries")
	from(layout.projectDirectory.dir("native/prebuilt/windows-x86_64")) {
		include("*.dll")
	}
	into(layout.buildDirectory.dir("generated/native/windows-x86_64"))
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<ProcessResources>("processResources") {
	dependsOn("stageNativeForJar", "stageWindowsNativeForJar")
	inputs.dir(layout.buildDirectory.dir("generated/native"))
		.withPropertyName("chunkupEmbeddedNatives")
	from(layout.buildDirectory.dir("generated/native")) {
		include("**/*")
		into("assets/chunkup/native")
	}
}

tasks.named<Jar>("jar") {
	dependsOn("copyNativeLibraries", "stageNativeForJar", "stageWindowsNativeForJar")
	inputs.dir(layout.buildDirectory.dir("generated/native"))
		.withPropertyName("chunkupEmbeddedNatives")
	inputs.dir(nativeOutputDir).withPropertyName("chunkupRuntimeNatives")
}

tasks.named("build") {
	dependsOn("copyNativeLibraries")
}

tasks.named("runClient") {
	dependsOn("copyNativeLibraries")
}

val releaseJarName = "chunkup-${project.version}.jar"
val releaseJarWorkName = "chunkup-${project.version}-build.jar"

// Canonical release artifact: build/remapped/chunkup-*.jar (full mod + embedded natives).
// Write to *.jar.work first to avoid corrupt/locked partial outputs on Windows.
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
	destinationDirectory.set(layout.buildDirectory.dir("remapped"))
	archiveFileName.set(releaseJarWorkName)

	doFirst {
		val work = layout.buildDirectory.file("remapped/$releaseJarWorkName").get().asFile
		val release = layout.buildDirectory.file("remapped/$releaseJarName").get().asFile
		work.parentFile.mkdirs()
		work.delete()
		release.delete()
	}

	doLast {
		val work = layout.buildDirectory.file("remapped/$releaseJarWorkName").get().asFile
		val release = layout.buildDirectory.file("remapped/$releaseJarName").get().asFile
		if (!work.exists()) {
			throw GradleException("remapJar work output missing: ${work.absolutePath}")
		}
		if (release.exists() && !release.delete()) {
			logger.lifecycle("Release JAR (work file): ${work.absolutePath}")
			logger.lifecycle(
				"Could not replace build/remapped/$releaseJarName (locked). " +
					"Install the .jar.work file or close runClient / reload IDE.",
			)
			return@doLast
		}
		if (!work.renameTo(release)) {
			work.copyTo(release, overwrite = true)
			work.delete()
		}

		val libsJar = layout.buildDirectory.file("libs/$releaseJarName").get().asFile
		libsJar.parentFile.mkdirs()
		val synced = runCatching {
			if (libsJar.exists() && !libsJar.delete()) {
				false
			} else {
				release.copyTo(libsJar, overwrite = true)
				true
			}
		}.getOrDefault(false)

		if (synced) {
			logger.lifecycle("Chunkup release JAR (with natives): ${libsJar.absolutePath}")
		} else {
			logger.lifecycle("Chunkup release JAR (with natives): ${release.absolutePath}")
			logger.lifecycle(
				"Could not update build/libs/$releaseJarName (file locked). " +
					"Install the remapped JAR above, or close runClient and reload the IDE.",
			)
		}
	}
}

tasks.register("chunkupRelease") {
	group = "chunkup"
	description = "Build the installable mod JAR with embedded native libraries"
	dependsOn("remapJar")
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}

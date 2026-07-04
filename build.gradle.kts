// Fabric mod (Kotlin/Java) + native engine build hooks

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.0"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

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
			vmArg("-Djava.library.path=${layout.buildDirectory.dir("native").get().asFile.absolutePath}")
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
// Run `./scripts/build-engine.ps1` or `./scripts/build-engine.sh` first.
// ---------------------------------------------------------------------------

val nativeLibDir = layout.projectDirectory.dir("build/native")
val rustReleaseDir = layout.projectDirectory.dir("engine/target/release")

tasks.register<Exec>("buildNativeEngine") {
	group = "chunkup"
	description = "Build Rust core via cargo (release)"
	workingDir = layout.projectDirectory.dir("engine").asFile
	commandLine(
		if (System.getProperty("os.name").lowercase().contains("windows")) "cargo" else "cargo",
		"build",
		"--release",
	)
}

tasks.register<Copy>("copyNativeLibraries") {
	group = "chunkup"
	description = "Copy chunkup_core native library into build/native for dev runs"
	dependsOn("buildNativeEngine")
	from(rustReleaseDir) {
		include("chunkup_core.dll", "libchunkup_core.so", "libchunkup_core.dylib")
	}
	from(nativeLibDir) {
		include("*.dll", "*.so", "*.dylib")
	}
	into(layout.buildDirectory.dir("native"))
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("runClient") {
	dependsOn("copyNativeLibraries")
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

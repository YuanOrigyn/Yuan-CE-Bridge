plugins {
    java
}

data class NeoForgeProfile(
    val targetFamily: String,
    val minecraftVersion: String,
    val neoForgeVersion: String,
    val minecraftDependency: String,
    val neoForgeDependency: String,
    val javaVersion: Int
)

// The NeoForge build reuses the exact client sources; only the entrypoint differs.
val profiles = mapOf(
    // One 26.x artifact, compiled against the available NeoForge 26.1.2 API and declared to cover
    // the whole 26.x family, including newer NeoForge releases whose shared APIs remain compatible.
    "26.x" to NeoForgeProfile(
        "26.x", "26.1.2", "26.1.2.109", "[26.1,<27)", "[26.1,<27)", 25
    ),
    // Minecraft 26.1.2, NeoForge 26.1.2.109.
    "26.1" to NeoForgeProfile(
        "26.1", "26.1.2", "26.1.2.109", "[26.1,26.2)", "[26.1,26.2)", 25
    )
)

val target = providers.gradleProperty("target")
    .orElse(providers.gradleProperty("bridgeTarget"))
    .orElse("26.1")
    .get()
val profile = profiles[target] ?: throw GradleException(
    "Unsupported NeoForge target '$target'. Available profiles: ${profiles.keys.joinToString()}")

val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse(profile.minecraftVersion).get()
val neoForgeVersion = providers.gradleProperty("neoForgeVersion").orElse(profile.neoForgeVersion).get()
val minecraftDependency = providers.gradleProperty("minecraftDependency").orElse(profile.minecraftDependency).get()
val neoForgeDependency = providers.gradleProperty("neoForgeDependency").orElse(profile.neoForgeDependency).get()

val modVersion: String by project
val mavenGroup: String by project
val modId: String by project

group = mavenGroup
version = modVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(profile.javaVersion))
    }
}

sourceSets {
    main {
        java.srcDir("../client/src/main/java")
        java.srcDir("../protocol/src/main/java")
        // The Fabric entrypoint (and its ClientPlayNetworking transport) is the only loader-specific file.
        // NeoForge has its own entrypoint in this project; everything else is compiled from the shared tree.
        java.exclude("com/ceclientmod/CraftEngineClientModInit.java")
    }
}

dependencies {
    // NeoForge, FancyModLoader, Minecraft, JEI and Jade are supplied as local jars in libs/ so the build
    // works without resolving anything from the network. See README.md for where each jar comes from.
    compileOnly(fileTree("libs") { include("*.jar") })
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(profile.javaVersion)
    }
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf(
            "mod_id" to modId,
            "mod_version" to modVersion,
            "minecraft_version" to minecraftVersion,
            "neoForge_version" to neoForgeVersion,
            "minecraft_dependency" to minecraftDependency,
            "neoforge_dependency" to neoForgeDependency,
            "bridge_target" to profile.targetFamily,
            "java_version" to profile.javaVersion
        )
        inputs.properties(props)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(props)
        }
        filesMatching("bridge-target.properties") {
            expand(props)
        }
    }
    jar {
        archiveBaseName.set("Yuan-CE-Bridge-neoforge-$target")
    }
}

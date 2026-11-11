plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.16-SNAPSHOT"
    id("legacy-looming") version "1.16-SNAPSHOT"
}

repositories {
    maven("https://maven.legacyfabric.net/")
}

dependencies {
    implementation(project(":common"))
    include(project(":common"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(legacy.yarn(property("minecraft_version").toString(), property("yarn_build").toString()))
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    // Legacy Fabric API is installed separately by players (like the 1.12.2 server mods of the other projects).
    modImplementation("net.legacyfabric.legacy-fabric-api:legacy-fabric-api:${property("legacy_fabric_api_version")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

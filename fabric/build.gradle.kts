plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

dependencies {
    implementation(project(":common"))
    include(project(":common"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

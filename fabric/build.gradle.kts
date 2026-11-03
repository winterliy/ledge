plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(project(":common"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

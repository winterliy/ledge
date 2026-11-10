plugins {
    id("fabric-loom") version "1.19-SNAPSHOT"
}

dependencies {
    implementation(project(":common"))
    include(project(":common"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // Ship the Fabric API modules this mod uses inside the jar (jar-in-jar): players only need Fabric Loader.
    for (module in listOf("fabric-api-base", "fabric-key-binding-api-v1", "fabric-lifecycle-events-v1", "fabric-networking-api-v1")) {
        include(fabricApi.module(module, property("fabric_api_version").toString()))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

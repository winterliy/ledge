plugins {
    java
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

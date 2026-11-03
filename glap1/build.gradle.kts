plugins {
    java
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("org.popcraft:chunky-common:1.5.4")
}

tasks.jar {
    from(
        project(":common")
            .extensions
            .getByType<org.gradle.api.tasks.SourceSetContainer>()
            .named("main")
            .get()
            .output
    )
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

plugins {
    java
}

dependencies {
    implementation(project(":common"))
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
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
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

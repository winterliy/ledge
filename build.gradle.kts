allprojects {
    group = "dev.ledge"
    version = "0.1.0-alpha.5"
}

subprojects {
    // Source uses Java 17 syntax; Jabel rewrites it so the jar loads on Java 8 (1.16.5 servers often still run it).
    plugins.withType<JavaPlugin> {
        dependencies {
            "annotationProcessor"("com.pkware.jabel:jabel-javac-plugin:1.0.1-2")
            "compileOnly"("com.pkware.jabel:jabel-javac-plugin:1.0.1-2")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        options.release.set(8)
        options.encoding = "UTF-8"
    }
}

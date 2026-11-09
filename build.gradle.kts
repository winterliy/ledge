allprojects {
    group = "dev.ledge"
    version = "0.1.0-alpha.4"
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(16)
        options.encoding = "UTF-8"
    }
}

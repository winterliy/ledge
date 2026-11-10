allprojects {
    group = "dev.ledge"
    version = "0.1.0-alpha.5"
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
}

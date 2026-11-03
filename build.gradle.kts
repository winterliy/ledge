allprojects {
    group = "dev.ledge"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
}

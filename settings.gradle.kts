pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.legacyfabric.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-public/")
    }
}

rootProject.name = "ledge"
include("common", "glap1", "fabric")

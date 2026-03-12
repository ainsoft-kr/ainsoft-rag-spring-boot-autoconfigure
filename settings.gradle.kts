pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

includeBuild("../ainsoft-rag-engine")

rootProject.name = "ainsoft-rag-spring-boot-autoconfigure"

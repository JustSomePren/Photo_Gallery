pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central; GitHub, where Central sends some files, often times out
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's copy of Maven Central; GitHub, where Central sends some files, often times out
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "PhotoGallery"
include(":app")

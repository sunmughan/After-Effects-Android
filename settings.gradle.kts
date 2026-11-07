pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // kobjects' KXML implementation keeps XmlPull API separate from its
        // parser classes, allowing Android to supply its platform interfaces.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EffectCraftAndroid"
include(":app")

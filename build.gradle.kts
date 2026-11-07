plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // google-services plugin (4.4.4, latest; AGP 9.x-compatible). Declared
    // here only; it is applied in the app module ONLY when
    // app/google-services.json exists, so builds without Firebase
    // registration keep working (see app/build.gradle.kts).
    id("com.google.gms.google-services") version "4.4.4" apply false
}

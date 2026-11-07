plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

// google-services plugin, applied ONLY when app/google-services.json exists.
// The plugin fails the build when the file is missing, and this app must
// build and run fully offline without Firebase registration, so it is never
// applied unconditionally. Once the app is registered (see
// docs/firebase-registration.md) and the real file is placed at
// app/google-services.json, the plugin applies on the next build and
// generates the Firebase config resources; FirebaseInit already handles
// both states at runtime, so no code change is needed at registration time.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// M7 release signing: read ONLY from Gradle properties supplied by the caller
// (in CI, from GitHub Actions secrets via -P flags in android-release.yml).
// No keystore, password, alias or secret value is ever committed or defaulted
// here. When any property is absent, no release signing config is created and
// a release build is unsigned: it must never be treated as a release artifact.
// The release workflow fails before Gradle runs if any required secret is
// absent, so that path cannot silently produce an unsigned "release" in CI.
val releaseKeystoreFile = providers.gradleProperty("effectcraft.release.keystoreFile").orNull
val releaseKeystorePassword = providers.gradleProperty("effectcraft.release.keystorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("effectcraft.release.keyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("effectcraft.release.keyPassword").orNull
val hasReleaseSigning = !releaseKeystoreFile.isNullOrBlank()
    && !releaseKeystorePassword.isNullOrBlank()
    && !releaseKeyAlias.isNullOrBlank()
    && !releaseKeyPassword.isNullOrBlank()

gradle.taskGraph.whenReady {
    val buildsRelease = hasTask(":app:assembleRelease") || hasTask(":app:bundleRelease")
    if (buildsRelease && !hasReleaseSigning) {
        logger.lifecycle(
            "EffectCraft release signing: no release signing properties supplied " +
            "(-Peffectcraft.release.*), so this release build will be UNSIGNED. " +
            "It is not a release artifact. Supply the four release secrets " +
            "(see docs/M7_RELEASE_BUILD.md) to sign.",
        )
    }
}

android {
    namespace = "com.codeair.effectcraft"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codeair.effectcraft"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment:1.9.1")

    // KXML parser/serializer implementation without its bundled XmlPull API.
    // Android provides org.xmlpull.v1 at runtime; test SDK stubs provide
    // the interface while the KXML implementation remains JVM-testable.
    implementation("com.github.kobjects:kxml2:v2.5.0") {
        exclude(group = "org.xmlpull", module = "xmlpull")
    }

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.4")

    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}

licenseReport {
    configurations = arrayOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
}

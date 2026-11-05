plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.jk1.dependency-license-report") version "3.1.4"
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
            // M7: first release build type. R8 minification + resource
            // shrinking apply to release only, so debug builds and the
            // existing debug CI path are unchanged.
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
    // Explicit (not transitive-only): the FCM service posts via NotificationCompat.
    implementation("androidx.core:core-ktx:1.17.0")

    // FCM push. No google-services plugin and no google-services.json yet:
    // the BoM pins versions only, and FirebaseInit guards all runtime use,
    // so the app builds and runs fully offline until registration lands.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.4")

    // Android's org.json methods are SDK stubs in local JVM tests; use the real implementation.
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}

licenseReport {
    // M7: the release workflow also generates this report, so it must cover
    // the release runtime graph, not only debug. Debug CI output is unchanged.
    configurations = arrayOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
}

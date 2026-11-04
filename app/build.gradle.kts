plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.jk1.dependency-license-report") version "3.1.4"
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
    configurations = arrayOf("debugRuntimeClasspath")
}

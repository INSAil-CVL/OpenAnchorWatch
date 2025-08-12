plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room") // plugin Room conservé
}

android {
    namespace = "com.insail.anchorwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.insail.anchorwatch"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // adapte à ta version Kotlin
    }

    // Optionnel mais utile : expose les schémas aux tests d'instrumentation
    sourceSets["androidTest"].assets.srcDirs(files("$projectDir/schemas"))

    packaging { resources.excludes += setOf("META-INF/*") }
}

// ➜ Configuration du plugin Room : dossier des schémas
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.24"))

    // Coroutines compatibles Kotlin 1.9.x
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // MapLibre Android SDK (stable 10.x via version catalog)
    implementation(libs.android.sdk)

    // Google Play Services Location (FusedLocation + Geofencing)
    implementation(libs.play.services.location)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Lifecycle & Coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (preferences)
    implementation(libs.androidx.datastore.preferences)

    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

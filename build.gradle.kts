plugins {
    id("com.android.application") version "8.5.2" apply false // ou 8.6.1 si tu as monté
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("androidx.room") version "2.6.1" apply false
}


subprojects {
    configurations.all {
        resolutionStrategy.force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.24",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24",
            "org.jetbrains.kotlin:kotlin-reflect:1.9.24"
        )
    }
}
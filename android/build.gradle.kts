// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9.0 provides built-in Kotlin; the standalone kotlin.android plugin is
    // removed. The Kotlin/KGP version is driven by the Compose compiler plugin
    // below (2.3.20), which is required to consume backdrop 1.0.6 (compiled with
    // Kotlin 2.3).
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.dagger.hilt.android") version "2.60" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

// AGP 9 ships built-in Kotlin support, so there is no org.jetbrains.kotlin.android
// plugin here — applying it is now an error.
plugins {
    id("com.android.application") version "9.3.1" apply false
}

// ~/.gradle/init.gradle.kts on this machine injects project-level repositories
// (mavenLocal, mavenCentral, internal Nexus) into allprojects. Project-level
// repositories take precedence over settings.gradle.kts, and that list has no
// google() — so AGP cannot resolve aapt2 or any androidx artifact.
//
// Adding google() here restores it without editing the global init script.
allprojects {
    repositories {
        google()
    }
}

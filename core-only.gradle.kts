// The root build for `-Pivy.coreOnly=true`: no Android Gradle plugin, so the
// core builds and tests on machines without the Android SDK or Google Maven.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

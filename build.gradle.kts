// The Android Gradle plugin is put on the classpath only when the Android module is in the build (settings.gradle.kts):
// a machine without the Android SDK may not reach Google's Maven either.
buildscript {
    if (findProject(":android") != null) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

allprojects {
    group = "com.chencohensolution.shed"
    version = "0.1.0"
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shed on Android: the device's keys in the Android Keystore and uploads in the background (WorkManager). Built only
// where the Android SDK is (settings.gradle.kts); the Android Gradle plugin comes from the root's buildscript.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.chencohensolution.shed.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":backend-journal"))
            implementation(libs.androidx.work.runtime)
        }
    }
}

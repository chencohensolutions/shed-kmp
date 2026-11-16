rootProject.name = "shed"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":core")
include(":backend-journal")

// The Android module needs the Android SDK. A machine without one (a cloud container) builds and tests the rest.
val home = System.getProperty("user.home")
val hasAndroidSdk = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).isNotEmpty() ||
    // Its own local.properties, or the app's when shed is a submodule of it (Audio Logs' shed/).
    listOf(file("local.properties"), file("../local.properties")).any { it.exists() && "sdk.dir" in it.readText() } ||
    file("$home/Library/Android/sdk").exists() || file("$home/Android/Sdk").exists() ||
    file("${System.getenv("LOCALAPPDATA")}/Android/Sdk").exists()
if (hasAndroidSdk) include(":android")

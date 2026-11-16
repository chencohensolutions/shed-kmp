plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm {
        // Android runs this build too, so it targets what Android reads (the apps compile to Java 11).
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            api(libs.okhttp)
            implementation(libs.okhttp.tls)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

// The end-to-end test runs only against a journal server that scripts/e2e.sh started (it sets SHED_E2E_*).
tasks.withType<Test>().configureEach {
    System.getenv().filterKeys { it.startsWith("SHED_E2E_") }.forEach { (k, v) -> environment(k, v) }
    outputs.upToDateWhen { false }
}

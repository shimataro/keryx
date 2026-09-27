import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test-only helpers shared by :shared's and :composeApp's tests (in-memory/file databases, row
// inserters, cloud-session fakes, a fake NotificationMessages). A module of its own because a
// Kotlin Multiplatform test source set cannot be depended on from another project. Never a
// dependency of any main source set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvmToolchain(25)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    // :shared's commonTest runs on its Apple targets too, and depends on this module's fakes.
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

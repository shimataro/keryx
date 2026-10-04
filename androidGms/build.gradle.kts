plugins {
    alias(libs.plugins.androidLibrary)
}

// Everything in the app that depends on Google Play services — Google Drive on Android, through
// Play services' AuthorizationClient (see docs/sync-architecture.md's "Google Drive on Android").
//
// A separate module because :shared and :composeApp are Kotlin Multiplatform libraries, which have
// no product flavors: a dependency declared in their androidMain reaches every flavor of
// :androidApp. Only :androidApp's `github` and `play` flavors depend on this module, so the
// `fdroid` flavor ships no com.google.android.gms class at all — F-Droid's policy forbids them.
// The app side reaches this code only through `AndroidGoogleDriveBackend` (:shared), registered
// from KeryxApplication, never by importing from here.
android {
    namespace = "works.merc.keryx.app.gms"
    // Kept in lockstep with :androidApp.
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // PlayServicesAuthorization & co. implement :shared's cloud interfaces and use its Result/Log;
    // :composeApp supplies AndroidAuthorizationHost, which carries the consent screen's result back
    // from MainActivity. :shared (with ktor and coroutines) arrives through :composeApp's `api`.
    implementation(project(":composeApp"))
    implementation(libs.play.services.auth)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

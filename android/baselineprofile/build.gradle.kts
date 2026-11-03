// The Baseline Profile generator for app-android: BaselineProfileGenerator's D-pad journeys on a connected
// Android TV, run against the app's nonMinifiedHarness build (the harness build without R8, so the rules name the
// classes as they are; R8 rewrites them for release). `:app-android:generateBaselineProfile` runs them and writes
// android/app/src/main/generated/baselineProfiles; the device test harness runs that on the TV emulator with the
// content the journeys use.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.edde746.tortuga.baselineprofile"
    compileSdk = 37
    defaultConfig {
        // Generation on an unrooted device (the TV emulator's user build) needs API 33, the journeys' fresh focus reads
        // (UiAutomation.clearCache) API 34.
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // app-android's build types beside debug, so the plugin makes their nonMinified and benchmark variants here too:
    // only harness has the dev session the journeys need.
    buildTypes {
        create("harness")
    }
    targetProjectPath = ":app-android"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

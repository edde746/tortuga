pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the JDK toolchains a host lacks: 27 (shared, dev, ui-compose, the server) and 25 (the
    // desktop modules; JetBrains Runtime on Linux).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// mpv-build's mpv-android library (libmpv, FFmpeg and the JNI glue with its Kotlin API, one AAR), pinned in
// mpv-build.lock.json: its key is the version :mpv-android depends on, and its sha256 the one Gradle's dependency
// verification checks (gradle/verification-metadata.xml, which tools/set_native_revision.sh writes with the lock).
// Gradle fetches it from the GitHub release through an Ivy repository: no metadata, no authentication. The repository
// is the AAR's own release (the publish run's that built it) when the lock names one, else the android group's.
@Suppress("UNCHECKED_CAST")
val mpvLock = groovy.json.JsonSlurper().parseText(providers.fileContents(layout.rootDirectory.file("mpv-build.lock.json")).asText.get())
    as Map<String, Map<String, Map<String, Any>>>
val mpvAndroid = mpvLock.getValue("artifacts")["android"]
    ?: throw GradleException("mpv-build.lock.json has no android group; run tools/set_native_revision.sh")
@Suppress("UNCHECKED_CAST")
val mpvAar = mpvAndroid.getValue("aar") as Map<String, String>
check(providers.fileContents(layout.rootDirectory.file("gradle/verification-metadata.xml")).asText.get()
    .contains("<sha256 value=\"${mpvAar.getValue("checksum")}\"")) {
    "gradle/verification-metadata.xml doesn't check mpv-android against mpv-build.lock.json's sha256; run tools/set_native_revision.sh"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "mpv-build"
                    url = uri(mpvAar["assetBase"] ?: mpvAndroid.getValue("assetBase") as String)
                    patternLayout { artifact("[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("edde746", "mpv-android") }
        }
    }
}

rootProject.name = "tortuga"

// Android modules need an Android SDK. Hosts without one (the Windows/Linux desktop test machines) build with
// -Ptortuga.android=false or TORTUGA_ANDROID=false; shared and ui-compose then drop their Android target too.
val androidEnabled = providers.gradleProperty("tortuga.android").orElse(providers.environmentVariable("TORTUGA_ANDROID"))
    .map { it.toBoolean() }.getOrElse(true)

// The app's version, shared with the Apple project (version.xcconfig): `tortuga.version` is MARKETING_VERSION
// (major.minor.patch), `tortuga.build` CURRENT_PROJECT_VERSION (the build number that grows with every release).
val appVersion = providers.fileContents(layout.rootDirectory.file("version.xcconfig")).asText.get().lineSequence()
    .mapNotNull { Regex("""^(\w+)\s*=\s*(\S+)$""").matchEntire(it.trim())?.destructured?.let { (key, value) -> key to value } }
    .toMap()
val marketingVersion = checkNotNull(appVersion["MARKETING_VERSION"]) { "version.xcconfig: no MARKETING_VERSION" }
val buildNumber = checkNotNull(appVersion["CURRENT_PROJECT_VERSION"]) { "version.xcconfig: no CURRENT_PROJECT_VERSION" }

// Where the Android and desktop builds are distributed (the shared BuildFlavor): store for Google
// Play, Amazon and the Microsoft Store, sideloaded (the default) for the GitHub release's APKs and Flatpaks. Set with
// -Ptortuga.flavor=store or TORTUGA_FLAVOR=store; `tortuga.flavor` is the BuildFlavor constant's name. The Apple apps
// are always APPLE (apple/Tortuga/Shared/App/AppLaunch.swift).
val flavor = providers.gradleProperty("tortuga.flavor").orElse(providers.environmentVariable("TORTUGA_FLAVOR"))
    .getOrElse("sideloaded")
check(flavor == "store" || flavor == "sideloaded") { "tortuga.flavor must be store or sideloaded, not '$flavor'" }

gradle.beforeProject {
    extensions.extraProperties["tortuga.android"] = androidEnabled
    extensions.extraProperties["tortuga.version"] = marketingVersion
    extensions.extraProperties["tortuga.build"] = buildNumber.toInt()
    extensions.extraProperties["tortuga.flavor"] = flavor.uppercase()
    extensions.extraProperties["mpv.aar"] = "edde746:mpv-android:${mpvAar.getValue("key")}@aar"
}

// The Android and desktop modules live in android/ and desktop/ under project names of their own, which name their
// outputs (app-android-debug.apk, ...).
include(":shared", ":dev", ":ui-compose", ":mpv-jvm", ":app-desktop", ":server")
project(":mpv-jvm").projectDir = file("desktop/mpv")
project(":app-desktop").projectDir = file("desktop/app")
if (androidEnabled) {
    include(":mpv-android", ":app-android", ":baselineprofile")
    project(":mpv-android").projectDir = file("android/mpv")
    project(":app-android").projectDir = file("android/app")
    project(":baselineprofile").projectDir = file("android/baselineprofile")
}

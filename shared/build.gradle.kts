import co.touchlab.skie.configuration.SuppressSkieWarning
import co.touchlab.skie.plugin.configuration.SkieExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("tortuga.strings")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// SKIE only does work for Apple frameworks, which only build on macOS. :dev applies it the same way for Debug's
// TortugaKit.
if (System.getProperty("os.name").startsWith("Mac")) {
    apply(plugin = libs.plugins.skie.get().pluginId)
    extensions.configure<SkieExtension> {
        // No Swift goes into the framework from Kotlin's source sets. SKIE adds the bundled Swift's directory to the
        // Kotlin/Native compile task as an absolute path, which keeps the compile out of Gradle's build cache between
        // checkouts; with bundling off that directory is never made, so the compile's cache key is the same in any
        // checkout of this tree.
        swiftBundling { enabled.set(false) }
        features {
            // Ktor's HttpStatusCode.description meets NSObject's description() and reaches Swift as description_.
            // Swift never reads it and it isn't ours to rename; the project's own names are Swift-safe at the source
            // (@ObjCName("descriptionText"), and i18n keys the strings plugin checks).
            group("io.ktor.http.HttpStatusCode") { SuppressSkieWarning.NameCollision(true) }
        }
    }
}

// Hosts without an Android SDK build with -Ptortuga.android=false (settings.gradle.kts). The Android plugin then
// isn't applied at all: applied without its target, it fails configuration ("compileSdk version is not set").
val androidEnabled = extra["tortuga.android"] as Boolean
if (androidEnabled) apply(plugin = libs.plugins.android.kmp.library.get().pluginId)

kotlin {
    jvmToolchain(27)

    // jvmAndAndroidMain: plain JDK code both JVM targets run (the loopback OAuth redirect receiver of desktop and
    // Android sign-in, the OkHttp engine, language names and decimals); an actual the two would copy goes there.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                // The Android KMP library plugin's target isn't a KotlinAndroidTarget, which withAndroidTarget() matches.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    if (androidEnabled) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "com.edde746.tortuga.shared"
            compileSdk = 37
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // Device tests run commonTest and src/androidDeviceTest on a real Android runtime (its JCA stack and regex
            // engine differ from the desktop JVM's), from the APK `:shared:assembleAndroidDeviceTest` builds.
            withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }

    // Desktop app (Windows/Linux) and, later, the sync server.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // One umbrella framework for every Apple target: several would each embed a Kotlin runtime. This is
    // the Release configuration's TortugaKit, without the development session (the Xcode script phase's
    // `:shared:embedAndSignAppleFrameworkForXcode`); Debug's is :dev's, with it.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64(), tvosArm64(), tvosSimulatorArm64()).forEach { target ->
        target.binaries.framework(listOf(NativeBuildType.RELEASE)) {
            baseName = "TortugaKit"
            isStatic = true
            // Explicit, or Kotlin/Native warns on every link that it cannot infer one.
            binaryOption("bundleId", "com.edde746.tortuga.TortugaKit")
        }
    }

    sourceSets {
        // `T` and the other string accessors, generated from shared/i18n/*.i18n.json (build-logic's tortuga.strings).
        // As a task-backed source directory it makes every compilation of commonMain depend on the generator.
        commonMain { kotlin.srcDir(tasks.named("generateStrings")) }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // JCA on JVM/Android, CryptoKit (CommonCrypto fallback) on Apple.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            // Addon transport and stream resolving (OkHttp on Android/JVM, Darwin on Apple).
            implementation(libs.ktor.client.core)
            // The anime mapping datasets (trackers/ids): cached files, parsed as streams.
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json.io)
            // Watch together: rooms, sync and matching on Open Watch Party (its relays' WebSockets go through the app's
            // HttpClient, which installs WebSockets).
            implementation(libs.openwatchparty)
        }
        // One OkHttp engine for desktop and Android (net/PlatformHttpEngine.jvmAndAndroid.kt).
        getByName("jvmAndAndroidMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        if (androidEnabled) {
            // Android pins the JCA provider (crypto/CryptoProvider.android.kt), which needs the JDK provider API.
            getByName("androidMain").dependencies {
                implementation(libs.cryptography.provider.jdk)
            }
            getByName("androidDeviceTest").dependencies {
                implementation(libs.androidx.test.runner)
            }
        }
    }
}

// -- addons: fixture corpus --
// The addon fixture corpus (src/commonTest/fixtures: recorded with `:dev:recordFixtures`, plus the
// hand-written malformed set) becomes one generated Kotlin source in commonTest, so the JVM, the Kotlin/Native
// simulators and Android device tests all read the same bytes with no platform file API, test-resource copying or
// simulator path plumbing. Every character outside printable ASCII is a \u escape and each string constant holds at
// most 16 000 characters (≤ 48 KB of modified UTF-8, under the JVM's 64 KiB constant limit), appended at runtime.
abstract class GenerateFixtureSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = fixtures.get().asFile
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val out = output.get().asFile.resolve("com/edde746/tortuga/fixtures/FixtureCorpus.kt")
        out.parentFile.deleteRecursively()
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("// Generated by :shared:generateAddonFixtures from shared/src/commonTest/fixtures. Edit those files.")
            appendLine("package com.edde746.tortuga.fixtures")
            appendLine()
            appendLine("internal object FixtureCorpus {")
            appendLine("    /** Every fixture by its path relative to `fixtures/` (`live/cinemeta/manifest.json`). */")
            appendLine("    val files: Map<String, String> by lazy {")
            appendLine("        mapOf(")
            files.forEachIndexed { i, file -> appendLine("            \"${file.relativeTo(root).invariantSeparatorsPath}\" to f$i(),") }
            appendLine("        )")
            appendLine("    }")
            appendLine()
            appendLine("    fun text(path: String): String = files[path] ?: error(\"no fixture \$path\")")
            files.forEachIndexed { i, file ->
                val text = file.readText()
                appendLine()
                appendLine("    private fun f$i(): String = StringBuilder(${text.length}).apply {")
                text.chunked(16_000).forEach { chunk -> appendLine("        append(\"${escape(chunk)}\")") }
                appendLine("    }.toString()")
            }
            appendLine("}")
        })
    }

    private fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '$' -> append("\\$")
            c.code in 0x20..0x7E -> append(c)
            else -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        }
    }
}

// -- this build's registrations --
// Client registrations with outside services become generated objects in commonMain, so every app (Android, desktop,
// the Apple framework) ships the same ones. Each value comes from a Gradle property or, for CI, an environment
// variable; a build without them leaves that service or flow out. The values ship in the binary, as for every native
// client of these services. Secrets that must stay private live on the server instead: MyAnimeList's and AniList's
// on the tracker relay, the Sign in with Apple key on the hosted instance.
abstract class GenerateRegistrations : DefaultTask() {
    /** The generated object, `<package>.<Name>`. */
    @get:Input
    abstract val objectName: Property<String>

    /** `internal` or `public`. */
    @get:Input
    abstract val visibility: Property<String>

    /** The type of the object's `config`, built from every value as a named String argument. */
    @get:Input
    abstract val configType: Property<String>

    /** The object's KDoc. */
    @get:Input
    abstract val doc: Property<String>

    /** The Gradle property prefix the values come from, for the generated header. */
    @get:Input
    abstract val propertyPrefix: Property<String>

    @get:Input
    abstract val values: MapProperty<String, String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageName = objectName.get().substringBeforeLast('.')
        val simpleName = objectName.get().substringAfterLast('.')
        // The whole output goes first: an object moved to another package leaves no copy behind in the old one.
        output.get().asFile.deleteRecursively()
        val out = output.get().asFile.resolve(packageName.replace('.', '/') + "/$simpleName.kt")
        out.parentFile.mkdirs()
        val fields = values.get().entries.sortedBy { it.key }.joinToString("") { (name, value) -> "        $name = \"${escape(value)}\",\n" }
        out.writeText(
            "// Generated by :shared:$name from the ${propertyPrefix.get()}.* Gradle properties.\n" +
                "package $packageName\n\n" +
                "/** ${doc.get()} */\n" +
                "${visibility.get()} object $simpleName {\n" +
                "    val config: ${configType.get()} = ${configType.get()}(\n" + fields + "    )\n" +
                "}\n",
        )
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '$' -> append("\\$")
            c.code in 0x20..0x7E -> append(c)
            else -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        }
    }
}

/**
 * Registers a [GenerateRegistrations] task: each config field from Gradle property `<prefix>.<field>`, else the
 * environment variable [fields] names, else its entry in [defaults], else blank.
 */
fun registrations(
    task: String,
    objectName: String,
    visibility: String,
    configType: String,
    doc: String,
    prefix: String,
    fields: Map<String, String>,
    defaults: Map<String, String> = emptyMap(),
) = tasks.register<GenerateRegistrations>(task) {
    this.objectName.set(objectName)
    this.visibility.set(visibility)
    this.configType.set(configType)
    this.doc.set(doc)
    propertyPrefix.set(prefix)
    for ((field, env) in fields) {
        values.put(
            field,
            providers.gradleProperty("$prefix.$field").orElse(providers.environmentVariable(env)).orElse(defaults[field] ?: ""),
        )
    }
    output.set(layout.buildDirectory.dir("generated/tortuga/$task/commonMain/kotlin"))
}

// The tracker services. The client IDs are public (every app sends them to the service) and default
// to Tortuga's registrations, the relay to the hosted server's; a fork can point at its own.
val generateTrackerConfig = registrations(
    task = "generateTrackerConfig",
    objectName = "com.edde746.tortuga.trackers.BuildTrackerConfig",
    visibility = "public",
    configType = "TrackerConfig",
    doc = "This build's tracker registrations; blank values leave their services out.",
    prefix = "tortuga.trackers",
    fields = mapOf(
        "traktClientId" to "TORTUGA_TRAKT_CLIENT_ID",
        "simklClientId" to "TORTUGA_SIMKL_CLIENT_ID",
        "mdblistClientId" to "TORTUGA_MDBLIST_CLIENT_ID",
        "malClientId" to "TORTUGA_MAL_CLIENT_ID",
        "relayBaseUrl" to "TORTUGA_TRACKER_RELAY_URL",
    ),
    defaults = mapOf(
        "traktClientId" to "PZ01i12ScnqRYXck6AbVM1u66RmHYo2dMqKvZ7029wY",
        "simklClientId" to "ae6f536adceaf79905c1cf9d1417fafc757c30725c0effb61ebbccbe9a339276",
        "mdblistClientId" to "mYZR3YEKsdzuealY8uh58Lo9zhLxCvIrMLykuGJr",
        "malClientId" to "7dac79a7e05d0d74b08a3f63b0400fec",
        "relayBaseUrl" to "https://api.usetortuga.app",
    ),
)

// Tortuga's own OAuth clients for sign-in: public, since each app's SignInPlatform reads it.
// The IDs are public (every app sends them to the provider) and default to Tortuga's registrations; a fork can point
// at its own. Google's installed-app secrets come from the properties or, in the release workflow, its secrets. The
// apps' bundle ID (the native Sign in with Apple sheet's audience), Google's iOS client (its sheet on iPhone, iPad and
// the Mac, the Info.plist's GIDClientID) and the GitHub OAuth App (the hosted server lists it) are carried so the apps
// use every one of Tortuga's clients with the hosted server alone (SignInConfig.clientIds, ProviderFlows).
val generateSignInConfig = registrations(
    task = "generateSignInConfig",
    objectName = "com.edde746.tortuga.sync.account.signin.BuildSignInConfig",
    visibility = "public",
    configType = "SignInConfig",
    doc = "This build's sign-in registrations; blank values leave their flows out.",
    prefix = "tortuga.signin",
    fields = mapOf(
        "appleBundleId" to "TORTUGA_APPLE_BUNDLE_ID",
        "appleServicesId" to "TORTUGA_APPLE_SERVICES_ID",
        "githubClientId" to "TORTUGA_GITHUB_CLIENT_ID",
        "googleServerClientId" to "TORTUGA_GOOGLE_SERVER_CLIENT_ID",
        "googleDesktopClientId" to "TORTUGA_GOOGLE_DESKTOP_CLIENT_ID",
        "googleDesktopClientSecret" to "TORTUGA_GOOGLE_DESKTOP_CLIENT_SECRET",
        "googleIosClientId" to "TORTUGA_GOOGLE_IOS_CLIENT_ID",
        "googleTvClientId" to "TORTUGA_GOOGLE_TV_CLIENT_ID",
        "googleTvClientSecret" to "TORTUGA_GOOGLE_TV_CLIENT_SECRET",
    ),
    defaults = mapOf(
        "appleBundleId" to "com.edde746.tortuga",
        "appleServicesId" to "com.edde746.tortuga.web",
        "githubClientId" to "Ov23lier0AgGWBrpr0so",
        "googleServerClientId" to "618565924534-3q9dni3mhq211avjdah7mhb9auuo077n.apps.googleusercontent.com",
        "googleDesktopClientId" to "618565924534-7tb56i655ee87s7pgq2jflsgon46hkmg.apps.googleusercontent.com",
        "googleIosClientId" to "618565924534-4bilnnc4hqgjeg3nc61807qsrci6eldi.apps.googleusercontent.com",
        "googleTvClientId" to "618565924534-2mksl2fbmttughj75dt74krpv2tlpep9.apps.googleusercontent.com",
    ),
)

// Tortuga's application on Discord, which Rich Presence's handshake names and Discord shows the activity as ("Watching
// Tortuga"). The ID is public (every client sends it) and defaults to Tortuga's; a fork can point at its own, and a
// blank one leaves Rich Presence out: no Discord switch, nothing connects (DiscordConfig).
val generateDiscordConfig = registrations(
    task = "generateDiscordConfig",
    objectName = "com.edde746.tortuga.presentation.player.BuildDiscordConfig",
    visibility = "public",
    configType = "DiscordConfig",
    doc = "This build's Discord application; a blank one leaves Rich Presence out.",
    prefix = "tortuga.discord",
    fields = mapOf("applicationId" to "TORTUGA_DISCORD_APPLICATION_ID"),
    defaults = mapOf("applicationId" to "1564986921187410030"),
)

// Crash reports' Sentry DSN: blank by default, so only a build given one sends reports. The release workflow passes
// its secret as TORTUGA_SENTRY_DSN; development, CI and harness builds have none.
val generateCrashReportConfig = registrations(
    task = "generateCrashReportConfig",
    objectName = "com.edde746.tortuga.crash.BuildCrashReportConfig",
    visibility = "public",
    configType = "CrashReportConfig",
    doc = "This build's crash-report endpoint; blank sends none.",
    prefix = "tortuga.crashReports",
    fields = mapOf("dsn" to "TORTUGA_SENTRY_DSN"),
)
kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateTrackerConfig)
    kotlin.srcDir(generateSignInConfig)
    kotlin.srcDir(generateDiscordConfig)
    kotlin.srcDir(generateCrashReportConfig)
}

// Persistence (SQLDelight). Every released schema version is checked in under databases/ (`N.db`, written by
// generateCommonMainTortugaDatabaseSchema); `check` runs verifyCommonMainTortugaDatabaseMigration, which fails when
// the .sq files drift from the last snapshot plus the migrations/ files.
sqldelight {
    databases {
        create("TortugaDatabase") {
            packageName.set("com.edde746.tortuga.data.db")
            // SQLite 3.18 is what Android API 26 ships; the Apple and JDBC builds are newer.
            dialect(libs.sqldelight.sqlite.dialect)
            schemaOutputDirectory.set(layout.projectDirectory.dir("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

val generateAddonFixtures = tasks.register<GenerateFixtureSources>("generateAddonFixtures") {
    fixtures.set(layout.projectDirectory.dir("src/commonTest/fixtures"))
    output.set(layout.buildDirectory.dir("generated/tortuga/fixtures/commonTest/kotlin"))
}
kotlin.sourceSets.commonTest { kotlin.srcDir(generateAddonFixtures) }

// -- playback: the chapter marker model --
// src/commonMain/models/chapter_marker_model.bin (playback/ChapterMarkerClassifier) becomes one generated Kotlin source
// in commonMain as base64 string constants of at most 60 000 characters (under the JVM's 64 KiB constant limit), so
// every app ships the same bytes with no platform resource API.
abstract class GenerateChapterMarkerModel : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val model: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val encoded = Base64.getEncoder().encodeToString(model.get().asFile.readBytes())
        val out = output.get().asFile.resolve("com/edde746/tortuga/playback/ChapterMarkerModel.kt")
        out.parentFile.deleteRecursively()
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("// Generated by :shared:generateChapterMarkerModel from src/commonMain/models/chapter_marker_model.bin.")
            appendLine("package com.edde746.tortuga.playback")
            appendLine()
            appendLine("internal object ChapterMarkerModel {")
            appendLine("    /** The model's bytes, base64. */")
            appendLine("    fun base64(): String = StringBuilder(${encoded.length}).apply {")
            encoded.chunked(60_000).forEach { appendLine("        append(\"$it\")") }
            appendLine("    }.toString()")
            appendLine("}")
        })
    }
}

val generateChapterMarkerModel = tasks.register<GenerateChapterMarkerModel>("generateChapterMarkerModel") {
    model.set(layout.projectDirectory.file("src/commonMain/models/chapter_marker_model.bin"))
    output.set(layout.buildDirectory.dir("generated/tortuga/chapterMarkerModel/commonMain/kotlin"))
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateChapterMarkerModel) }

// The generated database code is data's implementation detail, so it is made internal: the Apple framework then
// exports the repositories only, not every query class and row type (the generated `Profile` row would otherwise
// take data.profile.Profile's Swift name). SQLDelight has no visibility option; this runs as part of its task, so
// cached outputs are already rewritten.
tasks.withType<app.cash.sqldelight.gradle.SqlDelightTask>().configureEach {
    doLast {
        val topLevelPublic = Regex("^public (?=(data class|class|interface|object|fun|val) )", RegexOption.MULTILINE)
        outputDirectory.get().asFile.walk().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val rewritten = text.replace(topLevelPublic, "internal ")
            if (rewritten != text) file.writeText(rewritten)
        }
    }
}

kotlin {
    // data/DatabaseFactory is an expect class: each platform opens its database its own way.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.coroutines.extensions)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        appleMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        if (androidEnabled) {
            getByName("androidMain").dependencies {
                implementation(libs.sqldelight.android.driver)
            }
        }
    }
}

// The JVM migration test replays every checked-in schema snapshot (data/MigrationTest.kt); ThirdPartyLicenseFilesTest
// finds every in-app notice's licence texts in the groups of licenses/ each app ships (licenses/apps.json) and in the
// licenses/ of mpv-build's artefacts (mpv-android's AAR; mpv-jvm's libmpv artifact, this host's desktop's: Windows'
// on Windows, Linux's elsewhere), and the licence files the Android build's libraries carry (its shippedLicenseFiles)
// among the APK's; ThirdPartyNoticesCoverageTest finds a notice for every module each app ships (the apps'
// shippedModules, Apple's framework's libraries) and every package the Apple apps link (apple/project.yml).
val mpvArtefacts = configurations.dependencyScope("mpvArtefacts")
val mpvAar = configurations.resolvable("mpvAar") { extendsFrom(mpvArtefacts.get()) }
val mpvNatives = configurations.dependencyScope("mpvNatives")
val mpvNativesPath = configurations.resolvable("mpvNativesPath") {
    extendsFrom(mpvNatives.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("tortuga-mpv-natives")) }
}
dependencies {
    add(mpvArtefacts.name, project.extra["mpv.aar"] as String)
    add(mpvNatives.name, project(":mpv-jvm"))
}
tasks.named<Test>("jvmTest") {
    val aar = files(mpvAar)
    inputs.files(aar).withPropertyName("mpvAar").withPathSensitivity(PathSensitivity.NONE)
    val natives = files(mpvNativesPath)
    inputs.files(natives.asFileTree.matching { include("licenses/**") }).withPropertyName("mpvLicenses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val desktop = if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux"
    doFirst {
        systemProperty("tortuga.mpvArtefact.android", aar.singleFile.absolutePath)
        systemProperty("tortuga.mpvArtefact.$desktop", natives.singleFile.absolutePath)
    }
    val schemaDir = layout.projectDirectory.dir("src/commonMain/sqldelight/databases")
    inputs.dir(schemaDir).withPropertyName("sqldelightSchemas").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("tortuga.schemaDir", schemaDir.asFile.absolutePath)
    val licensesDir = rootProject.file("licenses")
    inputs.dir(licensesDir).withPropertyName("licenses").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("tortuga.licensesDir", licensesDir.absolutePath)
    for ((app, path) in listOfNotNull("desktop" to ":app-desktop", ("android" to ":app-android").takeIf { androidEnabled })) {
        val modules = project(path).layout.buildDirectory.file("shipped-modules.txt").get().asFile
        dependsOn("$path:shippedModules")
        inputs.file(modules).withPropertyName("${app}Modules").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("tortuga.shippedModules.$app", modules.absolutePath)
    }
    if (androidEnabled) {
        val licenseFiles = project(":app-android").layout.buildDirectory.file("shipped-license-files.txt").get().asFile
        dependsOn(":app-android:shippedLicenseFiles")
        inputs.file(licenseFiles).withPropertyName("androidLicenseFiles").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("tortuga.shippedLicenseFiles.android", licenseFiles.absolutePath)
    }
    val appleModules = configurations.named("iosArm64CompileKlibraries").map { klibraries ->
        klibraries.incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ModuleComponentIdentifier)?.moduleIdentifier?.toString() }.sorted()
    }
    inputs.property("appleModules", appleModules)
    doFirst { systemProperty("tortuga.shippedModules.apple", appleModules.get().joinToString(",")) }
    val appleProject = rootProject.file("apple/project.yml")
    inputs.file(appleProject).withPropertyName("appleProject").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("tortuga.appleProject", appleProject.absolutePath)
}

// -- secrets: Keychain tests --
// The Keychain tests (appleTest secrets/KeychainSecretStoreTest) need a booted simulator (a standalone spawn has no
// securityd) and an application identifier, which simulator binaries carry as simulated entitlements in a
// __TEXT,__entitlements section, as Xcode writes them. The macOS test binary is unsigned and the data protection
// keychain accepts only team-signed apps, so they don't run on macOS.
if (System.getProperty("os.name").startsWith("Mac")) {
    val entitlements = layout.projectDirectory.file("src/appleTest/keychain-tests.entitlements")
    listOf(kotlin.iosSimulatorArm64(), kotlin.tvosSimulatorArm64()).forEach { target ->
        target.binaries.getTest("DEBUG").apply {
            linkerOpts("-sectcreate", "__TEXT", "__entitlements", entitlements.asFile.absolutePath)
            linkTaskProvider.configure { inputs.file(entitlements).withPropertyName("simulatedEntitlements") }
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        standalone.set(false)
        val device = device
        // Boots the simulator unless it is running and waits for it. It stays booted, as after an Xcode test run.
        doFirst {
            val boot = ProcessBuilder("xcrun", "simctl", "bootstatus", device.get(), "-b").redirectErrorStream(true).start()
            val output = boot.inputStream.bufferedReader().readText()
            check(boot.waitFor() == 0) { "Couldn't boot simulator ${device.get()}:\n$output" }
        }
    }
    tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("macosArm64Test") {
        filter.excludeTestsMatching("com.edde746.tortuga.secrets.KeychainSecretStoreTest")
    }
}

// -- sync --
// The sync client and the tracker connections end to end against a running server (sync/e2e/SyncEndToEndTest.kt,
// TrackerEndToEndTest.kt). `jvmTest` leaves them out; server/scripts/sync-e2e.sh runs `syncEndToEndTest` with a
// stack's coordinates (-Ptortuga.e2e.server, -Ptortuga.e2e.dex, -Ptortuga.e2e.postgresContainer,
// -Ptortuga.e2e.trackers for the fake tracker server the test starts, and -Ptortuga.e2e.docker for a docker CLI off
// the PATH).
val syncEndToEndTests = "com.edde746.tortuga.sync.e2e.*"
tasks.named<Test>("jvmTest") { filter { excludeTestsMatching(syncEndToEndTests) } }
tasks.register<Test>("syncEndToEndTest") {
    group = "verification"
    description = "Runs the sync client end to end against a running server (see server/scripts/sync-e2e.sh)."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    filter { includeTestsMatching(syncEndToEndTests) }
    for (name in listOf("server", "dex", "postgresContainer", "trackers", "docker")) {
        systemProperty("tortuga.e2e.$name", providers.gradleProperty("tortuga.e2e.$name").getOrElse(""))
    }
    systemProperty("tortuga.e2e.fakeTrackers", rootProject.file("tools/media/test_trackers.py").absolutePath)
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// -- watch together --
// Watch together end to end: whole apps and another app's participant in one room on a running Open Watch Party relay
// (watch/e2e/WatchTogetherEndToEndTest.kt). `jvmTest` leaves it out; `watchEndToEndTest` runs it on the relay whose
// base URL -Ptortuga.e2e.watchRelay gives.
val watchEndToEndTests = "com.edde746.tortuga.watch.e2e.*"
tasks.named<Test>("jvmTest") { filter { excludeTestsMatching(watchEndToEndTests) } }
tasks.register<Test>("watchEndToEndTest") {
    group = "verification"
    description = "Runs watch together end to end on a running Open Watch Party relay (-Ptortuga.e2e.watchRelay)."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    filter { includeTestsMatching(watchEndToEndTests) }
    systemProperty("tortuga.e2e.watchRelay", providers.gradleProperty("tortuga.e2e.watchRelay").getOrElse(""))
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

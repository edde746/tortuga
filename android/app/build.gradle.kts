// Phone, tablet and Android TV in one APK.
//
// src/debug holds the test-only launch extras (dev sessions, the test report) that the device test harness drives, and
// the test builds' manifest (profileable, without the link filters, which stay the release build's); src/release has
// the same entry point without them, so release builds contain none of that code. The `harness` build type is the
// release build (R8, the same rules) with src/debug's extras and manifest: what the harness plays on to check
// minified code, since a release build only runs the product, which needs a sign-in.
//
// The test builds (debug, harness) install as com.edde746.tortuga.dev, "Tortuga Dev" in the launcher, beside the
// release build's com.edde746.tortuga: a device keeps its owner's release app and its data through every harness run.
// The device test harness installs, stops, clears and uninstalls only that package.
//
// The Baseline Profile (src/main/generated/baselineProfiles: baseline-prof.txt, which ART compiles ahead of time, and
// startup-prof.txt, which R8 lays the primary DEX file out by) comes from android/baselineprofile/'s journeys on the
// harness build, whose dev session reaches Home without an account. It is merged into main, so release and harness both
// ship it as assets/dexopt/baseline.prof, and profileinstaller compiles it where the store doesn't (sideloads, Amazon's
// store).
// Regenerate it with :app-android:generateBaselineProfile on an Android TV (android/baselineprofile/).
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.sentry.android)
}

// Release signing: key.properties in this directory (storeFile, relative to this directory, storePassword, keyAlias,
// keyPassword) names the upload keystore. The release workflow writes both files from its
// ANDROID_* secrets (.github/workflows/build.yml); git ignores them. Without key.properties, release builds are
// debug-signed, so the minified build still installs for device tests.
val uploadKeystore = providers.fileContents(layout.projectDirectory.file("key.properties")).asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "com.edde746.tortuga"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.edde746.tortuga"
        minSdk = 26
        targetSdk = 36
        // version.xcconfig, through settings.gradle.kts.
        versionCode = project.extra["tortuga.build"] as Int
        versionName = project.extra["tortuga.version"] as String
        // BuildFlavor's name (settings.gradle.kts): the release workflow builds the Play bundle with
        // -Ptortuga.flavor=store and the GitHub release's APKs as sideloaded, the default.
        buildConfigField("String", "BUILD_FLAVOR", "\"${project.extra["tortuga.flavor"]}\"")
        // The launcher's label (the manifest's android:label); the test builds' own below.
        manifestPlaceholders["appLabel"] = "Tortuga"
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore.getProperty("storeFile"))
                storePassword = uploadKeystore.getProperty("storePassword")
                keyAlias = uploadKeystore.getProperty("keyAlias")
                keyPassword = uploadKeystore.getProperty("keyPassword")
            }
        }
    }
    // Per-ABI APKs for the GitHub release (arm64-v8a, armeabi-v7a, x86_64) and the Amazon Appstore: the release workflow
    // builds them with -Ptortuga.abiSplits=true, each with a versionCode of its own (androidComponents, below). Off by
    // default, so the harness installs one APK with every ABI, and so the Play bundle (bundleRelease, every ABI) builds:
    // AGP refuses to build a bundle while splits are on.
    splits {
        abi {
            isEnable = providers.gradleProperty("tortuga.abiSplits").map(String::toBoolean).getOrElse(false)
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Tortuga Dev"
        }
        release {
            // R8 with mpv-android's consumer rules (JNI names, the callbacks native code resolves by name).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (uploadKeystore != null) "upload" else "debug")
        }
        create("harness") {
            initWith(getByName("release"))
            // A test build: debug-signed even where the upload keystore is configured, with debug's package and label.
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Tortuga Dev"
            matchingFallbacks += "release"
        }
    }
    sourceSets.getByName("harness") {
        kotlin.directories += "src/debug/kotlin"
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    mergeIntoMain = true
    dexLayoutOptimization = true
    variants {
        create("harness") { from(project(":baselineprofile")) }
    }
}

// Sentry's Gradle plugin does one job here: it puts a UUID made from the release build's R8 mapping file (its
// pg_map_hash) into the APK (assets/sentry-debug-meta.properties), and the SDK names that UUID in every report
// (debug_meta), so a report from an obfuscated build says which mapping retraces it. It uploads nothing: the
// crash-report server (edde746/bugs) takes no ProGuard mapping, so the release workflow keeps each one as a run
// artifact named by its UUID and uploads the native libraries' symbols itself (tools/upload_symbols.sh). Debug and
// harness builds skip it. The SDK comes from the dependencies below, so the plugin's own installation, bytecode
// instrumentation, runtime optimizations, dependency report and telemetry are off.
sentry {
    ignoredBuildTypes.set(setOf("debug", "harness"))
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(false)
    uploadNativeSymbols.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    autoInstallation { enabled.set(false) }
    tracingInstrumentation { enabled.set(false) }
    runtimeOptimizations { enabled.set(false) }
}

dependencies {
    implementation(project(":ui-compose"))
    implementation(project(":mpv-android"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    // The media session's artwork through the UI's image loader (AndroidMediaControls).
    implementation(libs.coil.compose)
    // Native Google sign-in (AndroidSignInPlatform): the play-services-auth provider runs Credential Manager's sheet.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.base)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    // AppGraph's HttpClient (the bootstrap builds the graph and the account over it).
    implementation(libs.ktor.client.core)
    // Offline downloads: the shared engine's transfers run in a foreground dataSync worker (downloads/DownloadWorker).
    implementation(libs.androidx.work.runtime.ktx)
    // Compiles the Baseline Profile at install or on first launch where no store does it.
    implementation(libs.androidx.profileinstaller)
    // Crash reports (AndroidCrashReports): Sentry's Android SDK and its NDK part, which catches native crashes (libmpv's).
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    // The development session and the test report's model (:dev), for the test builds' launch extras (src/debug).
    debugImplementation(project(":dev"))
    "harnessImplementation"(project(":dev"))
    // Unit tests (src/test): Android TV's Watch Next rules (watchnext/WatchNextRows), on JUnit 4 as AGP runs them.
    testImplementation(kotlin("test-junit"))
}

// The modules the release build ships, a group:name per line, for which :shared's ThirdPartyNoticesCoverageTest finds a
// notice.
tasks.register("shippedModules") {
    val modules = configurations.named("releaseRuntimeClasspath").map { classpath ->
        classpath.incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ModuleComponentIdentifier)?.moduleIdentifier?.toString() }.sorted()
    }
    val output = layout.buildDirectory.file("shipped-modules.txt")
    inputs.property("modules", modules)
    outputs.file(output)
    doLast { output.get().asFile.writeText(modules.get().joinToString("\n")) }
}

// The licence files the release build's libraries carry for the code bundled inside them (Google's AARs:
// third_party_licenses.txt), a "group:name:version sha256" per line, which :shared's ThirdPartyLicenseFilesTest finds
// among the APK's licence texts.
tasks.register("shippedLicenseFiles") {
    val classpath = configurations.getByName("releaseRuntimeClasspath")
    val archives = listOf("aar", "jar").map { type ->
        classpath.incoming.artifactView {
            lenient(true)
            attributes { attribute(Attribute.of("artifactType", String::class.java), type) }
        }.artifacts
    }
    val output = layout.buildDirectory.file("shipped-license-files.txt")
    for (artifacts in archives) inputs.files(artifacts.artifactFiles).withPropertyName("archives${archives.indexOf(artifacts)}")
    outputs.file(output)
    doLast {
        val lines = archives.flatMap { it.artifacts }.mapNotNull { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
            ZipFile(artifact.file).use { zip ->
                zip.getEntry("third_party_licenses.txt")?.let { entry ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(entry).use { it.readBytes() })
                    "${module.displayName} ${digest.joinToString("") { "%02x".format(it) }}"
                }
            }
        }.distinct().sorted()
        output.get().asFile.writeText(lines.joinToString("\n"))
    }
}

// The licence texts of what the app ships, which About shows: the android groups of the repository's licenses/
// (licenses/apps.json), each folder of them in the APK's assets/licenses/, and beside them the folders mpv-android's
// AAR brings there in its own assets/licenses/ (one per component of mpv-build's build, which mpv-build collects from
// the sources it built, and mpv-android/ for the JNI glue). Two groups never hold the same folder: the copy fails on a
// duplicate. Every APK and bundle is checked to hold each text, the AAR's too, before assemble and bundle finish: AAPT
// leaves out assets it ignores (a name starting with a dot, a folder's starting with an underscore) without a word, an
// app asset at an AAR asset's path replaces it, and an AAR without licence texts fails the check.
val licenseGroups = (groovy.json.JsonSlurper()
    .parseText(providers.fileContents(rootProject.layout.projectDirectory.file("licenses/apps.json")).asText.get()) as Map<*, *>)
    .let { apps -> (apps["android"] as List<*>).map { rootProject.layout.projectDirectory.dir("licenses/$it") } }

/** Copies the folders of [groups] into [output]'s licenses/. */
abstract class LicenseTexts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val groups: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(groups)
            into(output.dir("licenses"))
        }
    }
}

/**
 * Fails unless each APK or bundle of [packages] holds in its assets/licenses/ every file of [groups] and every file
 * under the [aar]'s assets/licenses/, byte for byte, or when the AAR has no such file.
 */
abstract class CheckLicenseTexts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val groups: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val library = aar.singleFile
        // Each text's path under licenses/ → its CRC-32.
        val carried = ZipFile(library).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/licenses/") }
                .map { it.name.removePrefix("assets/licenses/") to it.crc }.toList()
        }
        if (carried.isEmpty()) throw GradleException("${library.name} has no licence texts in assets/licenses/ (mpv-build.lock.json's pin)")
        val texts = carried + groups.files.flatMap { group ->
            group.walk().filter { it.isFile }.map { it.relativeTo(group).invariantSeparatorsPath to CRC32().apply { update(it.readBytes()) }.value }.toList()
        }
        val archives = packages.asFileTree.files.filter { it.extension == "apk" || it.extension == "aab" }
        if (archives.isEmpty()) throw GradleException("Nothing to check: packages $archives")
        for (archive in archives) {
            val prefix = if (archive.extension == "aab") "base/assets/licenses/" else "assets/licenses/"
            val wrong = ZipFile(archive).use { zip ->
                texts.filter { (path, crc) -> zip.getEntry(prefix + path)?.crc != crc }.map { prefix + it.first }
            }
            if (wrong.isNotEmpty()) throw GradleException("${archive.name} lacks these licence texts (or holds other bytes): ${wrong.joinToString()}")
            logger.lifecycle("${archive.name} holds all ${texts.size} licence texts, ${carried.size} of them ${library.name}'s")
        }
    }
}

val mpvAar = configurations.dependencyScope("mpvAar")
val mpvAarFile = configurations.resolvable("mpvAarFile") { extendsFrom(mpvAar.get()) }
dependencies { add(mpvAar.name, project.extra["mpv.aar"] as String) }

// The digit a per-ABI APK's versionCode ends in (onVariants, below), by which a store's APK slot names its ABI.
val abiDigits = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)

androidComponents {
    onVariants { variant ->
        // A per-ABI APK's versionCode is CURRENT_PROJECT_VERSION * 10 + its ABI's digit: the Amazon Appstore takes a
        // listing's APKs only with distinct versionCodes (its "Device Filtering and Compatibility on Fire OS",
        // Requirements for multiple APKs), and arm64-v8a's outranks armeabi-v7a's. The bundle has no ABI outputs and
        // keeps CURRENT_PROJECT_VERSION.
        for (output in variant.outputs) {
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier ?: continue
            output.versionCode.set(project.extra["tortuga.build"] as Int * 10 + abiDigits.getValue(abi))
        }
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val texts = tasks.register<LicenseTexts>("copy${name}LicenseTexts") { groups.from(licenseGroups) }
        variant.sources.assets?.addGeneratedSourceDirectory(texts, LicenseTexts::output)
        val packages = mapOf("Apk" to variant.artifacts.get(SingleArtifact.APK), "Bundle" to variant.artifacts.get(SingleArtifact.BUNDLE))
        for ((kind, artifact) in packages) {
            val check = tasks.register<CheckLicenseTexts>("check$name${kind}LicenseTexts") {
                groups.from(licenseGroups)
                aar.from(mpvAarFile)
                this.packages.from(artifact)
            }
            val packaging = if (kind == "Apk") "assemble$name" else "bundle$name"
            tasks.matching { it.name == packaging }.configureEach { dependsOn(check) }
        }
    }
}

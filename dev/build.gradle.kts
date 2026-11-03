import co.touchlab.skie.configuration.SuppressSkieWarning
import co.touchlab.skie.plugin.configuration.SkieExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

// Test-only code no release build links: the development session (DevSession.kt: `--dev` on desktop and Apple, the
// launch extras of Android's debug and harness builds), the test report's UiState→JSON model (jvmAndAndroidMain:
// Android's debug and harness builds and the desktop harness launcher write the same report) and the addon fixture
// recorder (jvmRecord, `:dev:recordFixtures`). Android links it from its debug and harness build types, desktop from
// its harness source set, Apple from its Debug configuration; nothing links the recorder.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library) apply false
}

// SKIE, as :shared applies it for Release's TortugaKit (only on macOS, where Apple frameworks build), for Debug's
// below.
if (System.getProperty("os.name").startsWith("Mac")) {
    apply(plugin = libs.plugins.skie.get().pluginId)
    extensions.configure<SkieExtension> {
        // As in :shared: no bundled Swift, so the compile's cache key holds no absolute path.
        swiftBundling { enabled.set(false) }
        features {
            // As in :shared: Ktor's HttpStatusCode.description reaches Swift as description_, which Swift never reads.
            group("io.ktor.http.HttpStatusCode") { SuppressSkieWarning.NameCollision(true) }
        }
    }
}

// Hosts without an Android SDK build with -Ptortuga.android=false (settings.gradle.kts), as :shared does.
val androidEnabled = extra["tortuga.android"] as Boolean
if (androidEnabled) apply(plugin = libs.plugins.android.kmp.library.get().pluginId)

kotlin {
    jvmToolchain(27)

    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    if (androidEnabled) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "com.edde746.tortuga.dev"
            compileSdk = 37
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // The fixture recorder's own compilation (src/jvmRecord), run by recordFixtures below.
        compilations.create("record") {
            defaultSourceSet.dependencies {
                implementation(project(":shared"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                runtimeOnly(libs.slf4j.nop)
            }
        }
    }

    // The Apple Debug configuration's TortugaKit (the one umbrella framework, since each KMP framework would embed a
    // Kotlin runtime of its own): :shared exported with the development session, built by the Xcode script phase
    // (`:dev:embedAndSignAppleFrameworkForXcode`). Release links :shared's TortugaKit, which has no development
    // session.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64(), tvosArm64(), tvosSimulatorArm64()).forEach { target ->
        target.binaries.framework(listOf(NativeBuildType.DEBUG)) {
            baseName = "TortugaKit"
            isStatic = true
            // Explicit, or Kotlin/Native warns on every link that it cannot infer one.
            binaryOption("bundleId", "com.edde746.tortuga.TortugaKit")
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        getByName("jvmAndAndroidMain").dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // DevStartTest's graph runs on Dispatchers.Main (AppBootstrap.local's MainScope), which the test sets.
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// `./gradlew :dev:recordFixtures` records shared's addon fixture corpus (shared/src/commonTest/fixtures/live) from the
// live Cinemeta and OpenSubtitles addons; `--args=<dir>` writes it into another folder.
tasks.register<JavaExec>("recordFixtures") {
    description = "Records shared's addon fixture corpus from the live Cinemeta and OpenSubtitles addons."
    val record = kotlin.jvm().compilations.getByName("record")
    classpath(record.output.allOutputs, record.runtimeDependencyFiles)
    mainClass.set("com.edde746.tortuga.fixtures.RecordFixturesKt")
    // The app's version (version.xcconfig, through settings.gradle.kts): the User-Agent's, and index.json's.
    systemProperty("tortuga.version", project.extra["tortuga.version"] as String)
    // Relative paths (the default folder, --args) are the repository root's.
    workingDir = rootDir
}

// Nothing else builds the recorder, so it compiles with dev's tests: an API change that breaks it fails there.
tasks.named("jvmTest") { dependsOn(kotlin.jvm().compilations.getByName("record").compileTaskProvider) }

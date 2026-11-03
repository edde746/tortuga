// The desktop app: Windows, and Linux as an Xwayland client inside a Wayland session.
//
//   ./gradlew :app-desktop:run --args="--mpv-log mpv.log"
//   ./gradlew :app-desktop:writeHarnessArgs && "$(cat desktop/app/build/run/java.txt)" @desktop/app/build/run/harness.args \
//     com.edde746.tortuga.desktop.harness.TestMainKt --dev --url http://<host>:8765/sample.mkv
//
// `run` starts the product (the release jar's main class) on a JDK 25 toolchain; on Linux that is JetBrains Runtime 25
// (vendor JETBRAINS, provisioned by the foojay resolver): on another runtime AWT may miss the window manager's resizes.
// The test-only options (a dev session, the test report) are the harness launcher's, in the harness source set below,
// started without Gradle through `writeHarnessArgs`' argfile (the desktop test harness does that).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(25) }

val isLinux = System.getProperty("os.name").startsWith("Linux")
val mainClassName = "com.edde746.tortuga.desktop.MainKt"
val nativeAccess = "--enable-native-access=ALL-UNNAMED"
// The app's JVM flags: FFM, and the AWT internals that reach each window's native window (its HWND, or its X window
// and AWT's X connection) for its dark title bar and frame (mpv-jvm's DesktopWindow.useDarkChrome).
val appJvmFlags = listOf(
    nativeAccess,
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.${if (isLinux) "X11" else "windows"}=ALL-UNNAMED",
)
// version.xcconfig, through settings.gradle.kts.
val appVersion = project.extra["tortuga.version"] as String

// mpv-jvm's extracted libmpv artifact, passed to the app as -Dtortuga.mpv.dir, and packaged with the app (below).
val mpvNatives = configurations.dependencyScope("mpvNatives")
val mpvNativesPath = configurations.resolvable("mpvNativesPath") {
    extendsFrom(mpvNatives.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("tortuga-mpv-natives")) }
}

dependencies {
    implementation(project(":ui-compose"))
    implementation(project(":mpv-jvm"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // AppGraph's HttpClient (the bootstrap builds the graph and the account over it).
    implementation(libs.ktor.client.core)
    // Crash reports (Main.kt starts Sentry's Java SDK through ui-compose's SentryReports).
    implementation(libs.sentry.java)
    // MPRIS (linux/LinuxMediaControls.kt): compiled everywhere, shipped only in Linux builds.
    compileOnly(libs.dbus.java.core)
    if (isLinux) {
        implementation(libs.dbus.java.core)
        runtimeOnly(libs.dbus.java.transport.unixsocket)
    }
    add(mpvNatives.name, project(":mpv-jvm"))
    testImplementation(kotlin("test"))
    // The portal's answers in FileChooserPortalTest are dbus-java types, on every build host.
    testImplementation(libs.dbus.java.core)
}

// The modules the app ships, a group:name per line, for which :shared's ThirdPartyNoticesCoverageTest finds a notice.
tasks.register("shippedModules") {
    val modules = configurations.runtimeClasspath.map { classpath ->
        classpath.incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ModuleComponentIdentifier)?.moduleIdentifier?.toString() }.sorted()
    }
    val output = layout.buildDirectory.file("shipped-modules.txt")
    inputs.property("modules", modules)
    outputs.file(output)
    doLast { output.get().asFile.writeText(modules.get().joinToString("\n")) }
}

val runtimeJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    if (isLinux) vendor.set(JvmVendorSpec.JETBRAINS)
}

// The licence texts each desktop app ships: its groups of the repository's licenses/ (licenses/apps.json), each with
// one folder per component.
val licenseApps = groovy.json.JsonSlurper()
    .parseText(providers.fileContents(rootProject.layout.projectDirectory.file("licenses/apps.json")).asText.get()) as Map<*, *>
val licenseGroups = listOf("linux", "windows").associateWith { os -> (licenseApps[os] as List<*>).map { it as String } }

// The app image's resources (app/resources; Compose takes common/ on every OS and linux/ or windows/ on that one): the
// packaged app's libmpv, from mpv-jvm's artifact: the library alone (libmpv-2.dll, or libmpv.so.2 as one file rather
// than its symlink chain), in mpv/, where MpvNatives looks when no directory is configured, and beside it in
// mpv/licenses/ the artifact's licenses/ as it is: the licence texts of every component linked into libmpv, one folder
// per component, which mpv-build collects from the sources it built; and in licenses/ the licence texts of what else the
// app ships (licenses/README.md). About shows both: the groups both desktops take in common/, the others in their OS's.
// Two groups never hold the same folder: the copy fails on a duplicate. An artifact without licence texts fails it too.
val packagedResources = tasks.register<Sync>("packagedResources") {
    from(mpvNativesPath) {
        include("**/libmpv-2.dll", "**/libmpv.so.2", "licenses/**")
        into("common/mpv")
    }
    for (group in licenseGroups.values.flatten().distinct()) {
        val os = licenseGroups.filterValues { group in it }.keys.singleOrNull() ?: "common"
        from(rootProject.file("licenses/$group")) { into("$os/licenses") }
    }
    // The player's bundled shaders (one copy in the repository, which the Android app ships as assets).
    from(rootProject.file("android/mpv/src/main/assets/shaders")) { into("common/shaders") }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("app-resources"))
    val natives = files(mpvNativesPath)
    doFirst {
        val licenses = natives.singleFile.resolve("licenses")
        if (licenses.walk().none { it.isFile && it.length() > 0 }) {
            throw GradleException("libmpv's artifact has no licence texts in $licenses (mpv-build.lock.json's pin)")
        }
    }
}

// `createDistributable` builds the app image the release packages wrap (desktop/app/packaging: the Flatpak and the
// MSIX): the launcher, the app's jars, the resources above and a runtime linked from the JDK above (JetBrains Runtime
// on Linux).
compose.desktop.application {
    mainClass = mainClassName
    javaHome = runtimeJdk.get().metadata.installationPath.asFile.absolutePath
    jvmArgs += appJvmFlags
    nativeDistributions {
        packageName = "Tortuga"
        packageVersion = appVersion
        vendor = "edde746"
        description = "A video player that works with Stremio addons"
        // Beyond Compose's defaults: what `./gradlew :app-desktop:suggestRuntimeModules` lists (java.sql for the
        // SQLite driver, jdk.unsupported, java.management, java.instrument), plus jdk.accessibility for screen readers
        // on Windows. Run it again when dependencies change: a missing module fails only at run time.
        modules("java.instrument", "java.management", "java.sql", "jdk.unsupported", "jdk.accessibility")
        appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
        // Icons are generated by tools/brand/generate.sh.
        windows { iconFile.set(project.file("icons/tortuga.ico")) }
        linux { iconFile.set(project.file("icons/tortuga.png")) }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(packagedResources) }

// About's version (Main.kt reads it from the jar's manifest), and the BuildFlavor's name from -Ptortuga.flavor
// (settings.gradle.kts; Bootstrap.kt reads it): the release workflow builds the MSIX as store, the Flatpaks as
// sideloaded.
tasks.jar {
    manifest { attributes("Implementation-Version" to appVersion, "Tortuga-Flavor" to project.extra["tortuga.flavor"] as String) }
}

// The window icon for unpackaged runs (Main.kt), read from the classpath.
tasks.processResources { from("icons/tortuga.png") { into("icons") } }

abstract class MpvDirArgument : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val natives: ConfigurableFileCollection

    override fun asArguments() = listOf("-Dtortuga.mpv.dir=${natives.singleFile.absolutePath}")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgumentProviders += objects.newInstance<MpvDirArgument>().apply { natives.from(mpvNativesPath) }
}

// -- harness: the harness launcher and test-only programs --
// The harness launcher (harness/TestMain.kt: the app with its test-only options, the test report and the dev session,
// through Main.kt's runApp) and the programs the desktop test harness runs next to the app against a
// `--data-dir` device, through the app's own bootstrap: the device-link approver (harness/ApproveLink.kt) and the
// Linux input program (harness/RobotInput.kt). Their own source set, the only one with :dev,
// keeps them out of the app's jar and runtime classpath, which createDistributable packages. The tests compile against
// it too (the report journal, the test options).
val harnessSources = sourceSets.create("harness")
kotlin.target.compilations.named("harness") { associateWith(kotlin.target.compilations.getByName("main")) }
kotlin.target.compilations.named("test") { associateWith(kotlin.target.compilations.getByName("harness")) }
dependencies { "harnessImplementation"(project(":dev")) }

/**
 * Writes, in build/run/:
 * - harness.args, a java @argfile (`java [jvm flags] @harness.args <main class> [arguments]`): the app's JVM flags, its
 *   libmpv directory, its resources folder (prepareAppResources', as the run task's: the licence texts About shows)
 *   and a classpath of the app's jar (its manifest names the version and flavor), the harness and their dependencies;
 * - java.txt: the path of the runtime JDK's java executable (JetBrains Runtime on Linux);
 * - harness.classpath: the harness's entries the app image lacks (its classes, :dev), one per line, which
 *   a test run adds to a copy of the image's Tortuga.cfg to start the harness
 *   launcher with the image's launcher, runtime and jars.
 */
tasks.register("writeHarnessArgs") {
    val jar = tasks.jar.flatMap { it.archiveFile }
    val classpath = harnessSources.runtimeClasspath
    val mainOutput = sourceSets.main.get().output
    val appRuntime = configurations.runtimeClasspath
    val natives = mpvNativesPath
    val appResources = tasks.named<Sync>("prepareAppResources")
    dependsOn(appResources)
    val java = runtimeJdk.map { it.executablePath.asFile.absolutePath }
    val argsOut = layout.buildDirectory.file("run/harness.args")
    val javaOut = layout.buildDirectory.file("run/java.txt")
    val imageOut = layout.buildDirectory.file("run/harness.classpath")
    inputs.file(jar)
    inputs.files(classpath, appRuntime, natives)
    inputs.property("java", java)
    inputs.property("jvmFlags", appJvmFlags)
    outputs.files(argsOut, javaOut, imageOut)
    doLast {
        // Forward slashes: backslashes are escapes inside quoted @argfile tokens.
        fun path(f: File) = f.absolutePath.replace('\\', '/')
        val app = mainOutput.files
        // Without the output directories a source set doesn't make (classes/java/harness: no Java sources).
        val harness = classpath.files.filter { it !in app && it.exists() }
        val cp = (listOf(jar.get().asFile) + harness).joinToString(File.pathSeparator, transform = ::path)
        val flags = appJvmFlags + listOf(
            "-Dtortuga.mpv.dir=\"${path(natives.get().singleFile)}\"",
            "-Dcompose.application.resources.dir=\"${path(appResources.get().destinationDir)}\"",
            "-cp \"$cp\"",
        )
        argsOut.get().asFile.writeText(flags.joinToString("\n", postfix = "\n"))
        javaOut.get().asFile.writeText(java.get() + "\n")
        val image = appRuntime.get().files
        imageOut.get().asFile.writeText(harness.filter { it !in image }.joinToString("\n", postfix = "\n") { it.absolutePath })
    }
}

// -- secrets: tests --
// The SecretStore contract (shared commonTest, secrets/) against this OS's store: Windows Credential Manager, which
// needs an interactive logon session, or libsecret, which needs a Secret Service on the session bus. Neither is a
// given on CI or over SSH, so the tests have their own source set and task: `secretStoreTest`, run in a desktop session.
val secretStoreTestSources = sourceSets.create("secretStoreTest")
kotlin.target.compilations.named("secretStoreTest") { associateWith(kotlin.target.compilations.getByName("main")) }
// Compiled here too, so that directory holds only the contract suite and uses only public API.
kotlin.sourceSets.named("secretStoreTest") { kotlin.srcDir("../../shared/src/commonTest/kotlin/com/edde746/tortuga/secrets") }
dependencies {
    "secretStoreTestImplementation"(kotlin("test-junit"))
    "secretStoreTestImplementation"(libs.kotlinx.coroutines.test)
}
tasks.register<Test>("secretStoreTest") {
    description = "Runs the SecretStore contract against Windows Credential Manager or libsecret."
    group = "verification"
    testClassesDirs = secretStoreTestSources.output.classesDirs
    classpath = secretStoreTestSources.runtimeClasspath
    jvmArgs(nativeAccess)
}


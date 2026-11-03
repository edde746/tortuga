plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("mpvArtifacts") {
            id = "tortuga.mpv-artifacts"
            implementationClass = "tortuga.buildlogic.MpvArtifactsPlugin"
        }
        register("strings") {
            id = "tortuga.strings"
            implementationClass = "tortuga.buildlogic.strings.StringsPlugin"
        }
    }
}

// What shared's strings runtime (LocaleTable.kt, PluralRule.kt) and the generated code compile against there:
// GeneratedCodeTest compiles and runs them on this classpath alone. A library they import goes here.
val stringsLibraries = configurations.dependencyScope("stringsLibraries")
val stringsClasspath = configurations.resolvable("stringsClasspath") {
    extendsFrom(stringsLibraries.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    testImplementation(kotlin("test"))
    // The codegen tests compile what the generator emits and call it.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$embeddedKotlinVersion")
    // The standard library at the test compiler's version; Gradle picks it over the older one the libraries ask for.
    "stringsLibraries"(kotlin("stdlib", embeddedKotlinVersion))
    "stringsLibraries"(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    // Generated code calls the handwritten runtime in shared; the codegen tests compile against the real one.
    val runtimeDir = layout.projectDirectory.dir("../../shared/src/commonMain/kotlin/com/edde746/tortuga/strings")
    val runtime = listOf("LocaleTable.kt", "PluralRule.kt").map { runtimeDir.file(it).asFile }
    inputs.files(runtime).withPropertyName("stringsRuntime").withPathSensitivity(PathSensitivity.NAME_ONLY)
    systemProperty("tortuga.stringsRuntime", runtime.joinToString(File.pathSeparator))
    val classpath = files(stringsClasspath)
    inputs.files(classpath).withPropertyName("stringsClasspath").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dtortuga.stringsClasspath=${classpath.asPath}") }
    // TranslationCoverageTest reads the app's string sources.
    val i18n = layout.projectDirectory.dir("../../shared/i18n")
    inputs.dir(i18n).withPropertyName("i18n").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("tortuga.i18n", i18n.asFile.path)
}

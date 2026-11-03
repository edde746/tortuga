import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ktor + Postgres sync server, hosted and self-hostable.
//
//   ./gradlew :server:test          Ktor test application against Postgres in Testcontainers (needs Docker)
//   ./gradlew :server:installDist   server/build/install/tortuga-server, what the Docker image runs
//   ./gradlew :server:smoke --args="…"   end-to-end client against a running server (server/scripts/smoke.sh)
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// JDK 27 compiles, tests and runs it (the image's runtime); Kotlin 2.4 emits Java 26 bytecode at most (27 comes with
// Kotlin 2.5.0), and Java's target must match Kotlin's.
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_26) }
}
java { targetCompatibility = JavaVersion.toVersion(26) }

application {
    applicationName = "tortuga-server"
    mainClass.set("com.edde746.tortuga.server.MainKt")
}

// A small client that drives a running server through sign-in, device link, sync and deletion with records as the
// apps write them (scripts/smoke.sh runs it against the compose stack).
val smoke: SourceSet = sourceSets.create("smoke")

dependencies {
    // Wire DTOs (sync/api).
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging) {
        // Jansi colours Ktor's default call lines, which the server neither writes nor colours (Application.kt), and
        // its console loads a native library.
        exclude(group = "org.fusesource.jansi")
    }
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.body.limit)
    // The account change notice streams (server-sent events).
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Outbound: provider discovery and JWKS, Apple's token and revoke endpoints.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.logback.classic)
    // Push bodies read as a stream of tokens, without a tree of their JSON (SyncRoutes).
    implementation(libs.jackson.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)

    "smokeImplementation"(project(":shared"))
    "smokeImplementation"(libs.kotlinx.serialization.json)
    "smokeImplementation"(libs.kotlinx.coroutines.core)
    "smokeImplementation"(libs.ktor.client.core)
    "smokeImplementation"(libs.ktor.client.okhttp)
    "smokeImplementation"(libs.ktor.client.content.negotiation)
    "smokeImplementation"(libs.ktor.serialization.kotlinx.json)
    "smokeRuntimeOnly"(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // The Postgres image the tests run against (Testcontainers); the compose file uses the same major version.
    systemProperty("tortuga.test.postgresImage", providers.gradleProperty("tortuga.test.postgresImage").getOrElse("postgres:18-alpine"))
}

// The commit the distribution is a build of, in its COMMIT file: -Ptortuga.commit=<git rev-parse HEAD>, "unknown"
// without it (build hosts get no .git). The hosted instance's deploy ships a distribution only when its COMMIT is HEAD.
val commitStamp = tasks.register("commitStamp") {
    val commit = providers.gradleProperty("tortuga.commit").orElse("unknown")
    val stamp = layout.buildDirectory.file("commit-stamp/COMMIT")
    inputs.property("commit", commit)
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText(commit.get() + "\n") }
}
distributions.named("main") { contents { from(commitStamp) } }

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Runs the end-to-end smoke client against a running server (see server/scripts/smoke.sh)."
    classpath = smoke.runtimeClasspath
    mainClass.set("com.edde746.tortuga.server.smoke.SmokeKt")
}

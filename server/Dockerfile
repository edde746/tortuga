# syntax=docker/dockerfile:1.7
# The Tortuga sync server image. The build context is the repository root, because the server
# compiles the shared module's wire types:
#
#   docker build -f server/Dockerfile -t tortuga-server .
#
# server/Dockerfile.dockerignore keeps the Apple project, the apps and build outputs out of the context. A host too
# small to run Gradle replaces the `dist` stage with a distribution built elsewhere (`:server:installDist`), and the
# build stage is then skipped: `docker build --build-context dist=<installDist dir> ...`.

FROM eclipse-temurin:27-jdk-resolute AS build
WORKDIR /src
COPY . .
# Hosts without an Android SDK build with tortuga.android=false (settings.gradle.kts); Kotlin compiles in-process so
# no daemon outlives the step. The cache mount keeps Gradle's downloads between image builds; locked, so a second build
# at the same time waits for it instead of two Gradles sharing one cache (which fails one of them).
RUN --mount=type=cache,target=/root/.gradle,id=tortuga-gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain \
        -Ptortuga.android=false \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Dorg.gradle.jvmargs=-Xmx3g \
        :server:installDist

# The installed server: build/install/tortuga-server's bin/ and lib/.
FROM scratch AS dist
COPY --from=build /src/server/build/install/tortuga-server /

FROM eclipse-temurin:27-jre-resolute
# curl for the health check below: Temurin's images carry none since JDK 27.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 tortuga \
    && useradd --system --uid 10001 --gid tortuga --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin tortuga
COPY --from=dist / /opt/tortuga-server
USER 10001:10001
# The JVM's whole footprint fits a container of 384 MB or more with headroom (server/README.md, Memory).
ENV TORTUGA_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# curl, not a second JVM: a JVM per check costs about half a CPU-second and 45 MB in the server's cgroup.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --start-interval=2s --retries=3 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${TORTUGA_PORT}/healthz" || exit 1
ENTRYPOINT ["/opt/tortuga-server/bin/tortuga-server"]

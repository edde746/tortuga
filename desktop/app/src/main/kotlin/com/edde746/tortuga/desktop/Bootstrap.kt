package com.edde746.tortuga.desktop

import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.app.SystemInputs
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.linux.systemLanguages
import com.edde746.tortuga.desktop.secrets.desktopSecretStore
import com.edde746.tortuga.desktop.windows.WindowsLocale
import com.edde746.tortuga.downloads.installDownloads
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile

/**
 * The desktop's shared core ([AppBootstrap]): the product on the device's database and the OS secret store. Files the
 * app can download again (the trackers' datasets) go to `cache` next to the database. It has downloads
 * ([DesktopDownloads]), each core in a folder of its own, so a database never meets another's files. The harness
 * launcher's dev session (desktop/app/src/harness) builds its core from the same parts. Every process reports the
 * system's settings first ([reportSystemInputs]).
 */
internal object Bootstrap {
    /**
     * Reports the system's settings the shared code follows ([SystemInputs]), before anything reads them: the
     * interface's languages until the profile chooses another (Windows' preferred languages, or Linux's LANGUAGE list,
     * else the JVM's locale); the region streams' country lists are checked against (Windows' country or region, else
     * the format locale's, LC_CTYPE's or LANG's, not the messages one's); captions and audio descriptions off, which
     * neither system has a setting for.
     */
    fun reportSystemInputs() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        SystemInputs.report(
            languages = if (windows) WindowsLocale.preferredLanguages() else systemLanguages(System.getenv(), Locale.getDefault()),
            region = if (windows) WindowsLocale.region() else Locale.getDefault(Locale.Category.FORMAT).country.takeIf { it.isNotEmpty() },
            captions = false,
            audioDescriptions = false,
        )
    }

    /**
     * @param startup the product's start, which the splash shows.
     * @param dataDir a test launch's device of its own on this OS user (`--data-dir`), with its database
     *   in this directory and its secrets under a name derived from the directory; null for the device itself. Its
     *   downloads go to `downloads` there; the device's to the Videos folder.
     */
    fun product(version: String, startup: Startup, dataDir: Path? = null): AppBootstrap {
        val secrets = dataDir?.let { PrefixedSecretStore(desktopSecretStore(), dataDirPrefix(it)) } ?: desktopSecretStore()
        val directory = dataDir?.toAbsolutePath() ?: DatabaseFactory.defaultDirectory()
        val database = DatabaseFactory(directory.resolve("tortuga.db"))
        return AppBootstrap.product(database, secrets, flavor, version, thisDevice(), cacheDirectory(directory), startup)
            .withDownloads(if (dataDir != null) directory.resolve("downloads") else DesktopDownloads.videosFolder())
    }

    /** Downloads on this core's graph, by default in [root]. */
    fun AppBootstrap.withDownloads(root: Path): AppBootstrap = apply { graph.installDownloads(DesktopDownloads(graph.http, root)) }

    /**
     * Where this build is distributed: the app jar manifest's Tortuga-Flavor (-Ptortuga.flavor,
     * desktop/app/build.gradle.kts), or SIDELOADED when the app runs from class directories (the tests).
     */
    val flavor: BuildFlavor by lazy {
        val location = File(Bootstrap::class.java.protectionDomain.codeSource.location.toURI())
        val name = if (location.isFile) JarFile(location).use { it.manifest?.mainAttributes?.getValue("Tortuga-Flavor") } else null
        name?.let(BuildFlavor::valueOf) ?: BuildFlavor.SIDELOADED
    }

    /** The trackers' datasets of the core whose database is in [dataDirectory]. */
    fun cacheDirectory(dataDirectory: Path): String = dataDirectory.resolve("cache").toString()

    /** What the server lists this device as: the computer's name, and Windows or Linux. */
    fun thisDevice(): DeviceDescriptor {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val name = (if (windows) System.getenv("COMPUTERNAME") else System.getenv("HOSTNAME"))?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.name")
        return DeviceDescriptor(name.take(64), if (windows) DevicePlatform.WINDOWS else DevicePlatform.LINUX)
    }

    /** `data-dir-<12 hex>/`: the secrets' name for a `--data-dir` device, the same on every run with that directory. */
    private fun dataDirPrefix(dir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(dir.toAbsolutePath().normalize().toString().toByteArray())
        return "data-dir-" + digest.take(6).joinToString("") { "%02x".format(it) } + "/"
    }
}

/** [inner] with every key under [prefix]: a `--data-dir` device's secrets, apart from the device's own. */
private class PrefixedSecretStore(private val inner: SecretStore, private val prefix: String) : SecretStore {
    override suspend fun get(key: String): ByteArray? = inner.get(prefix + key)

    override suspend fun put(key: String, value: ByteArray) = inner.put(prefix + key, value)

    override suspend fun delete(key: String) = inner.delete(prefix + key)
}

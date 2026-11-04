package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.configFromEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Secrets come as a value or as a file (`NAME_FILE`, Docker secrets): one form, never both. A setting the server can't
 * use stops it with one line that names it. A development stack's tracker sign-in relay signs in at the fake tracker
 * server.
 */
class ServerConfigTest {
    @Test
    fun aSecretSetBothAsAValueAndAsAFileRefusesToStart() {
        val file = Files.createTempFile("tortuga-secret", ".txt")
        try {
            Files.writeString(file, "from-the-file\n")
            val gitHubId = "TORTUGA_GITHUB_CLIENT_ID" to "Ov23liFixture"
            val fromFile = configFromEnvironment(gitHubId, "TORTUGA_GITHUB_CLIENT_SECRET_FILE" to file.toString())
            assertEquals("from-the-file", fromFile.github?.clientSecret)

            val secrets = listOf(
                "TORTUGA_GITHUB_CLIENT_SECRET",
                "TORTUGA_DATABASE_PASSWORD",
                "TORTUGA_DATABASE_URL",
                "TORTUGA_APPLE_PRIVATE_KEY",
            )
            for (name in secrets) {
                val both = listOf(name to "a-value", "${name}_FILE" to file.toString()) +
                    listOfNotNull(gitHubId.takeIf { name == "TORTUGA_GITHUB_CLIENT_SECRET" })
                val error = assertFailsWith<IllegalArgumentException>(name) { configFromEnvironment(*both.toTypedArray()) }
                assertEquals("$name and ${name}_FILE are both set: keep one", error.message)
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun aSecretFileLongerThanAnySecretFailsNamingIt() {
        val file = Files.createTempFile("tortuga-secret", ".txt")
        try {
            // A path that names something else (a disk image, a log): the server reads no further than any secret goes.
            Files.write(file, ByteArray(16 * 1024 + 1) { 'a'.code.toByte() })
            val error = assertFailsWith<IllegalArgumentException> {
                configFromEnvironment("TORTUGA_DATABASE_PASSWORD_FILE" to file.toString())
            }
            assertEquals("TORTUGA_DATABASE_PASSWORD_FILE: $file holds more than 16384 bytes, more than any secret: check the path", error.message)

            Files.write(file, ByteArray(16 * 1024) { 'a'.code.toByte() })
            assertEquals(16 * 1024, configFromEnvironment("TORTUGA_DATABASE_PASSWORD_FILE" to file.toString()).database.password.length)
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun aSettingTheServerCantUseFailsNamingIt() {
        val missing = Files.createTempDirectory("tortuga-config").resolve("missing")
        try {
            // main logs an IllegalArgumentException's message as its one line, "Not starting: …"; anything else escapes
            // as a stack trace.
            val unreadable = assertFailsWith<IllegalArgumentException> {
                configFromEnvironment("TORTUGA_DATABASE_PASSWORD_FILE" to missing.toString())
            }
            assertTrue(unreadable.message!!.startsWith("TORTUGA_DATABASE_PASSWORD_FILE: can't read $missing"), unreadable.message)

            val disk = assertFailsWith<IllegalArgumentException> {
                configFromEnvironment("TORTUGA_DATABASE_DISK_PATH" to missing.toString())
            }
            assertEquals("TORTUGA_DATABASE_DISK_PATH: $missing is not a directory", disk.message)

            val apple = assertFailsWith<IllegalArgumentException> {
                configFromEnvironment(
                    "TORTUGA_APPLE_TEAM_ID" to "TEAM000001",
                    "TORTUGA_APPLE_KEY_ID" to "KEY0000001",
                    "TORTUGA_APPLE_PRIVATE_KEY" to "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----",
                )
            }
            assertTrue(apple.message!!.startsWith("TORTUGA_APPLE_PRIVATE_KEY is not a Sign in with Apple key"), apple.message)

            // The review password itself, or a hash cut short, instead of its SHA-256.
            for (value in listOf("Kq7mR2vX9pLw4nTb8sHd3cYf", "ab".repeat(31))) {
                val review = assertFailsWith<IllegalArgumentException> {
                    configFromEnvironment("TORTUGA_REVIEW_PASSWORD_SHA256" to value)
                }
                assertTrue(review.message!!.startsWith("TORTUGA_REVIEW_PASSWORD_SHA256 must be"), review.message)
            }
        } finally {
            Files.delete(missing.parent)
        }
    }

    @Test
    fun aProviderOrPublicUrlIsHttpsUnlessItIsLocal() {
        // GitHub and an OpenID Connect provider on, so each of their addresses is read.
        val enabled = mapOf(
            "TORTUGA_GITHUB_CLIENT_ID" to "Ov23liFixture",
            "TORTUGA_GITHUB_CLIENT_SECRET" to "secret",
            "TORTUGA_OIDC_ISSUER" to "https://idp.example.com",
            "TORTUGA_OIDC_CLIENT_IDS" to "tortuga",
        )
        fun config(name: String, value: String) = configFromEnvironment(*(enabled + (name to value)).toList().toTypedArray())
        val names = listOf(
            "TORTUGA_OIDC_ISSUER", "TORTUGA_OIDC_JWKS_URL", "TORTUGA_GITHUB_URL", "TORTUGA_GITHUB_API_URL",
            "TORTUGA_GITHUB_TOKEN_URL", "TORTUGA_PUBLIC_URL",
        )
        for (name in names) {
            // Plain http to a host on the internet: anyone on the path could answer for the provider.
            for (value in listOf("http://idp.example.com/realms/home", "http://203.0.113.9", "ftp://idp.example.com", "idp.example.com")) {
                val error = assertFailsWith<IllegalArgumentException>("$name=$value") { config(name, value) }
                assertEquals("$name must be an https URL (http only to this machine or a private network), not $value", error.message)
            }
            // This machine, a container beside the server, a private network: a development stack or a home server.
            for (value in listOf("https://idp.example.com", "http://127.0.0.1:5556/dex", "http://dex:5556/dex", "http://localhost:8080", "http://192.168.1.20", "http://[::1]:5556")) {
                config(name, value)
            }
        }
    }

    @Test
    fun aDevelopmentStackSignsTrackersInAtTheFakeTrackerServer() {
        val relay = arrayOf(
            "TORTUGA_PUBLIC_URL" to "http://127.0.0.1:18180",
            "TORTUGA_MAL_CLIENT_ID" to "tortuga-dev-mal",
            "TORTUGA_ANILIST_CLIENT_ID" to "tortuga-dev-anilist",
            "TORTUGA_ANILIST_CLIENT_SECRET" to "tortuga-dev-anilist-secret",
        )
        val server = "TORTUGA_DEV_TRACKER_OAUTH_URL" to "http://trackers:8772"
        val browser = "TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL" to "http://127.0.0.1:5558"
        val dev = configFromEnvironment(*relay, server, browser).trackerOAuth
        // Browsers go to its authorization pages as they reach it; the server exchanges the codes as it reaches it.
        assertEquals("http://127.0.0.1:5558/mal/v1/oauth2/authorize", dev.myAnimeList?.authorizeUrl)
        assertEquals("http://trackers:8772/mal/v1/oauth2/token", dev.myAnimeList?.tokenUrl)
        assertEquals("http://127.0.0.1:5558/anilist/api/v2/oauth/authorize", dev.aniList?.authorizeUrl)
        assertEquals("http://trackers:8772/anilist/api/v2/oauth/token", dev.aniList?.tokenUrl)

        val hosted = configFromEnvironment(*relay).trackerOAuth
        assertEquals(TrackerOAuthConfig.MAL_AUTHORIZE_URL, hosted.myAnimeList?.authorizeUrl)
        assertEquals(TrackerOAuthConfig.ANILIST_TOKEN_URL, hosted.aniList?.tokenUrl)
        for (half in listOf(server, browser)) {
            val error = assertFailsWith<IllegalArgumentException>(half.first) { configFromEnvironment(*relay, half) }
            assertEquals("Set both TORTUGA_DEV_TRACKER_OAUTH_URL and TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL, or neither", error.message)
        }
    }
}

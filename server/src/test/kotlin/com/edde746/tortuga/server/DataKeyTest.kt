package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.crypto.RecordCipher
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** The server refuses to start without its data key (`TORTUGA_DATA_KEY_FILE`, exactly 32 bytes). Needs no database. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class DataKeyTest {
    private val env = mapOf("TORTUGA_DATABASE_URL" to "jdbc:postgresql://postgres:5432/tortuga", "TORTUGA_DATABASE_USER" to "tortuga")
    private val dir: Path = Files.createTempDirectory("tortuga-data-key")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    private fun file(name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }

    private fun random(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun configWithKey(path: Path) = ServerConfig.fromEnvironment(env + (DataKey.ENV to path.toString()))

    @Test
    fun refusesToStartWithoutADataKey() {
        for (missing in listOf(env, env + (DataKey.ENV to " "))) {
            val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(missing) }
            assertContains(error.message.orEmpty(), DataKey.ENV)
        }
    }

    @Test
    fun refusesAKeyFileThatIsUnreadableOrNot32Bytes() {
        val bad = mapOf(
            "missing" to dir.resolve("absent.key"),
            "a directory" to dir,
            "empty" to file("empty.key", ByteArray(0)),
            "31 bytes" to file("short.key", random(31)),
            "33 bytes" to file("long.key", random(33)),
            // `openssl rand -base64 32`: the key as text, not its bytes.
            "base64 text" to file("text.key", (Base64.getEncoder().encodeToString(random(32)) + "\n").encodeToByteArray()),
        )
        for ((case, path) in bad) {
            val error = assertFailsWith<IllegalArgumentException>(case) { configWithKey(path) }
            assertContains(error.message.orEmpty(), DataKey.ENV, message = case)
        }
    }

    @Test
    fun the32BytesOfTheFileAreTheKey() {
        val bytes = random(DataKey.SIZE)
        val config = configWithKey(file("tortuga-data.key", bytes))
        val account = UUID.randomUUID()
        assertContentEquals(
            RecordCipher(DataKey(bytes)).index(account, "library/profile-1/tt0111161"),
            RecordCipher(config.dataKey).index(account, "library/profile-1/tt0111161"),
        )
    }
}

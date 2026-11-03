package com.edde746.tortuga.desktop.harness

import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.desktop.Bootstrap
import com.edde746.tortuga.sync.account.AccountState
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Harness only: device 1 of an account test approves the device link device 2 shows, as a person would in its
 * Settings.
 *
 *     java @harness.args com.edde746.tortuga.desktop.harness.ApproveLinkKt <data dir> <link code>
 *
 * Opens the `--data-dir` device through [Bootstrap.product], so it needs what the app needs: the system's settings
 * reported first, the device's database and its secret-store entries (on Windows, Credential Manager in the desktop
 * session; on Linux, the Secret Service).
 * Claims the code, which names the requesting device, and approves it: that device's next poll signs it in. Exits 0
 * once approved, 1 with the reason otherwise.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: ApproveLinkKt <data dir> <link code>")
        exitProcess(2)
    }
    val approved = try {
        runBlocking { approve(Path.of(args[0]), args[1]) }
        true
    } catch (e: Exception) {
        System.err.println("not approved: $e")
        false
    }
    // The graph's HTTP client and database leave threads behind.
    exitProcess(if (approved) 0 else 1)
}

private suspend fun approve(dataDir: Path, code: String) {
    Bootstrap.reportSystemInputs()
    Bootstrap.product("harness", Startup(), dataDir).use { boot ->
        val account = checkNotNull(boot.account)
        account.restore()
        val state = account.state.value
        check(state is AccountState.SignedIn) { "the device can't approve links: ${state::class.simpleName}" }
        val link = account.claimDeviceLink(code)
        println("claimed ${link.code}: ${link.deviceName} (${link.platform})")
        link.approve()
        println("approved ${link.code}")
    }
}

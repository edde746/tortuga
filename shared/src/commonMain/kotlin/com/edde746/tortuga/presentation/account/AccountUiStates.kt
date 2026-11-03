package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.account.signin.DeviceUserCode
import com.edde746.tortuga.sync.account.signin.ProviderProgress
import com.edde746.tortuga.sync.account.signin.SignInMethod
import com.edde746.tortuga.sync.account.signin.SignInOption
import com.edde746.tortuga.sync.api.IdentityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A device flow's code screen (Google, GitHub or the self-hoster's provider on a TV, GitHub on a device without a
 * browser; a tracker's device code): "go to [verificationUrl] and enter [userCode]", or scan [qrPayload].
 *
 * @param instructions the line above the code, with the address to visit.
 * @param scanHint the line that offers the QR code to a phone.
 * @param expiresText "The code expires in 29:41.", counting down.
 */
data class DeviceCodeUiState(
    val userCode: String,
    val verificationUrl: String,
    val qrPayload: String,
    val instructions: String,
    val scanHint: String,
    val expiresText: String,
)

/**
 * The new device's side of a device link: the code and QR code to show while a signed-in device approves it, with a
 * countdown to the code's expiry. The approval signs this device in.
 *
 * Each case carries its own status line: a progress line while [Starting] and [Waiting], an error line with "Get a
 * new code" once [Failed].
 */
sealed interface DeviceLinkUiState {
    /** Asking the server for a code; [progress] says so. */
    data class Starting(val progress: String) : DeviceLinkUiState

    /**
     * The code is up; the device polls for the other device's answer.
     *
     * @param code `ABCD-EFGH`.
     * @param qrPayload the QR code's text (`tortuga://link?…`).
     * @param expiresText "The code expires in 9:41.", counting down.
     * @param progress the status line: waiting for approval.
     */
    data class Waiting(val code: String, val qrPayload: String, val expiresText: String, val progress: String) : DeviceLinkUiState

    /** The other device declined, nobody answered before the code expired, or getting a code or finishing failed: [error] says which. */
    data class Failed(val error: String) : DeviceLinkUiState
}

/** `m:ss` until [expiresAtMillis], rounded up so the last second shows `0:01`. */
internal fun remainingText(expiresAtMillis: Long, now: Long): String {
    val remaining = (expiresAtMillis - now).coerceAtLeast(0)
    return formatTimecode(((remaining + 999) / 1000).toDouble())
}

/** How long until the countdown text of [expiresAtMillis] changes next. */
internal fun nextTickMillis(expiresAtMillis: Long, now: Long): Long {
    val remaining = expiresAtMillis - now
    if (remaining <= 0) return 1_000
    return (remaining % 1_000).let { if (it == 0L) 1_000 else it }
}

internal fun DeviceUserCode.uiState(now: Long): DeviceCodeUiState = DeviceCodeUiState(
    userCode = userCode,
    verificationUrl = verificationUri,
    qrPayload = verificationUriComplete ?: verificationUri,
    instructions = T.account.signIn.deviceCode.instructions(isolate(displayHost(verificationUri))),
    scanHint = T.account.signIn.deviceCode.scan,
    expiresText = T.account.signIn.deviceCode.expires(remainingText(expiresAtMillis, now)),
)

/** Keeps a device flow's code screen counting down: [onTick] gets it again whenever its countdown text changes. */
internal class DeviceCodeTicker(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onTick: (DeviceCodeUiState) -> Unit,
) {
    private var job: Job? = null

    fun show(code: DeviceUserCode) {
        stop()
        job = scope.launch {
            while (true) {
                val now = clock()
                onTick(code.uiState(now))
                delay(nextTickMillis(code.expiresAtMillis, now))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * A sign-in button.
 *
 * @param provider what the store's sign-in intent takes: one of [IdentityProvider].
 * @param label "Sign in with Apple", "Sign in with Google", "Sign in with GitHub", "Sign in with Authentik".
 */
data class SignInOptionUi(val provider: String, val method: SignInMethod, val label: String)

/** [this]'s button: "Sign in with Apple", "Sign in with Authentik" (the self-hoster's provider by its name). */
internal fun SignInOption.toUi(): SignInOptionUi = SignInOptionUi(
    provider = provider.id,
    method = method,
    label = when (provider.id) {
        IdentityProvider.APPLE -> T.account.signIn.apple
        IdentityProvider.GOOGLE -> T.account.signIn.google
        IdentityProvider.GITHUB -> T.account.signIn.github
        else -> T.account.signIn.oidc(isolate(provider.displayName?.takeIf { it.isNotBlank() } ?: displayHost(provider.issuer)))
    },
)

/** The part of [url] a person reads: no scheme and no trailing slash (`sync.example.com/tortuga`). */
internal fun displayHost(url: String): String = url.substringAfter("://").trimEnd('/')

/** The progress line for [progress]; the device flow shows its code screen instead. */
internal fun progressText(progress: ProviderProgress): String = when (progress) {
    is ProviderProgress.Native -> T.account.signIn.native(
        when (progress.provider) {
            IdentityProvider.APPLE -> T.account.signIn.providerApple
            IdentityProvider.GOOGLE -> T.account.signIn.providerGoogle
            IdentityProvider.GITHUB -> T.account.signIn.providerGitHub
            else -> isolate(progress.provider)
        },
    )
    ProviderProgress.Browser -> T.account.signIn.browser
    is ProviderProgress.DeviceCode -> T.account.signIn.deviceCode.waiting
}

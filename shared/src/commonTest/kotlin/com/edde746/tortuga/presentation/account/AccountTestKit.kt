@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.account.signin.AppleWebSignIn
import com.edde746.tortuga.sync.account.signin.GitHubWebSignIn
import com.edde746.tortuga.sync.account.signin.OAuthClientConfig
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.account.signin.RedirectReceiver
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.account.signin.noProviderCalls
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One app install against the fake server with the account screens on top: its gate and store factories. */
internal class AccountTestApp(
    val server: FakeSyncServer,
    private val test: TestScope,
    val platform: TestSignInPlatform = TestSignInPlatform(),
    secrets: MemorySecretStore = MemorySecretStore(),
    name: String = "Test device",
    clockSkewMillis: Long = 0,
) : AutoCloseable {
    val device = TestDevice(name, server, test, test.backgroundScope, secrets, clockSkewMillis = clockSkewMillis)
    val clock: () -> Long = device.clock
    val account get() = device.account
    val data get() = device.data

    val gate = AccountGate(
        account = device.account,
        syncStatus = device.engine.status,
        profiles = device.data.profiles,
        deviceSettings = device.data.deviceSettings,
        startup = {
            device.account.restore()
            device.engine.start()
            // The platform reports the app in the foreground as it starts.
            device.engine.onForeground()
        },
        clock = clock,
        parentScope = test.backgroundScope,
    )

    fun flows(providerHttp: HttpClient = noProviderCalls, appleHttp: HttpClient = noProviderCalls, githubHttp: HttpClient = noProviderCalls) = ProviderFlows(
        providerHttp,
        platform,
        clock,
        appleWeb = { browser -> AppleWebSignIn(SyncApi(appleHttp), browser, platform::openRedirectReceiver) },
        githubWeb = { browser -> GitHubWebSignIn(SyncApi(githubHttp), browser, platform::openRedirectReceiver) },
    )

    fun signInStore(providerHttp: HttpClient = noProviderCalls, appleHttp: HttpClient = noProviderCalls, githubHttp: HttpClient = noProviderCalls) =
        SignInStore(account, flows(providerHttp, appleHttp, githubHttp), gate, clock, test.backgroundScope)

    override fun close() = device.close()
}

/** Signs [this] device up as a new account of [subject]. */
internal suspend fun TestDevice.signUp(server: FakeSyncServer, subject: String = "alice") {
    account.restore()
    assertEquals(SignInResult.NEW_ACCOUNT, account.signIn(server.serverUrl) { TestDevice.credential(subject) })
}

/** Waits until [predicate] holds for the flow's value; fails with the last value after an hour of virtual time. */
internal suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
    withTimeoutOrNull(3_600_000) { first(predicate) } ?: fail("Still waiting after an hour; last value: $value")

internal suspend fun AccountGate.awaitDestination(destination: AccountDestination): AccountGateUiState =
    state.await { it.destination == destination }

/** Waits until the gate runs the app; the profile it runs as. */
internal suspend fun AccountGate.awaitMain(): AccountDestination.Main =
    assertIs<AccountDestination.Main>(state.await { it.destination is AccountDestination.Main }.destination)

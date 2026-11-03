package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.app.StartupStep
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.account.signin.AppleWebSignIn
import com.edde746.tortuga.sync.account.signin.GitHubWebSignIn
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.account.signin.SyncServers
import com.edde746.tortuga.sync.engine.SyncEngine
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * The root store for this process. Its start waits for the database to be open, restores [account] from the secret
 * store (a store that can't be read leaves it signed out with `SignedOutReason.SESSION_UNREADABLE`; any other failure
 * is logged and leaves it signed out too) and starts [engine], so the app calls neither [SyncAccount.restore] nor
 * [SyncEngine.start] itself; it reports each step to [startup], the profiles' too when the account is signed in.
 * [account] and [engine] are built on this graph's data (`SyncAccount(secrets, graph.data, graph.http, device)`,
 * `SyncEngine(account, graph.data, graph.scope)`).
 */
fun AppGraph.accountGate(account: SyncAccount, engine: SyncEngine, startup: Startup): AccountGate = AccountGate(
    account = account,
    syncStatus = engine.status,
    profiles = data.profiles,
    deviceSettings = data.deviceSettings,
    startup = {
        data.open()
        startup.begin(StartupStep.ACCOUNT)
        delay(startup.slowStart)
        try {
            account.restore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AccountGate", e) { "Restoring the account failed" }
        }
        engine.start()
        if (account.state.value is AccountState.SignedIn) startup.begin(StartupStep.PROFILES)
    },
    clock = account.clock,
    parentScope = scope,
)

/**
 * The sign-in screen ([AccountDestination.SignIn]), starting on [hostedServer] (`SignInStore`'s; a launch's setting,
 * checked here: one that isn't a server URL is a programming error and crashes).
 */
fun AppGraph.signInStore(gate: AccountGate, platform: SignInPlatform, hostedServer: String = SyncServers.HOSTED): SignInStore =
    SignInStore(gate.account, providerFlows(gate.account, platform), gate, gate.account.clock, scope, ServerUrl.parse(hostedServer))

/** "Add a sign-in method" in the account settings. */
fun AppGraph.identityLinkStore(account: SyncAccount, platform: SignInPlatform, host: IdentityLinkHost): IdentityLinkStore =
    IdentityLinkStore(account, providerFlows(account, platform), host, account.clock, scope)

/** The provider flows this device runs ([platform]'s), for sign-in, linking and what the account settings offer. */
internal fun AppGraph.providerFlows(account: SyncAccount, platform: SignInPlatform) = ProviderFlows(
    http,
    platform,
    account.clock,
    appleWeb = { browser -> AppleWebSignIn(SyncApi(http), browser, platform::openRedirectReceiver) },
    githubWeb = { browser -> GitHubWebSignIn(SyncApi(http), browser, platform::openRedirectReceiver) },
)

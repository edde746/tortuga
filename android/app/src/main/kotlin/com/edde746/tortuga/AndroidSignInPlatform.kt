package com.edde746.tortuga

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.sync.account.signin.BuildSignInConfig
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.signin.LoopbackRedirectReceiver
import com.edde746.tortuga.sync.account.signin.OAuthClientConfig
import com.edde746.tortuga.sync.account.signin.RedirectReceiver
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Sign-in on Android phones, tablets and TVs. Phones and tablets with Google Play services sign in to
 * Google natively: Credential Manager's Sign in with Google sheet, which asks for an ID token for Tortuga's server
 * client (the Web application client, [BuildSignInConfig]'s `googleServerClientId`). Browser flows open in a Custom
 * Tab (the default browser without one) and come back through the loopback redirect desktop uses
 * (`http://127.0.0.1:53682/callback`), so the one redirect a self-hoster registers for the apps serves Android too; the
 * page there tells the user to return to Tortuga; the web flows through the server's return page (Apple's, GitHub's)
 * come back the same way. A TV signs in to Google with a code on another device (the device flow, Tortuga's TV
 * client), and Apple's web flow uses Tortuga's Services ID, where the build has them; Google and Tortuga's Services ID
 * serve the hosted server only. A TV, or a device without a browser, signs in to GitHub with a code.
 *
 * @param activity the activity on screen, which launches the browser and shows Google's sheet.
 */
internal class AndroidSignInPlatform(context: Context, private val activity: () -> Activity?) : SignInPlatform {
    private val context: Context = context.applicationContext

    override val isTv: Boolean = isTelevision(this.context)
    override val nativeApple: Boolean = false

    /** Not a TV, the build has the server client, and Google Play services, which run the sheet, are usable. */
    override val nativeGoogle: Boolean
        get() = !isTv && BuildSignInConfig.config.googleServerClientId != null &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** A browser is installed (TVs often have none; [hasBrowser]). */
    override val canOpenBrowser: Boolean
        get() = hasBrowser(context)

    override val canReceiveRedirect: Boolean = true
    override val googleBrowserClient: OAuthClientConfig? = null
    override val googleTvClient: OAuthClientConfig? = BuildSignInConfig.config.googleTvClient
    override val appleServicesId: String? = BuildSignInConfig.config.appleServicesId

    /**
     * Google's sign-in sheet, with [nonce] as the ID token's `nonce` claim. Only Google signs in natively here.
     *
     * @throws AccountException [AccountError.SignInCancelled] when the user closes the sheet,
     *   [AccountError.ProviderUnavailable] when Credential Manager fails or returns something other than a Google ID
     *   token, [AccountError.ProviderNotEnabled] for any other provider.
     */
    override suspend fun nativeSignIn(provider: String, nonce: String): ProviderCredential {
        val serverClientId = BuildSignInConfig.config.googleServerClientId
        if (provider != IdentityProvider.GOOGLE || serverClientId == null) throw AccountException(AccountError.ProviderNotEnabled)
        val activity = activity() ?: run {
            Log.w(TAG) { "No activity to show Google's sign-in from" }
            throw AccountException(AccountError.ProviderUnavailable)
        }
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val credential = try {
            CredentialManager.create(context).getCredential(activity, GetCredentialRequest(listOf(option))).credential
        } catch (e: GetCredentialCancellationException) {
            throw AccountException(AccountError.SignInCancelled, e)
        } catch (e: GetCredentialException) {
            Log.i(TAG) { "Google sign-in failed: ${e.type} ${e.errorMessage ?: ""}" }
            throw AccountException(AccountError.ProviderUnavailable, e)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            Log.w(TAG) { "Google sign-in returned a credential of type ${credential.type}" }
            throw AccountException(AccountError.ProviderUnavailable)
        }
        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG) { "Google's credential doesn't parse: ${e::class.simpleName}" }
            throw AccountException(AccountError.ProviderUnavailable, e)
        }
        return ProviderCredential(IdentityProvider.GOOGLE, idToken, nonce, authorizationCode = null)
    }

    /**
     * Opens the sign-in page [url] in a Custom Tab, else the browser; anything but an `http`/`https` link is refused. A
     * Custom Tab no app takes or the system refuses (a `SecurityException` from a restricted browser) falls back to the
     * browser; each refusal is logged by its class only, as its message can carry the link.
     */
    override fun openBrowser(url: String) {
        if (!WebUrl.isWeb(url)) return Log.w(TAG) { "Not opening a sign-in page with scheme ${WebUrl.scheme(url)}" }
        val activity = activity() ?: return Log.w(TAG) { "No activity to open the sign-in page from" }
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
        } catch (e: RuntimeException) {
            Log.w(TAG) { "No Custom Tab opens the sign-in page: ${e::class.simpleName}" }
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (again: RuntimeException) {
                Log.w(TAG) { "No browser opens the sign-in page: ${again::class.simpleName}" }
            }
        }
    }

    /** Nothing to close: the user leaves the Custom Tab (or browser) once the server's return page says to. */
    override fun closeBrowser() = Unit

    /** [LoopbackRedirectReceiver.openPreferred]. The app keeps listening while the browser is in front: a Custom Tab runs in this app's task. */
    override fun openRedirectReceiver(): RedirectReceiver = LoopbackRedirectReceiver.openPreferred()

    private companion object {
        const val TAG = "SignIn"
    }
}

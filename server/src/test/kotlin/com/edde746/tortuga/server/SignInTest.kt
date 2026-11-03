package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.IdTokenVerifier
import com.edde746.tortuga.server.support.FakeIssuer
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.configFromEnvironment
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.NonceResponse
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInTest {
    private val device = DeviceDescriptor("Pixel", "android")

    @Test
    fun infoListsTheConfiguredProviders() = serverTest {
        val info = get(SyncPaths.INFO).ok<ServerInfo>()
        assertEquals(listOf(IdentityProvider.APPLE, IdentityProvider.GOOGLE, IdentityProvider.OIDC), info.providers.map { it.id })
        assertEquals(listOf("com.edde746.tortuga", "com.edde746.tortuga.web"), info.providers[0].clientIds)
        assertEquals("Test IdP", info.providers[2].displayName)
        assertFalse(info.appleTokenRevocation)
    }

    @Test
    fun firstSignInCreatesTheAccountAndLaterOnesReturnToIt() = serverTest {
        val first = signIn("alice", email = "alice@example.com")
        assertTrue(first.accountCreated)
        assertEquals("alice@example.com", first.account.identities.single().email)

        val second = signIn("alice", deviceName = "Living room TV", platform = "android_tv")
        assertFalse(second.accountCreated)
        assertEquals(first.account.accountId, second.account.accountId)

        val devices = get(SyncPaths.DEVICES, second.session.accessToken).ok<DeviceList>().devices
        assertEquals(setOf("Test phone", "Living room TV"), devices.map { it.name }.toSet())
        assertEquals(second.session.deviceId, devices.single { it.current }.deviceId)

        // Another subject at the same issuer is another account.
        assertTrue(signIn("bob").accountCreated)
        assertEquals(2, TestPostgres.count("accounts"))
    }

    @Test
    fun anIdTokenSignsInOnce() = serverTest {
        val request = SignInRequest(IdentityProvider.OIDC, oidc.token("alice"), device = device)
        post(SyncPaths.AUTH_SIGN_IN, request).ok<SignInResponse>()
        post(SyncPaths.AUTH_SIGN_IN, request).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
    }

    @Test
    fun aUsedTokenStaysUsedUntilItIsNoLongerAccepted() = serverTest {
        val request = SignInRequest(IdentityProvider.OIDC, oidc.token("alice", lifetime = Duration.ofMinutes(10)), device = device)
        post(SyncPaths.AUTH_SIGN_IN, request).ok<SignInResponse>()
        // Past its exp the token is still accepted for the clock skew (60 s): the sweep keeps its marker until then.
        clock.advance(Duration.ofMinutes(10).plusSeconds(30))
        services.janitor.sweep()
        post(SyncPaths.AUTH_SIGN_IN, request).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        assertEquals(1, TestPostgres.count("used_id_tokens"))
        clock.advance(Duration.ofSeconds(30))
        services.janitor.sweep()
        assertEquals(0, TestPostgres.count("used_id_tokens"))
        post(SyncPaths.AUTH_SIGN_IN, request).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        assertEquals(1, TestPostgres.count("accounts"))
    }

    @Test
    fun anIssuerEndingInASlashVerifiesItsTokens() {
        // Authentik's issuer ends in a slash, and its tokens and discovery document carry it so.
        val issuer = "https://slash.test/"
        val oidcConfig = configFromEnvironment("TORTUGA_OIDC_ISSUER" to issuer, "TORTUGA_OIDC_CLIENT_IDS" to "tortuga-slash").oidc!!
        assertEquals(issuer, oidcConfig.issuer)
        lateinit var slashed: FakeIssuer
        serverTest(
            configure = { config, _ -> config.copy(oidc = oidcConfig) },
            external = { services, clock -> slashed = FakeIssuer(issuer, "tortuga-slash", clock).also { it.mount(services) } },
        ) {
            assertEquals(issuer, get(SyncPaths.INFO).ok<ServerInfo>().providers.single { it.id == IdentityProvider.OIDC }.issuer)
            assertTrue(signIn("alice", issuer = slashed).accountCreated)
        }
    }

    @Test
    fun rejectsTokensThatFailVerification() = serverTest {
        val rejected = listOf(
            oidc.token("alice", audience = listOf("someone-else")),
            oidc.token("alice", issuer = "https://evil.test"),
            oidc.token("alice", signingKey = FakeIssuer.foreignKey),
            oidc.token("alice", issuedAt = clock.instant() - Duration.ofMinutes(30)),
            oidc.token("alice", issuedAt = clock.instant() + Duration.ofMinutes(5)),
            oidc.token("alice", lifetime = Duration.ofDays(30)),
            // Several audiences need an authorized party among the client IDs.
            oidc.token("alice", audience = listOf(oidc.clientId, "other"), authorizedParty = "other"),
            // A Google token presented as the OIDC provider.
            google.token("alice"),
            "not-a-jwt",
        )
        for (token in rejected) {
            post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, token, device = device))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        }
        post(
            SyncPaths.AUTH_SIGN_IN,
            SignInRequest(IdentityProvider.OIDC, oidc.token("alice", audience = listOf(oidc.clientId, "other"), authorizedParty = oidc.clientId), device = device),
        ).ok<SignInResponse>()
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest("github", oidc.token("alice"), device = device))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.PROVIDER_NOT_ENABLED)
        assertEquals(1, TestPostgres.count("accounts"))
    }

    @Test
    fun nonceMustBeServerIssuedAndIsConsumed() = serverTest {
        val nonce = postEmpty(SyncPaths.AUTH_NONCE).ok<NonceResponse>().nonce
        fun request(tokenNonce: String?, sent: String?) =
            SignInRequest(IdentityProvider.OIDC, oidc.token("alice", nonce = tokenNonce), nonce = sent, device = device)

        post(SyncPaths.AUTH_SIGN_IN, request("ttn_made-up", "ttn_made-up"))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        post(SyncPaths.AUTH_SIGN_IN, request(nonce, null))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        post(SyncPaths.AUTH_SIGN_IN, request(nonce, nonce)).ok<SignInResponse>()
        // Consumed: a second token carrying the same nonce fails.
        post(SyncPaths.AUTH_SIGN_IN, request(nonce, nonce))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)

        // Its lifetime is sent relative; the server's own clock ends it.
        val late = postEmpty(SyncPaths.AUTH_NONCE).ok<NonceResponse>()
        assertEquals(Duration.ofMinutes(10).toMillis(), late.expiresInMillis)
        clock.advance(Duration.ofMillis(late.expiresInMillis))
        post(SyncPaths.AUTH_SIGN_IN, request(late.nonce, late.nonce))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
    }

    @Test
    fun unverifiedEmailIsNotStored() = serverTest {
        val request = SignInRequest(
            IdentityProvider.GOOGLE,
            google.token("carol", email = "carol@example.com", emailVerified = false),
            device = device,
        )
        assertNull(post(SyncPaths.AUTH_SIGN_IN, request).ok<SignInResponse>().account.identities.single().email)
    }

    @Test
    fun keyRotationRefetchesTheKeySet() = serverTest {
        signIn("alice")
        val fetches = oidc.keySetRequests.get()
        // Unknown key ids refetch at most every 30 seconds, so forged tokens can't hammer the provider.
        repeat(3) {
            post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, oidc.token("alice", signingKey = FakeIssuer.foreignKey), device = device))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        }
        assertEquals(fetches, oidc.keySetRequests.get())

        oidc.rotateKey()
        clock.advance(Duration.ofSeconds(31))
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, oidc.token("alice"), device = device)).ok<SignInResponse>()
        assertEquals(fetches + 1, oidc.keySetRequests.get())
    }

    @Test
    fun providerDocumentsPastTheirCapsFailAsAnUnreachableProvider() = serverTest {
        suspend fun attempt() = post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, oidc.token("alice"), device = device))
        oidc.discoveryBytes = IdTokenVerifier.MAX_DISCOVERY_BYTES.toInt() + 1
        attempt().assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)

        oidc.discoveryBytes = IdTokenVerifier.MAX_DISCOVERY_BYTES.toInt()
        oidc.keySetBytes = IdTokenVerifier.MAX_KEY_SET_BYTES.toInt() + 1
        attempt().assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        assertEquals(0, TestPostgres.count("accounts"))

        oidc.keySetBytes = IdTokenVerifier.MAX_KEY_SET_BYTES.toInt()
        attempt().ok<SignInResponse>()
    }

    @Test
    fun aConfiguredJwksUrlVerifiesTokensOfThePublicIssuer() = serverTest({ config, _ ->
        // The provider as a dev stack's container sees it: its public issuer unreachable, its keys at another address.
        config.copy(oidc = config.oidc!!.copy(issuer = PUBLIC_ISSUER, jwksUri = "https://oidc.test/keys"))
    }) {
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, oidc.token("alice", issuer = PUBLIC_ISSUER), device = device))
            .ok<SignInResponse>()
        // Tokens still need the public issuer: the address the keys come from isn't one.
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.OIDC, oidc.token("bob"), device = device))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        assertEquals(1, TestPostgres.count("accounts"))
    }

    @Test
    fun secondProviderSignsInToTheSameAccount() = serverTest {
        val alice = signIn("alice", email = "alice@example.com")
        val token = alice.session.accessToken
        val linked = post(
            SyncPaths.ACCOUNT_IDENTITIES,
            LinkIdentityRequest(IdentityProvider.GOOGLE, google.token("alice-google")),
            token,
        ).ok<AccountInfo>()
        assertEquals(listOf(IdentityProvider.OIDC, IdentityProvider.GOOGLE), linked.identities.map { it.provider })

        val viaGoogle = signIn("alice-google", issuer = google)
        assertEquals(alice.account.accountId, viaGoogle.account.accountId)

        // An identity that already has its own account can't be linked to another.
        val bob = signIn("bob")
        post(SyncPaths.ACCOUNT_IDENTITIES, LinkIdentityRequest(IdentityProvider.OIDC, oidc.token("alice")), bob.session.accessToken)
            .assertError(HttpStatusCode.Conflict, ApiErrorCode.IDENTITY_IN_USE)

        // Unlinking works down to the last identity.
        val googleId = linked.identities.single { it.provider == IdentityProvider.GOOGLE }.identityId
        delete("${SyncPaths.ACCOUNT_IDENTITIES}/$googleId", token).ok<AccountInfo>()
        val lastId = linked.identities.single { it.provider == IdentityProvider.OIDC }.identityId
        delete("${SyncPaths.ACCOUNT_IDENTITIES}/$lastId", token).assertError(HttpStatusCode.Conflict, ApiErrorCode.LAST_IDENTITY)
    }

    private companion object {
        /** A dev stack's Dex issuer, which devices reach through `adb reverse` and tunnels but the server can't. */
        const val PUBLIC_ISSUER = "http://127.0.0.1:5556/dex"
    }
}

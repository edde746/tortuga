package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.isUniqueViolation
import java.security.MessageDigest
import java.sql.SQLException
import java.util.HexFormat

/**
 * The review sign-in (server/README.md, Review sign-in): whoever has the review password approves a device link for
 * the one demo account ([Accounts.reviewAccount]) and no other, so a store's reviewer signs a device in through the
 * review page with no provider account. Only the password's SHA-256, [passwordSha256] as hex, is configured.
 */
class ReviewSignIn(passwordSha256: String, private val links: DeviceLinks, private val accounts: Accounts) {
    private val passwordHash = HexFormat.of().parseHex(passwordSha256)

    enum class Outcome { APPROVED, WRONG_PASSWORD, NOT_FOUND, EXPIRED }

    /** Approves the open link [code] for the demo account if [password] is the review password. */
    suspend fun approve(password: String, code: String): Outcome {
        // Equal-length digests compared in constant time: the time taken says nothing of the password.
        if (!MessageDigest.isEqual(Secrets.sha256(password), passwordHash)) return Outcome.WRONG_PASSWORD
        // Two first approvals can race to create the demo account; the loser retries and finds the winner's.
        repeat(2) { attempt ->
            try {
                return when (links.approve(code) { accounts.reviewAccount(this) }) {
                    LinkApproval.APPROVED -> Outcome.APPROVED
                    LinkApproval.EXPIRED -> Outcome.EXPIRED
                    LinkApproval.NOT_FOUND -> Outcome.NOT_FOUND
                }
            } catch (e: SQLException) {
                if (!e.isUniqueViolation() || attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    companion object {
        /** The demo account's identity provider: never listed in `/v2/info`, so no app offers it as a sign-in. */
        const val PROVIDER = "review"
    }
}

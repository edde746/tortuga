package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.http.PageForm
import com.edde746.tortuga.server.http.PageInput
import com.edde746.tortuga.server.http.respondStaticPage
import com.edde746.tortuga.server.service.ReviewSignIn
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The review page's path, served only where the review sign-in is configured. */
const val REVIEW_PATH = "/review"

/**
 * The review page ([ReviewSignIn]): the review password and the code a signed-out device shows on its Link from
 * another device screen. A submission approves that link for the demo account; the page says what happened and
 * shows nothing of the account. Submissions take [Limits.REVIEW]'s few a minute per client address.
 */
fun Route.reviewRoutes(review: ReviewSignIn) {
    get(REVIEW_PATH) {
        call.respondReviewPage(
            HttpStatusCode.OK,
            "Sign in for app review",
            "On the device, choose Link from another device. Enter the review password and the code the device shows.",
            focus = true,
        )
    }

    rateLimit(Limits.REVIEW) {
        post(REVIEW_PATH) {
            val form = call.receiveParameters()
            when (review.approve(form["password"].orEmpty(), form["code"].orEmpty())) {
                ReviewSignIn.Outcome.APPROVED -> call.respondReviewPage(
                    HttpStatusCode.OK,
                    "Device signed in",
                    "The device signs in to the review account in a few seconds. To sign in another device, enter its code.",
                )
                ReviewSignIn.Outcome.WRONG_PASSWORD -> call.respondReviewPage(
                    HttpStatusCode.Forbidden,
                    "Wrong review password",
                    "Check the review password in the review notes and enter it again.",
                )
                // 400, not 404: StatusPages answers every 404 with the API's JSON error, which would replace this page.
                ReviewSignIn.Outcome.NOT_FOUND -> call.respondReviewPage(
                    HttpStatusCode.BadRequest,
                    "No device shows this code",
                    "Check the code on the device and enter it again. A code works for 10 minutes: for a new one, " +
                        "choose Link from another device again on the device.",
                )
                ReviewSignIn.Outcome.EXPIRED -> call.respondReviewPage(
                    HttpStatusCode.Gone,
                    "This code has expired",
                    "A code works for 10 minutes. On the device, choose Link from another device again and enter the " +
                        "new code.",
                )
            }
        }
    }
}

/** The page with [title] and [body] over the form; [focus]: the password field takes the focus (not over an answer). */
private suspend fun ApplicationCall.respondReviewPage(status: HttpStatusCode, title: String, body: String, focus: Boolean = false) =
    respondStaticPage(
        status,
        title,
        body,
        PageForm(
            action = REVIEW_PATH,
            fields = emptyMap(),
            button = "Sign in the device",
            note = "This page signs devices in to Tortuga's review account only.",
            inputs = listOf(PageInput("password", "Review password", password = true), PageInput("code", "Code from the device")),
            focus = focus,
        ),
    )

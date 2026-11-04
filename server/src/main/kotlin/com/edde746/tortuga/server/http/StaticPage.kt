package com.edde746.tortuga.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

/**
 * A page for a browser that comes back from a sign-in (Sign in with Apple's and GitHub's web flows, the tracker OAuth
 * relay) or signs a device in (the review page): a title and one line saying what to do next, no script; with a
 * [form], its fields and one button that posts it, and a line under it. These pages are served to a browser, not drawn
 * by an app, so their text is English here rather than in the apps' string catalog.
 */
suspend fun ApplicationCall.respondStaticPage(status: HttpStatusCode, title: String, body: String, form: PageForm? = null) {
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    val formHtml = form?.let {
        val inputs = it.inputs.withIndex().joinToString("") { (i, input) ->
            """<label>${escapeHtml(input.label)}<input type="${if (input.password) "password" else "text"}" """ +
                """name="${escapeHtml(input.name)}" autocomplete="off" autocapitalize="off" spellcheck="false" required""" +
                """${if (i == 0 && it.focus) " autofocus" else ""}></label>"""
        }
        val fields = it.fields.entries.joinToString("") { (name, value) ->
            """<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">"""
        }
        """<form method="post" action="${escapeHtml(it.action)}">$inputs$fields<button type="submit">${escapeHtml(it.button)}</button></form>""" +
            """<p class="note">${escapeHtml(it.note)}</p>"""
    }.orEmpty()
    respondText(
        """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${escapeHtml(title)}</title>
        <style>body{font-family:system-ui,sans-serif;background:#05090D;color:#FFF1D6;display:grid;place-items:center;
        min-height:100vh;margin:0;text-align:center}main{max-width:28rem;padding:2rem;overflow-wrap:anywhere}h1{font-weight:600}
        button{font:inherit;font-weight:600;background:#FFF1D6;color:#000;border:0;border-radius:999px;padding:.8rem 2.4rem;
        margin:1rem 0;cursor:pointer}.note{opacity:.7;font-size:.9rem}label{display:block;text-align:left;margin:1.2rem 0;
        font-size:.9rem}label input{display:block;box-sizing:border-box;width:100%;font:inherit;font-size:1.1rem;margin-top:.4rem;
        padding:.8rem 1rem;background:#1A2129;color:#FFF1D6;border:0;border-radius:.75rem}</style>
        </head><body><main><h1>${escapeHtml(title)}</h1><p>${escapeHtml(body)}</p>$formHtml</main></body></html>
        """.trimIndent(),
        ContentType.Text.Html,
        status,
    )
}

/**
 * The form of a [respondStaticPage]: [inputs] for a person to fill in, the first one focused when [focus] is set (a
 * page that answers a submission leaves it unset, so a phone's keyboard doesn't cover the answer), then [fields],
 * hidden, posted to [action] on this server with its one [button]. [note] goes under it.
 */
class PageForm(
    val action: String,
    val fields: Map<String, String>,
    val button: String,
    val note: String,
    val inputs: List<PageInput> = emptyList(),
    val focus: Boolean = false,
)

/** A field of a [PageForm] a person fills in, under its [label]; a [password]'s characters don't show. */
class PageInput(val name: String, val label: String, val password: Boolean = false)

/**
 * Sends a browser that came back from a web sign-in on to the app on its own device (303, a GET even after Apple's
 * form post): [location] carries the hand-off's state and proof, so nothing may keep or pass it on.
 */
suspend fun ApplicationCall.respondAppReturn(location: String) {
    response.header(HttpHeaders.Location, location)
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respond(HttpStatusCode.SeeOther)
}

private fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '&' -> append("&amp;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

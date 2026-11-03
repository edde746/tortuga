#!/usr/bin/env python3
"""Sign in with GitHub end to end against a dev stack: its server and the fake GitHub (server/dev/fake_github.py),
which the server's /v2/info names as GitHub's issuer, the address the apps use. Python 3, standard library only.

    server/scripts/github-e2e.py <server URL>        e.g. http://127.0.0.1:18180

1. Web flow: a state from /v2/auth/github/start (the challenge of a fresh verifier), authorize at the fake with a cookie
   jar (a fresh user), the server's return page and its 303 back to the app's loopback address with the state and a
   proof (read, not followed: nothing listens there), the hand-off collected once with the verifier and the proof
   (another verifier, another proof or a second collect get 404; a state the server never issued gets the return page's
   400), sign-in: a new account whose GitHub identity has the user's email.
2. The same browser again (the fake's cookie keeps the user): the same account.
3. Device flow for that user: /login/device/code, /_fake/device approves, polling gets the token, sign-in: the same
   account.
4. A second GitHub user (the authorize `login` query) links to that account.
5. The account's deletion answers githubRevocation "revoked", and /_fake/state holds neither user's grant or token.

scripts/smoke.sh runs it on its own stack; for a stack kept up elsewhere, run it on that stack's host, where the server
and the fake are on 127.0.0.1. Exits 1 at the first check that fails.
"""

import base64
import hashlib
import http.cookiejar
import json
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEVICE = {"name": "github-e2e", "platform": "linux"}
# The app's loopback receiver (LoopbackRedirectReceiver's usual address).
REDIRECT_URI = "http://127.0.0.1:53682/callback"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hands a redirect back to the caller (as an HTTPError), as an app's browser hands GitHub's to the server and the
    server's to the app."""

    def redirect_request(self, *args, **kwargs):
        return None


def opener(jar=None):
    handlers = [NoRedirect()] + ([urllib.request.HTTPCookieProcessor(jar)] if jar is not None else [])
    return urllib.request.build_opener(*handlers)


def call(method, url, json_body=None, form=None, bearer=None, via=None):
    """(status, headers, body as JSON when it parses, else text) of one request; HTTP errors are answers too."""
    headers = {"Accept": "application/json"}
    data = None
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if bearer is not None:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        response = (via or opener()).open(request, timeout=30)
    except urllib.error.HTTPError as e:
        response = e
    with response:
        raw = response.read().decode("utf-8", "replace")
    try:
        body = json.loads(raw) if raw else None
    except ValueError:
        body = raw
    return response.status, response.headers, body


def check(condition, what):
    if not condition:
        sys.exit(f"github-e2e: FAILED: {what}")


def ask(what, method, url, json_body=None, **kwargs):
    """The body of a request that must answer 200."""
    status, _, body = call(method, url, json_body, **kwargs)
    check(status == 200, f"{what}: HTTP {status} {body!r}")
    return body


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def web_flow(server, github, client_id, browser, login=None):
    """The app's web flow in [browser] (an opener): the access token the hand-off gives."""
    verifier = b64url(secrets.token_bytes(32))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    start = ask("start", "POST", f"{server}/v2/auth/github/start",
                {"challenge": challenge, "redirectUri": REDIRECT_URI})
    state = start["state"]
    query = {"client_id": client_id, "redirect_uri": f"{server}/v2/auth/github/return", "scope": "user:email",
             "state": state, "allow_signup": "true"}
    if login is not None:
        query["login"] = login
    status, headers, body = call("GET", f"{github}/login/oauth/authorize?{urllib.parse.urlencode(query)}", via=browser)
    check(status == 302, f"authorize: HTTP {status} {body!r}")
    location = headers["Location"]
    check(location.startswith(f"{server}/v2/auth/github/return?"), "authorize redirects to the server's return page")
    status, headers, _ = call("GET", location, via=browser)
    check(status == 303, f"return page: HTTP {status}")
    back = headers.get("Location") or ""
    returned = urllib.parse.parse_qs(urllib.parse.urlsplit(back).query)
    # The proof is the hand-off's key: only the address and the query's names are told.
    check(back.startswith(f"{REDIRECT_URI}?") and returned.get("state") == [state]
          and len(returned.get("proof", [])) == 1,
          f"the return page sends the browser to the app's address with state and proof: "
          f"{back.partition('?')[0]!r} with {sorted(returned)}")
    proof = returned["proof"][0]
    handoff = f"{server}/v2/auth/github/handoff"
    status, _, _ = call("POST", handoff, {"state": state, "verifier": b64url(secrets.token_bytes(32)), "proof": proof})
    check(status == 404, f"a collect with another verifier: HTTP {status}")
    status, _, _ = call("POST", handoff, {"state": state, "verifier": verifier, "proof": secrets.token_urlsafe(32)})
    check(status == 404, f"a collect with another proof: HTTP {status}")
    collected = ask("collect", "POST", handoff, {"state": state, "verifier": verifier, "proof": proof})
    check(isinstance(collected.get("accessToken"), str), "the hand-off holds a token")
    status, _, _ = call("POST", handoff, {"state": state, "verifier": verifier, "proof": proof})
    check(status == 404, f"a second collect: HTTP {status}")
    never = urllib.parse.urlencode({"code": "never-issued", "state": b64url(secrets.token_bytes(32))})
    status, headers, _ = call("GET", f"{server}/v2/auth/github/return?{never}", via=browser)
    check(status == 400 and headers.get("Location") is None, f"the return page for a state never issued: HTTP {status}")
    return collected["accessToken"]


def sign_in(server, token):
    request = {"provider": "github", "idToken": token, "device": DEVICE}
    return ask("sign-in", "POST", f"{server}/v2/auth/signin", request)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    server = sys.argv[1].rstrip("/")
    info = ask("/v2/info", "GET", f"{server}/v2/info")
    provider = next((p for p in info["providers"] if p["id"] == "github"), None)
    check(provider is not None, "/v2/info lists github")
    github, client_id = provider["issuer"].rstrip("/"), provider["clientIds"][0]
    print(f"github-e2e: server {server}, GitHub {github}, client {client_id}")

    jar = http.cookiejar.CookieJar()
    browser = opener(jar)
    first = sign_in(server, web_flow(server, github, client_id, browser))
    login = next((c.value for c in jar if c.name == "fake_github_user"), None)
    check(login is not None, "the fake set its user cookie")
    account = first["account"]
    check(first["accountCreated"], "the first sign-in creates the account")
    identity = account["identities"][0]
    check(identity["provider"] == "github" and identity.get("email") == f"{login}@users.noreply.fake",
          f"the identity is the GitHub user's: {identity}")
    print(f"1. web flow: {login} signed in, account {account['accountId']} created")

    again = sign_in(server, web_flow(server, github, client_id, browser))
    check(not again["accountCreated"] and again["account"]["accountId"] == account["accountId"],
          "the same browser signs in to the same account")
    print("2. web flow again in the same browser: the same account")

    code = ask("device code", "POST", f"{github}/login/device/code",
               form={"client_id": client_id, "scope": "user:email"})
    poll = {"client_id": client_id, "device_code": code["device_code"],
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code"}
    pending = ask("device poll", "POST", f"{github}/login/oauth/access_token", form=poll)
    check(pending.get("error") == "authorization_pending", f"before approval: {pending}")
    approved = ask("/_fake/device", "POST", f"{github}/_fake/device",
                   form={"user_code": code["user_code"], "login": login})
    check(approved["status"] == "approved", f"/_fake/device: {approved}")
    time.sleep(code["interval"])
    token = ask("device poll", "POST", f"{github}/login/oauth/access_token", form=poll)
    check(isinstance(token.get("access_token"), str), f"after approval: {sorted(token)}")
    device = sign_in(server, token["access_token"])
    check(not device["accountCreated"] and device["account"]["accountId"] == account["accountId"],
          "the device flow signs the same user in to the same account")
    print(f"3. device flow: code {code['user_code']} approved, the same account")

    second = f"e2e-{secrets.token_hex(4)}"
    second_token = web_flow(server, github, client_id, opener(), login=second)
    session = device["session"]["accessToken"]
    linked = ask("link", "POST", f"{server}/v2/account/identities", {"provider": "github", "idToken": second_token},
                 bearer=session)
    emails = sorted(i.get("email") for i in linked["identities"] if i["provider"] == "github")
    check(emails == sorted([f"{login}@users.noreply.fake", f"{second}@users.noreply.fake"]),
          f"the account holds both GitHub users: {emails}")
    print(f"4. link: {second} linked to the account")

    state = ask("/_fake/state", "GET", f"{github}/_fake/state")
    granted = {g["login"] for g in state["grants"] if g["client_id"] == client_id}
    check({login, second} <= granted, f"both users granted Tortuga before the deletion: {sorted(granted)}")
    deletion = ask("account deletion", "DELETE", f"{server}/v2/account", bearer=session)
    check(deletion.get("githubRevocation") == "revoked", f"deletion: {deletion}")
    status, _, _ = call("GET", f"{server}/v2/account", bearer=session)
    check(status == 401, f"the deleted account's session: HTTP {status}")
    state = ask("/_fake/state", "GET", f"{github}/_fake/state")
    granted = {g["login"] for g in state["grants"] if g["client_id"] == client_id}
    live = {t["login"] for t in state["tokens"].get(client_id, [])}
    check(not ({login, second} & granted), f"grants left at GitHub: {sorted(granted)}")
    check(not ({login, second} & live), f"tokens left at GitHub: {sorted(live)}")
    print(f"5. deletion: githubRevocation={deletion['githubRevocation']}, no grant or token left at the fake GitHub")
    print("github-e2e: all steps passed")


if __name__ == "__main__":
    main()

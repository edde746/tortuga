#!/usr/bin/env python3
"""A fake GitHub for the dev stack and the harnesses: GitHub's OAuth web flow, its device flow and the OAuth App
endpoints Tortuga's server calls, all on one port (web and API at its root). Python 3, standard library only.

    python3 server/dev/fake_github.py [port] [host]     default 5557 on 127.0.0.1

The dev stack runs it as the compose service `github` (server/compose.dev.yaml), published on
127.0.0.1:${GITHUB_HTTP_PORT:-5557}: apps and browsers reach it there (through `adb reverse` and SSH tunnels too), the
server inside the compose network at http://github:5557. It keeps everything in memory: a restart forgets codes,
tokens, grants and device codes; a login keeps its id.

OAuth Apps (fake, fixed, like MinIO's dev credentials):
    tortuga-dev-github / tortuga-dev-github-secret   the dev stack's Tortuga (TORTUGA_GITHUB_CLIENT_ID and _SECRET)
    other-app / other-app-secret                     another app, whose tokens Tortuga's server must refuse

Users: a login names one. Its numeric id comes from the login (the same in every run of the fake), its one email is
<login>@users.noreply.fake, primary and verified. Logins are letters, digits, '.', '_' and '-', at most 64.

Web flow
    GET  /login/oauth/authorize?client_id=&redirect_uri=&scope=&state=[&login=<name>]
         Approves at once (GitHub's already-authorized case) and redirects (302) to redirect_uri?code=…&state=…. The
         user: the `login` query when given; else the login the cookie fake_github_user names (set by an earlier
         authorize in that browser; a login this fake doesn't know yet becomes a user, so a browser that kept the
         cookie across stacks stays one user); else a fresh user (login user-<hex>), whose login it sets in that
         cookie. The user `deny` or `deny-*` declines instead:
         redirect_uri?error=access_denied&error_description=…&state=…. An unknown client_id gets a 404 page, a
         missing redirect_uri a 400 page. A code lasts 10 minutes and exchanges once; the approval starts the user's
         grant of the app.
    POST /login/oauth/access_token   form client_id, client_secret, code (redirect_uri optional, must then match):
         {"access_token": "gho_…", "token_type": "bearer", "scope": "user:email"}. Refusals are 200s, as GitHub's:
         {"error": "incorrect_client_credentials"} for a wrong secret, "bad_verification_code" for a code unknown,
         used, expired or of another app, "redirect_uri_mismatch". JSON when Accept names it, else form-encoded.

Device flow (no secret)
    POST /login/device/code   form client_id, scope → {"device_code", "user_code" (like ABCD-1234),
         "verification_uri": "<base>/login/device" (<base> from the request's Host), "expires_in": 900, "interval": 5}
    GET  /login/device   a page with a form that approves or denies a code (it posts to /_fake/device)
    POST /login/oauth/access_token   form client_id, device_code,
         grant_type=urn:ietf:params:oauth:grant-type:device_code:
         {"error": "authorization_pending"} until the code is approved or denied; "slow_down" (with "interval" 5 s
         longer, which later polls must keep) for a poll more than a second sooner than the interval after the last
         one; "access_denied" once denied; "expired_token" after 900 s; "incorrect_device_code" for an unknown code
         or one already collected. Once approved, the token, once.

Harness only
    POST /_fake/device   form (or JSON) user_code (any case, dash optional), login (optional: else a fresh user),
         deny (optional, any non-empty value; also the login `deny` or `deny-*`): approves or denies a pending device
         code → {"user_code", "status": "approved" | "denied", "login", "id"}; 404 {"error": "unknown_user_code"}
         when no pending code matches.
    GET  /_fake/state   200 while the fake is up: {"users": [{"login", "id"}],
         "tokens": {"<client_id>": [{"login", "id", "scopes", "token_last_eight"}]}, the live tokens per app,
         "grants": [{"login", "id", "client_id"}],
         "device_codes": [{"user_code", "client_id", "status": "pending" | "approved" | "denied" | "expired", "login"}]}
         for checks such as "the grant is gone after the account's deletion".

API (Basic auth must be the path's client ID and its secret, else 401 {"message": "Bad credentials"})
    PATCH  /applications/{client_id}/token   JSON {"access_token"}: ends that token and answers its replacement,
           {"token", "token_last_eight", "scopes", "user": {"login", "id"}, "app": {"client_id"}}; 404 for a token
           that isn't a live one of this app (another app's, ended, made up), 422 without access_token.
    GET    /user/emails   Authorization: Bearer <token> (or token <token>): [{"email", "primary": true,
           "verified": true, "visibility": "private"}] when the token has user:email (or user), else 404; 401 for a
           token that isn't live.
    DELETE /applications/{client_id}/grant   JSON {"access_token"}: 204, and the user's grant of the app ends with all
           its tokens; 404 for a token that isn't a live one of this app.

Logs: one line per request, its method, path (never the query) and status; never a token, code or secret.
"""

import base64
import hashlib
import http.cookies
import json
import re
import secrets
import signal
import string
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit

CLIENTS = {"tortuga-dev-github": "tortuga-dev-github-secret", "other-app": "other-app-secret"}
COOKIE = "fake_github_user"
LOGIN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
CODE_TTL = 600
DEVICE_TTL = 900
DEVICE_INTERVAL = 5
POLL_TOLERANCE = 1
DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
EMAIL_SCOPES = {"user:email", "user"}

lock = threading.Lock()
users = {}  # login -> id
codes = {}  # code -> {login, client_id, scopes, redirect_uri, expires}
tokens = {}  # token -> {login, client_id, scopes}
grants = set()  # (login, client_id)
devices = {}  # device_code -> {user_code, client_id, scopes, expires, interval, last_poll, status, login}


def user_id(login):
    """login's numeric id, the same in every run of the fake (lock held)."""
    if login not in users:
        users[login] = int.from_bytes(hashlib.sha256(login.encode()).digest()[:5], "big") + 1
    return users[login]


def fresh_login():
    return "user-" + secrets.token_hex(4)


def declines(login):
    return login == "deny" or login.startswith("deny-")


def mint(login, client_id, scopes):
    """A new token of client_id for login (lock held)."""
    alphabet = string.ascii_letters + string.digits
    token = "gho_" + "".join(secrets.choice(alphabet) for _ in range(36))
    tokens[token] = {"login": login, "client_id": client_id, "scopes": scopes}
    return token


def end_grant(login, client_id):
    """login's grant of client_id ends, with every token of it (lock held)."""
    grants.discard((login, client_id))
    for token in [t for t, entry in tokens.items() if entry["login"] == login and entry["client_id"] == client_id]:
        del tokens[token]


def new_user_code():
    """A pending device code's user code, ABCD-1234 (lock held)."""
    taken = {entry["user_code"] for entry in devices.values()}
    while True:
        code = "".join(secrets.choice("BCDFGHJKLMNPQRSTVWXZ") for _ in range(4)) + "-" + "".join(
            secrets.choice(string.digits) for _ in range(4))
        if code not in taken:
            return code


def normal_user_code(code):
    return code.replace("-", "").strip().upper()


def scope_list(scope):
    return [s for s in re.split(r"[ ,]+", scope or "") if s]


def with_query(url, params):
    parts = urlsplit(url)
    query = parts.query + ("&" if parts.query else "") + urlencode(params)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeGitHub/1"

    def log_request(self, code="-", size="-"):
        sys.stderr.write(f"{self.log_date_time_string()} {self.command} {urlsplit(self.path).path} {code}\n")

    # Requests.

    def path_only(self):
        return urlsplit(self.path).path

    def query(self):
        return {k: v[0] for k, v in parse_qs(urlsplit(self.path).query).items()}

    def raw_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def json_body(self):
        """The JSON object sent, {} for none; None when it doesn't parse."""
        raw = self.raw_body()
        if not raw:
            return {}
        try:
            value = json.loads(raw)
        except ValueError:
            return None
        return value if isinstance(value, dict) else None

    def form(self):
        """Form or JSON parameters; GitHub also takes them in the query string."""
        params = self.query()
        if "json" in self.headers.get("Content-Type", ""):
            body = self.json_body() or {}
            params.update({k: str(v) for k, v in body.items()})
        else:
            params.update({k: v[0] for k, v in parse_qs(self.raw_body().decode("utf-8", "replace")).items()})
        return params

    def cookie_login(self):
        jar = http.cookies.SimpleCookie()
        try:
            jar.load(self.headers.get("Cookie", ""))
        except http.cookies.CookieError:
            return None
        morsel = jar.get(COOKIE)
        return morsel.value if morsel is not None and LOGIN.fullmatch(morsel.value) else None

    def app_authorized(self, client_id):
        scheme, _, credentials = self.headers.get("Authorization", "").partition(" ")
        if scheme.lower() != "basic" or client_id not in CLIENTS:
            return False
        try:
            decoded = base64.b64decode(credentials, validate=True).decode()
        except ValueError:
            return False
        return decoded == f"{client_id}:{CLIENTS[client_id]}"

    # Responses.

    def send(self, status, body=b"", content_type="application/json; charset=utf-8", headers=()):
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Cache-Control", "no-store")
        if status != 204:
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def json(self, status, value):
        self.send(status, json.dumps(value).encode())

    def token_answer(self, value):
        """The token endpoints' answer: always 200, JSON when Accept names it, else form-encoded."""
        if "json" in self.headers.get("Accept", ""):
            self.json(200, value)
        else:
            self.send(200, urlencode(value).encode(), "application/x-www-form-urlencoded")

    def page(self, status, title, text, extra=""):
        html = (f"<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>{title}</title></head>"
                f"<body><h1>{title}</h1><p>{text}</p>{extra}</body></html>")
        self.send(status, html.encode(), "text/html; charset=utf-8")

    # Routes.

    def do_GET(self):
        route = {
            "/login/oauth/authorize": self.authorize,
            "/login/device": self.device_page,
            "/user/emails": self.emails,
            "/_fake/state": self.state,
        }.get(self.path_only())
        route() if route else self.json(404, {"message": "Not Found"})

    def do_POST(self):
        route = {
            "/login/oauth/access_token": self.access_token,
            "/login/device/code": self.device_code,
            "/_fake/device": self.fake_device,
        }.get(self.path_only())
        route() if route else self.json(404, {"message": "Not Found"})

    def do_PATCH(self):
        match = re.fullmatch(r"/applications/([^/]+)/token", self.path_only())
        self.reset_token(match.group(1)) if match else self.json(404, {"message": "Not Found"})

    def do_DELETE(self):
        match = re.fullmatch(r"/applications/([^/]+)/grant", self.path_only())
        self.delete_grant(match.group(1)) if match else self.json(404, {"message": "Not Found"})

    def authorize(self):
        q = self.query()
        client_id, redirect_uri = q.get("client_id", ""), q.get("redirect_uri")
        if client_id not in CLIENTS:
            return self.page(404, "Not Found", "No OAuth App has this client_id.")
        if not redirect_uri:
            return self.page(400, "Bad request", "redirect_uri is missing.")
        headers = []
        login = q.get("login") or self.cookie_login()
        if login is None:
            login = fresh_login()
            headers.append(("Set-Cookie", f"{COOKIE}={login}; Path=/; HttpOnly; SameSite=Lax"))
        elif not LOGIN.fullmatch(login):
            return self.page(400, "Bad request", "login must be letters, digits, '.', '_' or '-', at most 64.")
        if declines(login):
            params = {"error": "access_denied", "error_description": "The user has denied your application access."}
        else:
            with lock:
                user_id(login)
                grants.add((login, client_id))
                code = secrets.token_hex(10)
                codes[code] = {"login": login, "client_id": client_id, "scopes": scope_list(q.get("scope")),
                               "redirect_uri": redirect_uri, "expires": time.time() + CODE_TTL}
            params = {"code": code}
        if "state" in q:
            params["state"] = q["state"]
        headers.append(("Location", with_query(redirect_uri, params)))
        self.send(302, b"", "text/plain", headers)

    def access_token(self):
        form = self.form()
        client_id = form.get("client_id", "")
        if form.get("grant_type") == DEVICE_GRANT:
            return self.poll_device(client_id, form.get("device_code", ""))
        if client_id not in CLIENTS or form.get("client_secret") != CLIENTS[client_id]:
            return self.token_answer({"error": "incorrect_client_credentials",
                                      "error_description": "The client_id and/or client_secret passed are incorrect."})
        with lock:
            entry = codes.pop(form.get("code", ""), None)
            if entry is None or entry["client_id"] != client_id or entry["expires"] <= time.time():
                answer = {"error": "bad_verification_code",
                          "error_description": "The code passed is incorrect or expired."}
            elif form.get("redirect_uri") and form["redirect_uri"] != entry["redirect_uri"]:
                answer = {"error": "redirect_uri_mismatch",
                          "error_description": "The redirect_uri MUST match the registered callback URL."}
            else:
                answer = {"access_token": mint(entry["login"], client_id, entry["scopes"]), "token_type": "bearer",
                          "scope": ",".join(entry["scopes"])}
        self.token_answer(answer)

    def device_code(self):
        form = self.form()
        client_id = form.get("client_id", "")
        if client_id not in CLIENTS:
            return self.token_answer({"error": "incorrect_client_credentials",
                                      "error_description": "The client_id passed is incorrect."})
        with lock:
            user_code = new_user_code()
            device_code = secrets.token_hex(20)
            devices[device_code] = {"user_code": user_code, "client_id": client_id,
                                    "scopes": scope_list(form.get("scope")), "expires": time.time() + DEVICE_TTL,
                                    "interval": DEVICE_INTERVAL, "last_poll": None, "status": "pending", "login": None}
        base = "http://" + self.headers.get("Host", f"127.0.0.1:{self.server.server_port}")
        self.token_answer({"device_code": device_code, "user_code": user_code,
                           "verification_uri": base + "/login/device", "expires_in": DEVICE_TTL,
                           "interval": DEVICE_INTERVAL})

    def poll_device(self, client_id, device_code):
        now = time.time()
        with lock:
            entry = devices.get(device_code)
            if entry is None or entry["client_id"] != client_id:
                answer = {"error": "incorrect_device_code",
                          "error_description": "The device_code provided is not valid."}
            elif entry["expires"] <= now:
                answer = {"error": "expired_token", "error_description": "The device_code has expired."}
            elif entry["last_poll"] is not None and now - entry["last_poll"] < entry["interval"] - POLL_TOLERANCE:
                entry["interval"] += 5
                entry["last_poll"] = now
                answer = {"error": "slow_down", "error_description": "Too many requests have been made in the same "
                          "timeframe.", "interval": entry["interval"]}
            elif entry["status"] == "denied":
                entry["last_poll"] = now
                answer = {"error": "access_denied", "error_description": "The authorization request was denied."}
            elif entry["status"] == "pending":
                entry["last_poll"] = now
                answer = {"error": "authorization_pending",
                          "error_description": "The authorization request is still pending."}
            else:
                del devices[device_code]
                answer = {"access_token": mint(entry["login"], client_id, entry["scopes"]), "token_type": "bearer",
                          "scope": ",".join(entry["scopes"])}
        self.token_answer(answer)

    def device_page(self):
        form = ('<form method="post" action="/_fake/device">'
                '<p><label>Code <input name="user_code" autofocus></label></p>'
                '<p><label>Login <input name="login" placeholder="a new user"></label></p>'
                '<p><button>Authorize</button> <button name="deny" value="1">Deny</button></p></form>')
        self.page(200, "Device activation", "Enter the code your device shows (fake GitHub).", form)

    def fake_device(self):
        form = self.form()
        wanted = normal_user_code(form.get("user_code", ""))
        login = form.get("login") or None
        if login is not None and not LOGIN.fullmatch(login):
            return self.json(400, {"error": "invalid_login"})
        deny = bool(form.get("deny")) or (login is not None and declines(login))
        now = time.time()
        with lock:
            entry = next((e for e in devices.values() if normal_user_code(e["user_code"]) == wanted
                          and e["status"] == "pending" and e["expires"] > now), None)
            if entry is None:
                answer = None
            elif deny:
                entry["status"] = "denied"
                answer = {"user_code": entry["user_code"], "status": "denied", "login": login,
                          "id": user_id(login) if login else None}
            else:
                login = login or fresh_login()
                grants.add((login, entry["client_id"]))
                entry.update(status="approved", login=login)
                answer = {"user_code": entry["user_code"], "status": "approved", "login": login, "id": user_id(login)}
        if answer is None:
            return self.json(404, {"error": "unknown_user_code"})
        self.json(200, answer)

    def state(self):
        now = time.time()
        with lock:
            value = {
                "users": [{"login": login, "id": uid} for login, uid in sorted(users.items())],
                "tokens": {client_id: [{"login": entry["login"], "id": users[entry["login"]], "scopes": entry["scopes"],
                                        "token_last_eight": token[-8:]}
                                       for token, entry in tokens.items() if entry["client_id"] == client_id]
                           for client_id in CLIENTS},
                "grants": [{"login": login, "id": users[login], "client_id": client_id}
                           for login, client_id in sorted(grants)],
                "device_codes": [{"user_code": entry["user_code"], "client_id": entry["client_id"],
                                  "status": "expired" if entry["status"] == "pending" and entry["expires"] <= now
                                  else entry["status"], "login": entry["login"]}
                                 for entry in devices.values()],
            }
        self.json(200, value)

    def app_token(self, client_id):
        """The live token of client_id the JSON body names, after the app's own credentials; or an error sent."""
        if not self.app_authorized(client_id):
            self.json(401, {"message": "Bad credentials"})
            return None
        body = self.json_body()
        if body is None:
            self.json(400, {"message": "Problems parsing JSON"})
            return None
        sent = body.get("access_token")
        if not isinstance(sent, str) or not sent:
            self.json(422, {"message": "Validation Failed"})
            return None
        return sent

    def reset_token(self, client_id):
        sent = self.app_token(client_id)
        if sent is None:
            return
        with lock:
            entry = tokens.get(sent)
            if entry is None or entry["client_id"] != client_id:
                answer = None
            else:
                del tokens[sent]
                token = mint(entry["login"], client_id, entry["scopes"])
                answer = {"token": token, "token_last_eight": token[-8:], "scopes": entry["scopes"],
                          "user": {"login": entry["login"], "id": user_id(entry["login"])},
                          "app": {"client_id": client_id, "name": client_id}}
        if answer is None:
            return self.json(404, {"message": "Not Found"})
        self.json(200, answer)

    def emails(self):
        scheme, _, token = self.headers.get("Authorization", "").partition(" ")
        with lock:
            entry = tokens.get(token.strip()) if scheme.lower() in ("bearer", "token") else None
        if entry is None:
            return self.json(401, {"message": "Bad credentials"})
        if not EMAIL_SCOPES & set(entry["scopes"]):
            return self.json(404, {"message": "Not Found"})
        self.json(200, [{"email": f"{entry['login']}@users.noreply.fake", "primary": True, "verified": True,
                         "visibility": "private"}])

    def delete_grant(self, client_id):
        sent = self.app_token(client_id)
        if sent is None:
            return
        with lock:
            entry = tokens.get(sent)
            if entry is not None and entry["client_id"] == client_id:
                end_grant(entry["login"], client_id)
        if entry is None or entry["client_id"] != client_id:
            return self.json(404, {"message": "Not Found"})
        self.send(204)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5557
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    # As PID 1 in its container, Python has no default handler for SIGTERM: `docker stop` would wait 10 s for nothing.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    print(f"fake GitHub on http://{host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

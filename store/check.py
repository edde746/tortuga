#!/usr/bin/env python3
"""Checks the store listings in store/: run it after every edit and before an upload.

  python3 store/check.py          every field each store asks for, in every locale: present, within the store's limit,
                                  the listing rules
  python3 store/check.py --urls   also fetches every URL the listings give (support, marketing, privacy, the demo addon)
                                  and checks each of the demo addon's stream files: 200, Accept-Ranges: bytes, the size
                                  its stream gives, and 206 for a range from the file's middle

Layout, so an upload is mechanical:
  apple/ios, apple/tvos     fastlane deliver metadata folders (platform ios, appletvos), one folder per locale. Name,
                            subtitle and privacy URL (each locale's), copyright and categories belong to the one app
                            record, so both folders must agree. No release_notes.txt: App Store Connect takes no What's
                            New for an app's first version.
  apple/age_rating.json     App Store Connect's age rating declaration (deliver's app_rating_config_path)
  apple/app_privacy_details.json  the App Privacy label (fastlane upload_app_privacy_details_to_app_store)
  google-play/metadata      fastlane supply's metadata_path, one folder per locale (changelogs/<versionCode>.txt)
  amazon/<locale>, microsoft/<locale>  one file per Developer Console / Partner Center field. Partner Center's listing
                            CSV keeps IDs from its own export, so its import is filled from these files after a first
                            export. Microsoft takes no What's New on a first submission.
  <store>/forms.json        that store's questionnaires (Play's Data safety, IARC, target audience, ...) and the review
                            notes of Play (app_access), Amazon (testing_instructions) and Microsoft
                            (notes_for_certification), in English: the reviewers read English
  website/static/demo       the demo addon every store's review notes name (usetortuga.app/demo/manifest.json): static
                            JSON, a catalog of public-domain and Blender films by IMDb id, each id's direct HTTPS
                            streams; the manifest's idPrefixes are exactly the catalog's ids, so it is asked for
                            nothing else
Graphics come from tools/brand/generate.sh; tools/brand/verify.sh checks their sizes and alpha.

Languages: the app's (shared/i18n/), each in every store that offers it (LANGUAGES), translated from English with the
app's own words for its features. The URL files and Apple's tvOS privacy text are the same in every locale.

Rules, in every language: never "free" (nor any word for free or no cost in that language, FREE); none of BANNED's
words for peer-to-peer and link services; every description says it works with Stremio addons (WORDING); each store's
name or title field is the name that store's record has (NAMES: "Tortuga" was taken on the App Store and the Microsoft
Store), in every locale; "Stremio" never in a keyword field, and in Apple's subtitle only as its language's "Works with
Stremio addons"; Apple keywords name no other app or trademark, repeat no word of the name and fit 100 bytes of UTF-8.
A field file has no trailing newline: deliver and supply upload its bytes as they are.

Review access: every store's review text signs in with Google as the reviewer account, REVIEWER_EMAIL, which is public.
Its password is never in this repository. Where the store has sign-in fields (Play's sign-in details, App Store
Connect's demo account) the text says the account is in them, and the password goes into them at entry time; Apple's
demo account goes to deliver as options at upload, never as review_information files here. Where the store has only
free text (Amazon's testing instructions, Microsoft's notes for certification) the text holds the token
{reviewer_password}, replaced with the password at entry time. Lengths count the token as the password: its real length
when STORE_REVIEWER_FILE names the reviewer account's JSON file ({"email", "password"}), else 40 characters.
Ends with RESULT: PASS or RESULT: FAIL; values starting "OWNER DECIDES" are listed as open.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

STORE = Path(__file__).resolve().parent
ROOT = STORE.parent
DEMO = ROOT / "website" / "static" / "demo"
DEMO_URL = "https://usetortuga.app/demo/"

# Limits, from each store's documentation (checked 24 Oct 2026):
#   Apple   developer.apple.com/help/app-store-connect/reference/app-information/app-information and
#           .../platform-version-information, developer.apple.com/app-store/product-page/
#   Play    support.google.com/googleplay/android-developer/answer/9859152
#   Amazon  developer.amazon.com/docs/app-submission/appstore-details.html
#   MS      learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msix/add-and-edit-store-listing-info
APPLE_LOCALE = {  # file in a deliver locale folder: (limit, unit); None: no documented limit
    "name.txt": (30, "chars"),
    "subtitle.txt": (30, "chars"),
    "promotional_text.txt": (170, "chars"),
    "description.txt": (4000, "chars"),
    "keywords.txt": (100, "bytes"),
    "support_url.txt": (None, "chars"),
    "marketing_url.txt": (None, "chars"),
    "privacy_url.txt": (None, "chars"),
}
APPLE_TVOS_LOCALE = {"apple_tv_privacy_policy.txt": (None, "chars")}
APPLE_PLATFORM = {  # file in a deliver folder, outside the locales
    "copyright.txt": (None, "chars"),
    "primary_category.txt": (None, "chars"),
    "secondary_category.txt": (None, "chars"),
    "review_information/notes.txt": (4000, "chars"),
}
APPLE_APP_LEVEL = ("name.txt", "subtitle.txt", "privacy_url.txt", "copyright.txt", "primary_category.txt",
                   "secondary_category.txt")
PLAY = {
    "title.txt": (30, "chars"),
    "short_description.txt": (80, "chars"),
    "full_description.txt": (4000, "chars"),
}
PLAY_CHANGELOG = (500, "chars")
PLAY_APP_ACCESS = 500  # characters of the instructions, as Play Console's sign-in details take them
AMAZON_TESTING = 4000  # characters of the testing instructions
# Amazon's User Data Privacy Questionnaire: each data type it asks about (collected, transferred to third parties and
# what for).
AMAZON_DATA_TYPES = ("user_info", "financial_info", "device_or_other_ids", "health_and_fitness", "location",
                     "web_browsing", "photos_or_videos", "audio_files", "app_info_and_performance", "app_activity",
                     "messages", "files_and_docs", "calendar", "contacts")
# The IARC questionnaire's follow-ups Partner Center asks beside the answers Play's content_rating_iarc has.
MICROSOFT_IARC = ("shared_user_generated_content_primary", "public_nudity", "public_real_graphic_violence",
                  "users_can_block_users_or_content", "users_can_report_users_or_content", "chat_moderated",
                  "interactions_limited_to_invited_friends", "cash_rewards_gift_cards_crypto_or_nfts",
                  "news_or_education", "promotes_age_restricted_products")
REVIEWER_EMAIL = "demo@usetortuga.app"
PASSWORD = "{reviewer_password}"
AMAZON = {
    "title.txt": (None, "chars"),
    "short_description.txt": (2000, "bytes"),  # Fire TV shows its first 200 characters
    "long_description.txt": (4000, "chars"),
    "feature_bullets.txt": (None, "chars"),  # 3 to 5 lines
    "keywords.txt": (None, "chars"),
}
MICROSOFT = {
    "title.txt": (None, "chars"),  # one of the reserved names
    "description.txt": (10000, "chars"),
    "short_description.txt": (1000, "chars"),  # some views show its first 270
    "features.txt": (None, "chars"),  # up to 20 lines of up to 200
}

# The listings' languages: the app's (shared/i18n/<tag>.i18n.json), each with the locale code each store's tool
# names its folder by; None: the store has no listing in that language. Checked 25 Oct 2026:
#   Apple   App Store Connect's locales (developer.apple.com/help/app-store-connect/reference/app-store-localizations),
#           as deliver names them (fastlane_core/lib/fastlane_core/languages.rb, ALL_LANGUAGES): all 21. Spanish is
#           es-MX, Spanish (Mexico), not es-ES: the app's es is Latin American Spanish.
#   Play    Play Console's listing languages as supply names them (support.google.com/googleplay/android-developer/
#           answer/9844778, "Add your own translations"): all 21. Spanish is es-419, Spanish (Latin America), not es-ES
#           or es-US.
#   Amazon  the Developer Console's listing localizations (developer.amazon.com/docs/app-submission/
#           distribute-your-app-internationally.html, "Localization options supported by the Appstore"): Dutch,
#           French, German, Italian, Japanese, Polish, Portuguese (Brazil and Portugal), Spanish and Swedish beside
#           English. Codes as the App Submission API writes them (developer.amazon.com/docs/app-submission-api/
#           flows.html, Supported languages: de_DE, es_ES, ...) with a hyphen, as en-US; the API's list lacks Dutch,
#           Polish and Swedish, which take the same language-country form. Spanish is es-ES, Amazon's one Spanish
#           listing (Amazon.mx's and Amazon.es's), which carries the app's Latin American Spanish. No listing in
#           Arabic, Czech, Hindi, Indonesian, Korean, Romanian, Russian, Thai, Turkish, Ukrainian or Vietnamese:
#           Amazon shows those customers the English one.
#   MS      Partner Center offers a listing in each language the package declares (build-msix.ps1's Resources: en-US
#           and i18n's tags; learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msix/
#           add-and-edit-store-listing-info, "Manage Store listing languages"), lowercase as in its list of codes
#           (learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/supported-languages): all 21. Spanish is es,
#           the package's tag.
LANGUAGES = {  # i18n tag: (Apple, Play, Amazon, Microsoft)
    "en": ("en-US", "en-US", "en-US", "en-us"),
    "ar": ("ar-SA", "ar", None, "ar"),
    "cs": ("cs", "cs-CZ", None, "cs"),
    "de": ("de-DE", "de-DE", "de-DE", "de"),
    "es": ("es-MX", "es-419", "es-ES", "es"),
    "fr": ("fr-FR", "fr-FR", "fr-FR", "fr"),
    "hi": ("hi", "hi-IN", None, "hi"),
    "id": ("id", "id", None, "id"),
    "it": ("it", "it-IT", "it-IT", "it"),
    "ja": ("ja", "ja-JP", "ja-JP", "ja"),
    "ko": ("ko", "ko-KR", None, "ko"),
    "nl": ("nl-NL", "nl-NL", "nl-NL", "nl"),
    "pl": ("pl", "pl-PL", "pl-PL", "pl"),
    "pt-BR": ("pt-BR", "pt-BR", "pt-BR", "pt-br"),
    "ro": ("ro", "ro", None, "ro"),
    "ru": ("ru", "ru-RU", None, "ru"),
    "sv": ("sv", "sv-SE", "sv-SE", "sv"),
    "th": ("th", "th", None, "th"),
    "tr": ("tr", "tr-TR", None, "tr"),
    "uk": ("uk", "uk", None, "uk"),
    "vi": ("vi", "vi", None, "vi"),
}
APPLE, PLAY_STORE, AMAZON_STORE, MICROSOFT_STORE = range(4)
# Each store's listing name, the one its record has: the name and title fields of every locale.
NAMES = ("Tortuga Player", "Tortuga", "Tortuga", "Tortuga Player")

BANNED = re.compile(r"\bfree\b|torrent|debrid|magnet|usenet", re.I)  # in every language
# Per language: the words for free or no cost (and "free" as in free software or ad-free, which reads the same), and
# BANNED's peer-to-peer words in its own script. Checked in its fields beside BANNED.
FREE = {
    "ar": r"مجان|بلا مقابل|بدون مقابل|دون مقابل|بدون تكلفة|دون تكلفة|بلا تكلفة|\bحر[ةه]?\b|تورنت|تورينت",
    "cs": r"zdarma|zadarmo|bezplatn|gratis|svobodn",
    "de": r"kostenlos|kostenfrei|gratis|umsonst|gebührenfrei|\w*frei(e[mnrs]?)?\b",
    "es": r"gratis|gratuit|\blibre|sin cost[eo]|sin cargo",
    "fr": r"gratuit|gratis|\blibre|sans frais",
    "hi": r"मुफ़्त|मुफ्त|मुफत|निःशुल्क|नि:शुल्क|निशुल्क|फ्री|फ़्री|बिना शुल्क|बिना किसी शुल्क|टोरेंट|टॉरेंट",
    "id": r"gratis|cuma.?cuma|percuma|\bbebas\b|tanpa biaya",
    "it": r"gratis|gratuit|\bliber[aeio]\b|senza cost|a costo zero",
    "ja": r"無料|無償|タダ|フリー|0円|０円|トレント|マグネット",
    "ko": r"무료|공짜|무상|프리|토렌트|마그넷",
    "nl": r"gratis|kosteloos|zonder kosten|voor niets|\w*vrije?\b",
    "pl": r"darmow|za darmo|bezpłatn|nieodpłatn|gratis|\bwoln\w*",
    "pt-BR": r"grátis|gratis|gratuit|de graça|sem custo|\blivre",
    "ro": r"gratuit|gratis|fără cost|fără plată|\bliber\w*",
    "ru": r"бесплатн|даром|безвозмездн|свободн|торрент|магнет",
    "sv": r"gratis|utan kostnad|\w*(?<!val)fri(a|tt)?\b",  # valfri: optional
    "th": r"ฟรี|ไม่มีค่าใช้จ่าย|ไม่เสียค่าใช้จ่าย|ไม่เสียเงิน|ไม่มีค่าบริการ|ไม่ต้องจ่าย|ทอร์เรนต์|ทอเรนต์",
    "tr": r"ücretsiz|bedava|parasız|bedelsiz|masrafsız|karşılıksız|özgür",
    "uk": r"безкоштовн|безплатн|задарма|даром|вільн|торент|торрент|магніт",
    "vi": r"miễn phí|không mất phí|không tốn phí|không mất tiền|không tốn tiền|tự do",
}
# Per language: Apple's subtitle, "Works with Stremio addons" (the one field besides the descriptions that may name
# Stremio), and how every description says it (a regex, any case).
WORDING = {
    "en": ("Works with Stremio addons", r"works with Stremio addons"),
    "ar": ("يعمل مع إضافات Stremio", r"يعمل مع إضافات Stremio"),
    "cs": ("Funguje s doplňky Stremio", r"Funguje s doplňky Stremio"),
    "de": ("Kompatibel mit Stremio-Add-ons", r"kompatibel mit Stremio-Add-ons|mit Stremio-Add-ons kompatib(el|le)"),
    "es": ("Admite complementos de Stremio", r"funciona con complementos de Stremio"),
    "fr": ("Compatible add-ons Stremio", r"compatible avec les add-ons Stremio"),
    "hi": ("Stremio ऐडऑन के अनुकूल", r"Stremio ऐडऑन के अनुकूल"),
    "id": ("Bekerja dengan add-on Stremio", r"Bekerja dengan add-on Stremio"),
    "it": ("Compatibile con add-on Stremio", r"compatibile con gli add-on Stremio"),
    "ja": ("Stremioアドオンに対応", r"Stremioアドオンに対応"),
    "ko": ("Stremio 애드온 지원", r"Stremio 애드온(을)? 지원"),
    "nl": ("Werkt met Stremio-add-ons", r"Werkt met Stremio-add-ons"),
    "pl": ("Działa z dodatkami Stremio", r"Działa z dodatkami Stremio"),
    "pt-BR": ("Funciona com addons do Stremio", r"Funciona com addons do Stremio"),
    "ro": ("Compatibil cu extensii Stremio", r"Compatibil cu extensii Stremio"),
    "ru": ("Поддержка дополнений Stremio", r"поддержк(а|ой) дополнений Stremio"),
    "sv": ("Fungerar med Stremio-tillägg", r"Fungerar med Stremio-tillägg"),
    "th": ("ทำงานร่วมกับส่วนเสริม Stremio", r"ทำงานร่วมกับส่วนเสริม Stremio"),
    "tr": ("Stremio eklentileriyle çalışır", r"Stremio eklentileriyle çalış(ır|an)"),
    "uk": ("Працює з додатками Stremio", r"Працює з додатками Stremio"),
    "vi": ("Hoạt động với tiện ích Stremio", r"Hoạt động với tiện ích Stremio"),
}
# Other apps and trademarks Apple keywords must not name (App Store Review Guidelines 2.3.7).
TRADEMARKS = re.compile(r"stremio|trakt|simkl|mdblist|myanimelist|anilist|vlc|infuse|kodi|plex|mpv|iina|netflix|"
                        r"youtube|dolby|dts|blender|apple|android|google|amazon|fire ?tv", re.I)
# An Apple keyword's shortest useful length: two characters make a word in Japanese and Korean (字幕, 영화).
MIN_TERM = {"ja": 2, "ko": 2}


class Report:
    def __init__(self) -> None:
        self.failures = 0
        self.fields = 0
        self.locales: dict[str, int] = {}
        self.urls: dict[str, str] = {}
        self.streams: list[tuple[str, int, str]] = []  # the demo addon's (url, size, label)
        self.owner: list[str] = []

    def line(self, ok: bool, label: str, detail: str) -> None:
        print(f"{'ok  ' if ok else 'FAIL'} {label}: {detail}")
        self.failures += not ok


def locales(store: int) -> list[tuple[str, str]]:
    """(i18n tag, locale code) of every language the store has a listing in, English first."""
    return [(lang, codes[store]) for lang, codes in LANGUAGES.items() if codes[store]]


def locale_folders(r: Report, label: str, base: Path, store: int, others: tuple[str, ...] = ()) -> None:
    """The store's folder holds a folder for each of its locales and nothing else (but `others`)."""
    want = {code for _, code in locales(store)}
    found = {p.name for p in base.iterdir() if p.is_dir() and p.name not in others}
    r.locales[label] = len(found & want)
    r.line(found == want, label, f"{len(want)} locales" if found == want else
           f"missing {sorted(want - found)}, unexpected {sorted(found - want)}")


def read_field(r: Report, path: Path, limit: int | None, unit: str, lang: str = "en") -> str | None:
    label = str(path.relative_to(ROOT))
    if not path.is_file():
        r.line(False, label, "missing")
        return None
    text = path.read_text(encoding="utf-8")
    r.fields += 1
    if text != text.strip():
        r.line(False, label, "leading or trailing whitespace (an editor's final newline?)")
    size = len(text.encode()) if unit == "bytes" else len(text)
    r.line(bool(text.strip()) and (limit is None or size <= limit), label, f"{size}/{limit or '-'} {unit}")
    for banned in (BANNED, re.compile(FREE[lang], re.I) if lang in FREE else None):
        if banned and banned.search(text):
            r.line(False, label, f"banned word: {banned.search(text).group(0)!r}")
    if path.name.endswith("url.txt"):
        r.line(text.startswith("https://"), label, "an https URL" if text.startswith("https://") else "not an https URL")
        r.urls.setdefault(text, label)
    for url in re.findall(r"https://[^\s)]+[^\s).,]", text) if "review" in label or "notes" in label else ():
        r.urls[url] = label
    return text


def no_stremio(r: Report, label: str, text: str | None) -> None:
    if text is not None and re.search("stremio", text, re.I):
        r.line(False, label, "names Stremio")


def store_name(r: Report, label: str, text: str | None, store: int) -> None:
    if text is not None:
        r.line(text == NAMES[store], label, f"the store's name {NAMES[store]!r}" if text == NAMES[store]
               else f"{text!r}, not the store's name {NAMES[store]!r}")


def has_phrase(r: Report, label: str, text: str | None, lang: str) -> None:
    if text is not None and lang not in WORDING:
        r.line(False, label, f"no WORDING for {lang}")
    elif text is not None:
        said = re.search(WORDING[lang][1], text, re.I)
        r.line(bool(said), label, f"says {said.group(0)!r}" if said else f"does not say {WORDING[lang][1]!r}")


def apple(r: Report) -> None:
    folders = {}
    for platform in ("ios", "tvos"):
        base = STORE / "apple" / platform
        locale_folders(r, f"apple/{platform}", base, APPLE, ("review_information",))
        values = {rel: read_field(r, base / rel, *spec) for rel, spec in APPLE_PLATFORM.items()}
        for lang, code in locales(APPLE):
            label = f"apple/{platform}/{code}"
            fields = {**APPLE_LOCALE, **(APPLE_TVOS_LOCALE if platform == "tvos" else {})}
            got = {rel: read_field(r, base / code / rel, *spec, lang) for rel, spec in fields.items()}
            values |= {f"{code}/{rel}": text for rel, text in got.items()}
            store_name(r, f"{label} name", got["name.txt"], APPLE)
            subtitle, wording = got["subtitle.txt"], WORDING[lang][0] if lang in WORDING else ""
            if subtitle is not None:
                no_stremio(r, f"{label} subtitle (but in {wording!r})", re.sub(re.escape(wording), "", subtitle, flags=re.I))
            has_phrase(r, f"{label} description", got["description.txt"], lang)
            keywords = got["keywords.txt"]
            if keywords is not None:
                terms = keywords.split(",")
                bad = [t for t in terms if t != t.strip() or len(t) < MIN_TERM.get(lang, 3) or TRADEMARKS.search(t)
                       or any(w.lower() in t.lower() for w in NAMES[APPLE].split())]
                r.line(not bad, f"{label} keywords", f"{len(terms)} terms" + (f", bad: {bad}" if bad else ""))
            if (base / code / "release_notes.txt").exists():
                r.line(False, label, "release_notes.txt: the first version takes no What's New")
        for demo in ("demo_user.txt", "demo_password.txt"):
            if (base / "review_information" / demo).exists():
                r.line(False, f"apple/{platform}/review_information/{demo}", "the demo account goes to deliver at upload")
        if values["review_information/notes.txt"] is not None:
            review_notes(r, f"apple/{platform} review notes", values["review_information/notes.txt"], fields=True)
        folders[platform] = values
    shared = [rel for rel in APPLE_APP_LEVEL if rel in APPLE_PLATFORM] + [
        f"{code}/{rel}" for _, code in locales(APPLE) for rel in APPLE_APP_LEVEL if rel in APPLE_LOCALE]
    differ = [rel for rel in shared if folders["ios"][rel] != folders["tvos"][rel]]
    r.line(not differ, "apple app record", f"{len(shared)} fields the same in ios and tvos" if not differ
           else f"differ between ios and tvos (one app record): {differ}")
    for name in ("age_rating.json", "app_privacy_details.json"):
        json_file(r, STORE / "apple" / name)


def play(r: Report, build: str) -> None:
    metadata = STORE / "google-play" / "metadata"
    locale_folders(r, "google-play", metadata, PLAY_STORE)
    for lang, code in locales(PLAY_STORE):
        base = metadata / code
        values = {rel: read_field(r, base / rel, *spec, lang) for rel, spec in PLAY.items()}
        store_name(r, f"google-play/{code} title", values["title.txt"], PLAY_STORE)
        has_phrase(r, f"google-play/{code} full description", values["full_description.txt"], lang)
        read_field(r, base / "changelogs" / f"{build}.txt", *PLAY_CHANGELOG, lang)
    forms = json_file(r, STORE / "google-play" / "forms.json")
    if forms:
        access, label = forms["app_access"], "google-play/forms.json app_access"
        r.line(bool(access.get("name")), f"{label} name", repr(access.get("name")) if access.get("name")
               else "missing: Play's sign-in details need the instruction set's name")
        r.line(access.get("username") == REVIEWER_EMAIL, f"{label} username", repr(access.get("username")))
        r.line(access.get("password") == PASSWORD, f"{label} password", f"{access.get('password')!r}, entered as the "
               "reviewer account's" if access.get("password") == PASSWORD else f"not {PASSWORD}")
        review_notes(r, f"{label} instructions", access["instructions"], PLAY_APP_ACCESS, fields=True)


def amazon(r: Report) -> None:
    locale_folders(r, "amazon", STORE / "amazon", AMAZON_STORE, ("images",))
    for lang, code in locales(AMAZON_STORE):
        base, label = STORE / "amazon" / code, f"amazon/{code}"
        values = {rel: read_field(r, base / rel, *spec, lang) for rel, spec in AMAZON.items()}
        store_name(r, f"{label} title", values["title.txt"], AMAZON_STORE)
        no_stremio(r, f"{label} keywords", values["keywords.txt"])
        has_phrase(r, f"{label} long description", values["long_description.txt"], lang)
        short = values["short_description.txt"]
        if short is not None:
            r.line("\n" not in short, f"{label} short description", "one paragraph" if "\n" not in short else "has line breaks")
            has_phrase(r, f"{label} short description (Fire TV shows 200)", short[:200], lang)
        bullets = values["feature_bullets.txt"]
        if bullets is not None:
            count = len(bullets.splitlines())
            r.line(3 <= count <= 5, f"{label} feature bullets", f"{count} lines (3 to 5)")
    forms = json_file(r, STORE / "amazon" / "forms.json")
    if forms:
        review_notes(r, "amazon/forms.json testing_instructions", forms["testing_instructions"], AMAZON_TESTING)
        support = forms.get("language_support", {})
        app = app_languages()
        r.line(set(support) == app and all(support.values()), "amazon/forms.json language_support",
               f"the app's {len(app)} languages" if set(support) == app and all(support.values())
               else f"missing {sorted(app - set(support))}, not the app's {sorted(set(support) - app)}")
        privacy = forms.get("user_data_privacy", {})
        for kind in AMAZON_DATA_TYPES:
            answer = privacy.get(kind, {})
            ok = (isinstance(answer.get("collected"), bool) and isinstance(answer.get("transferred_to_third_parties"), bool)
                  and bool(answer.get("purposes")) == (answer["collected"] or answer["transferred_to_third_parties"]))
            r.line(ok, f"amazon/forms.json user_data_privacy {kind}", "collected, transferred and purposes answered"
                   if ok else f"needs collected and transferred_to_third_parties, and purposes when either: {answer}")
        unknown = sorted(set(privacy) - set(AMAZON_DATA_TYPES) - {"_about"})
        if unknown:
            r.line(False, "amazon/forms.json user_data_privacy", f"not Amazon's data types: {unknown}")
        partial = forms.get("data_deletion", {}).get("only_data_deletion_from_the_account")
        r.line(isinstance(partial, bool), "amazon/forms.json data_deletion only_data_deletion_from_the_account",
               repr(partial))


def microsoft(r: Report) -> None:
    locale_folders(r, "microsoft", STORE / "microsoft", MICROSOFT_STORE, ("images",))
    for lang, code in locales(MICROSOFT_STORE):
        base, label = STORE / "microsoft" / code, f"microsoft/{code}"
        values = {rel: read_field(r, base / rel, *spec, lang) for rel, spec in MICROSOFT.items()}
        store_name(r, f"{label} title", values["title.txt"], MICROSOFT_STORE)
        description = values["description.txt"]
        has_phrase(r, f"{label} description", description, lang)
        if description is not None:
            r.line("http" not in description, f"{label} description", "no URL (Partner Center's guidance)"
                   if "http" not in description else "has a URL")
        short = values["short_description.txt"]
        if short is not None and len(short) > 270:
            print(f"note {label} short description: {len(short)} chars, some views show 270")
        features = values["features.txt"]
        if features is not None:
            lines = features.splitlines()
            long = [line for line in lines if len(line) > 200]
            r.line(len(lines) <= 20 and not long, f"{label} features", f"{len(lines)}/20 lines, longest {max(map(len, lines))}/200")
        if (base / "whats_new.txt").exists():
            r.line(False, label, "whats_new.txt: a first submission leaves What's new blank")
    forms = json_file(r, STORE / "microsoft" / "forms.json")
    if forms:
        review_notes(r, "microsoft/forms.json notes_for_certification", forms["notes_for_certification"])
        rating = forms.get("age_rating", {})
        missing = [key for key in MICROSOFT_IARC if not isinstance(rating.get(key), bool)]
        r.line(not missing, "microsoft/forms.json age_rating", f"the {len(MICROSOFT_IARC)} IARC follow-ups answered"
               if not missing else f"unanswered: {missing}")


def app_languages() -> set[str]:
    return {p.name.removesuffix(".i18n.json") for p in (ROOT / "shared" / "i18n").glob("*.i18n.json")}


def languages(r: Report) -> None:
    """Every language the app has, and no other, has its row in LANGUAGES, FREE and WORDING."""
    app = app_languages()
    rows = {"LANGUAGES": set(LANGUAGES), "FREE": set(FREE) | {"en"}, "WORDING": set(WORDING)}
    for table, have in rows.items():
        r.line(have == app, f"store/check.py {table}", f"the app's {len(app)} languages" if have == app else
               f"missing {sorted(app - have)}, not the app's {sorted(have - app)}")


def password_length() -> tuple[int, str]:
    """The reviewer account's password length, from STORE_REVIEWER_FILE, else the 40-character worst case."""
    path = os.environ.get("STORE_REVIEWER_FILE")
    if not path:
        return 40, "the 40-character worst case"
    return len(json.loads(Path(path).read_text(encoding="utf-8"))["password"]), "its real length"


def review_notes(r: Report, label: str, text: str, limit: int | None = None, fields: bool = False) -> None:
    """A review text: the demo addon, the reviewer account, and its password only where the store has no sign-in
    fields (`fields`), as the token; `limit` in characters with the token counted as the password."""
    for url in re.findall(r"https://[^\s)]+[^\s).,]", text):
        r.urls[url] = label
    r.line(f"{DEMO_URL}manifest.json" in text, label, f"names {DEMO_URL}manifest.json")
    r.line(REVIEWER_EMAIL in text, label, f"names the reviewer account {REVIEWER_EMAIL}")
    tokens = set(re.findall(r"\{\w+\}", text))
    want = set() if fields else {PASSWORD}
    r.line(tokens == want, label, (f"the password as {PASSWORD}" if want else "no password: the sign-in fields hold it")
           if tokens == want else f"tokens {sorted(tokens)}, wants {sorted(want)}")
    if limit is not None:
        length, source = password_length()
        size = len(text.replace(PASSWORD, "x" * length))
        r.line(size <= limit, label, f"{size}/{limit} chars, the password at {source}")


def demo(r: Report) -> None:
    """The demo addon's files agree with each other: every catalog id has a stream file, and nothing else does."""
    manifest = json_file(r, DEMO / "manifest.json")
    if not manifest:
        return
    ids = []
    for catalog in manifest["catalogs"]:
        data = json_file(r, DEMO / "catalog" / catalog["type"] / f"{catalog['id']}.json")
        ids += [(meta["type"], meta["id"]) for meta in (data or {}).get("metas", [])]
    same = sorted(manifest["idPrefixes"]) == sorted(i for _, i in ids)
    r.line(same, "website/static/demo/manifest.json idPrefixes", "the catalog's ids" if same else
           f"{manifest['idPrefixes']} but the catalog lists {[i for _, i in ids]}")
    for kind, item in ids:
        data = json_file(r, DEMO / "stream" / kind / f"{item}.json")
        for stream in (data or {}).get("streams", []):
            url, size = stream.get("url", ""), stream.get("behaviorHints", {}).get("videoSize")
            ok = url.startswith("https://") and isinstance(size, int) and set(stream) <= {"url", "name", "description", "behaviorHints"}
            r.line(ok, f"demo stream {kind}/{item}", f"{url} {size}" if ok else f"not a direct https url with its videoSize: {stream}")
            r.streams.append((url, size, f"{kind}/{item}"))
    files = {(p.parent.name, p.stem) for p in (DEMO / "stream").glob("*/*.json")}
    r.line(files == set(ids), "website/static/demo/stream", f"{len(files)} files, one per catalog id" if files == set(ids)
           else f"files without a catalog id: {sorted(files - set(ids))}, ids without a file: {sorted(set(ids) - files)}")


def json_file(r: Report, path: Path):
    label = str(path.relative_to(ROOT))
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        r.line(False, label, f"unreadable: {error}")
        return None
    r.line(True, label, "parses")
    text = json.dumps(data)
    if BANNED.search(text):
        r.line(False, label, f"banned word: {BANNED.search(text).group(0)!r}")
    for value in re.findall(r'"([^"]*OWNER DECIDES[^"]*)"', text):
        r.owner.append(f"{label}: {value}")
    return data


def fetch_streams(r: Report) -> None:
    """Each demo stream's file: a 1 KiB range from its middle answered 206 (players seek with ranges), then 200 with
    Accept-Ranges: bytes and the stream's videoSize. Redirects are followed, as the players do."""
    for url, size, label in r.streams:
        try:
            middle = size // 2
            ranged = urllib.request.Request(url, headers={"User-Agent": "tortuga-store-check", "Range": f"bytes={middle}-{middle + 1023}"})
            with urllib.request.urlopen(ranged, timeout=30) as response:
                # At most one byte past the range: a server that ignores the range answers 200 with the whole file.
                partial, got, content_range = response.status, len(response.read(1025)), response.headers.get("Content-Range")
            head = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "tortuga-store-check"})
            with urllib.request.urlopen(head, timeout=30) as response:
                status, ranges = response.status, response.headers.get("Accept-Ranges")
                length, host = int(response.headers.get("Content-Length") or -1), urllib.parse.urlsplit(response.url).netloc
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            r.line(False, f"demo stream {label}", f"{url}: {getattr(error, 'code', None) or getattr(error, 'reason', error)}")
            continue
        ok = (status, ranges, length, partial, got, content_range) == (
            200, "bytes", size, 206, 1024, f"bytes {middle}-{middle + 1023}/{size}")
        r.line(ok, f"demo stream {label}", f"{url}: {status}, Accept-Ranges {ranges}, {length} bytes (want {size}); "
               f"range at {middle}: {partial}, {got} bytes, {content_range}; served by {host}")


def fetch_urls(r: Report) -> None:
    for url, where in sorted(r.urls.items()):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "tortuga-store-check"})
            with urllib.request.urlopen(request, timeout=15) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        except (urllib.error.URLError, TimeoutError) as error:
            status = f"unreachable ({getattr(error, 'reason', error)})"
        r.line(status == 200, f"url {url}", f"{status} ({where})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--urls", action="store_true", help="also fetch every URL the listings give")
    args = parser.parse_args()
    version = dict(re.findall(r"^(\w+) = (\S+)$", (ROOT / "version.xcconfig").read_text(), re.M))
    r = Report()
    languages(r)
    apple(r)
    play(r, version["CURRENT_PROJECT_VERSION"])
    amazon(r)
    microsoft(r)
    demo(r)
    if args.urls:
        fetch_urls(r)
        fetch_streams(r)
    for item in r.owner:
        print(f"open {item}")
    print("locales: " + ", ".join(f"{store} {count}" for store, count in r.locales.items()))
    print(f"RESULT: {'FAIL' if r.failures else 'PASS'} {r.fields} fields, {r.failures} failures, "
          f"{len(r.owner)} owner decisions open")
    return 1 if r.failures else 0


if __name__ == "__main__":
    sys.exit(main())

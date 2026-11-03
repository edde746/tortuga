import Foundation
import OSLog
import TortugaKit

#if !os(macOS)
import UIKit
#endif

/// The shared core's `PlayerAppLauncher` on Apple platforms (`AppleAppPlatform.playerApps`, one per process): the
/// player apps Settings → Player and "Play with…" offer on this device, and handing a play to one.
///
/// - iPhone and iPad: VLC, Infuse and SenPlayer; Apple TV: VLC and Infuse. Each is opened through the URL scheme it
///   documents (`SchemePlayer`), and nothing comes back. `canOpenURL` finds them: their schemes are in the
///   Info.plist's `LSApplicationQueriesSchemes`. Custom players are URL templates, opened as they are, without asking
///   `canOpenURL` first: it answers false for any scheme the Info.plist doesn't declare. An `http` template opens
///   Safari. Other apps can't read this app's files, so downloads stay in Tortuga.
/// - Mac: IINA, VLC, mpv and Infuse (`MacPlayerApps`); custom players are programs.
@MainActor
final class ApplePlayerApps: @preconcurrency PlayerAppLauncher {
    #if os(macOS)
    let customPlayers: CustomPlayerKind? = .command
    let handsOffFiles = true
    #else
    let customPlayers: CustomPlayerKind? = .urlTemplate
    let handsOffFiles = false
    #endif

    func detect(done: any PlayerAppsFound) {
        #if os(macOS)
        done.onFound(players: MacPlayerApps.detect())
        #else
        let found = SchemePlayer.known.filter { player in
            URL(string: player.scheme + "://").map { UIApplication.shared.canOpenURL($0) } ?? false
        }
        done.onFound(players: found.map(\.app))
        #endif
    }

    /// Starts `app` (SKIE exposes the Kotlin `suspend fun launch` as this async requirement).
    nonisolated func __launch(app: PlayerApp, media: PlayerAppMedia, events: any PlayerAppEvents) async throws -> any PlayerAppLaunch {
        await start(UncheckedBox(Handoff(app: app, media: media, events: events))).value
    }

    private func start(_ handoff: UncheckedBox<Handoff>) async -> UncheckedBox<any PlayerAppLaunch> {
        let handoff = handoff.value
        #if os(macOS)
        return UncheckedBox(await MacPlayerApps.launch(app: handoff.app, media: handoff.media, events: handoff.events))
        #else
        // An app opened by its URL reports nothing: the events stay unused.
        return UncheckedBox(await open(app: handoff.app, media: handoff.media))
        #endif
    }

    #if !os(macOS)
    /// Opens `app`'s URL for `media`. When nothing opens it, a known player isn't installed any more, and a custom
    /// template's scheme has no app or its URL isn't valid.
    private func open(app: PlayerApp, media: PlayerAppMedia) async -> any PlayerAppLaunch {
        let url: URL?
        let failure: PlayerAppFailure
        if let custom = app.custom {
            url = URL(string: PlayerAppTemplates.shared.url(template: custom.command, media: media))
            failure = .other
        } else if let player = SchemePlayer.known.first(where: { $0.id == app.id }) {
            url = player.url(for: media)
            failure = .notInstalled
        } else {
            return PlayerAppLaunchFailed(failure: .notInstalled)
        }
        guard let url, await UIApplication.shared.open(url) else {
            Logger(subsystem: "com.edde746.tortuga", category: "player-apps")
                .notice("\(app.id, privacy: .public) didn't open")
            return PlayerAppLaunchFailed(failure: failure)
        }
        return PlayerAppLaunchLaunched(progress: .none)
    }
    #endif
}

/// What one `launch` was handed, carried to the main actor.
private struct Handoff {
    let app: PlayerApp
    let media: PlayerAppMedia
    let events: any PlayerAppEvents
}

/// A player app that plays a stream through the URL scheme it documents. Every value in the URL is percent-encoded,
/// as the x-callback-url specification asks; a player that takes one subtitle gets the first.
///
/// - VLC: `vlc-x-callback://x-callback-url/stream?url=…` (VideoLAN's wiki, Documentation:iOS, "x-callback-url",
///   on iPhone, iPad and Apple TV); `sub` is read by VLC's own handler (vlc-ios `URLHandler.swift`, `parseURL`).
///   The `stream` action plays at once, where a `vlc://` link asks to download or stream first.
/// - Infuse: `infuse://x-callback-url/play?url=…&position=…&filename=…&sub=…` (Firecore's "API for Third-Party
///   Apps & Services", Infuse 8.4.7 or later on iPhone, iPad, Apple TV and the Mac); the position in whole seconds.
/// - SenPlayer: `senplayer://x-callback-url/play?url=…&position=…&sub=…&name=…&User-Agent=…` (SenPlayer's "URL
///   Schemes" document, position from 6.1.0, the others from 5.9.0); iPhone and iPad.
///
/// Outplayer and nPlayer aren't offered: neither documents how its scheme plays a URL.
struct SchemePlayer {
    let id: String
    let name: String
    /// What `canOpenURL` asks about; declared in the Info.plist's `LSApplicationQueriesSchemes`.
    let scheme: String
    let capabilities: PlayerAppCapabilities
    /// The URL that plays, before its query.
    let action: String
    /// The query's parameters for a hand-off, unencoded.
    let parameters: (PlayerAppMedia) -> [(String, String)]

    var app: PlayerApp {
        PlayerApp(id: id, name: name, kind: .detected, capabilities: capabilities, custom: nil)
    }

    func url(for media: PlayerAppMedia) -> URL? {
        let query = parameters(media)
            .map { name, value in name + "=" + PlayerAppTemplates.shared.percentEncode(text: value) }
            .joined(separator: "&")
        return URL(string: action + "?" + query)
    }

    #if !os(macOS)
    /// This device's players, in the order Settings lists them.
    static var known: [SchemePlayer] {
        #if os(tvOS)
        [vlc, infuse]
        #else
        [vlc, infuse, senPlayer]
        #endif
    }
    #endif

    static var vlc: SchemePlayer {
        SchemePlayer(
            id: "vlc", name: "VLC", scheme: "vlc-x-callback",
            capabilities: PlayerAppCapabilities(startPosition: false, subtitles: true, progress: .none),
            action: "vlc-x-callback://x-callback-url/stream"
        ) { media in
            [("url", media.url)] + subtitle(media)
        }
    }

    static var infuse: SchemePlayer {
        SchemePlayer(
            id: "infuse", name: "Infuse", scheme: "infuse",
            capabilities: PlayerAppCapabilities(startPosition: true, subtitles: true, progress: .none),
            action: "infuse://x-callback-url/play"
        ) { media in
            [("url", media.url)] + position(media) + (media.filename.map { [("filename", $0)] } ?? []) + subtitle(media)
        }
    }

    static var senPlayer: SchemePlayer {
        SchemePlayer(
            id: "senplayer", name: "SenPlayer", scheme: "senplayer",
            capabilities: PlayerAppCapabilities(startPosition: true, subtitles: true, progress: .none),
            action: "senplayer://x-callback-url/play"
        ) { media in
            // The file's name, as SenPlayer reads the format from its extension; the only header it takes is the
            // User-Agent.
            let userAgent = media.headers.first { $0.key.caseInsensitiveCompare("User-Agent") == .orderedSame }?.value
            return [("url", media.url)] + position(media) + subtitle(media)
                + (media.filename.map { [("name", $0)] } ?? []) + (userAgent.map { [("User-Agent", $0)] } ?? [])
        }
    }

    /// The start in whole seconds, when past the first.
    private static func position(_ media: PlayerAppMedia) -> [(String, String)] {
        guard media.startSeconds.isFinite, media.startSeconds >= 1 else { return [] }
        return [("position", String(Int(media.startSeconds)))]
    }

    /// The one subtitle these apps take: the one the built-in player would show, else the first.
    private static func subtitle(_ media: PlayerAppMedia) -> [(String, String)] {
        (media.subtitles.first(where: { $0.selected }) ?? media.subtitles.first).map { [("sub", $0.url)] } ?? []
    }
}

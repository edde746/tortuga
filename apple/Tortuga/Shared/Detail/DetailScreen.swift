import SwiftUI
import TortugaKit

/// The Detail route of `app`: its `DetailStore` is kept by the navigator while the route is on the stack (under
/// "detail", as in Compose), so coming back from the streams or the player finds the page as it was, on the same
/// season. Popped, it draws what it showed while it slides out (`LastDrawn`).
struct DetailRoute: View {
    let app: AppModel
    let route: RouteDetail

    @State private var drawn = LastDrawn<(model: RetainedStore<DetailStore, DetailUiState>, intents: DetailIntents)>()

    var body: some View {
        if let page = drawn(retained().flatMap { model in intents(model.store).map { (model, $0) } }) {
            let model = page.model
            let stack = app.stacks.state.stackOf(route: route) ?? []
            let index = stack.lastIndex { ($0 as AnyObject).isEqual(route) }
            // Nothing over it: no Streams sheet, no player (tvOS gives the first focus only then).
            let isTop = index == stack.count - 1
            // The player over it, or the next episode's Streams sheet over that player.
            let underPlayer = index.map { at in stack[(at + 1)...].contains { $0 is RoutePlayer } } ?? false
            // Its destination's stack is the one that shows.
            let shown = app.backStack.contains { ($0 as AnyObject).isEqual(route) }
            DetailScreen(state: model.observer.state, intents: page.intents, isTop: isTop, underPlayer: underPlayer, shown: shown)
                .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<DetailStore, DetailUiState>? {
        let session = app.session
        let route = route
        return app.retained(
            route,
            key: "detail",
            create: { session.detailStore(route: route) },
            state: { $0.state },
            release: { $0.release() }
        )
    }

    /// The page's intents, made once for its store and kept beside it: a view given the same intents as before can be
    /// compared equal and left alone.
    private func intents(_ store: DetailStore) -> DetailIntents? {
        app.navigator.retain(route: route, key: "intents", create: { DetailIntents(store) }, release: { _ in }) as? DetailIntents
    }
}

/// What the Detail screen sends its store.
final class DetailIntents {
    let play: () -> Void
    let chooseStream: () -> Void
    let selectSeason: (Int32) -> Void
    let openEpisode: (String) -> Void
    let openLink: (String) -> Void
    let openTrailer: (String) -> Void
    let addToLibrary: () -> Void
    let removeFromLibrary: () -> Void
    let markWatched: (Bool) -> Void
    /// Carries out an entry of a season's chip's menu (`DetailSeason.menu`).
    let seasonMenuAction: (Int32, MediaAction) -> Void
    let episodeMenuAction: (String, MediaAction) -> Void
    let reload: () -> Void
    let pressDownload: () -> Void
    let downloadAction: (String) -> Void
    let dismissDownloadNotice: () -> Void
    let confirmDownloadDelete: () -> Void
    let dismissDownloadDelete: () -> Void
    let answerPlayerApp: (_ finished: Bool) -> Void
    let episodeShown: () -> Void
    /// Off Apple TV, lists a long season's range at that index (`DetailUiState.listedRange`).
    let pickRange: (Int) -> Void
    /// Rates the title on the profile's trackers (1–10), or with nil removes the rating (`DetailUiState.userRating`).
    let rate: (Int?) -> Void
    /// Opens a "More like this" title on a Detail page of its own (`DetailUiState.related`).
    let openRelated: (MetaCard) -> Void
    let relatedMenuAction: (MetaCard, MediaAction) -> Void
    /// The blocked page's "Ask a parent" (`DetailBody.Blocked`): a parent's PIN, then the page loads.
    let askParent: () -> Void
    /// "Block on this profile" (`DetailAction.kidsBlock`): a parent's PIN, then the title is blocked on the kids profile.
    let blockOnProfile: () -> Void
    /// Watchlist (`DetailAction.watchlist`): with one tracker puts the title on its list or takes it off; with several
    /// opens the app's tracker watchlists sheet (`AppSessionState.watchlist`).
    let watchlist: () -> Void

    init(_ store: DetailStore) {
        play = { store.play() }
        chooseStream = { store.chooseStream() }
        selectSeason = { store.selectSeason(season: $0) }
        openEpisode = { store.openEpisode(videoId: $0) }
        openLink = { store.openLink(id: $0) }
        openTrailer = { store.openTrailer(id: $0) }
        addToLibrary = { store.addToLibrary() }
        removeFromLibrary = { store.removeFromLibrary() }
        markWatched = { store.markWatched(watched: $0) }
        seasonMenuAction = { store.seasonMenuAction(season: $0, action: $1) }
        episodeMenuAction = { store.episodeMenuAction(videoId: $0, action: $1) }
        reload = { store.reload() }
        pressDownload = { store.pressDownload() }
        downloadAction = { store.downloadAction(id: $0) }
        dismissDownloadNotice = { store.dismissDownloadNotice() }
        confirmDownloadDelete = { store.confirmDownloadDelete() }
        dismissDownloadDelete = { store.dismissDownloadDelete() }
        answerPlayerApp = { store.answerPlayerApp(finished: $0) }
        episodeShown = { store.episodeShown() }
        pickRange = { store.pickRange(index: Int32($0)) }
        rate = { store.rate(score: $0.map { KotlinInt(int: Int32($0)) }) }
        openRelated = { store.openRelated(card: $0) }
        relatedMenuAction = { store.relatedMenuAction(card: $0, action: $1) }
        askParent = { store.askParent() }
        blockOnProfile = { store.blockOnProfile() }
        watchlist = { store.watchlist() }
    }
}

/// One title, laid out like the Apple TV app's pages: the backdrop full-bleed (across the top
/// of a phone, fading into the page; behind the whole window on wide windows; under iPad's and the Mac's sidebar to the
/// window's leading edge, dimmed there, the sidebar drawn over it), the title's logo (else its name), one metadata line,
/// the ratings row (each source's logo and score, plain on the page, then the user's own score once rated:
/// `DetailUserRatingScore`), one row of actions (Play/Resume with the watched part filled inside it, then round buttons
/// for choosing the stream when auto-play is on, the library, the watched mark, the download and the trailers; Rate,
/// opening the rating picker, behind ⋯, on Apple TV a round button), the
/// description (three lines, then More) and the title's information, its genres, cast, directors and writers as text
/// links. Phones centre the title, the metadata line, the ratings and the actions. A series adds its season chips
/// (`DetailSeasonChips`) and the season's episodes as free-flowing rows; wide windows put those in a column beside the
/// details. The poster shows only when there is no backdrop. Apple TV has its own page of sections, as Android TV's
/// (`DetailTvPage`).
///
/// Every text comes from `DetailUiState`. An episode (Select, and on Apple TV the remote's Play/Pause too) plays
/// auto-play's pick or opens its streams; its menu (a long press, a right click, a long Select on the remote, the ⋯ on
/// iOS and macOS) opens its streams whatever auto-play would pick (with auto-play on), marks it or its season watched,
/// manages its download and opens its trailers.
/// Share (`DetailUiState.share`, not on Apple TV) hands the title's page to the system's share sheet. The page is what
/// `DetailUiState.body` says, in an exhaustive switch: while the title loads a spinner (`detail.loading`), or the page
/// of the catalog item it was opened from (`DetailBody.Preview`); when no addon has the title, the error says why, with
/// the meta addons that failed under it (`DetailBody.Failed`). A title the kids profile blocks (`DetailBody.Blocked`)
/// shows why in place of the page, with "Ask a parent", which asks a parent's PIN and then loads the page
/// (`detail.kidsBlock`, `detail.kidsBlock.action`, focused on Apple TV); a title it allows offers "Block on this
/// profile" (`DetailAction.kidsBlock`: behind ⋯, on Apple TV the row's last button).
/// Where the device has downloads (not Apple TV), what came of a download choice shows as a toast
/// (`detail.downloadNotice`), and deleting a season's or the whole title's downloads asks first in an alert
/// (`detail.downloadDelete.<confirm|cancel>`). Under the actions, the title's playback in a player app: the line while
/// one plays it, or "Finished watching?" with Mark as watched and Not yet after one that reports nothing
/// (`detail.playerApp`); then, for the saved details shown offline, a banner saying so (`detail.offlineNote`).
struct DetailScreen: View {
    let state: DetailUiState
    let intents: DetailIntents
    /// No sheet or player covers the page.
    var isTop = true
    /// The player covers the page (with or without the next episode's Streams sheet over it).
    var underPlayer = false
    /// Its destination's stack is the one that shows: not while another tab shows.
    var shown = true

    var body: some View {
        switch onEnum(of: state.body) {
        case .loading:
            LoadingStateView(text: state.loadingNote)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("detail.loading")
                #if os(tvOS)
                // Holds focus while the title loads: with nothing focusable the focus engine brings back the tab bar,
                // which the page hides.
                .focusable()
                #endif
        case .page(let page):
            DetailPage(state: state, header: page.header, intents: intents, isTop: isTop, underPlayer: underPlayer, shown: shown)
                #if DEBUG
                .overlayPreferenceValue(DetailArtPending.self) { DetailArtProbe(header: page.header, pending: $0) }
                #endif
        case .failed(let failed):
            ErrorStateView(message: failed.message, title: failed.title, onRetry: intents.reload)
                .accessibilityIdentifier("detail.error")
        case .blocked(let block):
            BlockedStateView(title: block.title, reason: block.reason, actionLabel: block.askLabel, id: "detail.kidsBlock", onAction: intents.askParent)
        }
    }
}

/// What the Detail page can put focus on by itself: the primary action the first time, else the library toggle; back
/// from the player, the control that opened it (`episode` a row or a card, by its video id); on Apple TV, while the
/// title loads, the page of the catalog item it was opened from, which has no actions, holds it on the title's name
/// (`page`). Apple TV's page also
/// follows it to know which of its sections has focus, and lands a move into a section on the element the section
/// keeps: a round action (`action`, Rate's too, focused again as its picker closes), More, the
/// player app's answers (`playerApp`, Mark as watched or Not yet), a season's or a range's chip, the episode the row is
/// on (`episode`, the current one at first), a name of Cast & crew (`cast`, by its link's id); `edge` is its strip past
/// the sections shown, under them if `down`, which hands focus on to the section beyond.
enum DetailFocus: Hashable {
    case play
    case page
    case action(DetailAction)
    case more
    case playerApp(finished: Bool)
    case season(Int32)
    case range(Int)
    case episode(String)
    case cast(String)
    case edge(down: Bool)
}

/// The metrics the Detail page's parts share.
enum DetailMetrics {
    /// Windows at least `wideWindowWidth` wide whose page, beside a sidebar, is at least `widePageWidth` put the details
    /// and the episodes side by side over a full-screen backdrop. Narrower windows, a phone's in landscape (the iPhone
    /// Duo's inner screen: 951 pt), show the one-column page, as the phone's other screen does: beside a series' 340 pt
    /// details column the episodes' text there got 176 pt.
    static let wideWindowWidth: CGFloat = 1000
    static let widePageWidth: CGFloat = 840
    /// The details' column on wide windows is at most this wide.
    static let infoMaxWidth: CGFloat = 560
    /// The episodes' column on wide windows is at most this wide (longer rows read badly).
    static let episodesMaxWidth: CGFloat = 960
    /// The title's box (`DetailTitleArt`): as tall as a logo may be, phones and narrow windows 140 pt, wide windows 180,
    /// TV 192; taller where the name takes more room.
    static func titleHeight(_ formFactor: FormFactor, centred: Bool) -> CGFloat {
        if formFactor == .tv { return 192 }
        return centred ? 140 : 180
    }

    /// How much higher the title's box sits than its place in the headline (`DetailHeadline`), where the title has a
    /// box: the room a Cinemeta logo's transparent bottom took in that box when logos were drawn whole. Cinemeta's
    /// logos (fanart.tv's clear logos) are 800×310 canvases, drawn `logoWidth` wide or the box's height tall, whose
    /// bottom 66 px (Chainsmoker Cat's; The Shawshank Redemption's 75, Chernobyl's 95) are transparent: 24 pt on
    /// iPhone 17's 296 pt. Logos are cut to their art, so the art stays where it was and the metadata block rises to
    /// the headline's spacing under the art, the actions to the same spacing under the block.
    static func titleLift(logoWidth: CGFloat, boxHeight: CGFloat) -> CGFloat {
        min(logoWidth * 310 / 800, boxHeight) * 66 / 310
    }

    /// An episode row's inset on every side of its thumbnail and text, and between them: its hover and focus fill
    /// reach this far past them.
    static let rowInset: CGFloat = 12

    /// An episode thumbnail's corner radius; its row's is this plus the row's inset, so the two corners run parallel.
    static let thumbnailRadius = Radius.small
}

/// The page once its header is known (a catalog item's preview while the title loads, then the title's): one list on
/// phones and narrow windows (`DetailNarrowLayout`), the details and the episodes side by side over the backdrop on
/// wide windows (`DetailWideLayout`), Apple TV's page of sections (`DetailTvPage`). On tvOS focus starts on the primary
/// action (the library toggle until there is one), once the title is known, and until then on the title's name
/// (`DetailFocus.page`: with nothing focused the focus engine would bring back the tab bar over the page), unless the
/// user has moved focus on the
/// page before the primary action came (moves on a screen over it don't count); coming back from an episode's streams,
/// the focus engine returns it to what had it. Once the player's cover has gone, the page puts it back on what opened
/// the player (the episode card or Play, while still there; else the primary action, else the library toggle): the
/// focus engine's own return after that cover lands on an episode without SwiftUI or accessibility knowing it is
/// focused (so VoiceOver and the UI tests found nothing focused), so the episodes are built anew (`DetailRowReturn`)
/// and that one takes focus through `focus`.
private struct DetailPage: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    let isTop: Bool
    let underPlayer: Bool
    let shown: Bool

    @FocusState private var focus: DetailFocus?
    #if os(tvOS)
    /// The primary action has had its first focus, or the user moved focus first; from then on focus is the user's.
    @State private var focusedFirst = false
    /// The user's moves (`FocusRestore.userMoves`) as the page, on top with no player over it, could first take focus;
    /// nil again once it leaves the top, so moves on a screen over it don't count.
    @State private var firstFocusAsked: Int?
    /// The player has covered the page since it last took focus back.
    @State private var playerCovered = false
    /// What last had focus on the page while the player wasn't over it: the control that opened the player. Focus in
    /// the Streams sheet leaves `focus` nil, so the episode card (or Play) that opened the sheet stays.
    @State private var opener: DetailFocus?
    @State private var rowReturn = DetailRowReturn()
    @Environment(\.playerDismissals) private var playerDismissals
    #endif

    var body: some View {
        #if os(tvOS)
        DetailTvPage(state: state, header: header, intents: intents, focus: $focus)
            .defaultFocus($focus, state.preview ? .page : state.primaryAction != nil ? .play : .action(.library))
            .environment(\.detailRowReturn, rowReturn)
            .toast(state.downloadNotice, id: "detail.downloadNotice", onShown: intents.dismissDownloadNotice)
            .onAppear { Task { focusFirst() } }
            .onChange(of: state.primaryAction != nil) { focusFirst() }
            .onChange(of: state.preview) { focusFirst() }
            // Opened with its Streams sheet over it (a link to one of its videos, or a Continue Watching item whose
            // video has no stream to play), the page takes its first focus once that has gone; opened under the player
            // (a Continue Watching item playing) or from under it, once the player's cover has gone (below).
            .onChange(of: isTop) {
                // Leaving the top ends a first focus the user moved focus before; else it counts again once back.
                if !isTop, let asked = firstFocusAsked {
                    if FocusRestore.userMoves != asked { focusedFirst = true }
                    firstFocusAsked = nil
                }
                focusFirst()
            }
            .onChange(of: underPlayer, initial: true) { _, under in if under { playerCovered = true } }
            .onChange(of: focus) { _, focused in
                if let focused, !playerCovered { opener = focused }
            }
            .task(id: playerDismissals) {
                guard playerCovered, isTop else { return }
                playerCovered = false
                focusedFirst = true
                let target = lastFocus
                // Every card anew: the focus engine may return to any of them, not only to the one focus goes back to.
                rowReturn = DetailRowReturn(count: rowReturn.count + 1)
                // Set again until it holds, as the focus engine may still settle after the cover.
                await FocusRestore.restore($focus, to: target)
            }
            // Its tab shown again, as focus moves onto the tab's button: the page hides the tab bar, which leaves focus
            // on nothing, so it goes back to the control that last had it.
            .task(id: shown) {
                guard shown, focusedFirst, isTop, !playerCovered else { return }
                await FocusRestore.restore($focus, to: lastFocus)
            }
        #else
        GeometryReader { geometry in
            // iPad's and the Mac's leading safe area is the sidebar, which the backdrop runs under, dimmed; an iPhone's,
            // in landscape, is its sensor housing.
            #if os(iOS)
            let sidebar = isPhone ? 0 : geometry.safeAreaInsets.leading
            #else
            let sidebar = geometry.safeAreaInsets.leading
            #endif
            let window = geometry.size.width + geometry.safeAreaInsets.leading + geometry.safeAreaInsets.trailing
            if geometry.size.width >= DetailMetrics.widePageWidth, window >= DetailMetrics.wideWindowWidth {
                DetailWideLayout(
                    state: state,
                    header: header,
                    intents: intents,
                    size: geometry.size,
                    bottomInset: geometry.safeAreaInsets.bottom,
                    sidebar: sidebar,
                    focus: $focus
                )
            } else {
                DetailNarrowLayout(
                    state: state,
                    header: header,
                    intents: intents,
                    width: geometry.size.width,
                    height: geometry.size.height,
                    insets: geometry.safeAreaInsets,
                    sidebar: sidebar,
                    focus: $focus
                )
            }
        }
        .inlineNavigationTitle()
        #if os(macOS)
        // The window is named after the title (the Window menu, Mission Control); its toolbar shows no second one.
        .navigationTitle(header.title)
        .toolbarTitleHidden()
        #endif
        .toast(state.downloadNotice, id: "detail.downloadNotice", onShown: intents.dismissDownloadNotice)
        .confirmation(
            state.downloadDelete.map { Confirmation($0, id: "detail.downloadDelete") },
            onConfirm: intents.confirmDownloadDelete,
            onCancel: intents.dismissDownloadDelete
        )
        #endif
    }

    #if os(tvOS)
    private func focusFirst() {
        guard !focusedFirst, isTop, !playerCovered else { return }
        let asked = firstFocusAsked ?? FocusRestore.userMoves
        firstFocusAsked = asked
        if FocusRestore.userMoves != asked {
            focusedFirst = true
        } else if state.primaryAction != nil {
            focus = .play
            focusedFirst = true
        } else if state.preview {
            focus = .page
        } else if focus == nil || focus == .page {
            focus = .action(.library)
        }
    }

    /// The control that last had focus while the player wasn't over the page (the one that opened the player), while the
    /// page still shows it; else the primary action, else the library toggle.
    private var lastFocus: DetailFocus {
        if let opener, shows(opener) { return opener }
        return state.primaryAction != nil ? .play : .action(.library)
    }

    private func shows(_ target: DetailFocus) -> Bool {
        switch target {
        case .play: state.primaryAction != nil
        case .page: state.preview
        case .action(let action): state.tvActions.contains(action)
        case .more: true
        case .playerApp: state.playerApp != nil
        case .season(let season): state.seasons.contains { $0.season == season }
        case .range(let index): index < state.episodeRanges.count
        case .episode(let videoId): state.episodes.contains { $0.videoId == videoId }
        case .cast(let id): header.castAndCrew.contains { $0.id == id }
        case .edge: false
        }
    }
    #endif
}

#if !os(tvOS)
/// Phones and narrow windows: one scrolling page. The backdrop across the top (under the navigation bar, and under the
/// sides' safe area: iPad's and the Mac's sidebar, drawn over it, and an iPhone's sensor housing in landscape) fades to
/// black under the title, the actions and the description, the first three centred; then the season chips, scrolling
/// sideways, on the current episode's season, and the episodes (a long season's range of them,
/// `DetailUiState.listedEpisodes`); "More like this" (`DetailRelatedRow`); then the information with the links. The page
/// opens at its top, brought to no episode but an anime list entry's first (`DetailUiState.shownEpisodeFromEntry`),
/// that row's middle at the
/// scroll view's (`DetailShownEpisode`). The page itself stays inside the safe area. The title's box ends 96 pt above
/// the 16:9 backdrop's bottom, less its lift (`DetailMetrics.titleLift`), and the backdrop reaches on 34 pt under the
/// metadata line's top (the headline's 14 pt under the box), so its picture shows behind the metadata line. A one-line
/// name ends at the box's bottom; a longer one (a long name, large text) starts higher, and one taller than the box
/// grows it downwards, moving the metadata line past the picture. The fade's part over the box then moves up whole by
/// as far as the name's first line ends above the box's bottom (`DetailBackdrop.Style.top`'s `rise`), so that line
/// sits on the darkness a one-line name's does and the lines under it on more; the fade's 80 % stays at the metadata
/// line's place.
private struct DetailNarrowLayout: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    let width: CGFloat
    /// The page's height inside the safe area: a short one (a phone in landscape) keeps the hero to half of it.
    let height: CGFloat
    /// The page's safe area, which the backdrop reaches under at the top and the sides.
    let insets: EdgeInsets
    /// The leading `sidebar` points of the safe area are a sidebar (`DetailBackdrop`).
    let sidebar: CGFloat
    let focus: FocusState<DetailFocus?>.Binding
    /// How far under the title's box's top the name's first line ends (`DetailHeadline.onFirstLine`), nil under a logo.
    @State private var firstLine: CGFloat?

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        let backdrop = header.background != nil
        let topInset = insets.top
        let logoWidth = min((width - sides.horizontal) * 0.8, DetailMetrics.infoMaxWidth)
        let titleHeight = DetailMetrics.titleHeight(formFactor, centred: true)
        let lift = DetailMetrics.titleLift(logoWidth: logoWidth, boxHeight: titleHeight)
        // 16:9 under the bars, at most half a short page so the actions show under it; the details overlap its faded
        // lower part, the title's box never above the bars.
        let heroHeight = min(width * 9 / 16, height / 2) + topInset
        let top = max(heroHeight - 96, topInset + lift)
        let textTop = top - lift + titleHeight + 14
        let backdropHeight = textTop + 34
        let rise = firstLine.map { max(titleHeight - $0, 0) } ?? 0
        let inset = DetailMetrics.rowInset
        let thumbnailWidth: CGFloat = formFactor == .compact ? 128 : 176
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    if backdrop {
                        DetailBackdrop(
                            url: header.background,
                            style: .top(
                                bars: topInset, title: (top - lift)...(top - lift + titleHeight), rise: rise, text: textTop, height: backdropHeight
                            ),
                            sidebar: sidebar
                        )
                        .frame(width: insets.leading + width + insets.trailing, height: backdropHeight)
                        .padding(.leading, -insets.leading)
                    }
                    VStack(alignment: .center, spacing: 14) {
                        if !backdrop { DetailPosterFallback(header: header, width: formFactor == .compact ? 120 : 160) }
                        DetailHeadline(
                            state: state,
                            header: header,
                            intents: intents,
                            centred: true,
                            logoWidth: logoWidth,
                            actionsWidth: nil,
                            roomAbove: backdrop ? top - topInset : 12,
                            focus: focus,
                            onFirstLine: { firstLine = $0 }
                        )
                    }
                    .padding(sides.insets)
                    // The page's width, not the backdrop's beside it in the stack (wider by the sides' safe areas):
                    // centred on what shows of the page, clear of iOS 27.1's vertical bar.
                    .frame(width: width)
                    .padding(.top, backdrop ? top : topInset + 12)
                }
                if state.hasEpisodes {
                    DetailSeasonChips(
                        state: state,
                        intents: intents,
                        selectedRange: Int(state.listedRange),
                        onRange: intents.pickRange,
                        focus: focus,
                        // A chip's capsule reaches out past its name, but stays inside the safe area: beside the
                        // vertical bar, where the page's side is the safe area's edge, it starts there.
                        nameStart: max(sides.leading, ChipMetrics.inset(formFactor))
                    )
                    .padding(.top, dimens.sectionSpacing + 8)
                    .padding(.bottom, 4)
                    DetailEpisodeList(state: state, intents: intents, thumbnailWidth: thumbnailWidth, focus: focus)
                        .padding(.leading, sides.leading - inset)
                        .padding(.trailing, sides.trailing - inset)
                }
                if let related = state.related {
                    DetailRelatedRow(related: related, intents: intents)
                        .padding(.top, dimens.sectionSpacing + 8)
                }
                if !state.preview {
                    DetailInformation(header: header, onOpenLink: intents.openLink)
                        .padding(sides.insets)
                        .padding(.top, dimens.sectionSpacing + 8)
                }
            }
            .frame(width: width, alignment: .leading)
            .padding(.leading, insets.leading)
            .padding(.bottom, dimens.sectionSpacing * 2)
        }
        .scrollIndicators(.hidden)
        // An anime list entry's first episode at the middle of the scroll view, under the bars too; no other episode.
        .modifier(
            DetailShownEpisode(
                episode: state.shownEpisodeFromEntry ? state.shownEpisode : nil,
                intents: intents,
                thumbnailWidth: thumbnailWidth,
                below: 0
            )
        )
        // The scroll view under the sides' safe area too, so the backdrop reaches the window's edges; the page keeps
        // inside it by its leading padding and its width.
        .ignoresSafeArea(edges: [.top, .horizontal])
    }
}

/// Wide windows: the backdrop behind the whole window, fading to black at the leading edge and the bottom. The details
/// in a column at the leading edge (at most `DetailMetrics.infoMaxWidth`), "More like this" across the column before
/// the information; a series' episodes in a column beside them, the backdrop dimmed under them. A movie's details start
/// lower, under the backdrop's picture, and its action row and "More like this", with nothing beside the details, have
/// the page's width. On the Mac each column is a focus section for keyboard navigation.
private struct DetailWideLayout: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    let size: CGSize
    /// The page's bottom safe area, which the episodes' column scrolls on under (`DetailEpisodesPane`).
    let bottomInset: CGFloat
    /// The leading `sidebar` points of the safe area are a sidebar (`DetailBackdrop`).
    let sidebar: CGFloat
    let focus: FocusState<DetailFocus?>.Binding

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        let series = state.hasEpisodes
        let usable = max(size.width - sides.horizontal, 0)
        let gap: CGFloat = 40
        let infoWidth = Self.infoWidth(usable: usable, series: series)
        // The scrolling column: a series' is the details', beside the episodes; a movie's spans the page.
        let columnWidth = series ? infoWidth : usable
        let paneWidth = max(usable - infoWidth - gap, 0)
        let episodesWidth = min(paneWidth, DetailMetrics.episodesMaxWidth)
        let thumbnailWidth: CGFloat = 176
        // The focused control's ring stays inside the scrolling column.
        let reach = dimens.focusRoom
        let top: CGFloat = 16
        let columnTop = series ? top : max(size.height * 0.3, top)
        HStack(alignment: .top, spacing: gap - 2 * reach) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if header.background == nil { DetailPosterFallback(header: header, width: 160) }
                    DetailHeadline(
                        state: state,
                        header: header,
                        intents: intents,
                        centred: false,
                        logoWidth: infoWidth,
                        actionsWidth: columnWidth,
                        // Under the poster (no backdrop) the room is the column's spacing.
                        roomAbove: header.background == nil ? 20 : columnTop,
                        focus: focus
                    )
                    if let related = state.related {
                        // Its heading and first card at the column's start, its cards scrolling across the column.
                        DetailRelatedRow(related: related, intents: intents)
                            .frame(width: columnWidth + sides.horizontal)
                            .padding(.leading, -sides.leading)
                            .padding(.trailing, -sides.trailing)
                            .padding(.vertical, 8)
                    }
                    if !state.preview { DetailInformation(header: header, onOpenLink: intents.openLink) }
                }
                // The details at their reading width; the action row reaches past it to the column's end.
                .frame(width: infoWidth, alignment: .leading)
                .frame(width: columnWidth, alignment: .leading)
                .padding(.top, columnTop)
                .padding(.bottom, dimens.sectionSpacing * 2)
                .padding(.horizontal, reach)
            }
            .scrollIndicators(.hidden)
            .frame(width: columnWidth + 2 * reach)
            .focusSectionIfAvailable()
            if series {
                DetailEpisodesPane(
                    state: state,
                    intents: intents,
                    thumbnailWidth: thumbnailWidth,
                    reach: reach,
                    bottomInset: bottomInset,
                    focus: focus
                )
                .frame(width: episodesWidth + 2 * reach)
                .focusSectionIfAvailable()
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, sides.leading - reach)
        .padding(.trailing, sides.trailing - reach)
        .background {
            DetailBackdrop(url: header.background, style: .wide(dimmed: series), sidebar: sidebar).ignoresSafeArea()
        }
    }

    /// The details' column: a movie's at most `infoMaxWidth`; beside a series' episodes 40 % of the width within
    /// 340…`infoMaxWidth`.
    private static func infoWidth(usable: CGFloat, series: Bool) -> CGFloat {
        if !series { return min(usable, DetailMetrics.infoMaxWidth) }
        return min(max(usable * 0.4, 340), DetailMetrics.infoMaxWidth)
    }
}
#endif

/// The title's background art, fading into the black page: `top` across the top of a narrow page (darker behind the
/// bars and the back button, clear in the middle, black under the metadata line), `wide` behind the whole window (dark
/// at the leading edge and the bottom, and dimmed under a series' episodes), `tv` behind Apple TV's page. Under a
/// sidebar (iPad's, the Mac's) it is dimmed from its leading edge, the window's: black at 95 % there, eased out
/// (smoothstep) to clear at 3 times the sidebar's width, so the sidebar's labels read at 4.5:1 over a white picture and
/// no edge shows where the dim ends; the dim of Compose's `NavigationBackdrop`.
struct DetailBackdrop: View {
    enum Style {
        /// `height` points tall: 45 % black at the top, clear from the bars' bottom (`bars`) down to the title's box's
        /// top (`title.lowerBound`), then darker evenly to 80 % black at the metadata line's top (`text`: its text reads
        /// at 4.5:1 over a white picture) and black at the bottom; each fade a straight line. `rise` moves the box's part
        /// of that fade up whole, its darkness at the box's bottom coming that much higher, the 80 % staying: a name
        /// whose first line ends that far above the box's bottom has that line on the darkness a one-line name's has.
        /// Where that would start the fade above the bars, it starts at the bars at the darkness it has reached there,
        /// the top's 45 % fading to that instead of to clear.
        case top(bars: CGFloat, title: ClosedRange<CGFloat>, rise: CGFloat, text: CGFloat, height: CGFloat)
        case wide(dimmed: Bool)
        /// Android TV's: across the screen black at 95 % at the leading edge, easing to 80 % where the text column
        /// ends (`textEnd`, a share of the width), then to 10 % at the trailing edge; from 30 % of the height down (as
        /// Home's backdrop), easing to black at the bottom.
        case tv(textEnd: Double)
    }

    let url: String?
    let style: Style
    /// The leading points under a sidebar; 0 for none.
    let sidebar: CGFloat

    var body: some View {
        let black = Palette.background
        Color.clear
            .overlay { RemoteImage(url: url) { Color.clear.detailArtPending("backdrop") } }
            .clipped()
            .overlay {
                switch style {
                case .top(let bars, let title, let rise, let text, let height):
                    // What the even fade has at the box's bottom, there `rise` higher.
                    let boxEnd = 0.8 * (title.upperBound - title.lowerBound) / (text - title.lowerBound)
                    let start = title.lowerBound - rise
                    let end = title.upperBound - rise
                    let atBars = boxEnd * max(bars - start, 0) / (end - start)
                    LinearGradient(
                        stops: [
                            .init(color: black.opacity(0.45), location: 0),
                            .init(color: black.opacity(atBars), location: bars / height),
                            .init(color: black.opacity(atBars), location: max(start, bars) / height),
                            .init(color: black.opacity(boxEnd), location: end / height),
                            .init(color: black.opacity(0.8), location: text / height),
                            .init(color: black, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                case .wide(let dimmed):
                    ZStack {
                        // Under the episodes' column the picture at 30 %: the season chips' unselected names at 4.5:1.
                        if dimmed { black.opacity(0.7) }
                        LinearGradient(
                            stops: [
                                .init(color: black.opacity(0.92), location: 0),
                                .init(color: black.opacity(0.75), location: 0.3),
                                .init(color: black.opacity(0.1), location: 0.65),
                                .init(color: black.opacity(0), location: 1),
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        LinearGradient(
                            stops: [.init(color: black.opacity(0), location: 0.55), .init(color: black, location: 1)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    }
                case .tv(let textEnd):
                    ZStack {
                        LinearGradient(
                            stops: easedStops(black, from: 0.95, to: 0.8, over: 0...textEnd)
                                + easedStops(black, from: 0.8, to: 0.1, over: textEnd...1),
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        LinearGradient(stops: easedStops(black, from: 0, to: 1, over: 0.3...1), startPoint: .top, endPoint: .bottom)
                    }
                }
            }
            .overlay(alignment: .leading) {
                if sidebar > 0 {
                    LinearGradient(stops: Self.sidebarDim, startPoint: .leading, endPoint: .trailing)
                        .frame(width: sidebar * 3)
                }
            }
            .clipped()
            .background(black)
            .accessibilityHidden(true)
    }

    /// The dim under a sidebar, from the leading edge to 3 times its width.
    private static let sidebarDim = easedStops(Palette.background, from: 0.95, to: 0, over: 0...1)
}

/// `color` from opacity `from` to `to` across `over` (gradient locations, 0 to 1), eased (smoothstep), in 16 straight
/// pieces of the curve.
func easedStops(_ color: Color, from: Double, to: Double, over range: ClosedRange<Double>) -> [Gradient.Stop] {
    (0...16).map { step in
        let t = Double(step) / 16
        let eased = t * t * (3 - 2 * t)
        return .init(color: color.opacity(from + (to - from) * eased), location: range.lowerBound + (range.upperBound - range.lowerBound) * t)
    }
}

#if !os(tvOS)
/// The poster, only for a title without a backdrop.
private struct DetailPosterFallback: View {
    let header: DetailHeader
    let width: CGFloat

    @Environment(\.posterTemplate) private var posterTemplate

    var body: some View {
        let poster = posterTemplate.poster(type: header.type, id: header.id, imdbId: header.imdbId, addon: header.poster)
        if let url = poster.url {
            ZStack {
                Palette.surfaceContainerHigh
                RemoteImage(url: url, fallbackUrl: poster.fallback)
            }
            .frame(width: width, height: width / header.posterShape.aspectRatio)
            .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
            .accessibilityHidden(true)
        }
    }
}
#endif

extension View {
    /// Debug builds: `part` of the title's art (`backdrop`, `logo`) hasn't drawn while this view, its placeholder,
    /// shows (`DetailArtProbe`). Release builds: the view as it is.
    func detailArtPending(_ part: String) -> some View {
        #if DEBUG
        preference(key: DetailArtPending.self, value: [part])
        #else
        self
        #endif
    }
}

#if os(iOS)
/// The device is a phone (an iPhone, the iPhone Duo on either of its screens), whatever its window's width: a leading
/// safe area is a sensor housing there, not a sidebar (`DetailScreen`).
@MainActor
var isPhone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
#endif

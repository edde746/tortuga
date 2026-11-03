import SwiftUI
import TortugaKit

#if !os(tvOS)
/// A series' episodes beside its details (wide windows): the season chips (`DetailSeasonChips`) over the season's
/// episode rows (a long season's range of them, `DetailUiState.listedEpisodes`), which scroll on their own, brought to
/// the episode the page shows (`DetailUiState.shownEpisode`) with its middle at the middle of the column, which no bar
/// covers, down to the window's bottom: the column scrolls on under the page's bottom safe area (`bottomInset`: iPad's
/// home indicator's strip; iPad's tab bar is at the top). `reach` is the room the focused row's keyboard focus ring
/// takes past the column. The chips scroll within the column, its reach included, and draw nothing past it: not over the
/// details beside it.
struct DetailEpisodesPane: View {
    let state: DetailUiState
    let intents: DetailIntents
    let thumbnailWidth: CGFloat
    let reach: CGFloat
    let bottomInset: CGFloat
    let focus: FocusState<DetailFocus?>.Binding

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DetailSeasonChips(
                state: state,
                intents: intents,
                selectedRange: Int(state.listedRange),
                onRange: intents.pickRange,
                focus: focus,
                nameStart: reach + DetailMetrics.rowInset
            )
            .padding(.vertical, 8)
            // Their row draws past its frame (`DetailSeasonChips`): here up to the column's edges, the focus ring's
            // room above and below.
            .clipped()
            ScrollView {
                DetailEpisodeList(state: state, intents: intents, thumbnailWidth: thumbnailWidth, focus: focus)
                    .padding(.horizontal, reach)
                    // The keyboard focus ring needs the room.
                    .padding(.top, reach)
                    .padding(.bottom, dimens.sectionSpacing * 2)
            }
            .scrollIndicators(.hidden)
            .modifier(DetailShownEpisode(episode: state.shownEpisode, intents: intents, thumbnailWidth: thumbnailWidth, below: bottomInset))
            // A new season, or range, starts at its first episode.
            .id("\(state.selectedSeason?.intValue ?? -1).\(state.listedRange)")
        }
    }
}

/// Brings the episodes of the scroll view it is on to `episode` (a video id) once, then tells the store it has been
/// shown: the row's middle at the middle of what shows of the scroll view, its height down to its safe area's bottom
/// and `below` past it (what shows under the bottom safe area: the episodes' column's), where the list's ends let it.
/// `scrollTo` lines the row's anchor point up with the same point of the height down to the safe area's bottom, so the
/// anchor comes from that height and the row's, taken as tall as its 16:9 thumbnail (`thumbnailWidth`) and insets: a
/// row taller by d puts its middle off by (anchor - 0.5) × d, under a tenth of d. Nothing below is the anchor `.center`.
struct DetailShownEpisode: ViewModifier {
    let episode: String?
    let intents: DetailIntents
    let thumbnailWidth: CGFloat
    let below: CGFloat

    /// The scroll view's height down to its safe area's bottom.
    @State private var height: CGFloat = 0

    func body(content: Content) -> some View {
        ScrollViewReader { proxy in
            content
                .onGeometryChange(for: CGFloat.self) { $0.size.height - $0.safeAreaInsets.bottom } action: { height = $0 }
                // After the rows' first layout; not from a list the page has replaced meanwhile (another season or
                // range), whose store intent would come before the new list's.
                .task(id: episode) {
                    guard let row = episode, !Task.isCancelled else { return }
                    let rowHeight = thumbnailWidth * 9 / 16 + 2 * DetailMetrics.rowInset
                    let middle = (height + below) / 2
                    proxy.scrollTo(row, anchor: UnitPoint(x: 0.5, y: (middle - rowHeight / 2) / (height - rowHeight)))
                    intents.episodeShown()
                }
        }
    }
}

/// The selected season's episodes (or an item's videos) its list holds, one free-flowing row each, identified by its
/// video id: a long season's listed range of them (`DetailUiState.listedEpisodes`), else all of them.
struct DetailEpisodeList: View {
    let state: DetailUiState
    let intents: DetailIntents
    let thumbnailWidth: CGFloat
    let focus: FocusState<DetailFocus?>.Binding

    var body: some View {
        let start = Int(state.listedStart)
        LazyVStack(spacing: 2) {
            ForEach(Array(state.listedEpisodes.enumerated()), id: \.element.videoId) { offset, episode in
                DetailEpisodeRow(
                    episode: episode,
                    index: start + offset,
                    intents: intents,
                    thumbnailWidth: thumbnailWidth,
                    focus: focus
                )
                .id(episode.videoId)
            }
        }
    }
}

/// An episode as a free-flowing row (`EpisodeRowLabel`): its 16:9 thumbnail (the progress on it, a check once
/// watched), "3. Title", the date and runtime ("Finding a stream…" while auto-play finds the stream it plays), its
/// download's state where it has one (`detail.episode.<i>.download`), two lines of synopsis. No card: hover, keyboard
/// focus and a press lay a subtle fill behind the whole row (`EpisodeRowButtonStyle`).
/// Choosing it plays auto-play's pick or opens its streams; its menu (a long press, a right click, the ⋯ beside it) is
/// the store's (`DetailEpisode.menu`): Play from the beginning, its marks and its season's, Choose a stream with
/// auto-play on, its download's entries, its trailer, each `detail.menu.item.<action>`. Episodes not out yet are
/// dimmed. To accessibility the row is its button, read from its texts, its value the progress on its thumbnail and
/// "Watched" once watched (`DetailEpisode.stateText`: "90%", "Watched"), its menu's entries its actions. On touch the
/// menu lifts the thumbnail alone, in its own corners, as a poster card lifts its artwork (the whole row, shrunk beside
/// the menu on a short window, the iPhone Duo's cover turned, came out unreadable), and the texts stay where they are.
struct DetailEpisodeRow: View {
    let episode: DetailEpisode
    let index: Int
    let intents: DetailIntents
    let thumbnailWidth: CGFloat
    /// The page's focus: the row is `.episode(videoId)` in it.
    let focus: FocusState<DetailFocus?>.Binding
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #if os(iOS)
    @Environment(\.layoutDirection) private var layoutDirection
    #endif

    var body: some View {
        let entries = menuEntries
        // The ⋯ beside the row, not laid over it: a click on it never lands on the row. Built again only when its
        // entries change, not on each progress report of the episode's download (`SteadyMenuButton`).
        HStack(spacing: 0) {
            row(entries)
            SteadyMenuButton(key: [episode.videoId, episode.menu], label: episode.moreLabel, entries: entries, probe: "detail.menu.item")
                .equatable()
                .accessibilityIdentifier("detail.episode.\(index).menu")
        }
    }

    private func row(_ entries: [ItemMenuEntry]) -> some View {
        let stacked = dynamicTypeSize.isAccessibilitySize
        return Button { intents.openEpisode(episode.videoId) } label: {
            EpisodeRowLabel(
                title: episode.numberedTitle,
                infoLine: episode.infoLine,
                overview: episode.overview,
                hiddenOverviewLabel: episode.hiddenOverviewLabel,
                dimmed: episode.upcoming
            ) {
                #if os(iOS)
                // The thumbnail's room: the menu's layer over the row draws it.
                Color.clear.frame(width: thumbnailWidth, height: thumbnailWidth * 9 / 16)
                #else
                DetailEpisodeThumbnail(episode: episode, width: thumbnailWidth, radius: DetailMetrics.thumbnailRadius)
                #endif
            } badge: {
                if let download = episode.download, let label = download.label {
                    DownloadStateLine(
                        text: label,
                        systemImage: download.state.systemImage,
                        progress: download.progress?.doubleValue,
                        failed: download.state == .failed,
                        font: .caption
                    )
                    .accessibilityIdentifier("detail.episode.\(index).download")
                }
            }
            #if os(iOS)
            // The menu goes on a layer over the whole row that draws the thumbnail alone, the texts left outside it: the
            // system hides the view a context menu is on from the moment the menu lifts it until about a second after
            // the menu has gone, whatever part of it the preview takes (`PosterCard`'s caption). Inside the button's
            // label, so a tap anywhere on the row still chooses it and a press anywhere opens the menu.
            .overlay {
                DetailEpisodeThumbnail(episode: episode, width: thumbnailWidth, radius: DetailMetrics.thumbnailRadius)
                    .padding(DetailMetrics.rowInset)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: stacked ? .topLeading : .leading)
                    .contentShape(.interaction, Rectangle())
                    .contentShape(
                        .contextMenuPreview,
                        ThumbnailShape(
                            inset: DetailMetrics.rowInset,
                            size: CGSize(width: thumbnailWidth, height: thumbnailWidth * 9 / 16),
                            radius: DetailMetrics.thumbnailRadius,
                            rightToLeft: layoutDirection == .rightToLeft,
                            stacked: stacked
                        )
                    )
                    .itemMenu(entries: entries, probe: "detail.menu.item")
            }
            #endif
        }
        .buttonStyle(EpisodeRowButtonStyle())
        .focused(focus, equals: .episode(episode.videoId))
        .accessibilityValue(episode.stateText ?? "")
        .accessibilityActions(entries)
        .accessibilityIdentifier("detail.episode.\(index)")
        #if !os(iOS)
        .itemMenu(entries: entries, probe: "detail.menu.item")
        #endif
    }

    private var menuEntries: [ItemMenuEntry] {
        episode.menuEntries(intents)
    }
}

#if os(iOS)
/// The row's thumbnail where the row lays it out: `inset` in from the row's leading edge, centred on its height, or
/// `stacked` over the text, `inset` down from its top.
private struct ThumbnailShape: Shape {
    let inset: CGFloat
    let size: CGSize
    let radius: CGFloat
    let rightToLeft: Bool
    let stacked: Bool

    func path(in rect: CGRect) -> Path {
        let x = rightToLeft ? rect.maxX - inset - size.width : rect.minX + inset
        let y = stacked ? rect.minY + inset : rect.midY - size.height / 2
        let frame = CGRect(x: x, y: y, width: size.width, height: size.height)
        return RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: frame)
    }
}
#endif
#endif

extension DetailEpisode {
    /// Its menu's entries (`menu`), each sending its action for this episode.
    func menuEntries(_ intents: DetailIntents) -> [ItemMenuEntry] {
        menu.map { entry in
            ItemMenuEntry.media(entry, probe: "detail.menu.item") { intents.episodeMenuAction(self.videoId, $0) }
        }
    }
}

/// How many times the player's cover has gone from over Apple TV's page (it stays 0 elsewhere). The episode cards'
/// identities carry it (`DetailTvPage`), so the cards are built anew each time: tvOS's own return of focus to a card
/// that was there before the cover (the one that had focus, or the one a move into the row lands on) goes unseen by
/// SwiftUI and accessibility, while a card that has just appeared takes focus through the page's `FocusState` like any
/// other move.
struct DetailRowReturn {
    var count = 0

    /// The card's identity among the episodes: its video id with the count.
    func identity(of videoId: String) -> String {
        "\(videoId)#\(count)"
    }
}

extension EnvironmentValues {
    @Entry var detailRowReturn = DetailRowReturn()
}

/// An episode's 16:9 thumbnail: `url`, else `fallbackUrl` (its series' art) where it has none or it fails to load,
/// else the film symbol; `url` blurred past recognition while `blurred` (the fallback gives nothing away and stays
/// sharp), its `progress` on it (`ArtworkProgress`), a check once `watched`. Hidden from accessibility: its row or card
/// says both in its value (`DetailEpisode.stateText`).
struct DetailEpisodeThumbnail: View {
    let url: String?
    var fallbackUrl: String?
    let blurred: Bool
    var progress: Double?
    var watched = false
    let width: CGFloat
    let radius: CGFloat

    var body: some View {
        ZStack {
            Palette.surfaceContainerHigh
            RemoteImage(url: url, fallbackUrl: fallbackUrl, blurred: blurred) {
                Image(systemName: "film")
                    .font(.title3)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .frame(width: width, height: width * 9 / 16)
        .overlay {
            if let progress {
                ArtworkProgress(value: progress)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(alignment: .topTrailing) {
            if watched {
                Image(systemName: "checkmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(Palette.primary, Palette.scrim.opacity(0.55))
                    .font(.body)
                    .padding(6)
            }
        }
        .accessibilityHidden(true)
    }
}

extension DetailEpisodeThumbnail {
    /// `episode`'s thumbnail, blurred while `DetailEpisode.blurred`, with its progress and its watched mark.
    init(episode: DetailEpisode, width: CGFloat, radius: CGFloat) {
        self.init(
            url: episode.thumbnail, blurred: episode.blurred, progress: episode.progress?.doubleValue, watched: episode.watched,
            width: width, radius: radius
        )
    }
}

/// An episode row's content, Detail's and the Calendar's: `artwork` (its 16:9 thumbnail) and beside it `title` in
/// semibold, `infoLine` in secondary on one line, `badge` (Detail's download state), two lines of `overview` in
/// secondary (`DetailEpisodeOverview`: blurred past reading with its `hiddenOverviewLabel`), the row's inset
/// (`DetailMetrics.rowInset`) on every side and between artwork and text; `dimmed` (an episode not out yet) at 60 %.
/// At the accessibility text sizes the text goes under the artwork, whole on as many lines as it takes.
struct EpisodeRowLabel<Artwork: View, Badge: View>: View {
    let title: String
    let infoLine: String?
    let overview: String?
    let hiddenOverviewLabel: String?
    var dimmed = false
    @ViewBuilder let artwork: () -> Artwork
    @ViewBuilder var badge: () -> Badge

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let inset = DetailMetrics.rowInset
        let stacked = dynamicTypeSize.isAccessibilitySize
        (stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: inset)) : AnyLayout(HStackLayout(spacing: inset))) {
            artwork()
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(stacked ? nil : 2)
                if let infoLine {
                    Text(infoLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(stacked ? nil : 1)
                }
                badge()
                if let overview {
                    DetailEpisodeOverview(text: overview, hiddenLabel: hiddenOverviewLabel, blurRadius: 7)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(stacked ? nil : 2)
                        .padding(.top, 2)
                }
            }
            .opacity(dimmed ? 0.6 : 1)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(inset)
    }
}

extension EpisodeRowLabel where Badge == EmptyView {
    init(title: String, infoLine: String?, overview: String?, hiddenOverviewLabel: String?, @ViewBuilder artwork: @escaping () -> Artwork) {
        self.init(title: title, infoLine: infoLine, overview: overview, hiddenOverviewLabel: hiddenOverviewLabel, artwork: artwork) { EmptyView() }
    }
}

/// An episode's description, or with a `hiddenLabel` blurred past reading in its place: laid out as it would show (as
/// many lines, so nothing moves when the setting changes), blurred by `blurRadius` (about half its font size), and
/// VoiceOver told `hiddenLabel` instead of the text. The caller's font, colour and line limit apply.
struct DetailEpisodeOverview: View {
    let text: String
    let hiddenLabel: String?
    let blurRadius: CGFloat

    var body: some View {
        if let hiddenLabel {
            Text(text)
                .blur(radius: blurRadius)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(hiddenLabel)
        } else {
            Text(text)
        }
    }
}

#if !os(tvOS)
/// An episode row's look, its corners parallel to the thumbnail's: nothing behind it at rest; a subtle fill behind the
/// whole row while the pointer is over it or it has keyboard focus, a stronger one while pressed.
struct EpisodeRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Row(configuration: configuration)
    }

    private struct Row: View {
        let configuration: Configuration

        @Environment(\.isFocused) private var focused
        @State private var hovered = false

        var body: some View {
            let shape = RoundedRectangle(cornerRadius: DetailMetrics.thumbnailRadius + DetailMetrics.rowInset, style: .continuous)
            configuration.label
                .foregroundStyle(Palette.onBackground)
                .background(fill, in: shape)
                .contentShape(shape)
                .onHover { hovered = $0 }
                .animation(.easeOut(duration: 0.12), value: hovered)
        }

        private var fill: Color {
            if configuration.isPressed { return Palette.surfaceContainerHighest }
            return hovered || focused ? Palette.surfaceContainerHigh : .clear
        }
    }
}
#endif

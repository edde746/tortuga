import SwiftUI
import TortugaKit

/// A catalog or library item: the artwork in its addon's `shape`, then `title` and `subtitle` (both preformatted by the
/// store), one line each so every card of a row has the same height; with `caption` false the artwork alone, nothing
/// under it and no room kept for it (Settings → Appearance → Hide titles under posters), except on tvOS, where the
/// focused card shows its caption in room the row keeps. `progress` (0–1) shows on the
/// artwork (`ArtworkProgress`: Continue Watching, the library); `watched` marks it watched with a check; `badge`
/// labels the corner (a series' count of new episodes). One button: the tvOS card style lifts the artwork on focus;
/// elsewhere it dims while pressed and shows the focus ring on keyboard focus. `accessibilityId` and `focus` go on that
/// button.
///
/// The item's menu (`menu`, the store's `MediaMenuEntry`s, each `<accessibilityId>.menu.<action>`; `onMenu` takes the
/// picked entry's action) opens on the whole card (`itemMenu`: long press, right click, the remote's long select): on
/// touch the menu lifts the artwork alone, in its own corners (`ArtworkShape`), and the caption stays where it is
/// (`ArtworkLayer`).
///
/// To accessibility the card is that one button: named `title`, its value `badgeDescription` ("1 new episode"), the
/// subtitle, the progress and "Watched" (the shared `itemStateText`), the menu's entries its actions, with or without
/// the caption. The caption under it is hidden there: read beside the button, the title came twice and the subtitle as
/// an element of its own.
struct PosterCard: View {
    let title: String
    let imageURL: String?
    /// Loaded in `imageURL`'s place once it fails: the addon's poster under a poster template (`PosterTemplate.poster`).
    var fallbackImageURL: String?
    let shape: PosterShape
    var subtitle: String?
    /// `title` and `subtitle` show under the artwork; false: the artwork alone, but on tvOS the focused card still shows
    /// them, in room every card of the row keeps for them, so the row doesn't move as focus does.
    var caption = true
    var progress: Double?
    var watched = false
    var badge: String?
    /// What `badge` says, for assistive technologies: the card's value.
    var badgeDescription: String?
    /// The card's width; `TortugaDimens.cardWidth(shape)` when nil.
    var width: CGFloat?
    /// The button's accessibility identifier: the Compose probe name (`board.row.0.item.3`), so a UI test finds, taps
    /// and checks the focus of the card's button itself rather than its caption.
    let accessibilityId: String
    /// The button's place in the screen's focus state: first focus, focus back on the card that was opened.
    var focus: CardFocus?
    let menu: [MediaMenuEntry]
    let onMenu: (MediaAction) -> Void
    let action: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #if os(tvOS)
    /// The card's button has focus.
    @State private var focused = false
    #endif

    var body: some View {
        let width = width ?? dimens.cardWidth(shape)
        let height = width / shape.aspectRatio
        let entries = menu.map { ItemMenuEntry.media($0, probe: "\(accessibilityId).menu", perform: onMenu) }
        VStack(alignment: .leading, spacing: Self.captionGap) {
            #if os(iOS)
            // The artwork's room: the menu's layer over the card draws it.
            Color.clear.frame(width: width, height: height)
            #else
            button(width: width, height: height, entries: entries)
            #endif
            if keepsCaptionRoom { captionLabel }
        }
        .frame(width: width, alignment: .leading)
        #if os(iOS)
        // The menu goes on a layer over the whole card that draws the artwork alone, the caption left outside it: the
        // system hides the view a context menu is on from the moment the menu lifts it until about a second after the
        // menu has gone, whatever part of it the preview takes, and with the menu on the card the caption vanished for
        // all that time (3 Nov, iPhone: Continue Watching's "S1E1 · 48 min left"). A press anywhere on the card still
        // opens the menu, and a tap the title: on the artwork's button, or on the room under it, which a content shape
        // of its own makes take them (one around the button too gave the button the whole card's frame to
        // accessibility), hidden there as the caption is: to accessibility the card is its button.
        .overlay(alignment: .top) {
            VStack(spacing: 0) {
                button(width: width, height: height, entries: entries)
                Color.clear.contentShape(Rectangle())
                    .onTapGesture(perform: action)
                    .accessibilityHidden(true)
            }
            .contentShape(.contextMenuPreview, ArtworkShape(height: height))
            .itemMenu(entries: entries, probe: "\(accessibilityId).menu")
        }
        #else
        .itemMenu(entries: entries, probe: "\(accessibilityId).menu")
        #endif
    }

    private func button(width: CGFloat, height: CGFloat, entries: [ItemMenuEntry]) -> some View {
        Button(action: action) {
            artwork
                .frame(width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                .focusRing(radius: Radius.medium)
                #if os(tvOS)
                .background { FocusReporter(focused: $focused) }
                #endif
        }
        .cardButtonStyle()
        .accessibilityLabel(title)
        .accessibilityValue(
            MetaCardKt.itemStateText(
                badge: badgeDescription, detail: subtitle, progress: progress.map { KotlinDouble(double: $0) }, watched: watched
            ) ?? ""
        )
        .accessibilityActions(entries)
        .accessibilityIdentifier(accessibilityId)
        .cardFocus(focus)
    }

    /// The title and subtitle under the artwork: two lines each at the accessibility text sizes, kept on every card so a
    /// row's cards stay as tall.
    private var captionLabel: some View {
        let lines = dynamicTypeSize.isAccessibilitySize ? 2 : 1
        return VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(lines, reservesSpace: lines > 1)
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .lineLimit(lines, reservesSpace: lines > 1)
            }
        }
        .opacity(showsCaption ? 1 : 0)
        .accessibilityHidden(true)
    }

    /// The caption's room is laid out: with the caption, and on tvOS for the focused card's caption.
    private var keepsCaptionRoom: Bool {
        #if os(tvOS)
        true
        #else
        caption
        #endif
    }

    private var showsCaption: Bool {
        #if os(tvOS)
        caption || focused
        #else
        caption
        #endif
    }

    /// Between the artwork and its caption: on tvOS the focused card lifts and grows, and the caption stays clear.
    private static var captionGap: CGFloat {
        #if os(tvOS)
        16
        #else
        6
        #endif
    }

    private var artwork: some View {
        ZStack {
            Palette.surfaceContainerHighest
            RemoteImage(url: imageURL, fallbackUrl: fallbackImageURL) {
                Image(systemName: "film")
                    .font(.title)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .overlay(alignment: .topLeading) {
            if let badge {
                // Hidden: the card's value says it as `badgeDescription`.
                BadgeView(text: badge, emphasis: .strong).padding(6).accessibilityHidden(true)
            }
        }
        .overlay(alignment: .topTrailing) {
            if watched {
                // Hidden: SwiftUI names a checkmark symbol "Selected" and gives the button around it the selected
                // trait; the card's value says "Watched".
                Image(systemName: "checkmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(Palette.primary, Palette.scrim.opacity(0.55))
                    .font(.title3)
                    .padding(6)
                    .accessibilityHidden(true)
            }
        }
        .overlay {
            if let progress {
                ArtworkProgress(value: progress)
            }
        }
    }
}

extension PosterTemplate? {
    /// A title's poster (the title `id` of `type`, whose addon sent `imdbId` and `addon`'s poster): the template's first,
    /// the addon's once that fails to load; the addon's alone without a template or one the title has no id for.
    func poster(type: String, id: String, imdbId: String?, addon: String?) -> (url: String?, fallback: String?) {
        guard let url = self?.url(type: type, id: id, imdbId: imdbId) else { return (addon, nil) }
        return (url, addon)
    }
}

#if os(iOS)
/// A card's artwork inside the card: its rounded rectangle across the card's width, `height` from the top. The context
/// menu's preview takes it as its outline, so the menu lifts the artwork the card draws, not a rounded rectangle around
/// the whole card, whose corners cut the caption's first and last letters (21 Oct, iPhone: the "2" of a poster's 2026).
private struct ArtworkShape: Shape {
    let height: CGFloat

    func path(in rect: CGRect) -> Path {
        RoundedRectangle(cornerRadius: Radius.medium, style: .continuous)
            .path(in: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: height))
    }
}
#endif

/// A card's key in its screen's `@FocusState`: the state is `key` while the card's button has focus, and setting it to
/// `key` moves focus there.
struct CardFocus {
    let binding: FocusState<String?>.Binding
    let key: String
}

private extension View {
    @ViewBuilder
    func cardFocus(_ focus: CardFocus?) -> some View {
        if let focus { focused(focus.binding, equals: focus.key) } else { self }
    }
}

#if os(tvOS)
/// Inside a button's label: copies whether that button has focus into `focused`.
private struct FocusReporter: View {
    @Binding var focused: Bool
    @Environment(\.isFocused) private var isFocused

    var body: some View {
        Color.clear.onChange(of: isFocused, initial: true) { focused = isFocused }
    }
}
#endif

/// Progress on artwork (a poster, a Continue Watching card, an episode's thumbnail), one look wherever it sits there:
/// a capsule filled to `value` (0–1) in white on a 30 % white track, inset from the artwork's sides and bottom, over
/// a scrim fading from 60 % black at the bottom edge to clear over the lowest 30 % of the artwork, so the bar reads
/// on light artwork too. Laid over the artwork, whose size it takes.
struct ArtworkProgress: View {
    let value: Double

    #if os(tvOS)
    private static let height: CGFloat = 6
    private static let inset: CGFloat = 10
    #else
    private static let height: CGFloat = 4
    private static let inset: CGFloat = 8
    #endif

    var body: some View {
        GeometryReader { geometry in
            let track = max(geometry.size.width - 2 * Self.inset, 0)
            ZStack(alignment: .bottom) {
                LinearGradient(colors: [Palette.scrim.opacity(0.6), Palette.scrim.opacity(0)], startPoint: .bottom, endPoint: .top)
                    .frame(height: geometry.size.height * 0.3)
                ZStack(alignment: .leading) {
                    Capsule().fill(Palette.primary.opacity(0.3))
                    Capsule().fill(Palette.primary).frame(width: track * min(max(value, 0), 1))
                }
                .frame(width: track, height: Self.height)
                .clipShape(Capsule())
                .padding(.bottom, Self.inset)
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .bottom)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

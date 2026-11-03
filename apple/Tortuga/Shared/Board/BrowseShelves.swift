import SwiftUI
import TortugaKit

// Pieces the Board, Discover and Search share (the Compose `BrowseShelves`): a catalog item's card, a shelf's addon
// label, its loading and failed forms, and giving focus back to a card on tvOS.

/// A catalog item's poster card: the title, and under it the year and rating when the addon sends them, marked once the
/// title is watched. `width` sizes it to a grid cell; `TortugaDimens.cardWidth` otherwise. Its menu (`MetaCard.menu`) is
/// identified `<accessibilityId>.menu.<action>`, and `onMenu` takes the picked entry's action.
struct MetaPoster: View {
    let card: MetaCard
    var width: CGFloat?
    let accessibilityId: String
    var focus: CardFocus?
    let action: () -> Void
    let onMenu: (MediaAction) -> Void
    @Environment(\.posterTemplate) private var posterTemplate

    var body: some View {
        let poster = posterTemplate.poster(type: card.type, id: card.id, imdbId: card.preview?.imdbId, addon: card.posterUrl)
        PosterCard(
            title: card.title,
            imageURL: poster.url,
            fallbackImageURL: poster.fallback,
            shape: card.posterShape,
            subtitle: card.subtitle,
            caption: card.caption,
            watched: card.watched,
            width: width,
            accessibilityId: accessibilityId,
            focus: focus,
            menu: card.menu,
            onMenu: onMenu,
            action: action
        )

    }
}

/// Where a shelf comes from (an addon, a tracker service), beside its heading. Phones leave an addon's out, where the
/// heading needs the width, but keep a tracker's (`onPhones`): "Watchlist" alone doesn't say whose.
struct AddonLabel: View {
    let name: String
    var onPhones = false

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        if formFactor != .compact || onPhones {
            Text(name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.onSurfaceVariant)
                .lineLimit(1)
        }
    }
}

/// A shelf whose items are on their way: `title`, a spinner, `trailing` and a row of blank cards the size of real
/// ones, so the page doesn't jump when they arrive (`id`: its accessibility identifier). Nothing in it takes focus.
struct LoadingShelf<Trailing: View>: View {
    let title: String
    let id: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        ShelfRow(title: title, items: 0..<8, id: \.self, loading: true) {
            trailing()
        } content: { _ in
            BlankCard(shape: .poster)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityIdentifier(id)
    }
}

private struct BlankCard: View {
    let shape: PosterShape

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        let width = dimens.cardWidth(shape)
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: Radius.medium, style: .continuous)
                .fill(Palette.surfaceContainer)
                .frame(width: width, height: width / shape.aspectRatio)
            RoundedRectangle(cornerRadius: Radius.extraSmall, style: .continuous)
                .fill(Palette.surfaceContainer)
                .frame(width: width * 0.7, height: 12)
        }
    }
}

/// A shelf that failed: `title`, the store's `message` and a retry button (`retryId`: its accessibility identifier).
struct FailedShelf: View {
    let title: String
    let message: String
    let retryId: String
    let onRetry: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        VStack(alignment: .leading, spacing: dimens.headingGap) {
            Text(title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 16) {
                Image(systemName: "exclamationmark.circle")
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .accessibilityHidden(true)
                Text(message)
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ActionButton(title: T.shared.browse.retry, id: retryId, systemImage: "arrow.clockwise", fullWidth: false, action: onRetry)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
            #if os(tvOS)
            // One focus group as wide as the row: Down from any card above reaches Retry at its end.
            .focusSection()
            #endif
        }
        .padding(sides.insets)
    }
}

/// A screen's first focus on its first item (tvOS), kept with the screen's store (the view is rebuilt whenever its tab
/// is shown again): the Board's first card, and the first item of a screen opened on a link (a Board shelf's "See all",
/// a Detail link), given once the screen has one unless the user has moved focus while the screen was on top since it
/// was asked for (`FocusRestore`); a screen the tab bar opened leaves focus on the bar. Moves while another screen is
/// on top don't count (a Detail over the Board, another tab): the screen tells it when it comes on top (`shown`) and
/// leaves it (`hidden`), through `firstFocus(_:to:isTop:focus:hold:)`.
@MainActor
final class FirstFocus {
    private var pending: Bool
    /// The user's moves (`FocusRestore.userMoves`) as the screen last came on top or the first focus was asked for;
    /// nil while the screen isn't on top.
    private var since: Int?

    init(pending: Bool) {
        self.pending = pending
    }

    /// The screen is to give its first item focus (a link followed on it).
    func ask() {
        pending = true
        since = FocusRestore.userMoves
    }

    /// The screen is on top: the user's moves count from now, unless they already do (it was on top already).
    func shown() {
        if since == nil { since = FocusRestore.userMoves }
    }

    /// The screen left the top: a first focus still to give ends if the user moved focus while it was on top.
    func hidden() {
        if let since, FocusRestore.userMoves != since { pending = false }
        since = nil
    }

    /// The first focus still to give: the user's moves to count from (`FocusRestore.restore`'s `since`), or nil when
    /// none is pending. It stays pending until `given()`: a restore cut short (the target gone and back, the screen off
    /// the top) leaves it to the next.
    var pendingSince: Int? {
        guard pending else { return nil }
        return since ?? FocusRestore.userMoves
    }

    /// The first focus has been given, or the user moved first: none is pending any more.
    func given() {
        pending = false
    }
}

#if os(tvOS)
extension View {
    /// The screen's first focus (`FirstFocus`): `target` takes focus once there is one and the screen is on top, held
    /// for `hold` seconds (`FocusRestore.restore`); the screen coming on top and leaving it is told to `firstFocus`.
    func firstFocus<Value: Hashable>(
        _ firstFocus: FirstFocus,
        to target: Value?,
        isTop: Bool,
        focus: FocusState<Value?>.Binding,
        hold: Double = 0.5
    ) -> some View {
        onAppear { if isTop { firstFocus.shown() } }
            .onChange(of: isTop) { _, top in top ? firstFocus.shown() : firstFocus.hidden() }
            .onDisappear { firstFocus.hidden() }
            .task(id: FirstFocusTrigger(hasTarget: target != nil, isTop: isTop)) {
                guard let target, isTop, let since = firstFocus.pendingSince else { return }
                await FocusRestore.restore(focus, to: target, hold: hold, since: since)
                if !Task.isCancelled { firstFocus.given() }
            }
    }
}

/// When a screen's first-focus task runs: once its first item comes, and again once the screen is back on top.
private struct FirstFocusTrigger: Hashable {
    let hasTarget: Bool
    let isTop: Bool
}
#endif

@MainActor
enum FocusRestore {
    /// The user's moves of focus since launch: the focus system's updates with a heading (a press or a swipe on the
    /// remote), not focus the app sets, nor tvOS's own as a screen opens or a focused view goes (`countUserMoves`).
    private(set) static var userMoves = 0

    #if os(tvOS)
    /// Counts the user's moves (`userMoves`) from now on: once, as the app starts.
    static func countUserMoves() {
        _ = NotificationCenter.default.addObserver(forName: UIFocusSystem.didUpdateNotification, object: nil, queue: .main) { notification in
            // Delivered on the main queue, where it is read.
            nonisolated(unsafe) let notification = notification
            MainActor.assumeIsolated {
                guard let context = notification.userInfo?[UIFocusSystem.focusUpdateContextUserInfoKey] as? UIFocusUpdateContext,
                      !context.focusHeading.isEmpty else { return }
                userMoves += 1
            }
        }
    }
    #endif

    /// Moves focus to `target` unless an element of `focus` has it (with `replacing`, unless `target` has it), until one
    /// has held it for `hold` seconds (for up to 4 s), both measured on the clock however late each look comes: a lazily
    /// built card can take a few frames to attach, and at launch the system gives its first focus to the tab bar a
    /// moment after the first frames, so the first focus holds longer. A move of the user's ends it, before it has moved
    /// focus or after: once `userMoves` is past `since` (the moves as the restore was asked for; by default as it
    /// starts), focus stays where the user put it, the tab bar included. The Compose `RestoreFocus` asks once per frame
    /// the same way, until the user moves.
    static func restore<Value: Hashable>(
        _ focus: FocusState<Value?>.Binding, to target: Value?, hold: Double = 0.5, since: Int? = nil, replacing: Bool = false
    ) async {
        guard let target else { return }
        let since = since ?? userMoves
        let clock = ContinuousClock()
        let giveUp = clock.now + .seconds(4)
        var heldSince = clock.now
        while clock.now < giveUp {
            if Task.isCancelled || userMoves != since { return }
            if replacing ? focus.wrappedValue != target : focus.wrappedValue == nil {
                focus.wrappedValue = target
                heldSince = clock.now
            } else if heldSince.duration(to: clock.now) >= .seconds(hold) {
                return
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }
}

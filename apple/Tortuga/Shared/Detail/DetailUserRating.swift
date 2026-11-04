import SwiftUI
import TortugaKit

/// The user's own score, last in Detail's ratings row once a tracker that rates the title has one
/// (`DetailUserRating.text`): a star as tall as the text, then the score ("8"), or the range "7–8" where the trackers'
/// scores differ, plain like the other scores (`DetailRatingItem`); it takes no press and no focus. One element for
/// VoiceOver and the harness (`detail.userRating`), labelled "Your rating: 8". The rating is given in the picker,
/// which the action row's Rate opens (`DetailAction.rate`, `detailRatingPicker`).
struct DetailUserRatingScore: View {
    let text: String
    let label: String
    let textSize: CGFloat

    var body: some View {
        HStack(spacing: (textSize * 0.45).rounded()) {
            Image(systemName: "star.fill")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(height: textSize)
            Text(text)
                .font(.system(size: textSize, weight: .semibold))
                .monospacedDigit()
        }
        .lineLimit(1)
        .fixedSize()
        .foregroundStyle(Palette.onBackground)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityIdentifier("detail.userRating")
    }
}

extension View {
    /// Hosts the rating picker (`DetailRatingSheet`) while `isPresented`, which the action row's Rate sets
    /// (`DetailAction.rate`: ⋯'s `detail.overflow.rate`, on Apple TV the round `detail.rate`). On iPhone a sheet at half
    /// height that pulls up to full, with the grabber and no close button; on iPad a form sheet with Close; on the Mac a
    /// window sheet with Close (Escape); on Apple TV a `TrailingPanel`, which the remote's Menu closes, focus going back
    /// to Rate (`DetailFocus.action(.rate)`).
    func detailRatingPicker(
        isPresented: Binding<Bool>,
        rating: DetailUserRating?,
        onRate: @escaping (Int?) -> Void,
        focus: FocusState<DetailFocus?>.Binding
    ) -> some View {
        modifier(DetailRatingPicker(isPresented: isPresented, rating: rating, onRate: onRate, focus: focus))
    }
}

private struct DetailRatingPicker: ViewModifier {
    @Binding var isPresented: Bool
    let rating: DetailUserRating?
    let onRate: (Int?) -> Void
    let focus: FocusState<DetailFocus?>.Binding

    #if os(tvOS)
    /// The panel has opened since the row appeared: as it closes, focus is asked back for Rate (below).
    @State private var opened = false
    #else
    @Environment(\.formFactor) private var formFactor
    #endif

    func body(content: Content) -> some View {
        #if os(tvOS)
        content
            .trailingPanel(isPresented: $isPresented, id: "detail.userRating.sheet") {
                if let rating {
                    DetailRatingSheet(rating: rating, onRate: onRate)
                }
            }
            // The focus engine gives focus back to Rate as the panel goes; should nothing have it then, Rate asks,
            // until the page goes or the panel opens again.
            .onChange(of: isPresented) { _, shown in
                if shown { opened = true }
            }
            .task(id: isPresented) {
                guard opened, !isPresented else { return }
                await FocusRestore.restore(focus, to: .action(.rate))
            }
        #else
        content
            .sheet(isPresented: $isPresented) {
                if let rating {
                    DetailRatingSheet(rating: rating, onRate: onRate, compact: formFactor == .compact) { isPresented = false }
                }
            }
        #endif
    }
}

/// The rating picker, titled "Your rating": a muted line saying the score goes to every service listed, the score as
/// five half stars with the score beside them (`DetailRatingStars`), each tracker's line (`DetailRatingTrackers`), and
/// "Remove rating" at the foot, at its trailing end, once some tracker has a score (`DetailUserRating.text`). A score
/// rates the title at once and the picker stays open while the trackers answer. iOS: the title in the sheet's bar, the
/// rest scrolling under it; iPhone (`compact`) half height pulled up to full with the grabber, iPad a form sheet with
/// Close. The Mac: the title heading the sheet, Close (Escape) after Remove rating. Apple TV: the panel's heading, focus
/// on the stars as it opens, and on them again when Remove rating goes from under it. Harness names:
/// `detail.userRating.sheet`, `detail.userRating.stars`, `detail.userRating.tracker.<service id>`,
/// `detail.userRating.clear`, on iOS and the Mac `detail.userRating.close`.
struct DetailRatingSheet: View {
    let rating: DetailUserRating
    let onRate: (Int?) -> Void
    #if !os(tvOS)
    let compact: Bool
    let onClose: () -> Void
    #else
    @FocusState private var focus: DetailRatingFocus?
    #endif

    var body: some View {
        #if os(iOS)
        NavigationStack {
            ScrollView {
                content
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 20)
            }
            .scrollBounceBehavior(.basedOnSize)
            .pageTitle(T.shared.detail.userRating.title)
            .toolbar {
                if !compact {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(id: "detail.userRating.close", action: onClose) }
                }
            }
        }
        .presentationDetents(compact ? [.medium, .large] : [.large])
        .presentationDragIndicator(compact ? .visible : .hidden)
        .formSheetSizing()
        #elseif os(macOS)
        VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
            Text(T.shared.detail.userRating.title)
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            content
        }
        .padding(24)
        .frame(width: 480, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        #else
        ScrollView {
            VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
                Text(T.shared.detail.userRating.title)
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                content
            }
            .padding(.trailing, 48)
            .padding(.vertical, 48)
        }
        .scrollClipDisabled()
        .defaultFocus($focus, .stars, priority: .userInitiated)
        // The screen under the panel stays focusable: focus moves in as the panel shows, and back to the stars when
        // Remove rating, holding it, goes.
        .task(id: rating.text != nil) { await FocusRestore.restore($focus, to: .stars) }
        #endif
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
            Text(T.shared.detail.userRating.hint)
                .font(.subheadline)
                .foregroundStyle(Palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            #if os(tvOS)
            DetailRatingStars(score: rating.score?.intValue, onRate: onRate, focus: $focus)
            #else
            DetailRatingStars(score: rating.score?.intValue, onRate: onRate)
            #endif
            DetailRatingTrackers(lines: rating.trackers)
            foot
        }
        .accessibilityElement(children: .contain)
        #if !os(tvOS)
        .accessibilityIdentifier("detail.userRating.sheet")
        #endif
    }

    /// Remove rating once there is a score, at the trailing end; on the Mac Close after it.
    @ViewBuilder
    private var foot: some View {
        #if os(macOS)
        HStack(spacing: 12) {
            Spacer(minLength: 0)
            if rating.text != nil { removeButton }
            ActionButton(title: T.shared.nav.close, id: "detail.userRating.close", fullWidth: false, action: onClose)
                .keyboardShortcut(.cancelAction)
        }
        #else
        if rating.text != nil {
            HStack {
                Spacer(minLength: 0)
                removeButton
            }
            #if os(tvOS)
            // As wide as the panel, so Down from the stars reaches the button.
            .focusSection()
            #endif
        }
        #endif
    }

    private var removeButton: some View {
        ActionButton(title: T.shared.detail.userRating.clear, id: "detail.userRating.clear", fullWidth: false) { onRate(nil) }
        #if os(tvOS)
            .focused($focus, equals: .clear)
        #endif
    }
}

/// What has focus in the Apple TV rating panel.
enum DetailRatingFocus: Hashable {
    case stars, clear
}

/// The score 1–10 as five stars, each half one point (4½ stars: 9; the 4th star's trailing half 8): the given half
/// stars in the text colour, the rest a dim solid fill (never an outline), with the score large beside them, a dash
/// without one (`score` nil: unrated, or the trackers differing). The stars are `RatingMetrics.starMax` wide where the
/// room allows, smaller where it doesn't. A tap or a click on a half star gives its score; a drag along them lights the
/// stars under the finger or pointer and gives the score on release (the gap after a star counts as its trailing
/// half).
/// On Apple TV the stars are one focus stop, its focus a solid fill behind them: Left and Right move the score a
/// point, Select gives it; the Mac's keyboard (with keyboard navigation on) the same, Return or Space giving it. In a
/// right-to-left language the stars run from the right, each lit from its right, a tap or a drag counts them from the
/// right, and Left raises the score. One element for VoiceOver (`detail.userRating.stars`), "Your rating" valued "8" or
/// "Not rated", adjustable: a swipe up or down gives the score a point higher or lower.
struct DetailRatingStars: View {
    let score: Int?
    let onRate: (Int?) -> Void
    #if os(tvOS)
    let focus: FocusState<DetailRatingFocus?>.Binding
    #endif

    /// The point being chosen: under the finger or pointer while it drags, or moved to by Left and Right until Select
    /// gives it. It goes as the trackers' score changes.
    @State private var pending: Int?
    @State private var width: CGFloat = 0
    @Environment(\.layoutDirection) private var layoutDirection
    #if os(macOS)
    @FocusState private var keyboardFocused: Bool
    #endif
    /// The score's size and its least width (`RatingMetrics`), following Dynamic Type as the large title does.
    @ScaledMetric(relativeTo: .largeTitle) private var scoreSize: CGFloat = RatingMetrics.scoreSize
    @ScaledMetric(relativeTo: .largeTitle) private var scoreWidth: CGFloat = RatingMetrics.scoreWidth

    var body: some View {
        let shown = pending ?? score
        HStack(spacing: RatingMetrics.scoreGap) {
            stars(shown: shown)
            Text(shown.map(String.init) ?? "–")
                .font(.system(size: scoreSize, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(shown == nil ? Palette.onSurfaceVariant : Palette.onBackground)
                .frame(minWidth: scoreWidth, alignment: .leading)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .onChange(of: score) { pending = nil }
    }

    /// Each star's width: `RatingMetrics.starMax`, or what the room beside the score leaves.
    private var star: CGFloat {
        guard width > 0 else { return RatingMetrics.starMax }
        let room = width - scoreWidth - RatingMetrics.scoreGap - RatingMetrics.rowPadding * 2 - RatingMetrics.starGap * 4
        return min(RatingMetrics.starMax, max(room / 5, 1))
    }

    @ViewBuilder
    private func stars(shown: Int?) -> some View {
        #if os(tvOS)
        Button { if let pending { give(pending) } } label: { row(shown: shown) }
            .buttonStyle(StarsFocusStyle())
            .focused(focus, equals: .stars)
            .onMoveCommand { move($0, from: shown) }
            .ratingAccessibility(shown: shown, give: give)
        #else
        row(shown: shown)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { pending = point(at: $0.location.x) }
                    .onEnded { give(point(at: $0.location.x)) }
            )
            #if os(macOS)
            .background(keyboardFocused ? Palette.surfaceContainerHigh : .clear, in: RoundedRectangle(cornerRadius: RatingMetrics.focusRadius, style: .continuous))
            .focusable(true, interactions: .activate)
            .focusEffectDisabled()
            .focused($keyboardFocused)
            .onMoveCommand { move($0, from: shown) }
            .onKeyPress(.return) { givePending() }
            .onKeyPress(.space) { givePending() }
            #endif
            .ratingAccessibility(shown: shown, give: give)
        #endif
    }

    private func row(shown: Int?) -> some View {
        HStack(spacing: RatingMetrics.starGap) {
            ForEach(1...5, id: \.self) { index in
                let points = shown ?? 0
                StarGlyph(fill: points >= 2 * index ? 1 : points == 2 * index - 1 ? 0.5 : 0)
                    .frame(width: star, height: RatingMetrics.rowHeight)
            }
        }
        .padding(.horizontal, RatingMetrics.rowPadding)
        .frame(height: RatingMetrics.rowHeight)
        .contentShape(Rectangle())
    }

    #if !os(tvOS)
    /// The point (1–10) at `x` across the row, counted from its leading edge (the right right to left; `x` is from its
    /// left either way): a star's leading half its odd point, its trailing half and the gap after it the even one.
    private func point(at x: CGFloat) -> Int {
        let row = 5 * star + 4 * RatingMetrics.starGap + 2 * RatingMetrics.rowPadding
        let along = (layoutDirection == .rightToLeft ? row - x : x) - RatingMetrics.rowPadding
        let pitch = star + RatingMetrics.starGap
        let index = min(max(Int((along / pitch).rounded(.down)), 0), 4)
        return 2 * index + (along - CGFloat(index) * pitch < star / 2 ? 1 : 2)
    }
    #endif

    #if !os(iOS)
    /// Left and Right: the score a point lower or higher (higher and lower right to left), given by Select (Return or
    /// Space on the Mac).
    private func move(_ direction: MoveCommandDirection, from shown: Int?) {
        guard direction == .left || direction == .right else { return }
        if (direction == .right) == (layoutDirection == .leftToRight) {
            pending = min((shown ?? 0) + 1, 10)
        } else {
            pending = max((shown ?? 2) - 1, 1)
        }
    }
    #endif

    private func give(_ point: Int) {
        pending = nil
        if point != score { onRate(point) }
    }

    #if os(macOS)
    /// Return or Space: gives the point Left and Right moved to, if they moved.
    private func givePending() -> KeyPress.Result {
        guard let pending else { return .ignored }
        give(pending)
        return .handled
    }
    #endif
}

private extension View {
    /// The stars as VoiceOver's one adjustable element (`DetailRatingStars`).
    func ratingAccessibility(shown: Int?, give: @escaping (Int) -> Void) -> some View {
        accessibilityElement(children: .ignore)
            .accessibilityLabel(T.shared.detail.userRating.title)
            .accessibilityValue(shown.map(String.init) ?? T.shared.detail.userRating.notRated)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: give(min((shown ?? 0) + 1, 10))
                case .decrement: give(max((shown ?? 2) - 1, 1))
                @unknown default: break
                }
            }
            .accessibilityIdentifier("detail.userRating.stars")
    }
}

/// A star in the text colour from its leading edge as far as `fill` (0, ½ or 1), the rest a dim solid fill.
private struct StarGlyph: View {
    let fill: CGFloat

    var body: some View {
        let glyph = Image(systemName: "star.fill").resizable().aspectRatio(contentMode: .fit)
        glyph
            .foregroundStyle(Palette.onBackground.opacity(0.2))
            .overlay {
                if fill > 0 {
                    glyph
                        .foregroundStyle(Palette.onBackground)
                        .mask {
                            GeometryReader { proxy in
                                Rectangle().frame(width: proxy.size.width * fill)
                            }
                        }
                }
            }
    }
}

#if os(tvOS)
/// The stars' focus on Apple TV: a solid fill behind them while they have it, nothing grown or lifted; a click shrinks
/// them a little (`tvPressed`).
private struct StarsFocusStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Focused(configuration: configuration)
    }

    private struct Focused: View {
        let configuration: Configuration

        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .background(
                    focused ? Palette.surfaceContainer : .clear,
                    in: RoundedRectangle(cornerRadius: RatingMetrics.focusRadius, style: .continuous)
                )
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
        }
    }
}
#endif

/// Each tracker that rates the title, in the services' order, as grouped rows: its mark and name, then its score or
/// where its read or write is (`DetailTrackerScore.status`: Checking…, 7, Not rated, Saving…, Saved, Removed), in the
/// error colour where its score is unknown or not saved yet (Couldn't check, "Not sent yet, will retry", Not saved).
/// Each row one element, its name valued with its status (`detail.userRating.tracker.<service id>`).
private struct DetailRatingTrackers: View {
    let lines: [DetailTrackerScore]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(lines.enumerated()), id: \.element.service.id) { index, line in
                if index > 0 {
                    Divider().padding(.leading, RatingMetrics.lineInset + RatingMetrics.markSize + RatingMetrics.markGap)
                }
                HStack(spacing: RatingMetrics.markGap) {
                    Image(decorative: line.service.markImageName)
                        .resizable()
                        .scaledToFit()
                        .frame(width: RatingMetrics.markSize, height: RatingMetrics.markSize)
                        .accessibilityIgnoresInvertColors()
                    Text(line.name)
                        .foregroundStyle(Palette.onBackground)
                        .lineLimit(1)
                    Spacer(minLength: 16)
                    Text(line.status)
                        .font(.subheadline)
                        .foregroundStyle(line.attention ? Palette.error : Palette.onSurfaceVariant)
                        .multilineTextAlignment(.trailing)
                        .lineLimit(2)
                        .frame(maxWidth: RatingMetrics.statusWidth, alignment: .trailing)
                }
                .padding(.horizontal, RatingMetrics.lineInset)
                .frame(minHeight: RatingMetrics.lineHeight)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(line.name)
                .accessibilityValue(line.status)
                .accessibilityIdentifier("detail.userRating.tracker.\(line.service.id)")
            }
        }
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: RatingMetrics.groupRadius, style: .continuous))
    }
}

/// The picker's measures: Compose's dp in points on iOS and the Mac; on Apple TV, whose points are a 1080p screen's
/// pixels, the same share of the screen as on Compose's TV. The tracker watchlists' sheet (`WatchlistSheetView`) lays
/// its rows out with the same.
enum RatingMetrics {
    #if os(tvOS)
    static let spacing: CGFloat = 32
    static let starMax: CGFloat = 96
    static let starGap: CGFloat = 16
    static let rowPadding: CGFloat = 8
    static let rowHeight: CGFloat = 96
    static let scoreGap: CGFloat = 32
    static let scoreWidth: CGFloat = 112
    static let scoreSize: CGFloat = 72
    static let focusRadius: CGFloat = 24
    static let markSize: CGFloat = 48
    static let markGap: CGFloat = 32
    static let lineInset: CGFloat = 32
    static let lineHeight: CGFloat = 96
    static let statusWidth: CGFloat = 400
    static let groupRadius: CGFloat = Radius.large
    #else
    static let spacing: CGFloat = 16
    static let starMax: CGFloat = 48
    static let starGap: CGFloat = 8
    static let rowPadding: CGFloat = 4
    static let rowHeight: CGFloat = 48
    static let scoreGap: CGFloat = 16
    static let scoreWidth: CGFloat = 56
    static let scoreSize: CGFloat = 36
    static let focusRadius: CGFloat = 12
    static let markSize: CGFloat = 24
    static let markGap: CGFloat = 16
    static let lineInset: CGFloat = 16
    static let lineHeight: CGFloat = 52
    static let statusWidth: CGFloat = 200
    static let groupRadius: CGFloat = Radius.medium
    #endif
}

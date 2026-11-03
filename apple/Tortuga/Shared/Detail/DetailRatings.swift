import Nuke
import NukeUI
import SwiftUI
import TortugaKit

#if !os(tvOS)
/// The ratings row under the metadata line (`DetailHeader.ratings`, as the trackers answer): one item per score in the
/// store's order, then the user's own score (`DetailUserRatingScore`, read-only) once a tracker has one, plain on the
/// page, wrapping onto more lines when narrow, centred on phones. Absent while nobody has a score. The items are 1.2
/// times the scores' text size apart (16 pt on phones), the lines 0.6 times (8 pt).
struct DetailRatingsRow: View {
    let ratings: [DetailRating]
    let userRating: DetailUserRating?
    let centred: Bool
    let onOpenLink: (String) -> Void

    /// The scores' size: 13 pt on iPhone and iPad (following Dynamic Type), 12 on the Mac.
    @ScaledMetric(relativeTo: .footnote) private var textSize: CGFloat = Self.baseTextSize

    var body: some View {
        let own = userRating.flatMap { rating in rating.text.map { (text: $0, label: rating.label) } }
        if !ratings.isEmpty || own != nil {
            FlowLayout(
                spacing: (textSize * 1.2).rounded(),
                lineSpacing: (textSize * 0.6).rounded(),
                alignment: centred ? .center : .leading
            ) {
                ForEach(ratings, id: \.source.key) { rating in
                    DetailRatingItem(rating: rating, textSize: textSize, onOpenLink: onOpenLink)
                }
                if let own {
                    DetailUserRatingScore(text: own.text, label: own.label, textSize: textSize)
                }
            }
        }
    }

    private static var baseTextSize: CGFloat {
        #if os(macOS)
        12
        #else
        13
        #endif
    }
}
#endif

/// One score, with no fill or border: the source's logo at about the score's text height, in its own shape and centred
/// on the score, then the score as its site writes it (`88%`, `8.2`, Metacritic's `74`). One element for VoiceOver and
/// the UI tests (`detail.rating.<key>`, labelled "Rotten Tomatoes critics 88 percent"). On iOS and macOS IMDb's, which
/// carries the addon's IMDb link, is a button that opens it, drawn as Detail's text links are: the score underlined
/// while hovered, pressed or focused; the others do nothing. On Apple TV no rating takes focus.
struct DetailRatingItem: View {
    let rating: DetailRating
    let textSize: CGFloat
    let onOpenLink: (String) -> Void

    var body: some View {
        Group {
            #if os(tvOS)
            plain
            #else
            if let link = rating.linkId {
                Button { onOpenLink(link) } label: { content }
                    .buttonStyle(TextLinkButtonStyle())
            } else {
                plain
            }
            #endif
        }
        .accessibilityLabel(rating.label)
        .accessibilityIdentifier("detail.rating.\(rating.source.key)")
    }

    private var plain: some View {
        content
            .foregroundStyle(Palette.onBackground)
            .accessibilityElement(children: .ignore)
    }

    private var content: some View {
        HStack(spacing: (textSize * 0.45).rounded()) {
            Image(rating.logo.imageName)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(height: (textSize * 1.1).rounded())
                .accessibilityIgnoresInvertColors()
                .accessibilityHidden(true)
            Text(rating.text)
                .font(.system(size: textSize, weight: .semibold))
                .monospacedDigit()
        }
        .lineLimit(1)
        .fixedSize()
    }
}

private extension RatingLogo {
    /// Its image in RatingLogos.xcassets, each an SVG kept as vector data, at 64 pt tall. Plezy's SVGs
    /// (https://github.com/edde746/plezy, GPL-3.0 like this app: `assets/rating_icons/*.svg` and
    /// `assets/{trakt_circlemark,simkl_mark,mdblist_mark,mal_mark,anilist_mark}.svg`), with the parts Plezy tints in
    /// the text colour drawn white; the trackers' marks also stand before their names in Settings → Trackers
    /// (`TrackersSettingsPane`), MDBList's there only. Metacritic's roundel
    /// (https://commons.wikimedia.org/wiki/File:Metacritic_logo_Roundel.svg) and Letterboxd's three dots, cut out of
    /// its 2018 vertical logo (https://commons.wikimedia.org/wiki/File:Letterboxd_2018_logo_(vertical).svg), are from
    /// Wikimedia Commons, public domain there (below the threshold of originality). The marks are their owners'
    /// trademarks (Metacritic: Fandom, Inc., Letterboxd: Letterboxd Limited; IMDb, TMDB, Rotten Tomatoes, Trakt, Simkl,
    /// MDBList, MyAnimeList, AniList), shown only to say whose score follows or which service a row connects.
    var imageName: String {
        switch self {
        case .imdb: "rating-imdb"
        case .tmdb: "rating-tmdb"
        case .rtFresh: "rating-rt-fresh"
        case .rtRotten: "rating-rt-rotten"
        case .rtUpright: "rating-rt-upright"
        case .rtSpilled: "rating-rt-spilled"
        case .metacritic: "rating-metacritic"
        case .letterboxd: "rating-letterboxd"
        case .trakt: "rating-trakt"
        case .simkl: "rating-simkl"
        case .myanimelist: "rating-myanimelist"
        case .anilist: "rating-anilist"
        }
    }
}

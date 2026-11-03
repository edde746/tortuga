import Foundation
import TVServices

/// The Apple TV's Top Shelf as the app hands it to its extension: the running profile's Continue Watching, which the
/// shared core writes as JSON (`TopShelfPayload`, shared/…/presentation/homescreen) and the app keeps in the App
/// Group's defaults (`TopShelfPublisher`), and the extension shows when the system asks (`TopShelfProvider`). A profile
/// with a PIN, and a signed-out app, write no items: the system then shows the app's static Top Shelf image.
///
/// Compiled into the tvOS app (the App Group and the key it writes), the extension, and the tvOS UI tests'
/// `TopShelfContentTests`, which read the shared core's own fixture of the payload.
struct TopShelfContent: Decodable, Equatable {
    /// The App Group the app and its extension share, named after the app's bundle identifier.
    static let appGroup = "group.com.edde746.tortuga"
    /// The payload's key in the App Group's defaults.
    static let defaultsKey = "topShelf.continueWatching"
    /// The payload's format (`TopShelfPayload.SCHEMA_VERSION`): another one (an older or newer build's) shows nothing
    /// until the app writes its own.
    static let schemaVersion = 1

    /// One title of the row.
    struct Item: Decodable, Equatable {
        /// `<type>/<id>`: one item per title, as Continue Watching lists them.
        let id: String
        /// "S1E2 · Title" for an episode, the title for a movie.
        let title: String
        /// How far into its video playback got, 0 to 1; absent for none (up next, or under a second in).
        let progress: Double?
        let imageUrl: String?
        /// `poster` (2:3), `square` or `landscape` (16:9): the title's poster shape.
        let imageShape: String
        /// The Play/Pause button's link: resumes the video, as Home's Continue Watching card does.
        let playUrl: String
        /// Select's link: the title's Detail.
        let displayUrl: String
    }

    let schemaVersion: Int
    /// "Continue Watching" in the app's language: the extension has none of the app's strings.
    let sectionTitle: String
    let items: [Item]

    /// The payload in `data`, or nil when another schema version wrote it, whatever its other fields. Throws for bytes
    /// that aren't a payload of this version.
    static func decode(_ data: Data) throws -> TopShelfContent? {
        struct Version: Decodable { let schemaVersion: Int }
        let decoder = JSONDecoder()
        guard try decoder.decode(Version.self, from: data).schemaVersion == schemaVersion else { return nil }
        return try decoder.decode(TopShelfContent.self, from: data)
    }

    /// The row titled `sectionTitle` with the items in their order; nil when there is no item to show.
    func sectioned() -> TVTopShelfSectionedContent? {
        let shelfItems = items.compactMap(Self.shelfItem)
        guard !shelfItems.isEmpty else { return nil }
        let section = TVTopShelfItemCollection(items: shelfItems)
        section.title = sectionTitle
        return TVTopShelfSectionedContent(sections: [section])
    }

    /// The item as the shelf shows it; nil without both of its links (the shelf would open nothing).
    private static func shelfItem(_ item: Item) -> TVTopShelfSectionedItem? {
        guard let play = URL(string: item.playUrl), let display = URL(string: item.displayUrl) else { return nil }
        let shelfItem = TVTopShelfSectionedItem(identifier: item.id)
        shelfItem.title = item.title
        shelfItem.imageShape = switch item.imageShape {
        case "square": .square
        case "landscape": .hdtv
        default: .poster
        }
        if let progress = item.progress { shelfItem.playbackProgress = min(max(progress, 0), 1) }
        // One URL per scale, as TVServices keys them: a URL set for both scales at once reads nil for each alone.
        if let image = item.imageUrl.flatMap(URL.init(string:)) {
            shelfItem.setImageURL(image, for: .screenScale1x)
            shelfItem.setImageURL(image, for: .screenScale2x)
        }
        shelfItem.playAction = TVTopShelfAction(url: play)
        shelfItem.displayAction = TVTopShelfAction(url: display)
        return shelfItem
    }
}

import TVServices
import XCTest

/// The Top Shelf's mapping (TopShelf/TopShelfContent.swift, compiled into this bundle), on the shared core's own
/// fixture of the payload the app writes (shared/src/commonTest/fixtures/top-shelf/continue-watching.json, which
/// TopShelfPayloadTest checks `TopShelfPayload` writes): one row titled in the app's language with the items in their
/// order, each with its label (an episode's read without its direction marks), progress, poster and shape, Play/Pause
/// resuming its video and Select opening its title's Detail. A payload another build's format wrote, or one without
/// items, shows nothing (the system's static image); bytes that aren't a payload are an error. No app runs.
final class TopShelfContentTests: XCTestCase {
    func testTheSharedCoresPayloadIsOneTitledRowOfItsItems() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "continue-watching", withExtension: "json"))
        let content = try XCTUnwrap(try TopShelfContent.decode(Data(contentsOf: url)))
        let shelf = try XCTUnwrap(content.sectioned())
        XCTAssertEqual(shelf.sections.count, 1)
        let row = shelf.sections[0]
        XCTAssertEqual(row.title, "Continue Watching")
        XCTAssertEqual(row.items.map(\.identifier), ["series/tt0411008", "movie/tt0113277", "series/kitsu:1"])

        let episode = row.items[0]
        XCTAssertEqual(episode.title?.withoutDirectionMarks, "S2E5 · Lost")
        XCTAssertEqual(episode.playbackProgress, 0.25, accuracy: 0.0001)
        XCTAssertEqual(episode.imageShape, .poster)
        XCTAssertEqual(episode.imageURL(for: .screenScale1x), URL(string: "https://images.metahub.space/poster/medium/tt0411008/img"))
        XCTAssertEqual(episode.imageURL(for: .screenScale2x), URL(string: "https://images.metahub.space/poster/medium/tt0411008/img"))
        XCTAssertEqual(episode.playAction?.url, URL(string: "tortuga://play?profile=profile-1&type=series&id=tt0411008&video=tt0411008%3A2%3A5"))
        XCTAssertEqual(episode.displayAction?.url, URL(string: "tortuga://detail?profile=profile-1&type=series&id=tt0411008"))

        let movie = row.items[1]
        XCTAssertEqual(movie.title, "Heat")
        XCTAssertEqual(movie.playbackProgress, 0.5, accuracy: 0.0001)
        XCTAssertEqual(movie.imageShape, .square)
        XCTAssertEqual(movie.playAction?.url, URL(string: "tortuga://play?profile=profile-1&type=movie&id=tt0113277&video=tt0113277"))
        XCTAssertEqual(movie.displayAction?.url, URL(string: "tortuga://detail?profile=profile-1&type=movie&id=tt0113277"))

        // Up next on an episode-numbered series: no progress bar, no poster, a wide image slot.
        let upNext = row.items[2]
        XCTAssertEqual(upNext.title?.withoutDirectionMarks, "Episode 7 · Cowboy Bebop")
        XCTAssertEqual(upNext.playbackProgress, 0)
        XCTAssertEqual(upNext.imageShape, .hdtv)
        XCTAssertNil(upNext.imageURL(for: .screenScale2x))
        XCTAssertEqual(upNext.playAction?.url, URL(string: "tortuga://play?profile=profile-1&type=series&id=kitsu%3A1&video=kitsu%3A1%3A7"))
    }

    func testAnotherFormatOrNoItemShowsNothingAndOtherBytesAreAnError() throws {
        let newer = #"{"schemaVersion": 2, "rows": [{"title": "Continue Watching"}]}"#
        XCTAssertNil(try TopShelfContent.decode(Data(newer.utf8)))
        let empty = #"{"schemaVersion": 1, "sectionTitle": "Continue Watching", "items": []}"#
        let content = try XCTUnwrap(try TopShelfContent.decode(Data(empty.utf8)))
        XCTAssertNil(content.sectioned())
        XCTAssertThrowsError(try TopShelfContent.decode(Data(#"{"schemaVersion": 1, "items": 3}"#.utf8)))
        XCTAssertThrowsError(try TopShelfContent.decode(Data("not a payload".utf8)))
    }
}

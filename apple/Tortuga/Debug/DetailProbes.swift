import SwiftUI
import TortugaKit

/// The parts of the title's art whose placeholder shows (`detailArtPending`).
struct DetailArtPending: PreferenceKey {
    static let defaultValue: Set<String> = []

    static func reduce(value: inout Set<String>, nextValue: () -> Set<String>) {
        value.formUnion(nextValue())
    }
}

/// Debug builds: whether the title's backdrop and logo have drawn, as the label of an element the UI tests read
/// (`detail.art`; the Mac doesn't report a plain element's value): `backdrop=<drawn|loading|none>
/// logo=<drawn|loading|none>`, `loading` while its placeholder shows (the logo's is the name), `none` where the title
/// has none. The tests measure the page over its art once it reads drawn: a screenshot can't tell a picture still
/// loading from a dark one.
struct DetailArtProbe: View {
    let header: DetailHeader
    let pending: Set<String>

    var body: some View {
        Color.clear
            .frame(width: 1, height: 1)
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: "backdrop=\(reading("backdrop", backdrop)) logo=\(reading("logo", header.logo))"))
            .accessibilityIdentifier("detail.art")
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// The backdrop's picture: on Apple TV the poster where the title has no backdrop.
    private var backdrop: String? {
        #if os(tvOS)
        header.background ?? header.poster
        #else
        header.background
        #endif
    }

    private func reading(_ part: String, _ url: String?) -> String {
        url == nil ? "none" : pending.contains(part) ? "loading" : "drawn"
    }
}

import SwiftUI
import TortugaKit

/// A link nothing on this device opens (`AppOverlay.OpenOnPhone`; on Apple TV, which has no browser, any web page: an
/// addon's configuration, a trailer, a stream that plays outside the app): its QR code to open on a phone (a line saying
/// it can't be one where the link is too long for a QR code, `QRCodeImage`), the message saying so, the site it leads
/// to, and Close. The link itself is never written out: an addon's configuration link carries its keys. Escape presses
/// Close (iOS, macOS); on Apple TV the remote's Menu closes the page too.
///
/// Accessibility identifiers: `openOnPhone.qr`, `openOnPhone.site`, `openOnPhone.close`.
struct OpenOnPhonePage: View {
    let link: AppOverlayOpenOnPhone
    let onClose: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    #if os(tvOS)
    @FocusState private var focused: String?
    #endif

    var body: some View {
        AccountPage(title: link.title, intro: link.message, onBack: Self.backCloses ? onClose : nil) {
            QRCodeImage(payload: link.url, description: link.qrDescription, size: dimens.qrSize)
                .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                .accessibilityIdentifier("openOnPhone.qr")
            if let site = link.site {
                Text(site)
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .accessibilityIdentifier("openOnPhone.site")
            }
            ActionButton(title: link.closeLabel, id: "openOnPhone.close", fullWidth: false, action: onClose)
                #if os(tvOS)
                .focused($focused, equals: "close")
                #else
                .keyboardShortcut(.cancelAction)
                #endif
        }
        #if os(tvOS)
        // The screen under the panel stays focusable: Close takes focus as the panel shows.
        .task { await FocusRestore.restore($focused, to: "close") }
        #endif
    }

    /// The remote's Menu closes the page (tvOS); elsewhere Escape presses Close, and a back button would repeat it.
    private static var backCloses: Bool {
        #if os(tvOS)
        true
        #else
        false
        #endif
    }
}

#if os(tvOS)
extension View {
    /// Apple TV: `app`'s `AppOverlay.OpenOnPhone` in a `TrailingPanel` (`overlay.panel`) over whatever shows
    /// (`topmostCover`): the Addons details or install page, an install link's page and the Streams panel included. Gone
    /// another way than by the overlay closing (the system dismissed it), it closes the overlay too.
    func openOnPhoneCover(_ app: AppModel) -> some View {
        let session = app.session
        return topmostCover(item: app.overlay as? AppOverlayOpenOnPhone) { link in
            TrailingPanel(id: "overlay.panel") {
                OpenOnPhonePage(link: link, onClose: session.closeOverlay)
            }
            .onDisappear(perform: session.closeOverlay)
        }
    }
}
#endif

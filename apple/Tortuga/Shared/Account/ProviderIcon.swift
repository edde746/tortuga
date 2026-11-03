import SwiftUI
import TortugaKit

/// A sign-in provider's mark on its buttons and in the account's sign-ins: the Apple logo, Google's G, GitHub's
/// Invertocat, or `fallbackSymbol` (an SF Symbol) for a self-hoster's OpenID Connect provider.
struct ProviderIcon: View {
    let provider: String
    let fallbackSymbol: String

    var body: some View {
        switch provider {
        case IdentityProvider.shared.APPLE:
            Image(systemName: "apple.logo")
        case IdentityProvider.shared.GOOGLE:
            // An invisible symbol sizes the G, so it follows the button's font as the other buttons' symbols do.
            Image(systemName: "circle").hidden().overlay { GoogleLogo() }
        case IdentityProvider.shared.GITHUB:
            // Sized as the G; in the label's colour, as the Apple logo is.
            Image(systemName: "circle").hidden().overlay { GitHubLogo() }
        default:
            Image(systemName: fallbackSymbol)
        }
    }
}

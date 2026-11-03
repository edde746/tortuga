import SwiftUI
import TortugaKit

/// About: the app and its version, what the GPL-3.0 means with links to the source and the licence, the Trakt credit
/// under them, and every third-party component with its licence, each opening its page in the app (`NoticePane`).
///
/// Accessibility identifiers: `settings.about.source`, `settings.about.license`, `settings.about.notice.<i>`.
struct AboutPane: View {
    let about: AboutUiState
    let store: SettingsStore

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(about.appName).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    Text(about.version).font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
                Text(about.license)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
                SettingsLinkRow(
                    title: about.sourceLabel,
                    systemImage: "chevron.left.forwardslash.chevron.right",
                    trailingSymbol: "arrow.up.forward",
                    id: "settings.about.source",
                    action: store.openSourceCode
                )
                .accessibilityAddTraits(.isLink)
                SettingsLinkRow(
                    title: about.licenseLabel,
                    systemImage: "doc.text",
                    trailingSymbol: "arrow.up.forward",
                    id: "settings.about.license",
                    action: store.openLicense
                )
                .accessibilityAddTraits(.isLink)
            } footer: {
                Text(about.traktCredit)
            }
            Section(about.noticesHeader) {
                ForEach(Array(about.notices.enumerated()), id: \.offset) { index, notice in
                    SettingsLinkRow(
                        title: notice.name,
                        subtitle: notice.license,
                        id: "settings.about.notice.\(index)"
                    ) {
                        store.showNotice(index: Int32(index))
                    }
                }
            }
        }
        .settingsForm()
    }
}

import SwiftUI
import TortugaKit

/// The subtitles section over its store.
struct SubtitleSettingsRoute: View {
    let model: StoreModel<SubtitleSettingsStore, SubtitleSettingsUiState>

    var body: some View {
        let store = model.store
        SubtitleSettingsPane(
            state: model.observer.state,
            onStep: { store.stepNumber(field: $0, up: $1) },
            onChoose: { store.choose(key: $0, optionId: $1) },
            onToggle: { store.toggle(key: $0, checked: $1) }
        )
        .task { await model.observer.observe() }
    }
}

/// This device's subtitle style (never synced, as the note says) as a native form, with its preview in view while the
/// rows scroll: above them in a compact window and in an iOS window where the form would get less than 400 pt beside
/// the preview (the iPhone Duo's inner screen in portrait), beside them elsewhere on iPad, on the Mac and on Apple TV.
/// A menu gives its row's id and the option's to `onChoose`, a switch its row's id to `onToggle`.
///
/// Accessibility identifiers: `settings.subtitles.preview`, the steppers `settings.subtitles.<size|background|position>`
/// with their `.value` (`SettingsStepperRow`), the menus `settings.subtitles.<textColor|outline|outlineColor>` and the
/// switches `settings.subtitles.<bold|styledOverride>`.
struct SubtitleSettingsPane: View {
    let state: SubtitleSettingsUiState
    let onStep: (SubtitleNumber, Bool) -> Void
    let onChoose: (_ key: String, _ optionId: String) -> Void
    let onToggle: (_ key: String, _ checked: Bool) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.verticalBarEdge) private var verticalBar

    /// The least width that holds the form (400 pt) beside the preview and its margin.
    private static let besideWidth: CGFloat = 780

    var body: some View {
        switch formFactor {
        case .compact:
            stacked
        case .regular:
            #if os(iOS)
            GeometryReader { proxy in
                if proxy.size.width < Self.besideWidth { stacked } else { beside }
            }
            #else
            beside
            #endif
        case .tv:
            beside
        }
    }

    private var stacked: some View {
        VStack(spacing: 0) {
            // Beside the vertical bar at the safe area's edge, where the form's cards start.
            SubtitlePreviewFrame(preview: state.preview)
                .padding(ScreenSides(padding: 16, bar: verticalBar).insets)
                .padding(.vertical, 8)
            form
        }
        .background(Palette.background)
    }

    private var beside: some View {
        HStack(alignment: .top, spacing: 0) {
            form
            SubtitlePreviewFrame(preview: state.preview)
                .frame(width: formFactor == .tv ? 640 : 360)
                .padding(.top, formFactor == .tv ? 40 : 20)
                .padding(.trailing, formFactor == .tv ? 80 : 20)
        }
        .background(Palette.background)
    }

    private var form: some View {
        Form {
            Section(state.textHeader) {
                number(state.size, .size, "size")
                choice(state.textColor)
                toggle(state.bold)
            }
            Section(state.outlineHeader) {
                choice(state.outline)
                if let outlineColor = state.outlineColor {
                    choice(outlineColor)
                }
                number(state.background, .background, "background")
            }
            Section(state.positionHeader) {
                number(state.position, .position, "position")
            }
            Section {
                toggle(state.styledOverride)
            } header: {
                Text(state.styledHeader)
            } footer: {
                Text(state.note)
            }
        }
        .settingsForm()
    }

    private func number(_ row: SettingStepperRow, _ field: SubtitleNumber, _ name: String) -> some View {
        SettingsStepperRow(row: row, id: "settings.subtitles.\(name)") { onStep(field, $0) }
    }

    private func choice(_ row: SettingChoiceRow) -> some View {
        SettingsChoicePicker(row: row, id: "settings.subtitles.\(row.id)") { onChoose(row.id, $0) }
    }

    private func toggle(_ row: SettingToggleRow) -> some View {
        SettingsToggleRow(row: row, id: "settings.subtitles.\(row.id)") { onToggle(row.id, $0) }
    }
}

/// The sample line over a stand-in picture (a dusky gradient, light at the top and dark at the bottom, so light and
/// dark text both show what the outline and the box do), every length in proportion to the frame's height as the
/// player's are to the video's.
private struct SubtitlePreviewFrame: View {
    let preview: SubtitlePreview

    var body: some View {
        GeometryReader { geometry in
            let height = geometry.size.height
            ZStack(alignment: .bottom) {
                LinearGradient(
                    colors: [Color(rgb: 0x6E8BA6), Color(rgb: 0x3B4A57), Color(rgb: 0x15191D)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                line(height: height)
                    .padding(.horizontal, 8)
                    .padding(.bottom, height * preview.bottom)
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Radius.medium))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(preview.text)
        .accessibilityIdentifier("settings.subtitles.preview")
    }

    @ViewBuilder
    private func line(height: CGFloat) -> some View {
        let text = Text(preview.text)
            .font(.system(size: max(height * preview.fontSize, 1), weight: preview.bold ? .bold : .regular))
        let outline = height * preview.outlineWidth
        let drawn = text
            .foregroundStyle(Color(argb: preview.textColor))
            .multilineTextAlignment(.center)
            .background {
                // libass draws the outline outside each letter: copies of the text in its colour around the fill.
                if outline > 0 {
                    ZStack {
                        ForEach(0..<16, id: \.self) { step in
                            let angle = Double(step) / 16 * 2 * .pi
                            text.foregroundStyle(Color(argb: preview.outlineColor))
                                .multilineTextAlignment(.center)
                                .offset(x: outline * cos(angle), y: outline * sin(angle))
                        }
                    }
                }
            }
        if let box = preview.boxColor {
            drawn
                .padding(height * preview.boxPadding)
                .background(Color(argb: box.int64Value))
        } else {
            drawn
        }
    }
}

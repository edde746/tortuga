import SwiftUI
import TortugaKit
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The building blocks of the Settings screens, all native: a grouped `Form` on black, rows that open something,
/// switches (`Toggle`), steppers (`Stepper`; a Left/Right value on tvOS, which has none), menus (`Picker`; on tvOS a
/// row that opens its values as a list) and modal forms in sheets. Every text comes from the shared stores or `T`. A
/// row's title and subtitle show whole, and on iOS and macOS its control (or value) stays beside them while they keep a
/// readable column, else goes under them (`SettingsRowLayout`). A form's text field shows its hint inside it (`prompt`)
/// and hides a label that would only repeat the hint (`labelsHidden`): a Mac form draws a field's label at the row's
/// start, beside the text.

extension View {
    /// A settings page: a grouped form on the black background, a readable column on wide windows and TV. In a Mac
    /// sheet (`settingsSheet`) the form keeps the sheet's own background, which the sheet's title and button bars have
    /// too. A page with nothing to focus at the moment (`idle`: loading, its actions disabled while one runs) holds
    /// focus on Apple TV (`holdsBackFocus`).
    func settingsForm(idle: Bool = false) -> some View {
        modifier(SettingsFormStyle(idle: idle))
    }
}

private struct SettingsFormStyle: ViewModifier {
    let idle: Bool

    @Environment(\.isInSheet) private var isInSheet
    #if os(macOS)
    @Environment(\.formSetsSheetHeight) private var setsSheetHeight
    #endif

    func body(content: Content) -> some View {
        content
            .formStyle(.grouped)
            #if os(tvOS)
            // A focused row's platter reaches past the column's edge: the form doesn't cut it off.
            .scrollClipDisabled()
            #else
            .scrollContentBackground(blackPage ? .hidden : .automatic)
            #endif
            #if os(macOS)
            .modifier(SheetHeightFromForm(active: setsSheetHeight))
            #endif
            .readableColumn()
            .background(blackPage ? Palette.background : .clear)
            .holdsBackFocus(idle)
    }

    private var blackPage: Bool {
        #if os(macOS)
        !isInSheet
        #else
        true
        #endif
    }
}

extension View {
    /// A list or form as a column centred in wide windows and on TV, as wide as the window on phones. Narrower than
    /// the width it is centred in (the iPhone Duo's inner screen beside the vertical bar), the column keeps off the
    /// window's sides, so nothing in it is beside the bar (`verticalBarEdge`, `screenSides`).
    func readableColumn() -> some View {
        modifier(ReadableColumn())
    }
}

private struct ReadableColumn: ViewModifier {
    @Environment(\.formFactor) private var formFactor
    /// The column is narrower than the width it is centred in.
    @State private var narrower = false

    func body(content: Content) -> some View {
        let width = maxWidth
        content
            .transformEnvironment(\.verticalBarEdge) { if narrower { $0 = nil } }
            .frame(maxWidth: width)
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: Bool.self) { $0.size.width > width } action: { narrower = $0 }
    }

    private var maxWidth: CGFloat {
        switch formFactor {
        case .compact: .infinity
        case .regular: 720
        case .tv: 1200
        }
    }
}

/// A row's title, optional subtitle and optional `error` (why the setting doesn't take effect), in the error colour,
/// each shown whole: as many lines as its text takes at the width given. In a disabled row (a switch that takes no
/// change) the text fades, as the system's disabled controls do.
struct SettingsRowText: View {
    let title: String
    let subtitle: String?
    var error: String?

    @Environment(\.isEnabled) private var enabled

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let subtitle {
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Palette.error)
            }
        }
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// A note among a form's rows (a warning beside the action it concerns), not a control: its symbol and text in the
/// rows' subtitle style (`SettingsRowText`), the symbol scaled with the text.
struct SettingsNoteLabel: View {
    let text: String
    let systemImage: String

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

/// A row that opens something (a section, a list, a page outside the app): its icon, `title`, `subtitle` and `error`
/// (`SettingsRowText`), a `value` on the trailing side and `trailingSymbol` (a chevron, as a navigation link has; an arrow
/// for a page outside the app), the value and symbol beside the text or under it (`SettingsControlRow`; on tvOS always
/// beside). The whole row is the button; `id` is its accessibility identifier.
struct SettingsLinkRow: View {
    let title: String
    var subtitle: String?
    var value: String?
    var error: String?
    var systemImage: String?
    var trailingSymbol: String? = "chevron.forward"
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            #if os(tvOS)
            HStack(spacing: 12) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .frame(width: 48)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                SettingsRowText(title: title, subtitle: subtitle, error: error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                trailing
            }
            .contentShape(Rectangle())
            #else
            SettingsControlRow(title: title, subtitle: subtitle, error: error, systemImage: systemImage, spacing: 12) {
                trailing
            }
            .contentShape(Rectangle())
            #endif
        }
        .formRowButtonStyle()
        .accessibilityIdentifier(id)
    }

    /// The value, whole (several lines where it is wider than the row), and the symbol, which accessibility leaves out
    /// (a page outside the app is a link: `isLink`).
    @ViewBuilder
    private var trailing: some View {
        if let value {
            Text(value)
                .multilineTextAlignment(.trailing)
                .foregroundStyle(.secondary)
        }
        if let trailingSymbol {
            Image(systemName: trailingSymbol)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
    }
}

/// An on/off setting: the system switch with the row's title and subtitle, on iOS and macOS beside them or under them
/// (`SettingsControlRow`). VoiceOver and UI tests read it as a switch named by the title whose value is 1 while on. Not
/// `enabled`, it is the system's disabled switch: it keeps `checked`, takes no change and, on tvOS, no focus.
struct SettingsToggleRow: View {
    let title: String
    let subtitle: String?
    let checked: Bool
    let id: String
    var enabled = true
    let onChange: (Bool) -> Void

    var body: some View {
        let isOn = Binding(get: { checked }, set: { onChange($0) })
        #if os(tvOS)
        Toggle(isOn: isOn) {
            SettingsRowText(title: title, subtitle: subtitle)
        }
        .accessibilityIdentifier(id)
        .disabled(!enabled)
        #else
        SettingsControlRow(title: title, subtitle: subtitle) {
            Toggle(title, isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Palette.switchOn)
                .accessibilityIdentifier(id)
        }
        .disabled(!enabled)
        #endif
    }
}

extension SettingsToggleRow {
    /// The switch of a store's `SettingToggleRow`.
    init(row: SettingToggleRow, id: String, onChange: @escaping (Bool) -> Void) {
        self.init(title: row.title, subtitle: row.subtitle, checked: row.checked, id: id, enabled: row.enabled, onChange: onChange)
    }
}

/// A bounded whole-number setting (`SettingStepperRow`): on iOS and macOS the title, then the value and the system
/// stepper beside it or under it (`SettingsControlRow`), the stepper named by the title with the value as its value; on
/// tvOS, which has no stepper, the value between two chevrons that the remote's Left and Right step.
///
/// Accessibility identifiers: `[id]` (the stepper), `[id].value`.
struct SettingsStepperRow: View {
    let row: SettingStepperRow
    let id: String
    let onStep: (_ up: Bool) -> Void

    var body: some View {
        #if os(tvOS)
        HStack(spacing: 24) {
            SettingsRowText(title: row.title, subtitle: row.subtitle)
                .frame(maxWidth: .infinity, alignment: .leading)
            TVStepperValue(title: row.title, value: row.valueLabel, id: "\(id).value") { up in
                if up ? row.canIncrease : row.canDecrease { onStep(up) }
            }
        }
        #else
        SettingsControlRow(title: row.title, subtitle: row.subtitle) {
            value
            Stepper(row.title, onIncrement: increment, onDecrement: decrement)
                .labelsHidden()
                .accessibilityValue(row.valueLabel)
                .accessibilityIdentifier(id)
        }
        #endif
    }

    #if !os(tvOS)
    private var value: some View {
        Text(row.valueLabel)
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("\(id).value")
    }

    private func increment() {
        if row.canIncrease { onStep(true) }
    }

    private func decrement() {
        if row.canDecrease { onStep(false) }
    }
    #endif
}

#if os(tvOS)
/// A stepper's value on tvOS: one focusable control between two chevrons, each pointing its way along the reading
/// direction; the remote's press towards the forward one (Right, Left in a right-to-left language, as a mirrored slider)
/// steps it up, the other down. Up and Down leave it. Focused, it turns white with black text, as the system's rows do.
/// VoiceOver reads the row's title as its name and the value as its value, and steps it as an adjustable element.
private struct TVStepperValue: View {
    let title: String
    let value: String
    let id: String
    let onStep: (Bool) -> Void

    @FocusState private var focused: Bool
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "chevron.backward")
            Text(value).lineLimit(1).monospacedDigit().frame(minWidth: 180)
            Image(systemName: "chevron.forward")
        }
        .font(.headline)
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .foregroundStyle(focused ? Palette.onPrimary : Palette.onBackground)
        .background(focused ? Palette.primary : Palette.surfaceContainerHigh, in: Capsule())
        .scaleEffect(focused ? 1.05 : 1)
        .animation(.easeOut(duration: 0.15), value: focused)
        .focusable()
        .focused($focused)
        .onMoveCommand { direction in
            switch direction {
            case .left: onStep(layoutDirection == .rightToLeft)
            case .right: onStep(layoutDirection == .leftToRight)
            default: break
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value)
        // VoiceOver's swipe up and down: Left and Right move its cursor, not focus.
        .accessibilityAdjustableAction { onStep($0 == .increment) }
        .accessibilityIdentifier(id)
    }
}
#endif

/// A setting with a fixed list of values (`SettingChoiceRow`): on iOS and macOS the title, then the system's menu picker
/// showing the value beside it or under it (`SettingsControlRow`); on tvOS, whose menu picker in a form shows the value
/// alone, without the row's title, a row like the others (title, subtitle and value) that opens the values as a list,
/// the chosen one checked (`SettingChoiceList`). `onSelect` takes the option's id. Not `row.enabled`, it is dimmed and
/// doesn't open.
///
/// Accessibility identifiers: `id`; on tvOS the list's values `<id>.option.<option id>`.
struct SettingsChoicePicker: View {
    let row: SettingChoiceRow
    let id: String
    let onSelect: (String) -> Void

    #if os(tvOS)
    @State private var choosing = false
    #endif

    var body: some View {
        #if os(tvOS)
        SettingsLinkRow(title: row.title, subtitle: row.subtitle, value: row.valueLabel, id: id) { choosing = true }
            .settingsSheet(isPresented: $choosing) {
                SettingChoiceList(row: row, id: id) { optionId in
                    choosing = false
                    onSelect(optionId)
                } onClose: {
                    choosing = false
                }
            }
            .disabled(!row.enabled)
        #else
        SettingsControlRow(title: row.title, subtitle: row.subtitle) {
            Picker(row.title, selection: selection) { options }
                .pickerStyle(.menu)
                .labelsHidden()
                .fixedSize()
                .accessibilityIdentifier(id)
        }
        .disabled(!row.enabled)
        #endif
    }

    #if !os(tvOS)
    private var selection: Binding<String> {
        Binding(get: { row.options.first(where: \.selected)?.id ?? "" }, set: { onSelect($0) })
    }

    private var options: some View {
        ForEach(row.options, id: \.id) { option in
            Text(option.label).tag(option.id)
        }
    }
    #endif
}

#if !os(tvOS)
/// A settings row on iOS and macOS laid out by the rows' rule (`SettingsRowLayout`) rather than by the form: the icon
/// `systemImage` where given, then `title`, `subtitle` and `error` (`SettingsRowText`) and `trailing` (a switch, a menu,
/// a value and its stepper, a link's value and chevron), `spacing` apart.
struct SettingsControlRow<Trailing: View>: View {
    let title: String
    let subtitle: String?
    var error: String?
    var systemImage: String?
    var spacing: CGFloat = 8
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .frame(width: 28)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            SettingsRowLayout(
                leastTextWidth: SettingsRowLayout.leastTextWidth(title: title, subtitle: subtitle, dynamicTypeSize: dynamicTypeSize),
                spacing: spacing
            ) {
                SettingsRowText(title: title, subtitle: subtitle, error: error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: spacing) { trailing() }
            }
        }
    }
}

/// The settings rows' rule on iOS and macOS, for a row of two subviews, its text and its trailing part (a control, a
/// value): the trailing part at its ideal size beside the text, both on the row's centre line, `spacing` apart, while
/// that leaves the text at least `leastTextWidth`; otherwise the trailing part under the text, `stackedGap` below it at
/// the row's trailing edge (as wide as the row at most), and the text as wide as the row. tvOS's rows, 1200 pt wide,
/// keep their stacks.
struct SettingsRowLayout: Layout {
    let leastTextWidth: CGFloat
    var spacing: CGFloat = 8

    /// How far under the text the trailing part goes.
    static let stackedGap: CGFloat = 8

    /// The narrowest text column the trailing part stays beside, for a row titled `title` (in `titleStyle`, the body
    /// font unless given) with `subtitle` (footnote) at `dynamicTypeSize` (iOS; the Mac's text has one size): 8 em of
    /// the title's font, some 16 characters a line, two or three words; or the widest word of the title or subtitle in
    /// its font, where wider.
    static func leastTextWidth(
        title: String, subtitle: String?, titleStyle: PlatformFont.TextStyle = .body, dynamicTypeSize: DynamicTypeSize
    ) -> CGFloat {
        #if os(iOS)
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))
        let titleFont = UIFont.preferredFont(forTextStyle: titleStyle, compatibleWith: traits)
        let footnote = UIFont.preferredFont(forTextStyle: .footnote, compatibleWith: traits)
        #else
        let titleFont = NSFont.preferredFont(forTextStyle: titleStyle)
        let footnote = NSFont.preferredFont(forTextStyle: .footnote)
        #endif
        return max(8 * titleFont.pointSize, widestWord(title, font: titleFont), subtitle.map { widestWord($0, font: footnote) } ?? 0)
    }

    /// The width of `text`'s widest piece that a line can't break inside (a word with its punctuation; in Japanese,
    /// a character or a few), in `font`.
    private static func widestWord(_ text: String, font: PlatformFont) -> CGFloat {
        let string = text as NSString
        let tokenizer = CFStringTokenizerCreate(nil, text as CFString, CFRange(location: 0, length: string.length), kCFStringTokenizerUnitLineBreak, nil)
        var widest: CGFloat = 0
        while CFStringTokenizerAdvanceToNextToken(tokenizer) != [] {
            let range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            let word = string.substring(with: NSRange(location: range.location, length: range.length)).trimmingCharacters(in: .whitespacesAndNewlines)
            widest = max(widest, (word as NSString).size(withAttributes: [.font: font]).width)
        }
        return widest.rounded(.up)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let text = subviews.first else { return .zero }
        guard subviews.count > 1 else { return text.sizeThatFits(proposal) }
        let trailing = subviews[1]
        let ideal = trailing.sizeThatFits(.unspecified)
        guard let width = proposal.width else {
            let textSize = text.sizeThatFits(.unspecified)
            return CGSize(width: textSize.width + spacing + ideal.width, height: max(textSize.height, ideal.height))
        }
        if let textWidth = besideWidth(width, trailing: ideal) {
            return CGSize(width: width, height: max(text.sizeThatFits(ProposedViewSize(width: textWidth, height: nil)).height, ideal.height))
        }
        let textHeight = text.sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        let trailingHeight = trailing.sizeThatFits(ProposedViewSize(width: min(ideal.width, width), height: nil)).height
        return CGSize(width: width, height: textHeight + Self.stackedGap + trailingHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let text = subviews.first else { return }
        guard subviews.count > 1 else { return text.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size)) }
        let trailing = subviews[1]
        let ideal = trailing.sizeThatFits(.unspecified)
        if let textWidth = besideWidth(bounds.width, trailing: ideal) {
            text.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: textWidth, height: nil))
            trailing.place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing, proposal: ProposedViewSize(ideal))
        } else {
            let full = ProposedViewSize(width: bounds.width, height: nil)
            let textHeight = text.sizeThatFits(full).height
            text.place(at: bounds.origin, anchor: .topLeading, proposal: full)
            trailing.place(
                at: CGPoint(x: bounds.maxX, y: bounds.minY + textHeight + Self.stackedGap), anchor: .topTrailing,
                proposal: ProposedViewSize(width: min(ideal.width, bounds.width), height: nil)
            )
        }
    }

    /// The text's width beside the trailing part at its ideal size, where that leaves the text `leastTextWidth`.
    private func besideWidth(_ width: CGFloat, trailing: CGSize) -> CGFloat? {
        let textWidth = width - spacing - trailing.width
        return textWidth >= leastTextWidth ? textWidth : nil
    }
}

/// The platform's font, for measuring a row's words (`SettingsRowLayout`).
#if os(iOS)
typealias PlatformFont = UIFont
#else
typealias PlatformFont = NSFont
#endif
#endif

#if os(tvOS)
/// A setting's values as a list in a form, as tvOS's own Settings lists them: the chosen one checked and focused first;
/// picking one closes the list, as Menu does without picking.
private struct SettingChoiceList: View {
    let row: SettingChoiceRow
    let id: String
    let onPick: (String) -> Void
    let onClose: () -> Void

    @FocusState private var focused: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(row.options, id: \.id) { option in
                        Button { onPick(option.id) } label: {
                            HStack(spacing: 24) {
                                Text(option.label).frame(maxWidth: .infinity, alignment: .leading)
                                if option.selected { Image(systemName: "checkmark").accessibilityHidden(true) }
                            }
                        }
                        .focused($focused, equals: option.id)
                        .accessibilityAddTraits(option.selected ? .isSelected : [])
                        .accessibilityIdentifier("\(id).option.\(option.id)")
                    }
                } footer: {
                    if let subtitle = row.subtitle { Text(subtitle) }
                }
            }
            .settingsForm()
            .pageTitle(row.title)
            .defaultFocus($focused, row.options.first(where: \.selected)?.id)
        }
        .backAction(onClose)
    }
}
#endif

/// A store's notice ("Pixel 7 is signed out.", why a change failed) as a form row, until dismissed; its icon in
/// `iconStyle`, secondary in a form (a `NoticeCard`'s at full strength), or for an `error` (a file refused) the error
/// icon in the error colour. Its text is announced as it shows and as it changes: it comes after an action or on its
/// own, where VoiceOver isn't.
struct SettingsNotice: View {
    let text: String
    var iconStyle: HierarchicalShapeStyle = .secondary
    var error = false
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: error ? "exclamationmark.circle" : "info.circle")
                .foregroundStyle(error ? AnyShapeStyle(Palette.error) : AnyShapeStyle(iconStyle))
                .accessibilityHidden(true)
            Text(text).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onDismiss) {
                Image(systemName: "xmark").hitTargetLabel()
            }
            #if os(tvOS)
            .glassButtonStyle()
            .buttonBorderShape(.circle)
            #else
            .buttonStyle(.borderless)
            #endif
            #if os(macOS)
            .tooltip(T.shared.nav.dismiss)
            #endif
            .accessibilityLabel(T.shared.nav.dismiss)
            .accessibilityIdentifier("notice.dismiss")
            .hitTarget()
        }
        .announcing(text)
    }
}

/// Editing a typed setting (a stream filter's regular expression, the poster URL template), in a form: a field with the
/// stored `value` and `placeholder`, the line under it saying while it's typed why the setting would ignore the text
/// (`error`), else `subtitle`. Save stores the text (`onSave`), Clear stores "", Cancel leaves it as it was. tvOS types
/// it with the system keyboard. A `sensitiveURL` field takes an address that may hold a key: the URL keyboard, and the
/// system keeps it out of screenshots of private content.
///
/// Accessibility identifiers: `<id>.<field|error|save|clear|cancel>`.
struct SettingTextSheet: View {
    let title: String
    let subtitle: String
    let value: String
    let placeholder: String
    let saveLabel: String
    let clearLabel: String
    let id: String
    let sensitiveURL: Bool
    let error: (String) -> String?
    let onSave: (String) -> Void
    let onClose: () -> Void

    /// The field's text, from the stored value.
    @State private var text: String

    init(
        title: String, subtitle: String, value: String, placeholder: String, saveLabel: String, clearLabel: String, id: String,
        sensitiveURL: Bool = false, error: @escaping (String) -> String?, onSave: @escaping (String) -> Void, onClose: @escaping () -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.placeholder = placeholder
        self.saveLabel = saveLabel
        self.clearLabel = clearLabel
        self.id = id
        self.sensitiveURL = sensitiveURL
        self.error = error
        self.onSave = onSave
        self.onClose = onClose
        _text = State(initialValue: value)
    }

    var body: some View {
        let save = SheetAction(title: saveLabel, id: "\(id).save") {
            onSave(text)
            onClose()
        }
        let clear = SheetAction(title: clearLabel, id: "\(id).clear", role: .destructive, enabled: !text.isEmpty || !value.isEmpty) {
            onSave("")
            onClose()
        }
        let cancel = SheetAction(title: T.shared.settings.cancel, id: "\(id).cancel", action: onClose)
        NavigationStack {
            Form {
                Section {
                    TextField(title, text: $text, prompt: Text(placeholder))
                        .labelsHidden()
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        #if !os(macOS)
                        .keyboardType(sensitiveURL ? .URL : .default)
                        #endif
                        .privacySensitive(sensitiveURL)
                        .accessibilityIdentifier("\(id).field")
                } footer: {
                    if let error = error(text) {
                        Text(error)
                            .foregroundStyle(Palette.error)
                            .accessibilityIdentifier("\(id).error")
                    } else {
                        Text(subtitle)
                    }
                }
                #if !os(tvOS)
                Section {
                    Button(clear.title, role: .destructive, action: clear.action)
                        .disabled(!clear.enabled)
                        .accessibilityIdentifier(clear.id)
                }
                #endif
                SheetActionsSection(actions: [save, clear, cancel])
            }
            .settingsForm()
            .pageTitle(title)
            .sheetToolbar(cancel: cancel, confirm: save)
        }
        .backAction(onClose)
    }
}

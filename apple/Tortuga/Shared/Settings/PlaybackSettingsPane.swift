import SwiftUI
import TortugaKit

/// The playback section over its store: the pane, the language list editor and the Streams group's editors (the
/// qualities', dynamic ranges' or codecs' switches, a pattern's field); with `player` (where the device has player
/// apps), the Player row and its page.
struct PlaybackSettingsRoute: View {
    let model: StoreModel<PlaybackSettingsStore, PlaybackSettingsUiState>
    let player: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>?

    /// The Streams group's editor open over the pane.
    @State private var streamsEditor: StreamsEditor?

    var body: some View {
        let store = model.store
        let state = model.observer.state
        PlaybackSettingsPane(
            player: player,
            state: state,
            onChoose: { store.choose(key: $0, optionId: $1) },
            onToggle: { store.toggle(key: $0, checked: $1) },
            onStep: { store.stepNumber(field: $0, up: $1) },
            onOpenLanguages: { store.openLanguages(kind: $0) },
            onOpenStreamsEditor: { streamsEditor = $0 }
        )
        .task { await model.observer.observe() }
        .settingsSheet(item: state.languagePicker, size: .searchedForm, onDismiss: store.closeLanguages) { picker in
            LanguagePickerSheet(picker: picker, intents: LanguagePickerIntents(store))
        }
        .settingsSheet(item: streamsEditor, onDismiss: { streamsEditor = nil }) { editor in
            switch editor {
            case .qualities:
                StreamChoicesSheet(row: state.streamChoices(kind: .qualities), item: "quality", list: "qualities", onShown: { store.setStreamQualityShown(optionId: $0, shown: $1) }) {
                    streamsEditor = nil
                }
            case .ranges:
                StreamChoicesSheet(row: state.streamChoices(kind: .ranges), item: "range", list: "ranges", onShown: { store.setStreamRangeShown(optionId: $0, shown: $1) }) {
                    streamsEditor = nil
                }
            case .codecs:
                StreamChoicesSheet(row: state.streamChoices(kind: .codecs), item: "codec", list: "codecs", onShown: { store.setStreamCodecShown(optionId: $0, shown: $1) }) {
                    streamsEditor = nil
                }
            case .pattern(let kind):
                let row = state.streamPattern(kind: kind)
                let strings = T.shared.settings.playback.streams
                SettingTextSheet(
                    title: row.title, subtitle: row.subtitle, value: row.value, placeholder: row.placeholder,
                    saveLabel: strings.save, clearLabel: strings.clear, id: "settings.streams.pattern",
                    error: { store.patternError(pattern: $0) },
                    onSave: { store.setStreamPattern(kind: kind, pattern: $0) }
                ) {
                    streamsEditor = nil
                }
            }
        }
    }
}

/// Which of the Streams group's editors is open: the qualities', dynamic ranges' or codecs' switches, or one pattern's
/// field.
enum StreamsEditor: Equatable {
    case qualities
    case ranges
    case codecs
    case pattern(StreamPatternKind)
}

/// The profile's playback and language settings (per profile, synced, as the note says): the state's groups and rows in
/// their order (`PlaybackSettingsUiState.sections`), each group under its header and above its footer. This device's
/// Player (`PlayerSettingItem`, a device setting saying so) heads them where the device has player apps; the note is
/// the footer of the profile's first group, Languages. A choice (a menu) gives its row's id and the option's to
/// `onChoose`, a switch its row's id to `onToggle`. The language lists each open their editor, the Streams group's
/// switches and patterns theirs (`onOpenStreamsEditor`; a pattern the filters ignore shows why under its row).
///
/// Accessibility identifiers: `settings.playback.<audioLanguages|subtitleLanguages|secondSubtitleLanguage>`, the choices and switches
/// `settings.playback.<row id>` (`showSubtitles`, `bingeWatching`, `videoClick`, `skipIntros`, `skipRecaps`,
/// `skipCredits`, `streams.autoPlay`, `streams.suggestions`, `streams.minSize`, `streams.maxSize`),
/// the steppers `settings.playback.<watchedThreshold|upNextCountdown|seekShort|seekLong>` with
/// their `.value` (`SettingsStepperRow`) and `settings.playback.streams.<qualities|ranges|codecs|include|exclude>`.
struct PlaybackSettingsPane: View {
    /// This device's player; nil where it has no player apps.
    var player: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>?
    let state: PlaybackSettingsUiState
    let onChoose: (_ key: String, _ optionId: String) -> Void
    let onToggle: (_ key: String, _ checked: Bool) -> Void
    let onStep: (PlaybackNumber, Bool) -> Void
    let onOpenLanguages: (LanguageListKind) -> Void
    let onOpenStreamsEditor: (StreamsEditor) -> Void

    var body: some View {
        Form {
            ForEach(state.sections, id: \.group) { section in
                let footer = section.group == .languages ? state.note : section.footer
                let rows = ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in self.row(row) }
                if let header = section.header, let footer {
                    Section { rows } header: { Text(header) } footer: { Text(footer) }
                } else if let header = section.header {
                    Section(header) { rows }
                } else if let footer {
                    Section { rows } footer: { Text(footer) }
                } else {
                    Section { rows }
                }
            }
        }
        .settingsForm()
    }

    @ViewBuilder
    private func row(_ row: PlaybackSettingRow) -> some View {
        switch onEnum(of: row) {
        case .player:
            if let player { PlayerSettingItem(model: player) }
        case .languages(let languages):
            let id = switch languages.kind {
            case .audio: "settings.playback.audioLanguages"
            case .subtitles: "settings.playback.subtitleLanguages"
            case .secondSubtitle: "settings.playback.secondSubtitleLanguage"
            }
            SettingsLinkRow(title: languages.row.title, subtitle: languages.row.subtitle, value: languages.row.valueLabel, id: id) {
                onOpenLanguages(languages.kind)
            }
        case .choice(let choice):
            SettingsChoicePicker(row: choice.row, id: "settings.playback.\(choice.row.id)") { onChoose(choice.row.id, $0) }
        case .stepper(let stepper):
            SettingsStepperRow(row: stepper.row, id: "settings.playback.\((stepper.field as __PlaybackNumber).probeName)") { onStep(stepper.field, $0) }
        case .toggle(let toggle):
            SettingsToggleRow(row: toggle.row, id: "settings.playback.\(toggle.row.id)") { onToggle(toggle.row.id, $0) }
        case .streamChoices(let choices):
            SettingsLinkRow(title: choices.row.title, subtitle: choices.row.subtitle, value: choices.row.valueLabel, id: "settings.playback.streams.\((choices.kind as __StreamChoicesKind).probeName)") {
                onOpenStreamsEditor(Self.editor(choices.kind))
            }
        case .streamPattern(let pattern):
            let filter = pattern.row
            let id = filter.kind == .include ? "settings.playback.streams.include" : "settings.playback.streams.exclude"
            SettingsLinkRow(title: filter.title, subtitle: filter.subtitle, value: filter.valueLabel, error: filter.error, id: id) {
                onOpenStreamsEditor(.pattern(filter.kind))
            }
        }
    }

    /// The editor that shows a list of switches.
    private static func editor(_ kind: StreamChoicesKind) -> StreamsEditor {
        switch kind {
        case .qualities: .qualities
        case .ranges: .ranges
        case .codecs: .codecs
        }
    }
}

/// A filter's values the stream lists show, one switch each, in a form: the resolutions, the dynamic ranges or the
/// video codecs; Close (Escape; an iPhone's sheet has the grabber instead) closes it.
///
/// Accessibility identifiers: `settings.streams.<item>.<option id>` and `settings.streams.<list>.close` (`quality`,
/// `qualities`: `4k`, `1440p`, `1080p`, `720p`, `sd`, `unknown`; `range`, `ranges`: `sdr`, `hdr`, `dv`; `codec`,
/// `codecs`: `avc`, `hevc`, `av1`, `vp9`, `xvid`, `unknown`).
private struct StreamChoicesSheet: View {
    let row: SettingMultiChoiceRow
    let item: String
    let list: String
    let onShown: (_ optionId: String, _ shown: Bool) -> Void
    let onClose: () -> Void

    var body: some View {
        let close = SheetAction(title: T.shared.settings.close, id: "settings.streams.\(list).close", action: onClose)
        NavigationStack {
            Form {
                Section {
                    ForEach(row.options, id: \.id) { option in
                        Toggle(option.label, isOn: Binding(get: { option.selected }, set: { onShown(option.id, $0) }))
                            #if !os(tvOS)
                            .tint(Palette.switchOn)
                            #endif
                            .accessibilityIdentifier("settings.streams.\(item).\(option.id)")
                    }
                } footer: {
                    if let subtitle = row.subtitle { Text(subtitle) }
                }
                SheetActionsSection(actions: [close])
            }
            .settingsForm()
            .pageTitle(row.title)
            .sheetToolbar(close: close)
        }
        .backAction(onClose)
    }
}

/// What the language list editor sends the store whose list it edits: Playback's audio or subtitle languages, the
/// subtitle languages saved with downloads.
struct LanguagePickerIntents {
    let search: (String) -> Void
    let add: (String) -> Void
    let remove: (String) -> Void
    let move: (_ code: String, _ up: Bool) -> Void
    let moveTo: (_ code: String, _ index: Int32) -> Void
    let close: () -> Void
}

extension LanguagePickerIntents {
    init(_ store: PlaybackSettingsStore) {
        self.init(
            search: { store.searchLanguages(query: $0) },
            add: { store.addLanguage(code: $0) },
            remove: { store.removeLanguage(code: $0) },
            move: { store.moveLanguage(code: $0, up: $1) },
            moveTo: { store.moveLanguageTo(code: $0, index: $1) },
            close: { store.closeLanguages() }
        )
    }
}

/// Editing one language list, in a form: the chosen languages in order (on the Mac, iPhone and iPad each an
/// `OrderedRow` dragged by its handle, on iOS a touch held on it, and moved and removed from its ⋯, on the Mac a right
/// click too; moved and removed with their buttons on tvOS) and the others to add, searched by name. Close (Escape; an
/// iPhone's sheet has the grabber instead) closes it.
///
/// Accessibility identifiers: `settings.languages.chosen.<i>[.up|.down|.remove|.handle|.more]`,
/// `settings.languages.search`, `settings.languages.available.<i>`, `settings.languages.close`.
struct LanguagePickerSheet: View {
    let picker: LanguagePickerUiState
    let intents: LanguagePickerIntents

    /// The field keeps its own text: the store's state arrives a moment later, and typing must not wait for it. It
    /// starts as the store's (seeded here, not on appear, where it would go back to the store as if typed).
    @State private var query: String
    #if os(macOS)
    /// The code of the language being dragged by its handle.
    @State private var dragged: String?
    #endif

    init(picker: LanguagePickerUiState, intents: LanguagePickerIntents) {
        self.picker = picker
        self.intents = intents
        _query = State(initialValue: picker.query)
    }

    var body: some View {
        let close = SheetAction(title: T.shared.settings.close, id: "settings.languages.close", action: intents.close)
        NavigationStack {
            Form {
                Section {
                    if let none = picker.noneChosenText {
                        Text(none).foregroundStyle(.secondary)
                    }
                    #if os(macOS)
                    let codes = picker.chosen.map(\.code)
                    ForEach(Array(picker.chosen.enumerated()), id: \.element.code) { index, language in
                        let id = "settings.languages.chosen.\(index)"
                        let entries = entries(language, id: id)
                        OrderedRow(
                            id: language.code,
                            index: index,
                            ids: codes,
                            title: language.name,
                            probe: id,
                            handleLabel: language.dragLabel,
                            moreLabel: language.moreLabel,
                            entries: entries,
                            dragged: $dragged
                        ) { code, to in
                            intents.moveTo(code, Int32(to))
                        } content: {
                            ChosenLanguageRow(language: language, index: index, intents: intents)
                                .accessibilityActions(entries)
                        }
                    }
                    #elseif os(iOS)
                    ForEach(Array(picker.chosen.enumerated()), id: \.element.code) { index, language in
                        let id = "settings.languages.chosen.\(index)"
                        let entries = entries(language, id: id)
                        OrderedRow(title: language.name, probe: id, handleLabel: language.dragLabel, moreLabel: language.moreLabel, entries: entries) {
                            ChosenLanguageRow(language: language, index: index, intents: intents)
                                .accessibilityActions(entries)
                        }
                    }
                    .onMove { from, to in
                        guard let source = from.first else { return }
                        let language = picker.chosen[source]
                        // `to` counts the list with the row still in place.
                        intents.moveTo(language.code, Int32(to > source ? to - 1 : to))
                    }
                    #else
                    ForEach(Array(picker.chosen.enumerated()), id: \.element.code) { index, language in
                        ChosenLanguageRow(language: language, index: index, intents: intents)
                    }
                    #endif
                } header: {
                    Text(picker.chosenHeader)
                } footer: {
                    Text(picker.explanation)
                }
                Section(picker.availableHeader) {
                    TextField(picker.searchHint, text: $query, prompt: Text(picker.searchHint))
                        .labelsHidden()
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("settings.languages.search")
                        .onChange(of: query) { _, text in intents.search(text) }
                    if let empty = picker.emptyText {
                        Text(empty).foregroundStyle(.secondary)
                    }
                    ForEach(Array(picker.available.enumerated()), id: \.element.code) { index, option in
                        Button { intents.add(option.code) } label: {
                            Label(option.name, systemImage: "plus.circle")
                        }
                        .formRowButtonStyle()
                        .accessibilityIdentifier("settings.languages.available.\(index)")
                    }
                }
                SheetActionsSection(actions: [close])
            }
            .settingsForm()
            .pageTitle(picker.title)
            .sheetToolbar(close: close)
        }
        .backAction(intents.close)
    }

    #if !os(tvOS)
    /// Move up and Move down where `language` can go that way, then Remove.
    private func entries(_ language: SettingChosenLanguage, id: String) -> [ItemMenuEntry] {
        let text = T.shared.settings.playback
        var entries: [ItemMenuEntry] = []
        if language.canMoveUp {
            entries.append(ItemMenuEntry(label: text.moveUp, systemImage: "arrow.up", id: "\(id).up") { intents.move(language.code, true) })
        }
        if language.canMoveDown {
            entries.append(ItemMenuEntry(label: text.moveDown, systemImage: "arrow.down", id: "\(id).down") { intents.move(language.code, false) })
        }
        entries.append(
            ItemMenuEntry(label: text.removeLanguage, systemImage: "trash", id: "\(id).remove", destructive: true) { intents.remove(language.code) }
        )
        return entries
    }
    #endif
}

/// A chosen language: its rank and name; on tvOS, move up, move down and remove beside it.
private struct ChosenLanguageRow: View {
    let language: SettingChosenLanguage
    let index: Int
    let intents: LanguagePickerIntents

    var body: some View {
        let id = "settings.languages.chosen.\(index)"
        HStack(spacing: 12) {
            Text(language.rank)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 24, alignment: .leading)
            Text(language.name)
                .frame(maxWidth: .infinity, alignment: .leading)
            #if os(tvOS)
            IconButton(systemImage: "arrow.up", label: language.moveUpLabel, id: "\(id).up", enabled: language.canMoveUp) {
                intents.move(language.code, true)
            }
            IconButton(systemImage: "arrow.down", label: language.moveDownLabel, id: "\(id).down", enabled: language.canMoveDown) {
                intents.move(language.code, false)
            }
            IconButton(systemImage: "xmark", label: language.removeLabel, id: "\(id).remove") {
                intents.remove(language.code)
            }
            #endif
        }
        #if os(tvOS)
        // The circles' glass, one row of it.
        .glassGroup(spacing: 12)
        // One focus group per row: Down and Up reach the next row past its disabled Move down or Move up.
        .focusSection()
        #endif
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }
}

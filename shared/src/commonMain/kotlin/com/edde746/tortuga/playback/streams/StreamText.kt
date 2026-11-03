package com.edde746.tortuga.playback.streams

/**
 * A stream's `name` and `description` tidied for display in the stream list. Addons lay their text out for
 * Stremio's own list: some addons' custom formatters pad each name line with no-break spaces and zero-width joiners to
 * fill Stremio's fixed-width name column, and most addons leave runs of spaces and blank lines.
 *
 * In each line, a run of spaces (any Unicode space: no-break, en, em, ideographic) and zero-width characters that holds
 * at least one space becomes one space; zero-width characters at a line's ends go, and those between two visible
 * characters stay (a ZWJ joins an emoji sequence); lines are trimmed and blank ones dropped.
 */
object StreamText {
    /** [text]'s lines tidied, kept apart; null when nothing visible is left. */
    fun description(text: String?): String? = tidyLines(text)?.joinToString("\n")

    /** [text] tidied onto one line, its lines joined by a space: Stremio's name column wraps them, a row is wide. */
    fun name(text: String?): String? = tidyLines(text)?.joinToString(" ")

    private fun tidyLines(text: String?): List<String>? =
        text?.lines()?.map(::tidyLine)?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private fun tidyLine(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (!line[i].isBlankOrZeroWidth()) {
                out.append(line[i++])
                continue
            }
            val start = i
            var spaced = false
            while (i < line.length && line[i].isBlankOrZeroWidth()) {
                if (line[i].isWhitespace()) spaced = true
                i++
            }
            // A run at either end goes; between visible characters a spaced run is one space, a zero-width one stays.
            if (out.isNotEmpty() && i < line.length) {
                if (spaced) out.append(' ') else out.appendRange(line, start, i)
            }
        }
        return out.toString()
    }

    private fun Char.isBlankOrZeroWidth() = isWhitespace() || isZeroWidth()

    /**
     * [text] (a tidied description) as runs of one [StreamTextStyle] each, their texts in order making up [text] with the
     * styled Unicode letters written plainly. Stremio gives addons no styling, so formatters write it in Unicode, which
     * the system font mostly lacks: each such character would come from another font, at another size, weight and
     * baseline. Here they become plain letters for the UI to draw in its own font with the style applied:
     * - small capitals (`ᴇɴ`, `sᴜʙ`, `ʀᴇᴍᴜx`): a word (a run of letters) holding one, and else only small capitals or
     *   the letters formatters put in for those Unicode lacks or they missed (`s`, `x`, `ғ` for f, `ǫ` for q), is
     *   [StreamTextStyle.SMALL_CAPS] in lower case; a word with any other letter stays as written;
     * - superscript letters, digits and signs (`ᴹᵇᵖˢ`) [StreamTextStyle.SUPERSCRIPT], subscript ones (`₀₁`)
     *   [StreamTextStyle.SUBSCRIPT];
     * - text symbols (arrows, miscellaneous technical, geometric shapes, miscellaneous symbols, dingbats; `✎`, `▣`, `⛉`)
     *   [StreamTextStyle.SYMBOL], a variation selector 15 after one kept with it; one shown as an emoji (followed by
     *   variation selector 16, or an emoji by default: `⚡`) stays [StreamTextStyle.PLAIN].
     * Everything else is [StreamTextStyle.PLAIN], as written: a text without these characters is one plain run.
     */
    fun runs(text: String): List<StreamTextRun> {
        val runs = mutableListOf<StreamTextRun>()
        val pending = StringBuilder()
        var style = StreamTextStyle.PLAIN
        // Adds [char] in [next] style, ending the pending run where its style differs.
        fun add(char: Char, next: StreamTextStyle) {
            if (next != style && pending.isNotEmpty()) {
                runs += StreamTextRun(pending.toString(), style)
                pending.clear()
            }
            style = next
            pending.append(char)
        }
        // A super- or subscript character plainly in its style, any other as written.
        fun addScripted(char: Char) {
            SUPERSCRIPTS[char]?.let { return add(it, StreamTextStyle.SUPERSCRIPT) }
            SUBSCRIPTS[char]?.let { return add(it, StreamTextStyle.SUBSCRIPT) }
            add(char, StreamTextStyle.PLAIN)
        }
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char.isLetter()) {
                var end = i
                while (end < text.length && text[end].isLetter()) end++
                val smallCaps = (i until end).any { text[it] in SMALL_CAPITALS } &&
                    (i until end).all { text[it] in SMALL_CAPITALS || text[it] in SMALL_CAPITAL_STAND_INS }
                for (at in i until end) {
                    val letter = text[at]
                    if (smallCaps) {
                        add(SMALL_CAPITALS[letter] ?: SMALL_CAPITAL_STAND_INS.getValue(letter), StreamTextStyle.SMALL_CAPS)
                    } else {
                        addScripted(letter)
                    }
                }
                i = end
                continue
            }
            val next = text.getOrNull(i + 1)
            if (char.isTextSymbol() && next != EMOJI_PRESENTATION) {
                add(char, StreamTextStyle.SYMBOL)
                if (next == TEXT_PRESENTATION) add(text[++i], StreamTextStyle.SYMBOL)
            } else {
                addScripted(char)
            }
            i++
        }
        if (pending.isNotEmpty()) runs += StreamTextRun(pending.toString(), style)
        return runs
    }

    private fun Char.isTextSymbol(): Boolean =
        TEXT_SYMBOL_BLOCKS.any { this in it } && EMOJI_BY_DEFAULT.none { this in it }

    private const val TEXT_PRESENTATION = '\uFE0E'
    private const val EMOJI_PRESENTATION = '\uFE0F'

    /** Arrows, Miscellaneous Technical, Geometric Shapes, Miscellaneous Symbols, Dingbats, Miscellaneous Symbols and Arrows. */
    private val TEXT_SYMBOL_BLOCKS = listOf('\u2190'..'\u21FF', '\u2300'..'\u23FF', '\u25A0'..'\u27BF', '\u2B00'..'\u2BFF')

    /** The characters of [TEXT_SYMBOL_BLOCKS] shown as emoji by default (Unicode's Emoji_Presentation, 16.0). */
    private val EMOJI_BY_DEFAULT = listOf(
        '\u231A'..'\u231B', '\u23E9'..'\u23EC', '\u23F0'..'\u23F0', '\u23F3'..'\u23F3', '\u25FD'..'\u25FE',
        '\u2614'..'\u2615', '\u2648'..'\u2653', '\u267F'..'\u267F', '\u2693'..'\u2693', '\u26A1'..'\u26A1',
        '\u26AA'..'\u26AB', '\u26BD'..'\u26BE', '\u26C4'..'\u26C5', '\u26CE'..'\u26CE', '\u26D4'..'\u26D4',
        '\u26EA'..'\u26EA', '\u26F2'..'\u26F3', '\u26F5'..'\u26F5', '\u26FA'..'\u26FA', '\u26FD'..'\u26FD',
        '\u2705'..'\u2705', '\u270A'..'\u270B', '\u2728'..'\u2728', '\u274C'..'\u274C', '\u274E'..'\u274E',
        '\u2753'..'\u2755', '\u2757'..'\u2757', '\u2795'..'\u2797', '\u27B0'..'\u27B0', '\u27BF'..'\u27BF',
        '\u2B1B'..'\u2B1C', '\u2B50'..'\u2B50', '\u2B55'..'\u2B55',
    )

    /** Latin small capitals and their letters. */
    private val SMALL_CAPITALS = mapOf(
        'ᴀ' to 'a', 'ʙ' to 'b', 'ᴄ' to 'c', 'ᴅ' to 'd', 'ᴇ' to 'e', 'ꜰ' to 'f', 'ɢ' to 'g', 'ʜ' to 'h', 'ɪ' to 'i',
        'ᴊ' to 'j', 'ᴋ' to 'k', 'ʟ' to 'l', 'ᴍ' to 'm', 'ɴ' to 'n', 'ᴏ' to 'o', 'ᴘ' to 'p', 'ꞯ' to 'q', 'ʀ' to 'r',
        'ꜱ' to 's', 'ᴛ' to 't', 'ᴜ' to 'u', 'ᴠ' to 'v', 'ᴡ' to 'w', 'ʏ' to 'y', 'ᴢ' to 'z',
    )

    /** What formatters write among small capitals for the ones Unicode lacks (x) or they missed, and their letters. */
    private val SMALL_CAPITAL_STAND_INS = mapOf('s' to 's', 'x' to 'x', 'ғ' to 'f', 'ǫ' to 'q')

    /** Superscript (modifier) letters, digits and signs, and their plain characters. */
    private val SUPERSCRIPTS = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8',
        '⁹' to '9', '⁺' to '+', '⁻' to '-', '⁼' to '=', '⁽' to '(', '⁾' to ')',
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K',
        'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V',
        'ᵂ' to 'W', 'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e', 'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h',
        'ⁱ' to 'i', 'ᶦ' to 'i', 'ʲ' to 'j', 'ᵏ' to 'k', 'ˡ' to 'l', 'ᵐ' to 'm', 'ⁿ' to 'n', 'ᵒ' to 'o', 'ᵖ' to 'p',
        'ʳ' to 'r', 'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u', 'ᵛ' to 'v', 'ʷ' to 'w', 'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z',
    )

    /** Subscript letters, digits and signs, and their plain characters. */
    private val SUBSCRIPTS = mapOf(
        '₀' to '0', '₁' to '1', '₂' to '2', '₃' to '3', '₄' to '4', '₅' to '5', '₆' to '6', '₇' to '7', '₈' to '8',
        '₉' to '9', '₊' to '+', '₋' to '-', '₌' to '=', '₍' to '(', '₎' to ')',
        'ₐ' to 'a', 'ₑ' to 'e', 'ₕ' to 'h', 'ᵢ' to 'i', 'ⱼ' to 'j', 'ₖ' to 'k', 'ₗ' to 'l', 'ₘ' to 'm', 'ₙ' to 'n',
        'ₒ' to 'o', 'ₚ' to 'p', 'ᵣ' to 'r', 'ₛ' to 's', 'ₜ' to 't', 'ᵤ' to 'u', 'ᵥ' to 'v', 'ₓ' to 'x',
    )
}

/** A stretch of a stream's text in one [style] ([StreamText.runs]), its letters written plainly. */
data class StreamTextRun(val text: String, val style: StreamTextStyle)

/** How a [StreamTextRun] is drawn, in the text's own font. */
enum class StreamTextStyle {
    /** As the text's font draws it. */
    PLAIN,

    /** In the font's small capitals (OpenType `smcp`): the run is in lower case. */
    SMALL_CAPS,

    /** Smaller and raised. */
    SUPERSCRIPT,

    /** Smaller and lowered. */
    SUBSCRIPT,

    /** A text symbol, which the font may lack: one taken from another font is drawn at the text's size and baseline. */
    SYMBOL,
}

/** Zero-width space, non-joiner, joiner, word joiner and the byte order mark (a zero-width no-break space). */
internal fun Char.isZeroWidth() = this in '\u200B'..'\u200D' || this == '\u2060' || this == '\uFEFF'

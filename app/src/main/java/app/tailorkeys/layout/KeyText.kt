package app.tailorkeys.layout

enum class Layer { ABC, NUM, SYM }

/** What holding a letter offers (Prefs.LONG_PRESS_SHOWS, by ordinal). */
enum class HoldChoices { SYMBOLS, ACCENTS, BOTH }

/** What a key's small corner hint shows: nothing, its digit, or the first character holding it offers. */
enum class HintStyle { OFF, DIGITS, HOLD_CHOICE }

/** Which layer is showing and how letters are cased. Shared by the service and the view. */
class InputState {
    var language = "en"
    var layer = Layer.ABC
    var capsLock = false
    /** One-shot capital for the next letter, from auto-cap or a tap on ABC. */
    var shift = false
    val upper get() = layer == Layer.ABC && (capsLock || shift)
}

/** What each key code types and shows on each layer. */
object KeyText {
    private val alt = HashMap<String, Pair<String, String>>()

    init {
        fun map(src: String, num: String, sym: String) =
            src.forEachIndexed { i, ch -> alt[ch.toString()] = num[i].toString() to sym[i].toString() }
        map("qwertyuiop", "1234567890", "[]{}#%^*+=")
        map("asdfghjkl'", "@#\$%&-+()\"", "_\\|~<>€£¥•")
        map("zxcvbnm", "!?',.:;", "?!:,.';")
    }

    fun isLayerKey(k: String) = k == Keys.ABC || k == Keys.NUM || k == Keys.SYM
    /** A key on the 123 or #+= layer that types its text exactly as written, sent as "=" + text. */
    fun isLiteral(k: String) = k.length >= 2 && k[0] == '='
    fun literal(text: String) = "=$text"
    fun layerOf(k: String) = when (k) { Keys.NUM -> Layer.NUM; Keys.SYM -> Layer.SYM; else -> Layer.ABC }
    fun isDigit(k: String) = k.length == 2 && k[0] == 'd'
    fun isChar(k: String) = k.length == 1 || isDigit(k)

    /** The text a key types right now, or null for keys that don't type. */
    fun output(k: String, s: InputState): String? {
        if (isDigit(k) || isLiteral(k)) return k.substring(1)
        if (k.length != 1) return null
        return if (s.upper) k.uppercase() else k
    }

    /** The character offered on long-press: the key's number-layer character. */
    fun longPress(k: String, s: InputState): String? = if (k.length == 1 && s.layer == Layer.ABC) alt[baseKey(k, s.language)]?.first else null

    /** With the number row on, each digit offers the #+= character of the top-row letter under it. */
    private const val NUMBER_ROW_SYMBOLS = "[]{}#%^*+="

    /** The iOS English keyboard's accents, in the order its long-press row shows them. */
    private val englishAccents = mapOf(
        "a" to "àáâäæãåā", "c" to "çćč",
        "e" to "èéêëēėę", "i" to "îïíīįì", "l" to "ł",
        "n" to "ñń", "o" to "ôöòóœøōõ", "s" to "ßśš",
        "u" to "ûüùúū", "y" to "ÿ", "z" to "žźż")

    /**
     * The symbol holding [k] offers: the character the same key shows on the 123 layer. With the number row on, the
     * digits are already on screen, so the top letter row offers none and the number row offers #+= characters instead.
     */
    fun holdSymbol(k: String, s: InputState, numberRow: Boolean): String? {
        if (s.layer != Layer.ABC) return null
        if (isDigit(k)) return if (numberRow) NUMBER_ROW_SYMBOLS["1234567890".indexOf(k[1])].toString() else null
        val symbol = longPress(k, s) ?: return null
        return if (numberRow && symbol[0].isDigit()) null else symbol
    }

    /** The accented letters holding [k] offers in [language]: its own list for es, de and fr, the iOS list otherwise. */
    fun accents(k: String, language: String): String = when (language) {
        "es" -> mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "úü", "n" to "ñ", "ñ" to "n", "?" to "¿", "!" to "¡")
        "de" -> mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß", "ß" to "s")
        "fr" -> mapOf("a" to "àâæ", "e" to "éèêë", "i" to "îï", "o" to "ôœ", "u" to "ùûü", "c" to "ç", "y" to "ÿ")
        else -> englishAccents
    }[k].orEmpty()

    /**
     * What holding a key on 123 or #+= offers, by the text the key types: the iPhone's variants (0 to degree, dashes,
     * inverted ? and !, ellipsis, typographic quotes, per mille, section sign) and other currencies.
     */
    private val layerVariants = mapOf(
        "0" to "°", "-" to "–—•", "/" to "\\", "&" to "§", "%" to "‰",
        "?" to "¿", "!" to "¡", "." to "…",
        "\"" to "“”„«»", "'" to "‘’‚`",
        "$" to "¢£€¥₩", "€" to "$£¥₩", "£" to "€$¥", "¥" to "€$£",
        "•" to "·")

    /** The long-press row for [k] on 123 or #+= (see [layerVariants]); empty on the letters. */
    fun variants(k: String, s: InputState): List<String> =
        if (s.layer == Layer.ABC) emptyList() else output(k, s)?.let { layerVariants[it] }?.map { it.toString() }.orEmpty()

    /**
     * How a key's long-press is named in the saved list of keys with it turned off: its layer, then its code, since
     * the same code can sit on two layers (a number row digit and the 123 digit).
     */
    fun holdId(layer: Layer, k: String) = layer.name.lowercase() + ":" + k

    /**
     * The long-press row for [k], in order: on the letters the symbol first, then the accents, as [shows] allows; on
     * 123 and #+= the key's variants, whatever [shows] says (it picks between symbols and accents for letters).
     */
    fun alternatives(k: String, s: InputState, shows: HoldChoices, numberRow: Boolean): List<String> {
        if (s.layer != Layer.ABC) return variants(k, s)
        val symbol = if (shows != HoldChoices.ACCENTS) holdSymbol(k, s, numberRow) else null
        // A capital that is two letters (ß to SS) stays as it is.
        val accents = if (shows == HoldChoices.SYMBOLS || k.length != 1) emptyList()
            else accents(k, s.language).map { c -> c.toString().uppercase().takeIf { s.upper && it.length == 1 } ?: c.toString() }
        return (listOfNotNull(symbol) + accents).distinct()
    }

    /** The small corner hint for [k]: always the first character of its long-press row, so the two never disagree. */
    fun hint(k: String, s: InputState, style: HintStyle, shows: HoldChoices, numberRow: Boolean): String? {
        val first = if (style == HintStyle.OFF) null else alternatives(k, s, shows, numberRow).firstOrNull()
        return if (style == HintStyle.DIGITS) first?.takeIf { it.length == 1 && it[0].isDigit() } else first
    }

    /** Layer symbols and number hints follow the letter's position in the language preset. */
    private fun baseKey(k: String, lang: String): String {
        val variant = when (lang) {
            "es" -> "qwertyuiopasdfghjklñzxcvbnm"
            "de" -> "qwertzuiopasdfghjklßyxcvbnm"
            "fr" -> "azertyuiopqsdfghjklmwxcvbn'"
            else -> return k
        }
        val index = variant.indexOf(k).takeIf { k.length == 1 && it >= 0 } ?: return k
        return "qwertyuiopasdfghjkl'zxcvbnm"[index].toString()
    }

    /** A spoken name for the face the key types right now. */
    fun spoken(code: String, state: InputState, enterLabel: String?): String = when (code) {
        Keys.SPACE -> "space"
        Keys.BACKSPACE -> "backspace"
        Keys.ENTER -> enterLabel?.takeIf { it.isNotBlank() } ?: "enter"
        Keys.ABC -> "letters" + when { state.capsLock -> ", caps lock on"; state.shift -> ", shift on"; else -> "" }
        Keys.SHIFT -> "shift" + when { state.capsLock -> ", caps lock on"; state.shift -> ", on"; else -> "" }
        Keys.NUM -> "numbers"
        Keys.SYM -> "symbols"
        Keys.INSERT -> "Insert, emoji, kaomoji, clipboard and voice"
        else -> {
            val face = output(code, state) ?: code
            when (face) {
                "'" -> "apostrophe"
                "," -> "comma"
                "." -> "period"
                "?" -> "question mark"
                "!" -> "exclamation mark"
                "\"" -> "quotation mark"
                ":" -> "colon"
                ";" -> "semicolon"
                "-" -> "hyphen"
                "_" -> "underscore"
                "/" -> "slash"
                "\\" -> "backslash"
                "@" -> "at sign"
                "#" -> "number sign"
                "$" -> "dollar sign"
                "%" -> "percent sign"
                "&" -> "ampersand"
                "*" -> "asterisk"
                "+" -> "plus sign"
                "=" -> "equals sign"
                "(" -> "left parenthesis"
                ")" -> "right parenthesis"
                "[" -> "left bracket"
                "]" -> "right bracket"
                "{" -> "left brace"
                "}" -> "right brace"
                "<" -> "less than"
                ">" -> "greater than"
                "|" -> "vertical bar"
                "~" -> "tilde"
                "^" -> "caret"
                "€" -> "euro sign"
                "£" -> "pound sign"
                "¥" -> "yen sign"
                "•" -> "bullet"
                else -> if (state.upper && face.length == 1 && face[0].isLetter()) "capital $face" else face
            }
        }
    }

    fun label(k: String, s: InputState): String = when (k) {
        Keys.ABC -> when { s.capsLock -> "ABC"; s.shift && s.layer == Layer.ABC -> "Abc"; else -> "abc" }
        Keys.NUM -> "123"
        Keys.SYM -> "#+="
        Keys.INSERT -> "☺"
        Keys.SPACE, Keys.BACKSPACE, Keys.ENTER -> ""
        else -> output(k, s) ?: k
    }
}

package app.tailorkeys.layout

enum class Layer { ABC, NUM, SYM }

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
    fun layerOf(k: String) = when (k) { Keys.NUM -> Layer.NUM; Keys.SYM -> Layer.SYM; else -> Layer.ABC }
    fun isDigit(k: String) = k.length == 2 && k[0] == 'd'
    fun isChar(k: String) = k.length == 1 || isDigit(k)

    /** The text a key types right now, or null for keys that don't type. */
    fun output(k: String, s: InputState): String? {
        if (isDigit(k)) return k.substring(1)
        if (k.length != 1) return null
        if (s.layer != Layer.ABC) alt[baseKey(k, s.language)]?.let { return if (s.layer == Layer.NUM) it.first else it.second }
        return if (s.upper) k.uppercase() else k
    }

    /** The character offered on long-press: the key's number-layer character. */
    fun longPress(k: String, s: InputState): String? = if (k.length == 1 && s.layer == Layer.ABC) alt[baseKey(k, s.language)]?.first else null

    /** Small corner hint, shown only for long-press digits. */
    fun hint(k: String, s: InputState): String? = longPress(k, s)?.takeIf { it[0].isDigit() }

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

    fun alternatives(k: String, s: InputState, hints: Boolean): List<String> {
        if (s.layer != Layer.ABC || k.length != 1) return emptyList()
        val accents = when (s.language) {
            "es" -> mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "úü", "n" to "ñ", "ñ" to "n", "?" to "¿", "!" to "¡")
            "de" -> mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß", "ß" to "s")
            "fr" -> mapOf("a" to "àâæ", "e" to "éèêë", "i" to "îï", "o" to "ôœ", "u" to "ùûü", "c" to "ç", "y" to "ÿ")
            else -> emptyMap()
        }[k].orEmpty().map { if (s.upper) it.toString().uppercase() else it.toString() }
        val old = longPress(k, s)
        val first = if (old != null && (old.first().isDigit() && hints || !old.first().isDigit() && (s.language == "en" || accents.isEmpty()))) listOf(old) else emptyList()
        return (first + accents).distinct()
    }

    /** A spoken name for the face the key types right now. */
    fun spoken(code: String, state: InputState, enterLabel: String?): String = when (code) {
        Keys.SPACE -> "space"
        Keys.BACKSPACE -> "backspace"
        Keys.ENTER -> enterLabel?.takeIf { it.isNotBlank() } ?: "enter"
        Keys.ABC -> "letters" + when { state.capsLock -> ", caps lock on"; state.shift -> ", shift on"; else -> "" }
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

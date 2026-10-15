package app.tailorkeys.layout

enum class Layer { ABC, NUM, SYM }

/** Which layer is showing and how letters are cased. Shared by the service and the view. */
class InputState {
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
        if (s.layer != Layer.ABC) alt[k]?.let { return if (s.layer == Layer.NUM) it.first else it.second }
        return if (s.upper) k.uppercase() else k
    }

    /** The character offered on long-press: the key's number-layer character. */
    fun longPress(k: String, s: InputState): String? = if (k.length == 1 && s.layer == Layer.ABC) alt[k]?.first else null

    /** Small corner hint, shown only for long-press digits. */
    fun hint(k: String, s: InputState): String? = longPress(k, s)?.takeIf { it[0].isDigit() }

    fun label(k: String, s: InputState): String = when (k) {
        Keys.ABC -> when { s.capsLock -> "ABC"; s.shift && s.layer == Layer.ABC -> "Abc"; else -> "abc" }
        Keys.NUM -> "123"
        Keys.SYM -> "#+="
        Keys.SPACE, Keys.BACKSPACE, Keys.ENTER -> ""
        else -> output(k, s) ?: k
    }
}

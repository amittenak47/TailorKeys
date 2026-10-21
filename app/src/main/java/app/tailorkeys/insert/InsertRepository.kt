package app.tailorkeys.insert

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object InsertPrefs {
    const val HISTORY = "insert_history"
    const val KEEP = "insert_keep"
    const val TONE = "insert_tone"
    const val RECENTS = "insert_recents"
    const val VOICE = "insert_voice"
    const val DEVICE_ONLY = "insert_device_only"
    const val STORE = "insert_clips"
    const val SECTION = "insert_section"
    val toneNames = listOf("Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark")
}

/** Reload before each operation, so the app and the retained IME panel never overwrite each other. */
class InsertRepository(val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("insert_private", Context.MODE_PRIVATE))
    val keep get() = ClipKeep.entries.getOrElse(prefs.getInt(InsertPrefs.KEEP, 1)) { ClipKeep.DAY }
    fun store() = ClipboardStore.decode(prefs.getString(InsertPrefs.STORE, "[]") ?: "[]").also { it.expire(keep) }
    fun change(block: (ClipboardStore) -> Unit) { val s = store(); block(s); save(s) }
    fun save(store: ClipboardStore) {
        val json = store.encode()
        if (json != prefs.getString(InsertPrefs.STORE, "[]")) prefs.edit().putString(InsertPrefs.STORE, json).apply()
    }
    fun searchableItems(): List<ClipItem> = store().let { store ->
        (if (prefs.getBoolean(InsertPrefs.HISTORY, false)) store.clips() else emptyList()) + store.notes()
    }
    fun recent(section: String): List<String> = runCatching { val a = JSONArray(prefs.getString("insert_recent_$section", "[]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    fun used(section: String, text: String) {
        if (!prefs.getBoolean(InsertPrefs.RECENTS, true)) return
        val recent = InsertRecents(recent(section)); recent.use(text)
        prefs.edit().putString("insert_recent_$section", JSONArray(recent.values()).toString()).apply()
    }
    fun clearRecents() { prefs.edit().remove("insert_recent_Emoji").remove("insert_recent_Kaomoji").apply() }
}

object InsertCatalogs {
    private var emojiCache: EmojiCatalog? = null
    private var kaomojiCache: List<InsertSymbol>? = null
    @Synchronized fun emoji(context: Context): EmojiCatalog = emojiCache ?: context.assets.open("insert/emoji.tsv").bufferedReader().use { EmojiCatalog.load(it.lineSequence()) }.also { emojiCache = it }
    @Synchronized fun kaomoji(context: Context): List<InsertSymbol> = kaomojiCache ?: context.assets.open("insert/kaomoji.tsv").bufferedReader().use { reader -> reader.lineSequence().filter { it.isNotBlank() }.map { val f = it.split('\t'); InsertSymbol(f[0], f[1], f[2]) }.toList() }.also { kaomojiCache = it }
}

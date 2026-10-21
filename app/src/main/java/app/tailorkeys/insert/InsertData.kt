package app.tailorkeys.insert

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object InsertPrefs {
    const val HISTORY = "insert_history"
    const val KEEP = "insert_keep"
    const val TONE = "insert_tone"
    const val RECENTS = "insert_recents"
    const val KAOMOJI = "insert_kaomoji"
    const val VOICE = "insert_voice"
    const val DEVICE_ONLY = "insert_device_only"
    const val STORE = "insert_clips"
    const val SECTION = "insert_section"
    const val HAD_CLIPS = "insert_had_clips"
    val toneNames = listOf("Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark")
}

/** Remaining width is spacing, never a narrower touch target. */
fun emojiColumns(widthDp: Float, tablet: Boolean): Int =
    minOf(if (tablet) 14 else 8, (widthDp / if (tablet) 52 else 44).toInt()).coerceAtLeast(1)

data class InsertSymbol(val text: String, val name: String, val category: String, val keywords: String = "")

/** The bundled Unicode fully-qualified sequences are authoritative, including ZWJ placement. */
class EmojiCatalog(val entries: List<InsertSymbol>) {
    // A modifier replaces VS16 in sequences such as ✌️ → ✌🏽. Ignore VS16 for
    // family identity only; always insert the original fully-qualified sequence.
    private fun base(s: String) = s.codePoints().filter { it !in 0x1F3FB..0x1F3FF && it != 0xFE0F }.toArray().let { String(it, 0, it.size) }
    private fun hasTone(s: String) = s.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }
    private val families = entries.groupBy { base(it.text) }
    val primary = entries.filter { !hasTone(it.text) && it.category != "Component" }
    private val index = primary.associateWith { fold(it.name + " " + it.keywords) }
    fun search(query: String): List<InsertSymbol> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        return primary.filter { entry -> words.all { it in index.getValue(entry) } }
    }
    fun variants(text: String): List<InsertSymbol> {
        val family = families[base(text)].orEmpty()
        val plain = family.firstOrNull { !hasTone(it.text) } ?: return emptyList()
        return listOf(plain) + (0x1F3FB..0x1F3FF).mapNotNull { tone ->
            family.firstOrNull { e -> val tones = e.text.codePoints().filter { it in 0x1F3FB..0x1F3FF }.toArray(); tones.isNotEmpty() && tones.all { it == tone } }
        }
    }
    fun toned(entry: InsertSymbol, tone: Int) = if (hasTone(entry.text)) entry else variants(entry.text).getOrNull(tone) ?: entry
    companion object {
        fun load(lines: Sequence<String>) = EmojiCatalog(lines.filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val f = it.split('\t'); require(f.size >= 3); InsertSymbol(f[0], f[1], f[2], f.getOrElse(3) { "" })
        }.toList())
        private fun fold(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}

class InsertRecents(initial: List<String> = emptyList()) {
    private val items = initial.distinct().take(32).toMutableList()
    fun use(text: String) { items.remove(text); items.add(0, text); while (items.size > 32) items.removeAt(items.lastIndex) }
    fun values(): List<String> = items.toList()
}

enum class ClipboardPanelState { HISTORY_OFF, ACCESS_REQUIRED, FIRST_USE, EMPTY, ITEMS }

/** One state only. Disabled history wins even when stored clips or onboarding remain. */
fun clipboardPanelState(enabled: Boolean, hasItems: Boolean, hadItems: Boolean, dismissed: Boolean, fullAccess: Boolean = true): ClipboardPanelState = when {
    !enabled -> ClipboardPanelState.HISTORY_OFF
    !fullAccess -> ClipboardPanelState.ACCESS_REQUIRED
    hasItems -> ClipboardPanelState.ITEMS
    !hadItems && !dismissed -> ClipboardPanelState.FIRST_USE
    else -> ClipboardPanelState.EMPTY
}

enum class ClipKeep(val title: String, val millis: Long) {
    HOUR("1 hour", 3_600_000), DAY("1 day", 86_400_000), WEEK("1 week", 604_800_000), FOREVER("until cleared", Long.MAX_VALUE)
}

data class ClipItem(val id: String, val text: String, val at: Long, val pinned: Boolean = false, val note: Boolean = false, val title: String = "")

/** No platform APIs here: all capture entry points must pass through the same privacy gate. */
class ClipboardStore(initial: List<ClipItem> = emptyList(), private val clock: () -> Long = System::currentTimeMillis) {
    private val items = initial.toMutableList()
    private var deleted: Pair<ClipItem, Long>? = null
    fun expire(keep: ClipKeep) { if (keep != ClipKeep.FOREVER) items.removeAll { !it.note && !it.pinned && clock() - it.at >= keep.millis } }
    fun capture(text: String, enabled: Boolean, sensitive: Boolean, password: Boolean): Boolean {
        if (!enabled || sensitive || password || text.isBlank()) return false
        val safe = text.take(CLIP_MAX)
        if (items.any { !it.note && it.pinned && it.text == safe }) return false
        items.removeAll { !it.note && !it.pinned && it.text == safe }
        items.add(ClipItem(UUID.randomUUID().toString(), safe, clock()))
        val excess = clips().filter { !it.pinned }.drop(100).map { it.id }.toSet()
        items.removeAll { it.id in excess }
        return true
    }
    fun clips() = items.filter { !it.note }.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.at })
    fun notes() = items.filter { it.note }.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.at })
    fun pin(id: String) { replace(id) { it.copy(pinned = !it.pinned) } }
    fun edit(id: String, text: String, title: String = "") { replace(id) { it.copy(text = text.take(if (it.note) NOTE_MAX else CLIP_MAX), title = title.take(TITLE_MAX)) } }
    fun note(title: String, text: String): ClipItem = ClipItem(UUID.randomUUID().toString(), text.take(NOTE_MAX), clock(), note = true, title = title.take(TITLE_MAX)).also { items.add(it) }
    fun saveAsNote(id: String): ClipItem? = items.firstOrNull { it.id == id }?.let { note(it.text.take(TITLE_MAX), it.text) }
    fun delete(id: String) { items.firstOrNull { it.id == id }?.let { deleted = it to clock(); items.remove(it) } }
    fun undo(): Boolean { val d = deleted ?: return false; deleted = null; if (clock() - d.second > 6_000) return false; items.add(d.first); return true }
    fun clear() { items.removeAll { !it.note && !it.pinned }; deleted = null }
    fun restore(item: ClipItem) { if (items.none { it.id == item.id }) items.add(item) }
    private fun replace(id: String, change: (ClipItem) -> ClipItem) { val i = items.indexOfFirst { it.id == id }; if (i >= 0) items[i] = change(items[i]) }
    fun encode() = JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("pinned", it.pinned).put("note", it.note).put("title", it.title)) } }.toString()
    companion object {
        /** One limit for notes everywhere they are written: the panel editor, the settings dialog and the store. */
        const val NOTE_MAX = 2_000
        const val TITLE_MAX = 40
        const val CLIP_MAX = 20_000
        fun decode(json: String, clock: () -> Long = System::currentTimeMillis) = ClipboardStore(runCatching {
            val a = JSONArray(json); (0 until a.length()).map { val o = a.getJSONObject(it); ClipItem(o.getString("id"), o.getString("text"), o.getLong("at"), o.optBoolean("pinned"), o.optBoolean("note"), o.optString("title")) }
        }.getOrDefault(emptyList()), clock)
    }
}

/** New pins lead, duplicates never consume the sixteen retained slots. */
class InsertPins(initial: List<String> = emptyList()) {
    private val items = initial.distinct().take(16).toMutableList()
    fun toggle(text: String) { if (!items.remove(text)) { items.add(0, text); while (items.size > 16) items.removeAt(items.lastIndex) } }
    fun values(): List<String> = items.toList()
}

/** Owns only dictated text. A terminal action runs once even if the recognizer calls back late. */
class VoiceDraft(private val compose: (String) -> Unit, private val commit: (String) -> Unit, private val discard: () -> Unit) {
    var text = ""; private set
    var active = false; private set
    fun start() { cancel(); text = ""; active = true }
    fun partial(value: String) { if (active) { text = value; compose(value) } }
    fun finish(final: String = text) { if (active) { active = false; text = final; if (final.isNotBlank()) commit(final) else discard() } }
    fun cancel() { if (active) { active = false; discard() }; text = "" }
}
fun settledVoicePrefix(previous: String, current: String): Int {
    val old = previous.split(' '); val words = current.split(' ')
    val common = old.zip(words).takeWhile { it.first == it.second }.size.coerceAtMost((words.size-1).coerceAtLeast(0))
    return words.take(common).sumOf { it.length+1 }.coerceAtMost(current.length)
}

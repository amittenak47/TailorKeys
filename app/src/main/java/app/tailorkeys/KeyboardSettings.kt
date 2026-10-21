package app.tailorkeys

import android.content.Context
import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import org.json.JSONObject

enum class Align { LEFT, CENTER, RIGHT }

class IntPref(val key: String, val default: Int, val min: Int, val max: Int)
class BoolPref(val key: String, val default: Boolean)

/** Every user setting with its default and allowed range, shared by the keyboard and the settings screen. */
object Prefs {
    // Size and split are stored separately for portrait (_p) and landscape (_l).
    private val HEIGHT_P = IntPref("height_p", 44, 24, 70)
    private val HEIGHT_L = IntPref("height_l", 58, 24, 70)
    private val WIDTH_P = IntPref("width_p", 100, 50, 100)
    private val WIDTH_L = IntPref("width_l", 100, 50, 100)
    private val BOTTOM_P = IntPref("bottom_pad_p", 0, 0, 48)
    private val BOTTOM_L = IntPref("bottom_pad_l", 0, 0, 48)
    private val GAP_P = IntPref("split_gap_p", 16, 2, 40)
    private val GAP_L = IntPref("split_gap_l", 22, 2, 40)
    // Tilt is capped at ±10°: the layout reserves room for both ends, so a wider range would make every key narrower.
    private val TILT_P = IntPref("tilt_p", 0, -10, 10)
    private val TILT_L = IntPref("tilt_l", 0, -10, 10)

    fun height(landscape: Boolean) = if (landscape) HEIGHT_L else HEIGHT_P
    fun width(landscape: Boolean) = if (landscape) WIDTH_L else WIDTH_P
    fun bottomPadding(landscape: Boolean) = if (landscape) BOTTOM_L else BOTTOM_P
    fun splitGap(landscape: Boolean) = if (landscape) GAP_L else GAP_P
    fun tilt(landscape: Boolean) = if (landscape) TILT_L else TILT_P
    fun alignKey(landscape: Boolean) = if (landscape) "align_l" else "align_p"
    fun splitKey(landscape: Boolean) = if (landscape) "split_l" else "split_p"

    /** Ergonomic curve for split layout, as a percentage. */
    val CURVE = IntPref("curve", 70, 0, 100)
    val KEY_GAP = IntPref("key_gap", 5, 0, 14)
    val CORNER_RADIUS = IntPref("corner_radius", 8, 0, 20)
    val KEY_REPEAT_GUARD = IntPref("key_repeat_guard", 0, 0, 300)
    val TOUCH_SLACK = IntPref("touch_slack", 0, 0, 12)
    val TEXT_SCALE = IntPref("text_scale", 100, 60, 160)
    val LONG_PRESS_MS = IntPref("long_press_ms", 350, 150, 800)

    val FOLLOW_SYSTEM_TEXT = BoolPref("follow_system_text", true)
    val TYPE_ON_PRESS = BoolPref("type_on_press", false)
    val NUMBER_ROW = BoolPref("number_row", true)
    val SHOW_HINTS = BoolPref("show_hints", true)
    val CURSOR_GLOW = BoolPref("cursor_glow", false)
    val SPACE_ACCEPTS = BoolPref("space_accepts", true)
    val AUTO_CAP = BoolPref("auto_cap", true)
    // Retain the old key as the child of Automatic punctuation; existing choices migrate unchanged.
    val DOUBLE_SPACE_PERIOD = BoolPref("double_space_period", true)
    val HAPTICS = BoolPref("haptics", true)
    val KEY_TINT = BoolPref("key_tint", true)
    val SWIPE_LETTERS = BoolPref("swipe_letters", false)
    val AUTOMATIC_SPACE = BoolPref("automatic_space", true)
    val AUTOMATIC_PUNCTUATION = BoolPref("automatic_punctuation", true)
    val SWIPE_TYPING = BoolPref("swipe_typing", true)
    val CHORDING = BoolPref("chording", false)
    val SMART_TARGETS = BoolPref("smart_targets", true)
    val TYPO_BUZZ = BoolPref("typo_buzz", true)
    val LOCK_SIZE = BoolPref("lock_size", false)
    /** Portrait and landscape share one set of size and split settings. */
    val SAME_ORIENTATIONS = BoolPref("same_orientations", false)
    /** The first-run guide has been finished or skipped. */
    val ONBOARDED = BoolPref("onboarded", false)

    const val CURSOR_CONTROL = "cursor_control"
    const val THEME = "theme"
    const val LAYOUT = "layout"
    const val LANGUAGES = "languages"
    const val CURRENT_LANGUAGE = "current_language"
    val LANGUAGE_NAMES = linkedMapOf("en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français")
}

data class SizeProfile(
    val heightPercent: Int,
    val widthPercent: Int,
    val align: Align,
    val bottomPaddingDp: Int,
    val split: Boolean,
    val gap: Int,
    val tilt: Int,
)

class KeyboardSettings internal constructor(
    val prefs: SharedPreferences, private val tablet: Boolean, private val deviceLanguage: String,
) {
    init {
        // Retire the independent space-row angle; all rows now use the existing shared tilt.
        if (prefs.contains("space_tilt_p") || prefs.contains("space_tilt_l"))
            prefs.edit().remove("space_tilt_p").remove("space_tilt_l").apply()
    }
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE),
        context.resources.configuration.smallestScreenWidthDp >= 600,
        context.resources.configuration.locales[0].language.takeIf { it in Prefs.LANGUAGE_NAMES } ?: "en")
    fun languages(): List<String> {
        val stored = prefs.all[Prefs.LANGUAGES]
        val enabled = when (stored) {
            is Set<*> -> stored.filterIsInstance<String>().toSet()
            is String -> stored.split(',').toSet()
            else -> setOf(deviceLanguage)
        }
        return Prefs.LANGUAGE_NAMES.keys.filter { it in enabled }.ifEmpty { listOf(deviceLanguage) }
    }
    fun currentLanguage(): String = prefs.getString(Prefs.CURRENT_LANGUAGE, null)?.takeIf { it in languages() } ?: languages().first()
    fun setLanguages(enabled: Set<String>) {
        val valid = Prefs.LANGUAGE_NAMES.keys.filter { it in enabled }
        if (valid.isEmpty()) return
        val current = currentLanguage().takeIf { it in valid } ?: valid.first()
        prefs.edit().putStringSet(Prefs.LANGUAGES, valid.toSet()).putString(Prefs.CURRENT_LANGUAGE, current).apply()
    }
    fun setCurrentLanguage(lang: String) {
        if (lang in languages()) prefs.edit().putString(Prefs.CURRENT_LANGUAGE, lang).apply()
    }

    fun int(p: IntPref) = prefs.getInt(p.key, p.default).coerceIn(p.min, p.max)
    fun bool(p: BoolPref) = prefs.getBoolean(p.key, p.default)
    fun set(p: IntPref, value: Int) { prefs.edit().putInt(p.key, value.coerceIn(p.min, p.max)).apply(); syncAppearance(p.key) }
    fun set(p: BoolPref, value: Boolean) { prefs.edit().putBoolean(p.key, value).apply(); syncAppearance(p.key) }

    private val layoutInts get() = listOf(Prefs.CURVE, Prefs.KEY_GAP, Prefs.CORNER_RADIUS, Prefs.TEXT_SCALE) +
        listOf(false, true).flatMap { listOf(Prefs.height(it), Prefs.width(it), Prefs.bottomPadding(it), Prefs.splitGap(it), Prefs.tilt(it)) }
    private val layoutBools get() = listOf(Prefs.SAME_ORIENTATIONS, Prefs.LOCK_SIZE, Prefs.FOLLOW_SYSTEM_TEXT, Prefs.SHOW_HINTS, Prefs.KEY_TINT)

    /** Explicit defaults make settings portable between devices and between Android and iOS. */
    internal fun layoutSettings(): String = JSONObject().apply {
        layoutInts.forEach { put(it.key, int(it)) }
        layoutBools.forEach { put(it.key, bool(it)) }
        put(Prefs.THEME, theme().name)
        for (l in listOf(false, true)) { put(Prefs.alignKey(l), align(l).name); put(Prefs.splitKey(l), split(l)) }
    }.toString()

    /** Only Size/Look keys are accepted from storage or share codes; typing preferences never travel. */
    internal fun applyLayoutSettings(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val e = prefs.edit()
        layoutInts.forEach { p -> (o.opt(p.key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { e.putInt(p.key, it.coerceIn(p.min.toDouble(), p.max.toDouble()).toInt()) } }
        layoutBools.forEach { p -> (o.opt(p.key) as? Boolean)?.let { e.putBoolean(p.key, it) } }
        (o.opt(Prefs.THEME) as? String)?.takeIf { name -> ThemeName.entries.any { it.name == name } }?.let { e.putString(Prefs.THEME, it) }
        for (l in listOf(false, true)) {
            (o.opt(Prefs.alignKey(l)) as? String)?.takeIf { name -> Align.entries.any { it.name == name } }?.let { e.putString(Prefs.alignKey(l), it) }
            (o.opt(Prefs.splitKey(l)) as? Boolean)?.let { e.putBoolean(Prefs.splitKey(l), it) }
        }
        e.apply()
    }

    private fun syncAppearance(key: String? = null, landscape: Boolean = false) {
        if (key == null || key == Prefs.THEME || layoutInts.any { it.key == key } || layoutBools.any { it.key == key })
            LayoutLibrary(this) { landscape }.syncSettings()
    }

    fun cursorControl() = CursorControl.entries.firstOrNull { it.name == prefs.getString(Prefs.CURSOR_CONTROL, null) } ?: CursorControl.AUTO
    fun setCursorControl(value: CursorControl) = prefs.edit().putString(Prefs.CURSOR_CONTROL, value.name).apply()

    fun align(landscape: Boolean): Align {
        val name = prefs.getString(Prefs.alignKey(landscape), null)
        return Align.entries.firstOrNull { it.name == name } ?: Align.CENTER
    }

    fun setAlign(landscape: Boolean, align: Align) {
        prefs.edit().putString(Prefs.alignKey(landscape), align.name).apply(); syncAppearance(landscape = landscape)
    }

    /** Split defaults to on for tablets in landscape. */
    fun split(landscape: Boolean) = prefs.getBoolean(Prefs.splitKey(landscape), landscape && tablet)
    fun setSplit(landscape: Boolean, on: Boolean) {
        prefs.edit().putBoolean(Prefs.splitKey(landscape), on).apply(); syncAppearance(landscape = landscape)
    }

    fun profile(landscape: Boolean) = SizeProfile(
        heightPercent = int(Prefs.height(landscape)),
        widthPercent = int(Prefs.width(landscape)),
        align = align(landscape),
        bottomPaddingDp = int(Prefs.bottomPadding(landscape)),
        split = if (prefs.contains(LayoutKeys.TRIAL)) prefs.getString(LayoutKeys.TRIAL_SPLIT, null)?.toBooleanStrictOrNull() ?: split(landscape) else split(landscape),
        gap = int(Prefs.splitGap(landscape)),
        tilt = int(Prefs.tilt(landscape)),
    )

    // Setters for per-orientation settings: they write both orientations while SAME_ORIENTATIONS is on.

    private fun orientations(landscape: Boolean) = if (bool(Prefs.SAME_ORIENTATIONS)) listOf(false, true) else listOf(landscape)

    fun setFor(landscape: Boolean, pref: (Boolean) -> IntPref, value: Int) {
        val e = prefs.edit()
        orientations(landscape).forEach { val p = pref(it); e.putInt(p.key, value.coerceIn(p.min, p.max)) }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    fun setAlignFor(landscape: Boolean, align: Align) {
        val e = prefs.edit()
        orientations(landscape).forEach { e.putString(Prefs.alignKey(it), align.name) }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    /** Width and alignment form one gesture: notify observers after both are stored. */
    fun setHandleFor(landscape: Boolean, width: Int, align: Align) {
        val e = prefs.edit()
        for (orientation in orientations(landscape)) {
            val pref = Prefs.width(orientation)
            e.putInt(pref.key, width.coerceIn(pref.min, pref.max))
            e.putString(Prefs.alignKey(orientation), align.name)
        }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    fun setSplitFor(landscape: Boolean, on: Boolean) {
        applyLayoutSplit(landscape, on)
        syncAppearance(landscape = landscape)
    }

    internal fun applyLayoutSplit(landscape: Boolean, on: Boolean) {
        val e = prefs.edit()
        orientations(landscape).forEach { e.putBoolean(Prefs.splitKey(it), on) }
        e.apply()
    }

    fun quickOneHand(landscape: Boolean): Align =
        if (int(Prefs.width(landscape)) < 100) align(landscape) else Align.CENTER

    fun cycleQuickOneHand(landscape: Boolean) {
        val next = when (quickOneHand(landscape)) { Align.CENTER -> Align.LEFT; Align.LEFT -> Align.RIGHT; Align.RIGHT -> Align.CENTER }
        setFor(landscape, Prefs::width, if (next == Align.CENTER) 100 else if (split(landscape)) 70 else 78)
        setAlignFor(landscape, next)
    }

    fun adjustQuickHeight(landscape: Boolean, delta: Int) =
        setFor(landscape, Prefs::height, int(Prefs.height(landscape)) + delta)

    fun cycleQuickTheme() {
        val themes = ThemeName.entries // every theme, Warm and Midnight included
        setTheme(themes[(themes.indexOf(theme()) + 1) % themes.size])
    }

    fun cycleLanguage(direction: Int) {
        val enabled = languages()
        val index = enabled.indexOf(currentLanguage())
        setCurrentLanguage(enabled[((index + direction) % enabled.size + enabled.size) % enabled.size])
    }

    /** Turns on shared orientations, copying one orientation's size and split settings to the other. */
    fun linkOrientations(from: Boolean) {
        val to = !from
        val e = prefs.edit()
        for (pref in listOf(Prefs::height, Prefs::width, Prefs::bottomPadding, Prefs::splitGap, Prefs::tilt)) e.putInt(pref(to).key, int(pref(from)))
        e.putString(Prefs.alignKey(to), align(from).name)
        e.putBoolean(Prefs.splitKey(to), split(from))
        e.putBoolean(Prefs.SAME_ORIENTATIONS.key, true)
        e.apply()
        syncAppearance(landscape = from)
    }

    fun theme(): ThemeName {
        val name = prefs.getString(Prefs.THEME, null)
        return ThemeName.entries.firstOrNull { it.name == name } ?: ThemeName.SYSTEM
    }

    fun setTheme(theme: ThemeName) { prefs.edit().putString(Prefs.THEME, theme.name).apply(); syncAppearance(Prefs.THEME) }

    fun loadGrid(): KeyGrid {
        val preset = app.tailorkeys.layout.Template.entries.firstOrNull { prefs.getString(LayoutKeys.SELECTED, null) == "preset:${it.name}" }
        if (preset != null && prefs.getString(LayoutKeys.TRIAL, null) == null) return KeyGrid.template(preset, currentLanguage()).also { if (bool(Prefs.NUMBER_ROW)) it.setNumberRow(true) }
        return KeyGrid.savedOrDefault(KeyGrid.fromJson(prefs.getString(Prefs.LAYOUT, null)), currentLanguage(), bool(Prefs.NUMBER_ROW))
    }

    /** Saves the layout. The number-row switch follows whether the layout has a number row. */
    fun saveGrid(grid: KeyGrid) {
        if (!grid.isSound()) return
        prefs.edit().putString(Prefs.LAYOUT, grid.toJson()).putBoolean(Prefs.NUMBER_ROW.key, grid.rows == 5).apply()
    }

    /** Back to defaults, without showing the first-run guide again. */
    fun resetAll() = prefs.edit().clear().putBoolean(Prefs.ONBOARDED.key, true).apply()
}

package app.tailorkeys

import android.content.Context
import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import org.json.JSONObject

enum class Align { LEFT, CENTER, RIGHT }

class IntPref(val key: String, val default: Int, val min: Int, val max: Int)
/** [device]: kept on this device only (not in Android backups), for switches that must never come back by themselves. */
class BoolPref(val key: String, val default: Boolean, val device: Boolean = false)

/** Every user setting with its default and allowed range, shared by the keyboard and the settings screen. */
object Prefs {
    // Size and split are stored separately for portrait (_p) and landscape (_l).
    private val HEIGHT_P = IntPref("height_p", 44, 24, 70)
    private val HEIGHT_L = IntPref("height_l", 58, 24, 70)
    // Past 100 only for a split keyboard: the halves are at the screen edges and grow into the middle gap.
    private val WIDTH_P = IntPref("width_p", 100, 50, 120)
    private val WIDTH_L = IntPref("width_l", 100, 50, 120)
    private val BOTTOM_P = IntPref("bottom_pad_p", 0, 0, 48)
    private val BOTTOM_L = IntPref("bottom_pad_l", 0, 0, 48)
    private val GAP_P = IntPref("split_gap_p", 16, 2, 40)
    private val GAP_L = IntPref("split_gap_l", 22, 2, 40)
    // Tilt is capped at ±10°: the layout reserves room for both ends, so a wider range would make every key narrower.
    private val TILT_P = IntPref("tilt_p", 0, -10, 10)
    private val TILT_L = IntPref("tilt_l", 0, -10, 10)
    // Where each split half sits, dragged by its grip: its outer edge's distance from its side of the screen, in
    // thousandths of the screen width. 0 is flush with the edge, as before halves could move.
    private val SPLIT_LEFT_P = IntPref("split_left_p", 0, 0, 1000)
    private val SPLIT_LEFT_L = IntPref("split_left_l", 0, 0, 1000)
    private val SPLIT_RIGHT_P = IntPref("split_right_p", 0, 0, 1000)
    private val SPLIT_RIGHT_L = IntPref("split_right_l", 0, 0, 1000)

    fun height(landscape: Boolean) = if (landscape) HEIGHT_L else HEIGHT_P
    fun width(landscape: Boolean) = if (landscape) WIDTH_L else WIDTH_P
    fun bottomPadding(landscape: Boolean) = if (landscape) BOTTOM_L else BOTTOM_P
    fun splitGap(landscape: Boolean) = if (landscape) GAP_L else GAP_P
    /** The narrowest middle gap, in percent of the width, that a split Width past 100 can leave. */
    const val MIN_SPLIT_GAP = 2
    /** The largest Width: 100, or for a split keyboard as far as the default gap allows down to MIN_SPLIT_GAP. */
    fun maxWidth(landscape: Boolean, split: Boolean) = if (split) 100 + splitGap(landscape).default - MIN_SPLIT_GAP else 100
    fun tilt(landscape: Boolean) = if (landscape) TILT_L else TILT_P
    fun splitLeft(landscape: Boolean) = if (landscape) SPLIT_LEFT_L else SPLIT_LEFT_P
    fun splitRight(landscape: Boolean) = if (landscape) SPLIT_RIGHT_L else SPLIT_RIGHT_P
    /** Width/height percentages and additive tilt for each split half, per orientation. */
    fun halfShape(landscape: Boolean, side: Int): List<IntPref> = halfShapes[(if (landscape) 2 else 0) + side]
    private val halfShapes = listOf(false, true).flatMap { land -> (0..1).map { side ->
        val suffix = "${if (side == 0) "left" else "right"}_${if (land) "l" else "p"}"
        listOf(IntPref("half_width_$suffix", 100, 50, 150), IntPref("half_height_$suffix", 100, 50, 150),
            IntPref("half_tilt_$suffix", 0, -20, 20))
    } }
    /** The editor starts with symmetric half adjustments. */
    val MIRROR_HALVES = BoolPref("mirror_halves", true)
    fun alignKey(landscape: Boolean) = if (landscape) "align_l" else "align_p"
    fun splitKey(landscape: Boolean) = if (landscape) "split_l" else "split_p"

    /** Ergonomic curve for split layout, as a percentage. */
    val CURVE = IntPref("curve", 70, 0, 100)
    /**
     * How deep the split halves' keys look sunk into their bowl, as a percentage; below 0 a dome, the rim keys
     * smaller instead. Drawing only (taps are unchanged), unless [DEPTH_COMPACT] closes the keys up.
     */
    val DEPTH = IntPref("depth", 50, -100, 100)
    /** The shrunk keys close up, gaps staying even, and answer touches as drawn. */
    val DEPTH_COMPACT = BoolPref("depth_compact", false)
    val KEY_GAP = IntPref("key_gap", 5, 0, 14)
    val CORNER_RADIUS = IntPref("corner_radius", 8, 0, 20)
    val KEY_REPEAT_GUARD = IntPref("key_repeat_guard", 0, 0, 300)
    val TOUCH_SLACK = IntPref("touch_slack", 0, 0, 12)
    val TEXT_SCALE = IntPref("text_scale", 100, 60, 160)
    val LONG_PRESS_MS = IntPref("long_press_ms", 350, 150, 800)
    /** How strongly a push in the cursor gap moves the cursor, as a percentage. */
    val CURSOR_SENSITIVITY = IntPref("cursor_sensitivity", 100, 50, 200)

    val FOLLOW_SYSTEM_TEXT = BoolPref("follow_system_text", true)
    val TYPE_ON_PRESS = BoolPref("type_on_press", false)
    /** After a symbol from 123, or from #+=, the letters come back (digits keep the layer, for numbers). */
    val RETURN_FROM_NUM = BoolPref("return_from_num", false)
    val RETURN_FROM_SYM = BoolPref("return_from_sym", false)
    val NUMBER_ROW = BoolPref("number_row", true)
    val SHOW_HINTS = BoolPref("show_hints", true)
    val CURSOR_GLOW = BoolPref("cursor_glow", false)
    val SPACE_ACCEPTS = BoolPref("space_accepts", true)
    val AUTO_CAP = BoolPref("auto_cap", true)
    // Retain the old key as the child of Automatic punctuation; existing choices migrate unchanged.
    val DOUBLE_SPACE_PERIOD = BoolPref("double_space_period", true)
    val AUTOCORRECT = IntPref("autocorrect", 1, 0, 2)
    /** Never suggest or correct to offensive words; typed ones are still left alone. */
    val BLOCK_OFFENSIVE = BoolPref("block_offensive", true)
    val LEARNING = BoolPref("learning", true)
    const val LEARNING_REVISION = "learning_revision"
    val SOUND = BoolPref("sound", true)
    val SOUND_VOLUME = IntPref("sound_volume", 50, 0, 100)
    val HAPTICS = BoolPref("haptics", true)
    val KEY_TINT = BoolPref("key_tint", true)
    /** Key style 3D: a raised top over a darker edge. Drawing only. */
    val KEYS_3D = BoolPref("keys_3d", false)
    val SWIPE_LETTERS = BoolPref("swipe_letters", false)
    val AUTOMATIC_SPACE = BoolPref("automatic_space", true)
    val AUTOMATIC_PUNCTUATION = BoolPref("automatic_punctuation", true)
    val SWIPE_TYPING = BoolPref("swipe_typing", true)
    val CHORDING = BoolPref("chording", false)
    val SMART_TARGETS = BoolPref("smart_targets", true)
    /** Follow each hand as it drifts off the keys (TouchDrift). */
    val TOUCH_DRIFT = BoolPref("touch_drift", true)
    val TYPO_BUZZ = BoolPref("typo_buzz", true)
    val LOCK_SIZE = BoolPref("lock_size", false)
    /** Lock halves: the split grips and the one-hand arrow fade out and stop taking touches. */
    val SPLIT_LOCKED = BoolPref("split_locked", false)
    /**
     * For testing autocorrect: real typing kept in a file on this device (TypingLog). Off: it logs what you type. Kept
     * out of backups, so a phone restored from one never starts logging.
     */
    val LOG_DECISIONS = BoolPref("log_decisions", false, device = true)
    /** The suggestion bar's height, in percent of its default. */
    val SUGGESTION_BAR = IntPref("suggestion_bar", 100, 60, 140)
    /** Portrait and landscape share one set of size and split settings. */
    val SAME_ORIENTATIONS = BoolPref("same_orientations", false)
    /** The first-run guide has been finished or skipped. */
    val ONBOARDED = BoolPref("onboarded", false)

    const val CURSOR_CONTROL = "cursor_control"
    const val TOUCH_BAR_STYLE = "touch_bar_style"
    const val THEME = "theme"
    const val THEMES_CUSTOM = "themes_custom"
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
    /** Sets how wide each half is (it shares the width with the center); where the halves sit is [leftInset] and [rightInset]. */
    val gap: Int,
    val tilt: Int,
    val leftInset: Int = 0,
    val rightInset: Int = 0,
    val leftShape: HalfShape = HalfShape(),
    val rightShape: HalfShape = HalfShape(),
)

class KeyboardSettings internal constructor(
    val prefs: SharedPreferences, private val tablet: Boolean, private val deviceLanguage: String,
    /** Settings kept on this device only (BoolPref.device): device_private.xml, excluded from backup. */
    val device: SharedPreferences = prefs,
) {
    init {
        // Retire the independent space-row angle; all rows now use the existing shared tilt.
        if (prefs.contains("space_tilt_p") || prefs.contains("space_tilt_l"))
            prefs.edit().remove("space_tilt_p").remove("space_tilt_l").apply()
        // Device-only switches saved before they had their own file move there, out of the backed-up one.
        if (device !== prefs) for (p in listOf(Prefs.LOG_DECISIONS)) if (prefs.contains(p.key)) {
            if (!device.contains(p.key)) device.edit().putBoolean(p.key, prefs.getBoolean(p.key, p.default)).apply()
            prefs.edit().remove(p.key).apply()
        }
    }
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE),
        context.resources.configuration.smallestScreenWidthDp >= 600,
        // English only for now (the autocorrect model is English); the other languages' lists stay bundled.
        "en",
        context.applicationContext.getSharedPreferences("device_private", Context.MODE_PRIVATE))
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
    fun bool(p: BoolPref) = (if (p.device) device else prefs).getBoolean(p.key, p.default)
    fun set(p: IntPref, value: Int) { prefs.edit().putInt(p.key, value.coerceIn(p.min, p.max)).apply(); syncAppearance(p.key) }
    fun set(p: BoolPref, value: Boolean) { (if (p.device) device else prefs).edit().putBoolean(p.key, value).apply(); syncAppearance(p.key) }

    private val layoutInts get() = listOf(Prefs.CURVE, Prefs.DEPTH, Prefs.KEY_GAP, Prefs.CORNER_RADIUS, Prefs.TEXT_SCALE) +
        listOf(false, true).flatMap { listOf(Prefs.height(it), Prefs.width(it), Prefs.bottomPadding(it), Prefs.splitGap(it), Prefs.tilt(it),
            Prefs.splitLeft(it), Prefs.splitRight(it)) + Prefs.halfShape(it, 0) + Prefs.halfShape(it, 1) }
    private val layoutBools get() = listOf(Prefs.SAME_ORIENTATIONS, Prefs.LOCK_SIZE, Prefs.FOLLOW_SYSTEM_TEXT, Prefs.SHOW_HINTS, Prefs.KEY_TINT, Prefs.KEYS_3D, Prefs.DEPTH_COMPACT)

    /** Explicit defaults make settings portable between devices and between Android and iOS. */
    internal fun layoutSettings(): String = JSONObject().apply {
        layoutInts.forEach { put(it.key, int(it)) }
        layoutBools.forEach { put(it.key, bool(it)) }
        put(Prefs.THEME, themeId())
        for (l in listOf(false, true)) { put(Prefs.alignKey(l), align(l).name); put(Prefs.splitKey(l), split(l)) }
    }.toString()

    /** Only Size/Look keys are accepted from storage or share codes; typing preferences never travel. */
    internal fun applyLayoutSettings(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val e = prefs.edit()
        layoutInts.forEach { p -> (o.opt(p.key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { e.putInt(p.key, it.coerceIn(p.min.toDouble(), p.max.toDouble()).toInt()) } }
        layoutBools.forEach { p -> (o.opt(p.key) as? Boolean)?.let { e.putBoolean(p.key, it) } }
        (o.opt(Prefs.THEME) as? String)?.takeIf { name -> name.startsWith("custom:") || ThemeName.entries.any { it.name == name } }?.let { e.putString(Prefs.THEME, resolvedThemeId(it)) }
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
    fun touchBarStyle() = TouchBarStyle.entries.firstOrNull { it.name == prefs.getString(Prefs.TOUCH_BAR_STYLE, null) } ?: TouchBarStyle.JOYSTICK
    fun setTouchBarStyle(value: TouchBarStyle) = prefs.edit().putString(Prefs.TOUCH_BAR_STYLE, value.name).apply()

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
        widthPercent = int(Prefs.width(landscape)).coerceAtMost(Prefs.maxWidth(landscape, split(landscape))),
        align = align(landscape),
        bottomPaddingDp = int(Prefs.bottomPadding(landscape)),
        split = if (prefs.contains(LayoutKeys.TRIAL)) prefs.getString(LayoutKeys.TRIAL_SPLIT, null)?.toBooleanStrictOrNull() ?: split(landscape) else split(landscape),
        // The halves' size leaves the default gap's room for moving them; a gap saved by an older version or a size
        // preset could leave none, with no slider left to change it. Width past 100 takes it, point for point.
        gap = (Prefs.splitGap(landscape).default - (int(Prefs.width(landscape)) - 100).coerceAtLeast(0)).coerceAtLeast(Prefs.MIN_SPLIT_GAP),
        tilt = int(Prefs.tilt(landscape)),
        leftInset = int(Prefs.splitLeft(landscape)),
        rightInset = int(Prefs.splitRight(landscape)),
        leftShape = halfShape(landscape, 0),
        rightShape = halfShape(landscape, 1),
    )

    // Setters for per-orientation settings: they write both orientations while SAME_ORIENTATIONS is on.

    private fun orientations(landscape: Boolean) = if (bool(Prefs.SAME_ORIENTATIONS)) listOf(false, true) else listOf(landscape)

    fun setFor(landscape: Boolean, pref: (Boolean) -> IntPref, value: Int) {
        val e = prefs.edit()
        orientations(landscape).forEach { val p = pref(it); e.putInt(p.key, value.coerceIn(p.min, p.max)) }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    /** Where the split halves sit, in thousandths of the screen width from each side; saved together. */
    fun setSplitInsetsFor(landscape: Boolean, left: Int, right: Int) {
        val e = prefs.edit()
        orientations(landscape).forEach {
            e.putInt(Prefs.splitLeft(it).key, left.coerceIn(0, 1000))
            e.putInt(Prefs.splitRight(it).key, right.coerceIn(0, 1000))
        }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    private fun halfShape(landscape: Boolean, side: Int): HalfShape {
        val p = Prefs.halfShape(landscape, side)
        return HalfShape(int(p[0]), int(p[1]), int(p[2]))
    }

    /** Saves one complete half gesture, including placement; linked orientations get the same adjustments. */
    internal fun setHalfGeometryFor(landscape: Boolean, profile: SizeProfile) {
        val e = prefs.edit()
        for (land in orientations(landscape)) {
            for ((side, shape) in listOf(profile.leftShape, profile.rightShape).withIndex()) {
                val p = Prefs.halfShape(land, side)
                val value = shape.bounded()
                e.putInt(p[0].key, value.width).putInt(p[1].key, value.height).putInt(p[2].key, value.tilt)
            }
            e.putInt(Prefs.splitLeft(land).key, profile.leftInset.coerceIn(0, 1000))
            e.putInt(Prefs.splitRight(land).key, profile.rightInset.coerceIn(0, 1000))
        }
        e.apply()
        syncAppearance(landscape = landscape)
    }

    /** Only half adjustments and placement travel through editor undo; the base sliders stay independent. */
    internal fun halfGeometry(): String = JSONObject().apply {
        for (land in listOf(false, true)) for (p in Prefs.halfShape(land, 0) + Prefs.halfShape(land, 1) +
            listOf(Prefs.splitLeft(land), Prefs.splitRight(land))) put(p.key, int(p))
    }.toString()

    /** Restores both orientations from a half gesture's undo snapshot. */
    internal fun restoreHalfGeometry(json: String) {
        applyLayoutSettings(json)
        syncAppearance()
    }

    /** [profile] with the halves' shapes and placement taken from [halves] (a [halfGeometry] snapshot), when given. */
    internal fun profile(landscape: Boolean, halves: String?): SizeProfile {
        val p = profile(landscape)
        val o = halves?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return p
        fun v(pref: IntPref) = o.optInt(pref.key, pref.default).coerceIn(pref.min, pref.max)
        fun shape(side: Int) = Prefs.halfShape(landscape, side).let { HalfShape(v(it[0]), v(it[1]), v(it[2])) }
        return p.copy(leftInset = v(Prefs.splitLeft(landscape)), rightInset = v(Prefs.splitRight(landscape)),
            leftShape = shape(0), rightShape = shape(1))
    }

    /** [setHalfGeometryFor] applied to a [halfGeometry] snapshot instead of the settings in use. */
    internal fun halvesWith(halves: String, landscape: Boolean, profile: SizeProfile): String {
        val o = runCatching { JSONObject(halves) }.getOrElse { JSONObject() }
        for (land in orientations(landscape)) {
            for ((side, shape) in listOf(profile.leftShape, profile.rightShape).withIndex()) {
                val p = Prefs.halfShape(land, side)
                val value = shape.bounded()
                o.put(p[0].key, value.width).put(p[1].key, value.height).put(p[2].key, value.tilt)
            }
            o.put(Prefs.splitLeft(land).key, profile.leftInset.coerceIn(0, 1000))
            o.put(Prefs.splitRight(land).key, profile.rightInset.coerceIn(0, 1000))
        }
        return o.toString()
    }

    /** A layout's saved settings reduced to its halves' shapes and placement, as a [halfGeometry] snapshot. */
    internal fun halvesOf(snapshot: String?): String = JSONObject().apply {
        val o = snapshot?.let { runCatching { JSONObject(it) }.getOrNull() }
        for (land in listOf(false, true)) for (p in Prefs.halfShape(land, 0) + Prefs.halfShape(land, 1) +
            listOf(Prefs.splitLeft(land), Prefs.splitRight(land))) put(p.key, o?.optInt(p.key, p.default) ?: p.default)
    }.toString()

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
        val themes = ThemeName.entries.map { it.name } + customThemes().map { it.selection }
        setThemeId(themes[(themes.indexOf(themeId()) + 1) % themes.size])
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
        for (pref in listOf(Prefs::height, Prefs::width, Prefs::bottomPadding, Prefs::splitGap, Prefs::tilt, Prefs::splitLeft, Prefs::splitRight))
            e.putInt(pref(to).key, int(pref(from)))
        for (side in 0..1) Prefs.halfShape(to, side).zip(Prefs.halfShape(from, side)).forEach { (dest, src) ->
            e.putInt(dest.key, int(src))
        }
        e.putString(Prefs.alignKey(to), align(from).name)
        e.putBoolean(Prefs.splitKey(to), split(from))
        e.putBoolean(Prefs.SAME_ORIENTATIONS.key, true)
        e.apply()
        syncAppearance(landscape = from)
    }

    private var cachedThemesJson: String? = null
    private var cachedThemes = emptyList<CustomTheme>()
    fun customThemes(): List<CustomTheme> {
        val json = prefs.getString(Prefs.THEMES_CUSTOM, null)
        if (json != cachedThemesJson) { cachedThemesJson = json; cachedThemes = CustomTheme.decode(json) }
        return cachedThemes
    }
    private fun resolvedThemeId(id: String): String = if (id.startsWith("custom:") && customThemes().none { it.selection == id }) ThemeName.WARM.name else id
    fun themeId(): String = resolvedThemeId(prefs.getString(Prefs.THEME, ThemeName.SYSTEM.name) ?: ThemeName.SYSTEM.name)
    fun theme(): ThemeName = ThemeName.entries.firstOrNull { it.name == themeId() } ?: ThemeName.WARM
    fun setTheme(theme: ThemeName) = setThemeId(theme.name)
    fun setThemeId(id: String) { prefs.edit().putString(Prefs.THEME, resolvedThemeId(id)).apply(); syncAppearance(Prefs.THEME) }
    fun saveTheme(theme: CustomTheme) {
        val themes = customThemes().toMutableList()
        val saved = theme.copy(name = theme.name.trim().take(24).ifEmpty { "Custom" })
        val index = themes.indexOfFirst { it.id == theme.id }
        if (index >= 0) themes[index] = saved else themes.add(saved)
        prefs.edit().putString(Prefs.THEMES_CUSTOM, CustomTheme.encode(themes)).apply()
        setThemeId(theme.selection)
    }
    fun deleteTheme(id: String) {
        val selected = themeId() == "custom:$id"
        prefs.edit().putString(Prefs.THEMES_CUSTOM, CustomTheme.encode(customThemes().filterNot { it.id == id }))
            .apply { if (selected) putString(Prefs.THEME, ThemeName.WARM.name) }.apply()
        if (selected) syncAppearance(Prefs.THEME)
    }

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

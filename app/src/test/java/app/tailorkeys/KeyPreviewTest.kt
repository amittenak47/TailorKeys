package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.layout.HintStyle
import app.tailorkeys.layout.HoldChoices
import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.cos
import kotlin.math.sin

class KeyPreviewTest {
    private fun en(upper: Boolean = false) = InputState().apply { language = "en"; shift = upper }

    // ---- The setting ----

    @Test fun settingsStartOnInPlaceBothAndHoldChoiceHints() {
        val s = settings()
        assertEquals(KeyPreview.IN_PLACE, s.int(Prefs.KEY_PREVIEW))
        assertEquals(HoldChoices.BOTH, s.holdChoices())
        assertEquals(HintStyle.HOLD_CHOICE, s.hintStyle())
        assertTrue(s.holdOff().isEmpty())
    }

    @Test fun keyPreviewSettingTurnsOffAndPicksEachShape() {
        val s = settings()
        for (style in listOf(KeyPreview.OFF, KeyPreview.UPWARD, KeyPreview.BALLOON, KeyPreview.IN_PLACE)) {
            s.set(Prefs.KEY_PREVIEW, style)
            assertEquals(style, s.int(Prefs.KEY_PREVIEW))
        }
        s.set(Prefs.KEY_PREVIEW, 9)
        assertEquals(KeyPreview.BALLOON, s.int(Prefs.KEY_PREVIEW))
    }

    @Test fun hintStyleRoundTripsThroughTheOldHintsSwitch() {
        val s = settings()
        s.setHintStyle(HintStyle.OFF)
        assertEquals(HintStyle.OFF, s.hintStyle())
        assertFalse(s.bool(Prefs.SHOW_HINTS))
        s.setHintStyle(HintStyle.DIGITS)
        assertEquals(HintStyle.DIGITS, s.hintStyle())
        assertTrue(s.bool(Prefs.SHOW_HINTS))
        s.setHintStyle(HintStyle.HOLD_CHOICE)
        assertEquals(HintStyle.HOLD_CHOICE, s.hintStyle())
    }

    @Test fun chosenLongPressKeysApplyOnlyWhileChosenKeysIsOn() {
        val s = settings()
        s.setHoldOff(setOf("abc:q", "num:=,", "abc:d1"))
        assertTrue("All keys ignores the saved choice", s.holdOff().isEmpty())
        s.set(Prefs.LONG_PRESS_KEYS, 1)
        assertEquals(setOf("abc:q", "num:=,", "abc:d1"), s.holdOff())
        s.set(Prefs.LONG_PRESS_KEYS, 0)
        assertEquals(setOf("abc:q", "num:=,", "abc:d1"), s.savedHoldOff())
    }

    // ---- What holding a key offers ----

    @Test fun symbolsAreTheSameKeysCharacterOnThe123Layer() {
        assertEquals(listOf("%"), KeyText.alternatives("f", en(), HoldChoices.SYMBOLS, numberRow = false))
        assertEquals(listOf("1"), KeyText.alternatives("q", en(), HoldChoices.SYMBOLS, numberRow = false))
        assertEquals(listOf("!"), KeyText.alternatives("z", en(), HoldChoices.SYMBOLS, numberRow = false))
    }

    @Test fun accentsFollowTheIosEnglishRowAndKeysWithoutAccentsHaveNone() {
        assertEquals("èéêëēėę".map(Char::toString), KeyText.alternatives("e", en(), HoldChoices.ACCENTS, numberRow = false))
        assertEquals("ôöòóœøōõ".map(Char::toString), KeyText.alternatives("o", en(), HoldChoices.ACCENTS, numberRow = false))
        assertTrue(KeyText.alternatives("f", en(), HoldChoices.ACCENTS, numberRow = false).isEmpty())
    }

    @Test fun bothPutsTheSymbolFirstThenTheAccents() {
        assertEquals(listOf("3") + "èéêëēėę".map(Char::toString), KeyText.alternatives("e", en(), HoldChoices.BOTH, numberRow = false))
        assertEquals(listOf("%"), KeyText.alternatives("f", en(), HoldChoices.BOTH, numberRow = false))
    }

    @Test fun withTheNumberRowTheTopRowOffersNoDigitAndTheDigitsOfferSymbols() {
        assertEquals("èéêëēėę".map(Char::toString), KeyText.alternatives("e", en(), HoldChoices.BOTH, numberRow = true))
        assertTrue(KeyText.alternatives("q", en(), HoldChoices.SYMBOLS, numberRow = true).isEmpty())
        assertEquals(listOf("@"), KeyText.alternatives("a", en(), HoldChoices.SYMBOLS, numberRow = true).take(1))
        val digits = "1234567890".map { KeyText.alternatives("d$it", en(), HoldChoices.BOTH, numberRow = true).single() }
        assertEquals("[]{}#%^*+=".map(Char::toString), digits)
        assertTrue("Accents only: the number row has no hold", KeyText.alternatives("d1", en(), HoldChoices.ACCENTS, numberRow = true).isEmpty())
        assertTrue("Without the number row a digit key has no hold", KeyText.alternatives("d1", en(), HoldChoices.BOTH, numberRow = false).isEmpty())
    }

    @Test fun capitalsFollowShiftButSharpSStaysOneLetter() {
        assertEquals(listOf("3", "È"), KeyText.alternatives("e", en(upper = true), HoldChoices.BOTH, numberRow = false).take(2))
        assertEquals("ßŚŠ".map(Char::toString), KeyText.alternatives("s", en(upper = true), HoldChoices.ACCENTS, numberRow = false))
    }

    @Test fun otherLanguagesKeepTheirOwnAccents() {
        val es = InputState().apply { language = "es" }
        assertEquals(listOf("@", "á"), KeyText.alternatives("a", es, HoldChoices.BOTH, numberRow = false))
        assertEquals(listOf("ñ"), KeyText.alternatives("n", es, HoldChoices.ACCENTS, numberRow = false))
    }

    @Test fun nothingIsOfferedOffTheLetterLayer() {
        val num = en().apply { layer = Layer.NUM }
        assertTrue(KeyText.alternatives("e", num, HoldChoices.BOTH, numberRow = false).isEmpty())
    }

    @Test fun hintsAlwaysMatchTheFirstHoldChoice() {
        for (numberRow in listOf(false, true)) for (shows in HoldChoices.entries) for (code in listOf("q", "e", "f", "o", "z", "d5")) {
            val first = KeyText.alternatives(code, en(), shows, numberRow).firstOrNull()
            assertEquals("$code $shows $numberRow", first, KeyText.hint(code, en(), HintStyle.HOLD_CHOICE, shows, numberRow))
            assertNull(KeyText.hint(code, en(), HintStyle.OFF, shows, numberRow))
        }
    }

    @Test fun digitHintsShowOnlyDigitsTheTopRowStillOffers() {
        assertEquals("1", KeyText.hint("q", en(), HintStyle.DIGITS, HoldChoices.BOTH, numberRow = false))
        assertNull(KeyText.hint("f", en(), HintStyle.DIGITS, HoldChoices.BOTH, numberRow = false))
        assertNull("The number row already shows the digits", KeyText.hint("q", en(), HintStyle.DIGITS, HoldChoices.BOTH, numberRow = true))
        assertNull(KeyText.hint("q", en(), HintStyle.DIGITS, HoldChoices.ACCENTS, numberRow = false))
    }

    // ---- When the preview shows ----

    @Test fun onlyKeysThatTypeWhatTheyShowArePreviewed() {
        for (code in listOf("a", "z", "'", ",", ".", "d1", "=€")) assertTrue(code, KeyPreview.previewable(code))
        for (code in listOf(Keys.SPACE, Keys.ENTER, Keys.BACKSPACE, Keys.SHIFT, Keys.ABC, Keys.NUM, Keys.SYM, Keys.INSERT, Keys.GAP, null))
            assertFalse(code.toString(), KeyPreview.previewable(code))
    }

    @Test fun previewShowsWhileHeldAndUntypedAndHidesOtherwise() {
        fun shows(style: Int = KeyPreview.IN_PLACE, held: Boolean = true, consumed: Boolean = false, typedOnPress: Boolean = false,
                  gliding: Boolean = false, ownsPopup: Boolean = false, editMode: Boolean = false, code: String = "e") =
            KeyPreview.shows(style, held, consumed, typedOnPress, gliding, ownsPopup, editMode, code)
        assertTrue(shows())
        for (style in listOf(KeyPreview.UPWARD, KeyPreview.BALLOON)) assertTrue(shows(style = style))
        assertFalse("The setting is off", shows(style = KeyPreview.OFF))
        assertFalse("The next finger landed and typed it", shows(held = false))
        assertFalse("Its letter is typed", shows(consumed = true))
        assertTrue("Type on press keeps it until the next finger or the lift", shows(consumed = true, typedOnPress = true))
        assertFalse("Swipe typing", shows(gliding = true))
        assertFalse("The long-press row replaced it", shows(ownsPopup = true))
        assertFalse("Layout editing", shows(editMode = true))
        assertFalse("Space has no letter", shows(code = Keys.SPACE))
    }

    // ---- Where it goes ----

    private fun straight(cx: Float, cy: Float) = floatArrayOf(1f, 0f, cx, 0f, 1f, cy)
    private fun tilted(cx: Float, cy: Float, degrees: Double): FloatArray {
        val a = Math.toRadians(degrees); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return floatArrayOf(c, -s, cx, s, c, cy)
    }

    @Test fun aPreviewInTheMiddleDoesNotMove() {
        val sh = KeyPreview.shape(KeyPreview.IN_PLACE, 60f, 60f, 12f)
        val d = KeyPreview.place(straight(500f, 300f), sh.left, sh.top, sh.right, sh.bottom, 1000f, 400f, 4f, -sh.slide, sh.slide)
        assertEquals(0f, d[0], .001f); assertEquals(0f, d[1], .001f)
    }

    @Test fun edgeKeysSlideInwardAndTheTopRowSlidesDown() {
        val w = 60f; val h = 60f
        for (style in listOf(KeyPreview.IN_PLACE, KeyPreview.UPWARD, KeyPreview.BALLOON)) {
            val sh = KeyPreview.shape(style, w, h, 12f)
            // A key at the left edge, 10 px from the top: every shape would cross the top.
            val m = straight(4f + w / 2, 10f + h / 2)
            val d = KeyPreview.place(m, sh.left, sh.top, sh.right, sh.bottom, 1000f, 400f, 4f, -sh.slide, sh.slide)
            assertEquals("$style left", 4f, m[2] + sh.left + d[0], .01f)
            assertEquals("$style top", 4f, m[5] + sh.top + d[1], .01f)
            assertTrue("$style stays over its key", sh.left + d[0] <= -w / 2 && sh.right + d[0] >= w / 2)
        }
    }

    @Test fun rightEdgeAndBottomRowSlideInward() {
        val sh = KeyPreview.shape(KeyPreview.IN_PLACE, 60f, 60f, 12f)
        val m = straight(1000f - 4f - 30f, 400f - 4f - 30f)
        val d = KeyPreview.place(m, sh.left, sh.top, sh.right, sh.bottom, 1000f, 400f, 4f, -sh.slide, sh.slide)
        assertEquals(1000f - 4f, m[2] + sh.right + d[0], .01f)
        assertEquals(400f - 4f, m[5] + sh.bottom + d[1], .01f)
    }

    @Test fun tiltedKeysStayInsideMeasuredOnTheTiltedShape() {
        val sh = KeyPreview.shape(KeyPreview.BALLOON, 60f, 60f, 12f)
        val m = tilted(40f, 70f, 8.0)
        val d = KeyPreview.place(m, sh.left, sh.top, sh.right, sh.bottom, 1000f, 400f, 4f, -sh.slide, sh.slide)
        val xs = floatArrayOf(sh.left, sh.right, sh.right, sh.left).map { it + d[0] }
        val ys = floatArrayOf(sh.top, sh.top, sh.bottom, sh.bottom).map { it + d[1] }
        for (i in 0 until 4) {
            val vx = m[0] * xs[i] + m[1] * ys[i] + m[2]; val vy = m[3] * xs[i] + m[4] * ys[i] + m[5]
            assertTrue("corner $i x $vx", vx >= 4f - .05f)
            assertTrue("corner $i y $vy", vy >= 4f - .05f)
        }
    }

    // ---- The long-press row ----

    @Test fun theRowStartsOverTheKeyAndRunsAwayFromTheNearerEdge() {
        val right = KeyPreview.row(4, 60f, 60f, 1000f, 1f, balloon = false, rightward = true)
        assertEquals(listOf(0f, 60f, 120f, 180f), right.centres.toList())
        val left = KeyPreview.row(4, 60f, 60f, 1000f, 1f, balloon = false, rightward = false)
        assertEquals(listOf(0f, -60f, -120f, -180f), left.centres.toList())
        assertTrue("Above the key", right.bottom < -30f)
    }

    @Test fun manyChoicesShrinkTheCellsToFitTheScreen() {
        val row = KeyPreview.row(8, 90f, 60f, 400f, 1f, balloon = false, rightward = true)
        assertTrue(row.right - row.left <= 400f - 8f + .01f)
    }

    @Test fun theHighlightFollowsTheFingerAlongTheRowAndClearsWellBelowTheKey() {
        val centres = floatArrayOf(0f, 60f, 120f)
        assertEquals(0, KeyPreview.nearestCell(centres, 5f, 0f, 60f))
        assertEquals(1, KeyPreview.nearestCell(centres, 70f, -90f, 60f))
        assertEquals("Past the end stays on the last", 2, KeyPreview.nearestCell(centres, 400f, -60f, 60f))
        assertEquals("Just under the key still picks", 0, KeyPreview.nearestCell(centres, 0f, 80f, 60f))
        assertEquals("More than a key height below clears it", -1, KeyPreview.nearestCell(centres, 0f, 91f, 60f))
    }

    @Test fun aBalloonRowKeepsItsTopOverTheNeck() {
        val row = KeyPreview.row(6, 60f, 60f, 1000f, 1f, balloon = true, rightward = true)
        assertTrue(row.right + row.slideMin >= -30f + 12f - .01f)
        assertTrue(row.left + row.slideMax <= 30f - 12f + .01f)
    }

    // ---- Choosing which keys have a long-press ----

    @Test fun numbersAndSymbolsOfferTheirIphoneVariants() {
        val num = InputState().apply { layer = Layer.NUM }
        fun on(code: String, st: InputState = num) = KeyText.alternatives(code, st, HoldChoices.BOTH, numberRow = false).joinToString("")
        assertEquals("\u00B0", on("d0"))
        assertEquals("\u2013\u2014\u2022", on(KeyText.literal("-")))
        assertEquals("\u00A2\u00A3\u20AC\u00A5\u20A9", on(KeyText.literal("$")))
        assertEquals("\u00BF", on(KeyText.literal("?")))
        assertEquals("\u00A1", on(KeyText.literal("!")))
        assertEquals("\u2026", on(KeyText.literal(".")))
        assertEquals("A shared bottom-row key offers by what it types", "\u2026", on("."))
        assertEquals("\u201C\u201D\u201E\u00AB\u00BB", on(KeyText.literal("\"")))
        assertEquals("\u00A7", on(KeyText.literal("&")))
        assertEquals("Most keys offer nothing", "", on("d5"))
        assertEquals("", on(KeyText.literal("@")))
        val sym = InputState().apply { layer = Layer.SYM }
        assertEquals("\u2030", on(KeyText.literal("%"), sym))
        assertEquals("$\u00A3\u00A5\u20A9", on(KeyText.literal("\u20AC"), sym))
        assertEquals("\u00B7", on(KeyText.literal("\u2022"), sym))
        assertEquals("", on(KeyText.literal("["), sym))
    }

    @Test fun numbersAndSymbolsIgnoreLongPressShows() {
        val num = InputState().apply { layer = Layer.NUM }
        for (shows in HoldChoices.entries) assertEquals(listOf("\u00BF"), KeyText.alternatives(KeyText.literal("?"), num, shows, false))
    }

    @Test fun eachLayerKeepsItsOwnLongPressChoice() {
        assertEquals("abc:d1", KeyText.holdId(Layer.ABC, "d1"))
        assertEquals("num:d1", KeyText.holdId(Layer.NUM, "d1"))
        assertEquals("sym:=%", KeyText.holdId(Layer.SYM, KeyText.literal("%")))
        assertTrue("The number row digit and the 123 digit are told apart", KeyText.holdId(Layer.ABC, "d0") != KeyText.holdId(Layer.NUM, "d0"))
    }

    @Test fun theLetterHintsStayOnTheLetters() {
        val num = InputState().apply { layer = Layer.NUM }
        assertEquals("Letters have no hold on 123", emptyList<String>(), KeyText.alternatives("e", num, HoldChoices.BOTH, false))
        assertEquals("Variants are for 123 and #+= only", emptyList<String>(), KeyText.variants(KeyText.literal("?"), InputState()))
    }

    private fun settings(): KeyboardSettings {
        val values = HashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0]); editor }
                "apply" -> null
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0])
                "getInt", "getString", "getBoolean" -> values[args[0]] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
        return KeyboardSettings(prefs, true, "en")
    }
}

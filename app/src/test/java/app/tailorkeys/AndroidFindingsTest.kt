package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AndroidFindingsTest {
    private fun window(text: String, start: Int, end: Int = start): EditorWindow = EditorWindow(
        text, 0, start, end, text.take(minOf(start, end)), text.drop(maxOf(start, end)), start != end)

    @Test fun suggestionReplacesOnlySelection() {
        val text = "help world"
        val remove = EditorRules.replacementLength(4, selected = true)
        val result = text.take(4 - remove) + "helped " + text.drop(10)
        assertEquals("helphelped ", result)
        assertEquals(4, EditorRules.replacementLength(4, selected = false))
    }

    @Test fun cursorAndExtensionPreserveEmojiAndCombiningSequences() {
        for (cluster in listOf("😀", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇺🇸", "1️⃣", "e\u0301", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F")) {
            val text = "A${cluster}B"
            val end = 1 + cluster.length
            assertEquals(cluster, 1, window(text, end).cursorTarget(-1, false))
            assertEquals(cluster, end, window(text, 1).cursorTarget(1, false))
            assertEquals(cluster, 1, window(text, 0, end).cursorTarget(-1, true))
            assertEquals(cluster, end, window(text, 0, 1).cursorTarget(1, true))
        }
        assertEquals(1, window("A😀B", 2).cursorTarget(-1, false))
        assertEquals(3, window("A😀B", 2).cursorTarget(1, false))
    }

    @Test fun noPersonalizationBlocksDirectAndDeferredLearning() {
        val allowed = EditorRules.canLearn(true, false, false, true)
        assertFalse(allowed)
        assertFalse(EditorRules.deferredLearning(allowed, true))
        assertFalse(EditorRules.deferredLearning(true, allowed))
        assertTrue(EditorRules.deferredLearning(true, true))
        assertFalse(EditorRules.canLearn(true, true, false, false))
        assertTrue(EditorRules.canLearn(true, false, false, false))
    }

    @Test fun invalidLayoutBoundsFacesAndOverflowRecoverToDefault() {
        val mutations: List<(KeyGrid) -> Unit> = listOf(
            { it.items[0].idx = -1 }, { it.items[0].idx = 1 },
            { it.items[0].r = -1 }, { it.items[0].r = 4 },
            { it.items[0].c = -1 }, { it.items[0].c = 11 },
            { it.items[0].w = 0 }, { it.items[0].w = Int.MAX_VALUE },
            { it.items[0].c = Int.MAX_VALUE; it.items[0].w = 2 },
            { it.items[0].keys.clear() }, { it.items[0].keys[0] = "" },
            { it.items[0].w = 6 }, { it.rows = Int.MAX_VALUE },
            { it.items[0].c = it.items[1].c },
        )
        for ((i, mutate) in mutations.withIndex()) {
            val bad = KeyGrid.default().also(mutate)
            assertFalse("case $i", bad.isSound())
            val recovered = KeyGrid.savedOrDefault(bad, "en", true)
            assertTrue(recovered.isSound())
            assertEquals(5, recovered.rows)
            assertNotSame(bad, recovered)
        }
    }

    @Test fun rolloverIncludesUntouchedCharacterStacksExactlyOnce() {
        val output = StringBuilder()
        var consumed = false
        if (EditorRules.pendingCharacter(false, true, false, consumed, true)) {
            output.append('a'); consumed = true
        }
        output.append('c')
        if (EditorRules.pendingCharacter(false, true, false, consumed, true)) output.append('a')
        assertEquals("ac", output.toString())
        assertFalse(EditorRules.pendingCharacter(false, true, true, false, true))
        assertFalse(EditorRules.pendingCharacter(false, true, false, false, false))
    }

    @Test fun selectionRefusesUnknownPositionsAndExtendsBackwardSelections() {
        val unknown = EditorWindow("", -1, -1, -1, "", "", false)
        assertFalse(unknown.known)
        assertNull(unknown.cursorTarget(1, true))
        assertEquals(10, window("0123456789", 8, 3).cursorTarget(20, true))
        assertEquals(0, window("0123456789", 8, 3).cursorTarget(-20, true))
        assertEquals(2, window("0123456789", 8, 3).cursorTarget(-1, false))
    }

    @Test fun clippedEmojiAndFlagWindowsRefuseUnsafeDestinations() {
        val emoji = "👩‍".repeat(100) + "👩"
        val clipped = EditorWindow(emoji, 100, 100 + emoji.length, 100 + emoji.length, emoji, "", false)
        assertNull(clipped.cursorTarget(-1, false))
        val flags = "🇸🇺🇸A"
        val partialFlags = EditorWindow(flags, 101, 103, 103, flags.take(2), flags.drop(2), false)
        assertNull(partialFlags.cursorTarget(1, false))
    }

    @Test fun cursorScansOnlyAroundFocusEvenWithHugeSelectedText() {
        var reads = 0
        val text = object : CharSequence {
            override val length = 1_000_000
            override fun get(index: Int): Char { reads++; return 'a' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must not copy text")
        }
        val live = CursorRead().set(text, 200, 900_000, 100)
        assertTrue(live.known)
        assertSame(text, live.text)
        assertEquals(301, live.target(1, true))
        assertTrue("reads=$reads", reads < 1_000)
        reads = 0
        assertEquals(900_201, live.target(1, false))
        assertTrue("reads=$reads", reads < 1_000)
        assertFalse(CursorRead().set("abc", -1, 0, 0).known)
    }

    @Test fun cursorUsesLiveWindowDespiteDelayedSelectionCallbacks() {
        // The callback still says (0,0); the snapshot after committing abc says (3,3).
        val stale = window("", 0)
        val live = window("abc", 3)
        assertEquals(0, stale.cursorTarget(-1, false))
        assertEquals(2, live.cursorTarget(-1, false))
        // A late callback cannot affect the next live read either.
        assertEquals(1, window("abc", 2).cursorTarget(-1, false))
    }

    @Test fun editAccessibilityEditsWithoutTyping() {
        assertFalse(EditorRules.typesFromAccessibility(true))
        assertTrue(EditorRules.typesFromAccessibility(false))
        val grid = KeyGrid.default()
        val a = grid.items[0]; val b = grid.items[1]
        assertTrue(LayoutEditing.apply(grid, b, LayoutEditing.Action.COMBINE, a))
        assertTrue(b.isStack)
        assertTrue(LayoutEditing.apply(grid, b, LayoutEditing.Action.SEPARATE))
        assertFalse(b.isStack)
        assertTrue(grid.isSound())
        assertFalse(LayoutEditing.apply(grid, b, LayoutEditing.Action.UP))
    }

    @Test fun handleShrinkAndRestoreUpdateBothLinkedOrientations() {
        val values = HashMap<String, Any?>()
        lateinit var edit: SharedPreferences.Editor
        edit = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, a ->
            when (m.name) {
                "putInt", "putString", "putBoolean" -> { values[a[0] as String] = a[1]; edit }
                "apply" -> null
                else -> error(m.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            when (m.name) {
                "edit" -> edit
                "contains" -> values.containsKey(a[0])
                "getInt", "getString", "getBoolean" -> values[a[0]] ?: a[1]
                else -> error(m.name)
            }
        } as SharedPreferences
        val settings = KeyboardSettings(prefs, true, "en")
        settings.set(Prefs.SAME_ORIENTATIONS, true)
        val shrink = handleChange(100, Align.CENTER, false, true, null)
        settings.setHandleFor(false, shrink.width, shrink.align)
        for (land in listOf(false, true)) {
            assertEquals(78, settings.int(Prefs.width(land)))
            assertEquals(Align.LEFT, settings.align(land))
        }
        val restore = handleChange(shrink.width, shrink.align, false, false, shrink.restore)
        settings.setHandleFor(true, restore.width, restore.align)
        for (land in listOf(false, true)) {
            assertEquals(100, settings.int(Prefs.width(land)))
            assertEquals(Align.CENTER, settings.align(land))
        }
    }
}

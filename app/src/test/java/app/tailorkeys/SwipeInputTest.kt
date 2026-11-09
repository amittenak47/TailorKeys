package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class SwipeInputTest {
    private val keys = mapOf('a' to GestureDecoder.Point(0f, 0f), 'b' to GestureDecoder.Point(100f, 0f),
        'c' to GestureDecoder.Point(100f, 100f), 'd' to GestureDecoder.Point(200f, 0f))
    @Test fun straightPathKeepsOnlyEndpoints() {
        val trace = SwipeTrace(keys, 100f)
        for (x in 0..200 step 10) trace.sample(x.toFloat(), 0f, x.toLong(), x / 10)
        assertEquals("ad", trace.text())
    }
    @Test fun sharpCornerIsIncluded() {
        val trace = SwipeTrace(keys, 100f)
        for (x in 0..100 step 10) trace.sample(x.toFloat(), 0f, x.toLong(), x / 10)
        for (y in 10..100 step 10) trace.sample(100f, y.toFloat(), 100L + y, 10 + y / 10)
        assertEquals("abc", trace.text())
    }
    @Test fun dwellDoublesOnceAndLeavingRearms() {
        val trace = SwipeTrace(keys, 100f)
        trace.sample(0f, 0f, 0, 0)
        trace.sample(1f, 1f, 209, 1)
        assertEquals("a", trace.text())
        trace.sample(1f, 1f, 210, 2)
        trace.sample(1f, 1f, 700, 3)
        assertEquals("aa", trace.text())
        assertEquals(1, trace.hints.size)
        trace.sample(100f, 0f, 800, 4)
        trace.sample(100f, 0f, 1010, 5)
        assertEquals("aabb", trace.text())
        assertEquals(2, trace.hints.size)
    }
    @Test fun ordinarySlowPassDoesNotDouble() {
        val trace = SwipeTrace(keys, 100f)
        for (x in 0..200 step 10) trace.sample(x.toFloat(), 0f, x * 10L, x / 10)
        assertTrue(trace.hints.isEmpty())
    }
    @Test fun intendedLettersSurviveFastCrossingsAndPauses() {
        for (width in listOf(50f, 100f)) {
            val board = linkedMapOf<Char, GestureDecoder.Point>()
            for ((row, text) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
                text.forEachIndexed { col, ch -> board[ch] = GestureDecoder.Point((col + row * .35f) * width, row * width) }
            for (word in listOf("hello", "quick", "type", "keyboard")) {
                val trace = SwipeTrace(board, width)
                var time = 0L; var index = 0
                var previous = board.getValue(word.first())
                trace.sample(previous.x, previous.y, time, index++)
                var i = 1
                while (i < word.length) {
                    val point = board.getValue(word[i])
                    if (word[i] == word[i - 1]) {
                        time += 220
                        trace.sample(point.x, point.y, time, index++)
                    } else {
                        for (step in 1..20) {
                            time += 2
                            trace.sample(previous.x + (point.x - previous.x) * step / 20,
                                previous.y + (point.y - previous.y) * step / 20, time, index++)
                        }
                        if (i < word.lastIndex) {
                            time += 120
                            trace.sample(point.x, point.y, time, index++)
                        }
                    }
                    previous = point; i++
                }
                assertEquals(word, trace.text())
            }
        }
    }
    @Test fun automaticSpaceOffAndLettersNeverSpace() {
        for (after in listOf("", "next", " next", ".")) {
            assertFalse(TextInputRules.addSpace(false, after))
            assertFalse(TextInputRules.addSpace(true, after, letters = true))
            assertFalse(TextInputRules.replacement(3, after, false).space)
            assertEquals(".", TextInputRules.punctuation(".", "swipe ", after, true, true, trailingSpace = false)?.insert)
        }
    }
    private val wordKeys = "corlay".mapIndexed { i, ch -> ch to GestureDecoder.Point(i * 100f, if (i % 2 == 0) 0f else 100f) }.toMap()
    private fun path(text: String) = text.mapIndexed { i, ch -> wordKeys.getValue(ch).copy(time = i * 60L) }
    @Test fun doubleHintClearlyRanksCorollaryAboveCorolary() {
        val decoder = GestureDecoder(listOf("corolary", "corollary"))
        decoder.setKeys(wordKeys, 100f)
        val matches = decoder.decode(path("corolary"), null, { 1.0 }, { _, _ -> 0.0 }, listOf(GestureDecoder.DoubleHint('l', 4)))
        assertEquals("corollary", matches.first().word)
        assertTrue(matches[0].score - matches[1].score > 5)
    }
    @Test fun forgettingRemovesLearnedGestureEntries() {
        val decoder = GestureDecoder(listOf("corollary"))
        decoder.setKeys(wordKeys, 100f)
        decoder.setUserWords(setOf("corolary"))
        assertTrue(decoder.decode(path("corolary"), null, { 1.0 }, { _, _ -> 0.0 }).any { it.word == "corolary" })
        decoder.setUserWords(emptySet())
        assertFalse(decoder.decode(path("corolary"), null, { 1.0 }, { _, _ -> 0.0 }).any { it.word == "corolary" })
        assertTrue(decoder.decode(path("corolary"), null, { 1.0 }, { _, _ -> 0.0 }).any { it.word == "corollary" })
    }
    @Test fun typedPrefixConstrainsRemainingPath() {
        val decoder = GestureDecoder(listOf("corollary", "ollary", "corolary", "collary"))
        decoder.setKeys(wordKeys, 100f)
        val matches = decoder.decode(path("ollary"), null, { 1.0 }, { _, _ -> 0.0 },
            listOf(GestureDecoder.DoubleHint('l', 1)), prefix = "cor")
        assertEquals("corollary", matches.first().word)
        assertTrue(matches.all { it.word.startsWith("cor") })
        assertEquals("corollary", SwipeTrace.appendToPrefix("cor", "ollary"))
        assertEquals("corollary", SwipeTrace.appendToPrefix("cor", "rollary"))
    }
    @Test fun partialDecodeUsesObservedPrefix() {
        val decoder = GestureDecoder(listOf("corollary"))
        decoder.setKeys(wordKeys, 100f)
        assertEquals("cor", decoder.decode(path("cor"), null, { 1.0 }, { _, _ -> 0.0 }, partial = true).first().prefix)
    }
    @Test fun prefixOverlapAndMissingDictionaryFallback() {
        val decoder = GestureDecoder(listOf("corollary", "ollary"))
        decoder.setKeys(wordKeys, 100f)
        assertEquals("corollary", decoder.decode(path("rolary"), null, { 1.0 }, { _, _ -> 0.0 },
            prefix = "cor").first().word)
        assertTrue(decoder.decode(path("olary"), null, { 1.0 }, { _, _ -> 0.0 }, prefix = "car").isEmpty())
        assertEquals("carolary", SwipeTrace.appendToPrefix("car", "olary"))
    }
    @Test fun partialDecodeTracksARevisitedKey() {
        val decoder = GestureDecoder(listOf("corollary"))
        decoder.setKeys(wordKeys, 100f)
        assertEquals("coro", decoder.decode(path("coro"), null, { 1.0 }, { _, _ -> 0.0 }, partial = true).first().prefix)
    }
    @Test fun workerScoringPreservesRankingAndIsolatesLearning() {
        val dir = java.nio.file.Files.createTempDirectory("swipe-scoring").toFile().apply { deleteOnExit() }
        val assets = java.io.File("src/main/assets")
        val predictor = Predictor({ java.io.File(assets, it).inputStream() }, dir, enableModel = false)
        predictor.learn("corollary", "a", "the")
        val scoring = predictor.gestureScoring()
        val frequency = scoring.frequency("corollary")
        val context = scoring.context("a", "corollary")
        assertEquals(predictor.gestureFrequency("corollary"), frequency, 1e-12)
        assertEquals(predictor.contextScore("a", "corollary"), context, 1e-12)
        repeat(5) { predictor.learn("corollary", "a", "the") }
        predictor.learn("tailorkeyszz", null)
        assertEquals(frequency, scoring.frequency("corollary"), 1e-12)
        assertEquals(context, scoring.context("a", "corollary"), 1e-12)
        assertFalse(scoring.words.contains("tailorkeyszz"))
        assertTrue(predictor.gestureFrequency("corollary") > frequency)
    }
    @Test fun doubleHintsMatchTheCorrectOccurrenceAndTypedSuffix() {
        val decoder = GestureDecoder(listOf("coocoa", "cocooa", "cocoa"))
        decoder.setKeys(wordKeys, 100f)
        assertEquals("coocoa", decoder.decode(path("cocoa"), null, { 1.0 }, { _, _ -> 0.0 },
            listOf(GestureDecoder.DoubleHint('o', 1))).first().word)
        assertEquals("cocooa", decoder.decode(path("coa"), null, { 1.0 }, { _, _ -> 0.0 },
            listOf(GestureDecoder.DoubleHint('o', 1)), prefix = "co").first().word)
    }
    @Test fun partialPrefixIncludesRepeatedLetters() {
        val decoder = GestureDecoder(listOf("corollary"))
        decoder.setKeys(wordKeys, 100f)
        assertEquals("coroll", decoder.decode(path("corol"), null, { 1.0 }, { _, _ -> 0.0 }, partial = true).first().prefix)
    }
    @Test fun replacementPreservesSurroundingTextAndSpacing() {
        fun replace(before: String, after: String, word: String, on: Boolean, spaced: Int = 0): String {
            val prefix = if (spaced > 0) spaced else before.takeLastWhile(TextInputRules::wordChar).length
            val plan = TextInputRules.replacement(prefix, after, on, spacedWordLength = spaced)
            return before.dropLast(plan.before) + word + (if (plan.space) " " else "") + after.drop(plan.after)
        }
        assertEquals("hello ", replace("hel", "", "hello", true))
        assertEquals("hello", replace("hel", "", "hello", false))
        assertEquals("say hello next", replace("say he", "llo next", "hello", true))
        assertEquals("hello.", replace("hel", ".", "hello", true))
        assertEquals("dogs next", replace("dog ", "next", "dogs", true, spaced = 3))
        assertEquals("dogs ", replace("dog ", "", "dogs", true, spaced = 3))
        assertEquals(TextInputRules.Replacement(0, 0, false), TextInputRules.replacement(3, " next", true, selected = true))
    }
    @Test fun spacingAtEndAndWithinText() {
        assertTrue(TextInputRules.addSpace(true, ""))
        assertTrue(TextInputRules.addSpace(true, "next"))
        for (after in listOf(" ", " next", "\nnext", "\t", ".", ", next", ")", "!")) assertFalse(after, TextInputRules.addSpace(true, after))
        assertFalse(TextInputRules.addSpace(false, ""))
        assertFalse(TextInputRules.addSpace(true, "", password = true))
        assertEquals(3, TextInputRules.suffixLength("llo world"))
        assertEquals(4, TextInputRules.suffixLength("og's "))
    }
    @Test fun punctuationAttachesOnlyToAnAutomaticSpace() {
        for (ch in ".,?!:;") {
            assertEquals(TextInputRules.Edit(1, "$ch "), TextInputRules.punctuation(ch.toString(), "dog ", "", true, true))
            assertNull(TextInputRules.punctuation(ch.toString(), "dog ", "", false, true))
            assertNull(TextInputRules.punctuation(ch.toString(), "dog ", "", true, false))
        }
        assertEquals(TextInputRules.Edit(1, "'"), TextInputRules.punctuation("'", "dog ", "", true, true))
        assertNull(TextInputRules.punctuation("'", "dog ", "", false, true))
    }
    @Test fun pairsInsertAndExistingClosersStepOver() {
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}', '"' to '"')) {
            assertEquals(TextInputRules.Edit(insert = "$open$close", move = -1), TextInputRules.punctuation(open.toString(), "", "", false, true))
            assertEquals(TextInputRules.Edit(move = 1), TextInputRules.punctuation(close.toString(), "", close.toString(), false, true))
            assertNull(TextInputRules.punctuation(open.toString(), "", "", false, true, password = true))
        }
    }
    @Test fun quotesOnlyPairAtTheStartOfAWord() {
        assertEquals(TextInputRules.Edit(insert = "\"\"", move = -1), TextInputRules.punctuation("\"", "said ", "", false, true))
        assertEquals(TextInputRules.Edit(insert = "\"\"", move = -1), TextInputRules.punctuation("\"", "(", "", false, true))
        assertNull(TextInputRules.punctuation("\"", "5", "", false, true))
        assertNull(TextInputRules.punctuation("\"", "word", "", false, true))
    }
    @Test fun doubleSpaceRespectsTimeSettingsAndPassword() {
        assertTrue(TextInputRules.doubleSpace(true, "dog ", 300))
        for (text in listOf(" ", "dog  ", "dog. ", "\n ")) assertFalse(TextInputRules.doubleSpace(true, text, 300))
        assertFalse(TextInputRules.doubleSpace(false, "dog ", 300))
        assertFalse(TextInputRules.doubleSpace(true, "dog ", 600))
        assertFalse(TextInputRules.doubleSpace(true, "dog ", 300, true))
    }
    @Test fun aLetterFallingOntoTheSpaceBarEndsNoSentence() {
        // The 5 November take: n at the bar's top edge, then the real space mid-bar.
        assertTrue(TextInputRules.slippedSpace(first = .43f, second = 1.2f))
        // A thumb repeating: mid-bar twice, or at the same edge twice.
        assertFalse(TextInputRules.slippedSpace(first = 1.2f, second = 1.1f))
        assertFalse(TextInputRules.slippedSpace(first = .43f, second = .5f))
        assertFalse(TextInputRules.slippedSpace(first = Float.MAX_VALUE, second = Float.MAX_VALUE))
    }
    @Test fun pluralAndPossessiveForms() {
        assertEquals(listOf("dogs", "dog's"), TextInputRules.wordForms("dog") { it == "dogs" })
        assertEquals(listOf("Cities", "City's"), TextInputRules.wordForms("City") { it == "cities" })
        assertEquals(listOf("boxes", "box's"), TextInputRules.wordForms("box") { it == "boxes" })
        assertEquals(listOf("Alex's"), TextInputRules.wordForms("Alex") { false })
    }
}

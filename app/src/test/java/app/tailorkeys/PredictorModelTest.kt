package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime

class PredictorModelTest {
    companion object {
        private val assets = File("src/main/assets")
        private val dir = Files.createTempDirectory("predictor-model").toFile().apply { deleteOnExit() }
        private val predictor = Predictor({ File(assets, it).inputStream() }, dir).apply { setLayout(SplitTaps.grid) }
        private val model get() = EnglishModel.load { File(assets, it).inputStream() }!!
    }

    @Test fun nextWordsAndSpecialTokens() {
        predictor.updateContext("i want to ", "")
        assertTrue(predictor.closest("", "to", "want").take(3).any { it in listOf("go", "be", "see", "do", "get") })
        predictor.updateContext("thank ", "")
        assertEquals("you", predictor.closest("", "thank").first())
        for (text in listOf("", "thank ", "1,000.5, ", "hello! ", "what? yes. okay! ")) {
            predictor.updateContext(text, "")
            assertTrue(predictor.closest("", n = 400).none { it.startsWith("<") })
            assertTrue(predictor.explore("", n = 400).none { it.startsWith("<") })
            assertTrue(predictor.nextLetters("", null).keys.none { it == '<' })
        }
    }

    @Test fun tokensMatchTrainingAndRetainOnlyTwoPreviousSentences() {
        assertEquals(listOf("<s>", "i", "don't", "know", "<,>", "<num>", "<,>", "déjà", "vu", "</s>",
            "<s>", "yes", "</s>", "<s>", "really"), EnglishTokens.context("I DON’T know, 1,000.50, déjà_vu... Yes!? really"))
        assertEquals(listOf("<s>", "two", "</s>", "<s>", "three", "</s>", "<s>"), EnglishTokens.context("one. two! three?"))
        assertEquals(listOf("<s>", "<num>", "cats", "</s>", "<s>", "\u2173"), EnglishTokens.context("\u00b2 cats. \u2163"))
    }

    @Test fun lettersReuseScoresWordsAndPunctuationAdvanceAndEditsReset() {
        val context = EnglishContext(emptyList(), { model }, false, {})
        context.update("i want to ", 0)
        assertEquals(4, context.steps)
        val steps = context.steps; val softmaxes = context.softmaxes; val resets = context.resets
        val scores = context.withScores { it }
        for (word in listOf("g", "go", "going")) context.update("i want to $word", word.length)
        assertEquals(steps, context.steps); assertEquals(softmaxes, context.softmaxes)
        assertSame(scores, context.withScores { it })
        context.update("i want to go ", 0)
        assertEquals(steps + 1, context.steps); assertEquals(resets, context.resets)
        context.update("i want to go. ", 0)
        assertEquals(steps + 3, context.steps); assertEquals(resets, context.resets)
        context.update("i want to ", 0)
        assertEquals(resets + 1, context.resets)
        context.reset(); context.update("i want to ", 0)
        assertEquals(resets + 2, context.resets)
        val expected = FloatArray(model.vocabularySize)
        val state = model.initialState()
        for (t in EnglishTokens.context("i want to ")) model.step(state, model.idOf(t))
        model.logProbs(state, expected)
        context.withScores { assertArrayEquals(expected, it!!.probabilities, 0f) }
        val cachedSoftmaxes = context.softmaxes
        context.update("i want to   ", 0)
        assertEquals(cachedSoftmaxes, context.softmaxes)
    }

    @Test fun pendingContextUsesFallbackAndCoalescesWithoutPublishingStaleScores() {
        val tasks = ArrayList<() -> Unit>()
        var callbacks = 0
        lateinit var context: EnglishContext
        context = EnglishContext(emptyList(), {
            // Simulate a cursor move while loading/recomputing, before publication.
            context.update("thank ", 0)
            model
        }, true, { callbacks++ }, { tasks.add(it) })
        context.update("i want to ", 0)
        assertNull(context.withScores { it })
        context.update("something else ", 0)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).invoke()
        assertEquals(1, callbacks)
        context.withScores { assertEquals("you", it!!.model.vocabulary[it.top[0]]) }
        context.update("a new field ", 0)
        assertNull(context.withScores { it })
        context.reset()
        tasks.removeAt(0).invoke()
        assertNull(context.withScores { it })
        assertEquals(1, callbacks)
    }

    @Test fun failedModelKeepsTheExactLegacyScoring() {
        val fallback = Predictor({ File(assets, it).inputStream() }, dir, enableModel = false).apply { setLayout(SplitTaps.grid) }
        // No context has been supplied: model-not-ready is exactly the same as disabled.
        val notReady = Predictor({ File(assets, it).inputStream() }, dir, modelLoader = { null }).apply { setLayout(SplitTaps.grid) }
        for (prefix in listOf("", "th", "teh", "sev", "aeven", "zzzzz")) {
            notReady.resetContext()
            assertEquals(fallback.closest(prefix, "six", "five"), notReady.closest(prefix, "six", "five"))
            notReady.updateContext("five six $prefix", prefix)
            assertEquals(fallback.closest(prefix, "six", "five"), notReady.closest(prefix, "six", "five"))
            assertEquals(fallback.explore(prefix, "six", "five"), notReady.explore(prefix, "six", "five"))
            assertEquals(fallback.nextLetters(prefix, "six", "five"), notReady.nextLetters(prefix, "six", "five"))
            assertEquals(fallback.contextScore("six", "seven"), notReady.contextScore("six", "seven"), 0.0)
        }
        val failed = EnglishContext(emptyList(), { null }, false, {})
        failed.update("thank ", 0)
        assertNull(failed.withScores { it })
    }

    @Test fun reusedProbabilityBuffersInvalidateCandidateCaches() {
        predictor.updateContext("i want to ", "")
        predictor.closest("", "to", "want")
        predictor.updateContext("they want to ", "")
        predictor.updateContext("she may want to ", "")
        val expected = predictor.english!!.withScores { s -> s!!.top.take(8).map { s.model.vocabulary[it] } }
        assertEquals(expected, predictor.closest("", "to", "want"))
    }

    @Test fun contextCacheTimingAndAllocations() {
        assumeTrue(System.getenv("TK_LM_EVAL") == "1")
        val context = EnglishContext(emptyList(), { model }, false, {})
        val before = "i would like to g"
        context.update(before, 1)
        repeat(5000) { context.update(before, 1) }
        val steps = context.steps; val softmaxes = context.softmaxes
        val times = DoubleArray(9) { measureNanoTime { repeat(10000) { context.update(before, 1) } } / 10000.0 }
        var bytesPerKey = -1.0
        try {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val api = Class.forName("com.sun.management.ThreadMXBean")
            if (api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true) {
                api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
                val getter = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                val thread = Thread.currentThread().id
                fun allocated() = getter.invoke(bean, thread) as Long
                repeat(20) { allocated() }
                val start = allocated(); val overhead = allocated() - start
                val initial = allocated()
                repeat(10000) { context.update(before, 1) }
                bytesPerKey = maxOf(0.0, (allocated() - initial - overhead) / 10000.0)
                assertTrue("Context cache allocates $bytesPerKey bytes/key", bytesPerKey <= 1.0)
            }
        } catch (_: ReflectiveOperationException) { }
        assertEquals(steps, context.steps); assertEquals(softmaxes, context.softmaxes)
        val longText = List(8) { "i would like to have a cup of hot tea " }.joinToString("") // 80 tokens + <s>
        val rebuilds = DoubleArray(9) {
            context.reset()
            measureNanoTime { context.update(longText, 0) } / 1e6
        }
        times.sort(); rebuilds.sort()
        val result = "cache_update_median_ns=${times[4]}; cache_allocated_bytes_per_key=$bytesPerKey; " +
            "recompute_81_tokens_median_ms=${rebuilds[4]}; model_steps_during_typing=0; softmaxes_during_typing=0"
        File("build/reports/integrated-lm-cache-timing.txt").apply { parentFile.mkdirs(); writeText(result + "\n") }
        println(result)
    }

    @Test fun spatialCorrectionsStillWorkWithModelScores() {
        predictor.updateContext("five six ", "")
        for (word in listOf("awvwn", "aivwn", "siv2n", "aeven")) {
            val result = predictor.closest(word, "six", "five", taps = SplitTaps.word(word, slips = true))
            assertTrue("$word: $result", "seven" in result.take(3))
        }
    }

    @Test fun otherLanguagesNeverLoadOrUseTheEnglishModel() {
        for (language in listOf("es", "fr", "de")) {
            val p = Predictor({ File(assets, it).inputStream() }, dir, language, modelLoader = { error("English load for $language") })
            assertNull(p.english)
            val before = p.closest("", "de")
            p.updateContext("this context must be ignored ", "")
            assertEquals(before, p.closest("", "de"))
        }
    }

    @Test fun personalWordsPairsTriplesAndOovFrequencyRemainEffective() {
        val personalDir = Files.createTempDirectory("predictor-personal").toFile()
        try {
            val p = Predictor({ File(assets, it).inputStream() }, personalDir)
            p.updateContext("thank ", "")
            val before = p.contextScore("thank", "zorbles")
            repeat(8) { p.learn("zorbles", "thank", "i") }
            p.updateContext("i thank ", "")
            assertTrue(p.contextScore("thank", "zorbles") > before + 10)
            assertTrue(p.closest("", "thank", "i").take(3).contains("zorbles"))
            p.english!!.withScores { s ->
                val words = p.gestureWords()
                val oov = words.indices.filter { s!!.ids[it] == 2 }
                assertTrue(oov.size > 2)
                assertTrue(s!!.probability(words[oov.first()], oov.first()) > s.probability(words[oov.last()], oov.last()))
            }
            p.updateContext("thank ", "")
            val expected = p.english!!.withScores { it!!.probability("you", p.gestureWords().indexOf("you")) }
            assertEquals(expected, p.contextScore("thank", "you"), 0.0)
        } finally { personalDir.deleteRecursively() }
    }
}

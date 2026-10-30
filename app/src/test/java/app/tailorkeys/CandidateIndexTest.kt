package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class CandidateIndexTest {
    @Test fun indexedCandidatesExactlyMatchFullScanIncludingSpatialTypos() {
        val dir = Files.createTempDirectory("tk-index-test").toFile()
        try {
            for (language in listOf("en", "es", "de", "fr")) {
                fun predictor(indexed: Boolean) = Predictor({ File("src/main/assets", it).inputStream() }, dir,
                    language, enableModel = false, indexed = indexed).apply {
                    setLayout(SplitTaps.grid)
                    learn("tailorkeys", "hello", "say")
                    learn("seven", "six", "five")
                }
                val indexed = predictor(true)
                val scan = predictor(false)
                val rnd = Random(734)
                val prefixes = linkedSetOf("", "t", "th", "teh", "hte", "sev", "sevn", "sevem", "awvwn", "aivwn", "siv2n", "aeven", "zzzzz", "tailor")
                val words = indexed.gestureWords()
                val searched = indexed.searchedWords()
                repeat(if (language == "en") 180 else 45) {
                    val word = words[rnd.nextInt(words.size)]
                    val prefix = word.take(rnd.nextInt(1, minOf(12, word.length) + 1))
                    prefixes.add(prefix)
                    if (prefix.length > 1) {
                        val i = rnd.nextInt(prefix.length)
                        prefixes.add(prefix.replaceRange(i, i + 1, ('a' + rnd.nextInt(26)).toString()))
                        prefixes.add(prefix.removeRange(i, i + 1))
                        prefixes.add(prefix[1].toString() + prefix[0] + prefix.drop(2))
                    }
                }
                for (prefix in prefixes) for (spatial in listOf(false, true)) {
                    val taps = if (spatial && prefix.all { it in SplitTaps.centres || it == '2' }) SplitTaps.word(prefix, slips = true) else null
                    val expected = scan.rankedCandidates(prefix, "six", "five", taps)
                    assertEquals("$language prefix=$prefix spatial=$spatial", expected,
                        indexed.rankedCandidates(prefix, "six", "five", taps))
                    assertEquals(scan.closest(prefix, "six", "five", taps = taps), indexed.closest(prefix, "six", "five", taps = taps))
                    assertEquals(scan.explore(prefix, "six", "five", taps = taps), indexed.explore(prefix, "six", "five", taps = taps))
                    assertEquals(scan.nextLetters(prefix, "six", "five", taps), indexed.nextLetters(prefix, "six", "five", taps))
                    val hasPrefix = searched.any { it.startsWith(prefix.lowercase()) } || "tailorkeys".startsWith(prefix.lowercase()) || "seven".startsWith(prefix.lowercase())
                    assertEquals(prefix.length >= 2 && !hasPrefix, indexed.isDeadEnd(prefix))
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun contradictorySpatialTapsStillKeepAllLiteralCompletions() {
        val dir = Files.createTempDirectory("tk-index-extreme").toFile()
        try {
            fun predictor(indexed: Boolean) = Predictor({ File("src/main/assets", it).inputStream() }, dir,
                enableModel = false, indexed = indexed).apply { setLayout(SplitTaps.grid) }
            val indexed = predictor(true); val scan = predictor(false)
            for (prefix in listOf("th", "sev", "seve", "keyboar", "absolutely")) {
                val taps = ComposingTaps()
                val distances = FloatArray(ComposingTaps.LETTERS) { 100f }.also { it['z'.code] = 0f }
                for (ch in prefix) taps.append(taps.word, ch.toString(), distances)
                assertEquals(prefix, scan.rankedCandidates(prefix, null, null, taps), indexed.rankedCandidates(prefix, null, null, taps))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun indexPreservesEnglishModelScoresAndOrdering() {
        val dir = Files.createTempDirectory("tk-index-model").toFile()
        try {
            fun predictor(indexed: Boolean) = Predictor({ File("src/main/assets", it).inputStream() }, dir,
                indexed = indexed).apply { setLayout(SplitTaps.grid) }
            val indexed = predictor(true); val scan = predictor(false)
            for (context in listOf("five six ", "i want to ", "thank ")) {
                for (prefix in listOf("", "th", "teh", "hte", "sev", "sevn", "sevem", "awvwn", "aeven", "zzzzz")) {
                    indexed.updateContext(context + prefix, prefix)
                    scan.updateContext(context + prefix, prefix)
                    val taps = SplitTaps.word(prefix, slips = true)
                    assertEquals("$context $prefix", scan.rankedCandidates(prefix, null, null, taps),
                        indexed.rankedCandidates(prefix, null, null, taps))
                }
            }
        } finally { dir.deleteRecursively() }
    }
}

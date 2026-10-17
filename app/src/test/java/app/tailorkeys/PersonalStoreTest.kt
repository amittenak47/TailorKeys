package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class PersonalStoreTest {
    @Test fun savesUseImmutableSnapshotsAndAtomicReplacement() {
        val dir = Files.createTempDirectory("tk-store").toFile()
        try {
            val words = hashMapOf("hello" to 1)
            val pairs = hashMapOf("say" to hashMapOf("hello" to 2))
            val triples = hashMapOf("please\tsay" to hashMapOf("hello" to 3))
            val snapshot = PersonalStore.Snapshot(words, pairs, triples)
            words["hello"] = 99; pairs.getValue("say")["hello"] = 99
            triples.getValue("please\tsay")["hello"] = 99
            val files = listOf("words", "pairs", "triples").map { File(dir, it).apply { writeText("old") } }
            var writerThread = ""
            PersonalStore.submit {
                writerThread = Thread.currentThread().name
                snapshot.write(files[0], files[1], files[2])
            }.get(10, TimeUnit.SECONDS)
            assertEquals("TailorKeys-personal-io", writerThread)
            assertEquals("hello\t1\n", files[0].readText())
            assertEquals("say\thello\t2\n", files[1].readText())
            assertEquals("please\tsay\thello\t3\n", files[2].readText())
            assertEquals(3, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun successiveSavesKeepNewestGenerationAndReloadAllLearning() {
        val dir = Files.createTempDirectory("tk-save").toFile()
        try {
            fun model() = Predictor({ File("src/main/assets", it).inputStream() }, dir, enableModel = false)
            val p = model()
            p.learn("tailorkeys", "hello", "say")
            val first = p.save()
            p.learn("tailorkeys", "hello", "say")
            p.save()!!.get(10, TimeUnit.SECONDS)
            first!!.get(10, TimeUnit.SECONDS)
            assertTrue(File(dir, "user_words.txt").readLines().contains("tailorkeys\t2"))
            assertTrue(File(dir, "user_bigrams.txt").readLines().contains("hello\ttailorkeys\t2"))
            assertTrue(File(dir, "user_trigrams.txt").readLines().contains("say\thello\ttailorkeys\t2"))
            assertEquals(p.closest("tailor", "hello", "say"), model().closest("tailor", "hello", "say"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun chordReaderOpensOnlyBoundedDictionaryAndPreservesRanks() {
        val opened = ArrayList<String>()
        val words = DictionaryWords.read({ name ->
            opened.add(name)
            " the\nTHE\n\nhello\nworld\n".byteInputStream()
        }, "de", 2, unique = true)
        assertEquals(listOf("words_de.txt"), opened)
        assertEquals(listOf("the", "hello"), words)
    }
}

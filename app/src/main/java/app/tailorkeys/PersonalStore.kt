package app.tailorkeys

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** Copies are taken on the predictor's owning thread; only this worker ever writes files. */
internal object PersonalStore {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TailorKeys-personal-io").apply { isDaemon = true }
    }
    private val generations = HashMap<String, Long>()
    @Synchronized fun generation(dir: File): Long = generations[dir.absolutePath] ?: 0L
    @Synchronized private fun bump(dir: File) { generations[dir.absolutePath] = generation(dir) + 1 }
    fun listWords(dir: File): List<Pair<String, String>> = app.tailorkeys.Prefs.LANGUAGE_NAMES.keys.flatMap { lang ->
        val suffix = if (lang == "en") "" else "_$lang"
        File(dir, "user_words$suffix.txt").takeIf { it.exists() }?.readLines()?.mapNotNull {
            it.substringBefore('\t').takeIf { word -> word.isNotBlank() }?.let { word -> lang to word }
        }.orEmpty()
    }.sortedWith(compareBy({ it.second }, { it.first }))
    /** Serialize with old saves; their generation guard prevents forgotten data coming back. */
    fun forget(dir: File, language: String? = null, word: String? = null) {
        bump(dir)
        submit {
            for (lang in app.tailorkeys.Prefs.LANGUAGE_NAMES.keys) {
                if (language != null && lang != language) continue
                val suffix = if (lang == "en") "" else "_$lang"
                for (kind in listOf("words", "bigrams", "trigrams", "learning")) {
                    val file = File(dir, "user_$kind$suffix.txt")
                    if (word == null) file.delete()
                    else if (file.exists()) atomic(file) { out ->
                        file.forEachLine { line ->
                            if (line.split('\t').dropLast(if (kind == "learning") 2 else 1).none { it == word.lowercase() }) out.append(line).append('\n')
                        }
                    }
                }
            }
        }.get()
    }
    class Snapshot(words: Map<String, Int>, pairs: Map<String, Map<String, Int>>, triples: Map<String, Map<String, Int>>, private val metadata: String? = null) {
        private val words = HashMap(words)
        private val pairs = pairs.mapValues { HashMap(it.value) }
        private val triples = triples.mapValues { HashMap(it.value) }
        fun write(wordFile: File, pairFile: File, tripleFile: File, metadataFile: File? = null) {
            if (metadata != null && metadataFile != null) atomic(metadataFile) { it.append(metadata) }
            atomic(wordFile) { out -> for ((word, count) in words) out.append("$word\t$count\n") }
            atomic(pairFile) { out -> for ((previous, row) in pairs) for ((word, count) in row) out.append("$previous\t$word\t$count\n") }
            atomic(tripleFile) { out -> for ((context, row) in triples) for ((word, count) in row) out.append("$context\t$word\t$count\n") }
        }
    }
    fun submit(action: () -> Unit) = worker.submit(action)
    private fun atomic(file: File, write: (Appendable) -> Unit) {
        val temporary = File.createTempFile(file.name + ".", ".tmp", file.absoluteFile.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                val writer = stream.bufferedWriter()
                write(writer)
                writer.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

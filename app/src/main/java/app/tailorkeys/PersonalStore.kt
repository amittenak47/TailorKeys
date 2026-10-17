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
    class Snapshot(words: Map<String, Int>, pairs: Map<String, Map<String, Int>>, triples: Map<String, Map<String, Int>>) {
        private val words = HashMap(words)
        private val pairs = pairs.mapValues { HashMap(it.value) }
        private val triples = triples.mapValues { HashMap(it.value) }
        fun write(wordFile: File, pairFile: File, tripleFile: File) {
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

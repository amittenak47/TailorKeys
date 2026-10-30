package app.tailorkeys

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryProbeTest {
    private fun used(): Long { repeat(4) { System.gc(); Thread.sleep(100) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
    @Test fun predictorHeap() {
        assumeTrue(System.getenv("TK_MEM") == "1")
        val before = used()
        val p = Predictor({ File("src/main/assets", it).inputStream() }, Files.createTempDirectory("tk-mem").toFile(), enableModel = false)
        val after = used()
        println("TK_MEM predictor ${(after - before) / 1_000_000} MB, words ${p.searchedWords().size}")
        val d = GestureDecoder(p.gestureWords())
        println("TK_MEM + gesture decoder ${(used() - after) / 1_000_000} MB ${d.hashCode()} ${p.hashCode()}")
    }
}

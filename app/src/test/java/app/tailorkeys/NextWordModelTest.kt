package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.exp

class NextWordModelTest {
    private fun asset(): File = listOf(File("src/main/assets/lm_en.bin"), File("app/src/main/assets/lm_en.bin"))
        .first { it.exists() }

    private fun load(): NextWordModel = asset().inputStream().use { NextWordModel.load(it) }

    @Test fun pytorchReferenceVectors() {
        val model = load()
        val reference = javaClass.getResourceAsStream("/lm_reference.txt")!!.bufferedReader().use { it.readLines() }
        val sha = MessageDigest.getInstance("SHA-256").digest(asset().readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(reference.contains("# sha256=$sha"))
        val out = FloatArray(model.vocabularySize)
        var checked = 0
        for (line in reference.filter { it.isNotBlank() && !it.startsWith("#") }) {
            val parts = line.split('\t')
            val input = parts[0].split(' ').map { it.toInt() }
            val expected = parts[1].split(' ').map { it.toInt() }
            val probabilities = parts[2].split(' ').map { it.toFloat() }
            val state = model.initialState()
            for (id in input) assertSame(state, model.step(state, id))
            model.logProbs(state, out)
            val actual = out.indices.sortedWith(compareByDescending<Int> { out[it] }.thenBy { it }).take(10)
            assertEquals("Top 10 for $input", expected, actual)
            for (i in expected.indices) assertEquals("Log probability for ${expected[i]}", probabilities[i], out[expected[i]], 0.15f)
            assertEquals(1.0, out.sumOf { exp(it.toDouble()) }, 0.00001)
            val candidates = expected.toIntArray()
            val subset = FloatArray(candidates.size)
            model.scoreCandidates(state, candidates, subset)
            val normalizer = subset[0] - out[candidates[0]]
            for (i in candidates.indices) assertEquals(out[candidates[i]], subset[i] - normalizer, 0.00001f)
            state.reset()
            for (id in input) model.step(state, id)
            val repeated = FloatArray(out.size)
            model.logProbs(state, repeated)
            assertArrayEquals(out, repeated, 0f)
            checked++
        }
        assertTrue("At least three independent reference sequences", checked >= 3)
        assertEquals(2, model.idOf("zzzznotawordzzzz"))
        assertEquals(model.idOf("don't"), model.idOf("DON\u2019T"))
        assertEquals(0, model.idOf("<s>"))
    }

    @Test fun malformedFilesFailCleanly() {
        val bytes = asset().readBytes()
        fun rejected(b: ByteArray) {
            try {
                NextWordModel.load(ByteArrayInputStream(b))
                fail("Malformed model accepted")
            } catch (_: IOException) { /* Expected: format errors, not unchecked array failures. */ }
        }
        rejected(bytes.copyOf(40))
        rejected(bytes.copyOf(bytes.size - 1))
        rejected(bytes + byteArrayOf(0))
        rejected(bytes.copyOf().apply { this[0] = 0 })
        rejected(bytes.copyOf().apply { this[8] = 2 })
        rejected(bytes.copyOf().apply { for (i in 12..15) this[i] = 127 })
        val model = load()
        try {
            model.step(model.initialState(), -1)
            fail("Invalid ID accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun jvmTiming() {
        val model = load()
        val assetSha = MessageDigest.getInstance("SHA-256").digest(asset().readBytes()).joinToString("") { "%02x".format(it) }
        val state = model.initialState()
        val out = FloatArray(model.vocabularySize)
        val words = listOf("<s>", "i", "would", "like", "to", "have", "a", "cup", "of", "tea", "</s>")
            .map { model.idOf(it) }.toIntArray()
        // Real dot products and full softmax; buffers, word IDs and model already allocated.
        repeat(300) { model.step(state, words[it % words.size]); model.logProbs(state, out) }
        var allocatedPerStep = -1.0
        // Android's compilation stubs omit java.management. Reflect only in this
        // host-JVM test; no management classes or dependencies enter the app.
        try {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val api = Class.forName("com.sun.management.ThreadMXBean")
            if (api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true) {
                api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
                val thread = Thread.currentThread().id
                val getter = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                fun allocated(): Long = getter.invoke(bean, thread) as Long
                repeat(20) { allocated() }
                val overheadBefore = allocated()
                val overhead = allocated() - overheadBefore
                val before = allocated()
                repeat(100) { model.step(state, words[it % words.size]); model.logProbs(state, out) }
                allocatedPerStep = maxOf(0.0, (allocated() - before - overhead) / 100.0)
                assertTrue("No per-invocation heap allocations after warmup: $allocatedPerStep bytes", allocatedPerStep <= 1.0)
            }
        } catch (_: ReflectiveOperationException) { /* Optional on non-HotSpot hosts. */ }
        val stepTimes = DoubleArray(9)
        val totalTimes = DoubleArray(9)
        var checksum = 0f
        for (trial in stepTimes.indices) {
            state.reset()
            var started = System.nanoTime()
            repeat(100) { model.step(state, words[it % words.size]) }
            stepTimes[trial] = (System.nanoTime() - started) / 100_000_000.0
            started = System.nanoTime()
            repeat(100) {
                model.step(state, words[it % words.size])
                model.logProbs(state, out)
                checksum += out[0]
            }
            totalTimes[trial] = (System.nanoTime() - started) / 100_000_000.0
        }
        stepTimes.sort()
        totalTimes.sort()
        assertTrue(checksum.isFinite())
        val result = "{\"step_median_ms\":${stepTimes[4]},\"step_softmax_median_ms\":${totalTimes[4]}," +
            "\"asset_sha256\":\"$assetSha\"," +
            "\"allocated_bytes_per_step_softmax\":$allocatedPerStep," +
            "\"step_softmax_min_ms\":${totalTimes.first()},\"step_softmax_max_ms\":${totalTimes.last()}," +
            "\"java\":\"${System.getProperty("java.version")}\",\"arch\":\"${System.getProperty("os.arch")}\"}"
        File("build/reports/lm-benchmark.json").apply { parentFile.mkdirs(); writeText(result + "\n") }
        println("TKLM JVM TIMING $result")
        // Timing is reported, not asserted: a shared CI host is not a tablet benchmark.
    }
}

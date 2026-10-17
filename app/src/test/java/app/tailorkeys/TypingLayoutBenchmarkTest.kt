package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime

/**
 * TK_PERF=1; median of seven batches, identical workloads before/after optimization.
 * Baseline c600a17 (JDK 23, 256 MB test heap): keystroke 3.592183 ms, resize 0.155319 ms,
 * cached tilt 0.003616 ms. Loading, editor IPC and Android rendering are excluded.
 */
class TypingLayoutBenchmarkTest {
    @Test fun frameMathAllocatesNothingAfterWarmup() {
        assumeTrue(System.getenv("TK_PERF") == "1")
        // Android's compile classpath omits java.management; reflection is confined to this JVM test.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        assumeTrue(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val readBytes = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val joystick = CursorJoystick()
        val cursor = CursorRead()
        val editorText = "a".repeat(1024)
        var checksum = 0
        var time = 0L
        fun frames() { repeat(20_000) {
            time += 16
            checksum += joystick.displace(15f, time)
            checksum += joystick.advance(time + 8)
            checksum += slideOffset(150f, time % 300, false).toBits()
            cursor.set(editorText, 0, 500, 500)
            checksum += cursor.target(-1, false)
        } }
        repeat(5) { frames() }
        val id = Thread.currentThread().id
        fun bytes() = readBytes.invoke(bean, id) as Long
        repeat(100) { bytes() }
        val samples = LongArray(7)
        for (i in samples.indices) {
            val probeStart = bytes()
            val probeCost = bytes() - probeStart
            val before = bytes()
            frames()
            samples[i] = bytes() - before - probeCost
        }
        // Ignore one-time JVM/JIT initialization; persistent per-frame allocations affect every batch.
        samples.sort()
        val allocated = samples[3]
        println("TK_PERF frame_math_allocated_bytes=$allocated frames=20000 samples=${samples.toList()} checksum=$checksum")
        assertEquals(0L, allocated)
    }

    @Test fun typingAndLayout() {
        assumeTrue(System.getenv("TK_PERF") == "1")
        val dir = Files.createTempDirectory("tk-perf").toFile()
        try {
            val grid = KeyGrid.default()
            val p = Predictor({ File("src/main/assets", it).inputStream() }, dir, enableModel = false)
            p.setLayout(grid)
            val prefixes = listOf("t", "th", "the", "sev", "seven", "becau", "people", "keyboard", "awvwn", "aeven", "zzzzz")
            val taps = prefixes.map { SplitTaps.word(it, slips = true) }
            var checksum = 0
            fun typing() { repeat(12) { r -> for (i in prefixes.indices) {
                val word = prefixes[i]
                val prev = if (r % 2 == 0) "six" else "the"
                checksum += p.nextLetters(word, prev, "five", taps[i]).hashCode()
                checksum += p.explore(word, prev, "five", taps = taps[i]).hashCode()
                checksum += p.closest(word, prev, "five", taps = taps[i]).hashCode()
                checksum += p.isDeadEnd(word).hashCode()
            } } }
            val solver = LayoutSolver()
            fun layout(resize: Boolean) { repeat(80) { i ->
                val target = if (resize) 340f + i % 20 else 360f
                val s = solver.compute(1.5f, 4, 7.5f, 60f, 21f, 6f, 0f, target,
                    270f, 630f, true, false, (i % 21 - 10).toFloat(), 0f, .7f, grid)
                checksum += s.total.toBits()
            } }
            repeat(4) { typing(); layout(true); layout(false) }
            for (mode in 0..2) {
                val values = (0..6).map { measureNanoTime {
                    when (mode) { 0 -> typing(); 1 -> layout(true); else -> layout(false) }
                } / (if (mode == 0) 132.0 else 80.0) / 1e6 }.sorted()
                println("TK_PERF mode=${listOf("keystroke", "resize", "tilt")[mode]} median_ms=${values[3]} checksum=$checksum")
            }
        } finally { dir.deleteRecursively() }
    }
}

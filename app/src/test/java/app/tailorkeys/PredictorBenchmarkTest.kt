package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime

/**
 * Opt in with SPATIAL_BENCHMARK=1. Alternating context defeats the candidate cache.
 * Median of seven 720-scan batches, after 1,800 warmup scans per mode. Asset/model loading
 * and tap preparation are excluded. The pre-change comparison used this same workload
 * with b740f25's predictor plus only the stream-loading constructor refactor.
 */
class PredictorBenchmarkTest {
    @Test fun benchmark() {
        assumeTrue(System.getenv("SPATIAL_BENCHMARK") == "1")
        val assets = File("src/main/assets")
        val dir = Files.createTempDirectory("predictor-benchmark").toFile()
        try {
            val predictor = Predictor({ File(assets, it).inputStream() }, dir, enableModel = false)
            predictor.setLayout(KeyGrid.template(Template.SPLIT))
            val prefixes = listOf("th", "the", "sev", "seven", "becau", "because", "people", "keyboard", "awvwn", "aivwn", "aeven", "zzzzz")
            val taps = prefixes.map { SplitTaps.word(it, slips = true) }
            var checksum = 0
            fun batch(rounds: Int, mode: Int) {
                repeat(rounds) { r -> for ((i, p) in prefixes.withIndex()) {
                    val prev = if (r % 2 == 0) "six" else "the"
                    checksum += when (mode) {
                        0 -> predictor.closest(p, prev, "five")
                        else -> predictor.closest(p, prev, "five", taps = taps[i])
                    }.hashCode()
                } }
            }
            for (mode in 0..1) batch(150, mode)
            val samples = Array(2) { ArrayList<Double>() }
            repeat(7) { for (mode in 0..1) samples[mode].add(measureNanoTime { batch(60, mode) } / (60.0 * prefixes.size * 1e6)) }
            for (mode in 0..1) {
                val label = if (mode == 0) "fallback" else "spatial"
                println("BENCHMARK mode=$label words=${predictor.gestureWords().size} median_ms=${samples[mode].sorted()[3]} samples=${samples[mode]} checksum=$checksum")
            }
        } finally { dir.deleteRecursively() }
    }
}

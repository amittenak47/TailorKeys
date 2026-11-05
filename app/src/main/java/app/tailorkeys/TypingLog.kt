package app.tailorkeys

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Real typing for tools/lm (Settings, Help and about, "Log autocorrect decisions"; off by default, as it keeps what you
 * type): every key with where it was tapped and the model's features for it, and every model fix as it is written,
 * one JSON object per line in files/typing-log/<field start>.jsonl, a file per field. Kept on this device only; read
 * with adb (run-as on a debug build). Only where the decision log may run (never in passwords or incognito fields).
 *   key   the key's code, the tap's x and y, the model's features for it when it typed a letter
 *   fix   a fix written: from the space bar or a recheck, the word as shown, what it became, ms since the word's space
 *   text  the text before the cursor when a field ends (the last 2,000 characters), to line the keys up against
 */
internal object TypingLog {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "typing-log").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    /** A field starts: a new file when logging, none otherwise. */
    fun start(dir: File, on: Boolean) {
        if (!on) { file = null; return }
        val folder = File(dir, "typing-log").apply { mkdirs() }
        file = File(folder, "${System.currentTimeMillis()}.jsonl")
    }

    fun key(code: String, x: Float, y: Float, features: FloatArray?) = add(JSONObject().put("e", "key").put("code", code).apply {
        if (!x.isNaN() && !y.isNaN()) put("x", round(x)).put("y", round(y))
        if (features != null) put("f", JSONArray(features.map { round(it) }))
    })

    fun fix(source: String, shown: String, fix: String, delayMs: Long) =
        add(JSONObject().put("e", "fix").put("source", source).put("shown", shown).put("fix", fix).put("ms", delayMs))

    fun text(before: String) = add(JSONObject().put("e", "text").put("before", before.takeLast(2000)))

    private fun round(v: Float) = Math.round(v * 1000) / 1000.0

    private fun add(event: JSONObject) {
        val f = file ?: return
        val line = event.put("t", SystemClock.uptimeMillis()).toString() + "\n"
        writer.execute {
            // A field's file stops growing past MAX_BYTES (a runaway session can't fill the device).
            try { if (f.length() < MAX_BYTES) f.appendText(line) } catch (_: Exception) {}
        }
    }

    private const val MAX_BYTES = 5_000_000L
}

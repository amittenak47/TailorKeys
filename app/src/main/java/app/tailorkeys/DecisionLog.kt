package app.tailorkeys

import android.util.Log
import org.json.JSONObject

/**
 * For testing autocorrect (Settings, Help and about, "Log autocorrect decisions"): what each decision saw, written to
 * the Android log so a typing session can be replayed through tools/lm. Off by default, as it logs what you type.
 *   TKGEOM  each letter's centre tap on this layout (KeyboardView.centreTap), once per keyboard process
 *   TKMODEL the word, the fix, and the time queued and deciding (reused: scored after its last tap already)
 *   TKDUMP  the context, the taps and the top five candidates, as JSON
 */
internal object DecisionLog {
    private var geometryLogged = false

    fun geometry(view: KeyboardView) {
        if (geometryLogged) return
        geometryLogged = true
        // One line per letter: Android cuts a log line at about 4,000 characters.
        for (c in TapSearch.LETTERS) Log.i("TKGEOM", "$c " + (view.centreTap(c)?.joinToString(",", "[", "]") { "%.2f".format(it) } ?: "null"))
    }

    fun decision(corrector: StudentCorrector, left: String, right: String, word: String, taps: List<FloatArray>, first: Boolean,
                 fix: String?, queuedMs: Long, decidedMs: Long, reused: Boolean) {
        Log.i("TKMODEL", "space $word -> $fix: queued $queuedMs ms, decided $decidedMs ms, reused $reused")
        try {
            val live = corrector.live(left, right, word, taps, first)
            val top = live?.scored?.let { s -> s.candidates.indices.sortedByDescending { s.probability[it] }.take(5)
                .joinToString(",") { "${JSONObject.quote(s.candidates[it])}:${"%.3f".format(s.probability[it])}" } }
            val tapsJson = taps.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { "%.3f".format(it) } }
            Log.i("TKDUMP", "{\"left\":${JSONObject.quote(left.takeLast(200))},\"right\":${JSONObject.quote(right.take(100))}," +
                "\"typed\":${JSONObject.quote(word)},\"fix\":${JSONObject.quote(fix ?: "")},\"top\":{$top},\"taps\":$tapsJson}")
        } catch (e: Throwable) {}
    }
}

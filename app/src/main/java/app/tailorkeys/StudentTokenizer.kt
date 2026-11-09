package app.tailorkeys

import org.json.JSONObject
import java.io.InputStream
import java.util.regex.Pattern

/**
 * The student model's word pieces: byte-level BPE as the student's tokenizer.json (Hugging Face tokenizers, GPT-2's
 * pre-tokenizer regex, no prefix space). StudentTokenizerTest checks it against the Python tokenizer.
 */
internal class StudentTokenizer(vocab: Map<String, Int>, merges: List<Pair<String, String>>) {
    private val ids = HashMap(vocab)
    private val ranks = HashMap<Pair<String, String>, Int>(merges.size * 2).apply { merges.forEachIndexed { i, m -> put(m, i) } }
    private val cache = object : LinkedHashMap<String, IntArray>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>) = size > 20000
    }

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        val m = SPLIT.matcher(text)
        while (m.find()) {
            val piece = m.group()
            val known = synchronized(cache) { cache[piece] }
            val pieceIds = known ?: bpe(piece).also { synchronized(cache) { cache[piece] = it } }
            for (id in pieceIds) out.add(id)
        }
        return out.toIntArray()
    }

    private fun bpe(piece: String): IntArray {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val parts = ArrayList<String>(bytes.size)
        for (b in bytes) parts.add(BYTE_CHARS[b.toInt() and 0xff].toString())
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks[parts[i] to parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            val a = parts[best]
            val b = parts[best + 1]
            // Every occurrence of the pair, left to right, as the reference implementation merges.
            var i = 0
            while (i < parts.size - 1) {
                if (parts[i] == a && parts[i + 1] == b) { parts[i] = a + b; parts.removeAt(i + 1) }
                i++
            }
        }
        return IntArray(parts.size) { ids[parts[it]] ?: error("no piece for ${parts[it]}") }
    }

    companion object {
        /** Unicode white space, as Rust's regex reads \s (Android's regex has no flag for it). */
        private const val WS = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
        /** GPT-2's split. */
        private val SPLIT = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^$WS\\p{L}\\p{N}]+|[$WS]+(?![^$WS])|[$WS]+")

        /** GPT-2's bytes_to_unicode: printable bytes stand for themselves, the rest move past 255. */
        private val BYTE_CHARS: CharArray = CharArray(256).also { table ->
            val printable = (33..126) + (161..172) + (174..255)
            var next = 256
            for (b in 0 until 256) table[b] = if (b in printable) b.toChar() else (next++).toChar()
        }

        fun load(openAsset: (String) -> InputStream, name: String = "model/tokenizer.json"): StudentTokenizer {
            val json = JSONObject(openAsset(name).bufferedReader().use { it.readText() })
            val model = json.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>()
            for (key in vocabJson.keys()) vocab[key] = vocabJson.getInt(key)
            val mergesJson = model.getJSONArray("merges")
            val merges = (0 until mergesJson.length()).map { i ->
                val m = mergesJson.get(i)
                if (m is org.json.JSONArray) m.getString(0) to m.getString(1)
                else (m as String).split(' ', limit = 2).let { it[0] to it[1] }
            }
            return StudentTokenizer(vocab, merges)
        }
    }
}

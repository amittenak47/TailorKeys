package app.tailorkeys

import java.io.InputStream

/** Shared parsing/rank order; chord dialogs need no personal files, n-grams or inference model. */
internal object DictionaryWords {
    fun read(openAsset: (String) -> InputStream, language: String, limit: Int = Int.MAX_VALUE, unique: Boolean = false): List<String> {
        val suffix = if (language == "en") "" else "_$language"
        return openAsset("words$suffix.txt").bufferedReader().useLines { lines ->
            val words = lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            (if (unique) words.distinct() else words).take(limit).toList()
        }
    }

    /**
     * One of the extended English lists (words_extra.txt, words_known.txt) in list order, each word with where its
     * frequency places it among words.txt when the line gives one ("meta\t10013"); empty when not bundled.
     */
    fun readRanked(openAsset: (String) -> InputStream, name: String): List<Pair<String, Int?>> = try {
        openAsset(name).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) line.lowercase() to null else line.substring(0, tab).lowercase() to line.substring(tab + 1).toIntOrNull()
            }.toList()
        }
    } catch (_: java.io.IOException) { emptyList() }
}

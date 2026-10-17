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
}

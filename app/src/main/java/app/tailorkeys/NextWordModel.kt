package app.tailorkeys

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Standalone CIFG language model. Load once; keep one mutable State per text stream.
 * Feed <s> at the start, </s> then <s> between sentences. Tokenization is the caller's
 * responsibility (see tools/lm/README.md). This class does not access keyboard/user data.
 */
class NextWordModel private constructor(
    val vocabulary: List<String>,
    val embeddingSize: Int,
    val hiddenSize: Int,
    private val embeddings: Matrix,
    private val inputWeights: Matrix,
    private val recurrentWeights: Matrix,
    private val projection: Matrix,
    private val bias: FloatArray,
    private val outputBias: FloatArray
) {
    val vocabularySize: Int get() = vocabulary.size
    private val ids = vocabulary.withIndex().associate { it.value to it.index }

    class State internal constructor(internal val owner: NextWordModel) {
        internal var hidden = FloatArray(owner.hiddenSize)
        internal var spare = FloatArray(owner.hiddenSize)
        internal val cell = FloatArray(owner.hiddenSize)
        internal val input = FloatArray(owner.embeddingSize)
        internal val projected = FloatArray(owner.embeddingSize)

        /** Reuse storage when the cursor/context changes. Feed <s> after reset. */
        fun reset() {
            hidden.fill(0f)
            spare.fill(0f)
            cell.fill(0f)
            input.fill(0f)
            projected.fill(0f)
        }
    }

    fun idOf(word: String): Int = ids[word.lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'')] ?: 2
    internal fun normalizedIdOf(word: String): Int = ids[word] ?: 2
    fun initialState(): State = State(this)

    /** Updates and returns state. No allocations; states must not be used concurrently. */
    fun step(state: State, wordId: Int): State {
        require(state.owner === this) { "State belongs to another model" }
        require(wordId in 0 until vocabularySize) { "Invalid word ID" }
        embeddings.row(wordId, state.input)
        val h = state.hidden
        val next = state.spare
        for (j in 0 until hiddenSize) {
            val f = sigmoid(inputWeights.dot(j, state.input) + recurrentWeights.dot(j, h) + bias[j])
            val gRow = hiddenSize + j
            val g = tanh(inputWeights.dot(gRow, state.input) + recurrentWeights.dot(gRow, h) + bias[gRow])
            val oRow = 2 * hiddenSize + j
            val o = sigmoid(inputWeights.dot(oRow, state.input) + recurrentWeights.dot(oRow, h) + bias[oRow])
            val c = f * state.cell[j] + (1f - f) * g
            state.cell[j] = c
            next[j] = o * tanh(c)
        }
        state.hidden = next
        state.spare = h
        for (j in 0 until embeddingSize) state.projected[j] = projection.dot(j, next)
        return state
    }

    /** Full-vocabulary natural-log probabilities; caller supplies reusable output storage. */
    fun logProbs(state: State, out: FloatArray) {
        require(state.owner === this && out.size >= vocabularySize)
        var maximum = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabularySize) {
            val score = embeddings.dot(i, state.projected) + outputBias[i]
            out[i] = score
            if (score > maximum) maximum = score
        }
        var sum = 0.0
        for (i in 0 until vocabularySize) sum += exp((out[i] - maximum).toDouble())
        val normalizer = maximum + ln(sum).toFloat()
        for (i in 0 until vocabularySize) out[i] -= normalizer
    }

    /** Only computes these candidates' unnormalized logits (same ordering as logProbs).
     * Normalizing over a subset would not yield full-vocabulary log probabilities.
     */
    fun scoreCandidates(state: State, candidateIds: IntArray, out: FloatArray) {
        require(state.owner === this && out.size >= candidateIds.size)
        for (i in candidateIds.indices) {
            val id = candidateIds[i]
            require(id in 0 until vocabularySize) { "Invalid candidate ID" }
            out[i] = embeddings.dot(id, state.projected) + outputBias[id]
        }
    }

    private class Matrix(val rows: Int, val columns: Int, val scales: FloatArray, val bytes: ByteArray) {
        fun dot(row: Int, x: FloatArray): Float {
            var sum = 0f
            val base = row * columns
            for (j in 0 until columns) sum += bytes[base + j].toInt() * x[j]
            return sum * scales[row]
        }

        fun row(row: Int, out: FloatArray) {
            val scale = scales[row]
            val base = row * columns
            for (j in 0 until columns) out[j] = bytes[base + j].toInt() * scale
        }
    }

    companion object {
        private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

        /** Reads TKLM v1; validates dimensions before allocating. Does not close input. */
        @JvmStatic
        @Throws(IOException::class)
        fun load(input: InputStream): NextWordModel {
            val f = DataInputStream(BufferedInputStream(input))
            val magic = ByteArray(8)
            f.readFully(magic)
            if (!magic.contentEquals(byteArrayOf(84, 75, 76, 77, 69, 78, 49, 0))) throw IOException("Invalid TKLM magic")
            fun int(): Int = Integer.reverseBytes(f.readInt())
            fun float(): Float = Float.fromBits(int())
            if (int() != 1) throw IOException("Unsupported TKLM version")
            val v = int()
            val d = int()
            val h = int()
            if (v !in 5..32000 || d !in 1..256 || h !in 1..1024 ||
                v.toLong() * d + 3L * h * (d + h) + d.toLong() * h > 5_000_000L) throw IOException("Invalid TKLM dimensions")
            val words = ArrayList<String>(v)
            val unique = HashSet<String>(v)
            var vocabularyBytes = 0
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            repeat(v) {
                val length = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(f.readShort()))
                vocabularyBytes += length + 2
                if (length !in 1..256 || vocabularyBytes > 1_000_000) throw IOException("Invalid TKLM vocabulary length")
                val bytes = ByteArray(length)
                f.readFully(bytes)
                val word = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                if (!unique.add(word)) throw IOException("Duplicate TKLM word")
                words.add(word)
            }
            if (words.take(5) != listOf("<s>", "</s>", "<unk>", "<num>", "<,>")) throw IOException("Invalid TKLM special IDs")
            fun floats(n: Int, scale: Boolean = false): FloatArray = FloatArray(n) {
                float().also { if (!it.isFinite() || scale && it <= 0f) throw IOException("Invalid TKLM float") }
            }
            fun matrix(rows: Int, columns: Int): Matrix {
                val scales = floats(rows, true)
                val bytes = ByteArray(rows * columns)
                f.readFully(bytes)
                return Matrix(rows, columns, scales, bytes)
            }
            val embeddings = matrix(v, d)
            val wx = matrix(3 * h, d)
            val wh = matrix(3 * h, h)
            val projection = matrix(d, h)
            val bias = floats(3 * h)
            val outputBias = floats(v)
            if (f.read() != -1) throw IOException("Trailing TKLM bytes")
            return NextWordModel(words.toList(), d, h, embeddings, wx, wh, projection, bias, outputBias)
        }
    }
}

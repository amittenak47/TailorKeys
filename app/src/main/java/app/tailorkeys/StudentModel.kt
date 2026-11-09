package app.tailorkeys

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * The student model (tools/lm/student.py, exported by tools/lm/export.py as one int8 ONNX graph, step), run with ONNX
 * Runtime on the CPU. The graph takes rows of new tokens on top of a saved reading (the keys and values of every
 * token read so far): one row while reading (context pieces, then each tap as it lands), one row per candidate when
 * scoring. Not thread-safe: one caller at a time, off the UI thread.
 */
internal class StudentModel(path: String, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(path, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    /**
     * Graphs from 2026-11-02 on also take each tap's source (tsrc) and the casual setting (style). Both go in at their
     * defaults, a letter tap and "fix", as nothing in the keyboard sets them yet; older graphs don't have them.
     */
    private val newer = "style" in session.inputNames

    /** A saved reading: per layer and head, each token's key and value, laid out [layers, 1, heads, tokens, dim]. */
    class Reading(val tokens: Int, val keys: FloatArray, val values: FloatArray) {
        /**
         * The reading of its first [n] tokens: their keys and values, as each token sees only those before it. Exact,
         * but for int8 rounding, which depends on how the tokens were grouped into calls.
         */
        fun prefix(n: Int): Reading = when {
            n >= tokens -> this
            n <= 0 -> EMPTY
            else -> Reading(n, cut(keys, n), cut(values, n))
        }

        private fun cut(a: FloatArray, n: Int) = FloatArray(LAYERS * HEADS * n * DIM).also { out ->
            for (block in 0 until LAYERS * HEADS) System.arraycopy(a, block * tokens * DIM, out, block * n * DIM, n * DIM)
        }

        companion object { val EMPTY = Reading(0, FloatArray(0), FloatArray(0)) }
    }

    /** One row of new tokens. Candidate rows fill the alignment; reading rows fill the taps. */
    class Row(val ids: IntArray, val seg: IntArray, val tapx: Array<FloatArray>? = null, val tapi: IntArray? = null,
              val alk: IntArray? = null, val ali: IntArray? = null, val alx: Array<FloatArray>? = null)

    class Output(val reading: Reading?, val wheel: FloatArray?, val score: FloatArray, val capital: FloatArray)

    /**
     * Rows of new tokens at positions after [past]. With one row, the reading grown by it and the wheel's word scores
     * after its last token; with several (candidates), or [scoring] one, their score and capital logits. [options] lets
     * another thread stop the run (setTerminate), which then throws.
     */
    fun run(past: Reading, rows: List<Row>, options: OrtSession.RunOptions? = null, scoring: Boolean = rows.size != 1): Output {
        val n = rows.size
        val t = rows.maxOf { it.ids.size }
        val longs = { f: (Row, Int) -> Long -> LongArray(n * t).also { a -> for (r in 0 until n) for (j in rows[r].ids.indices) a[r * t + j] = f(rows[r], j) } }
        val ids = longs { r, j -> r.ids[j].toLong() }
        val seg = longs { r, j -> r.seg[j].toLong() }
        val pos = LongArray(n * t) { past.tokens + (it % t).toLong() }
        val tapi = longs { r, j -> (r.tapi?.get(j) ?: 0).toLong() }
        val alk = longs { r, j -> (r.alk?.get(j) ?: 0).toLong() }
        val ali = longs { r, j -> (r.ali?.get(j) ?: 0).toLong() }
        val tapx = FloatArray(n * t * TAP_INPUTS)
        val alx = FloatArray(n * t * 2)
        for (r in 0 until n) for (j in rows[r].ids.indices) {
            rows[r].tapx?.get(j)?.copyInto(tapx, (r * t + j) * TAP_INPUTS)
            rows[r].alx?.get(j)?.copyInto(alx, (r * t + j) * 2)
        }
        val last = LongArray(n) { (rows[it].ids.size - 1).toLong() }
        val cache = longArrayOf(LAYERS.toLong(), 1, HEADS.toLong(), past.tokens.toLong(), DIM.toLong())
        val shape = longArrayOf(n.toLong(), t.toLong())
        val inputs = linkedMapOf(
            "ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape),
            "seg" to OnnxTensor.createTensor(env, LongBuffer.wrap(seg), shape),
            "pos" to OnnxTensor.createTensor(env, LongBuffer.wrap(pos), shape),
            "tapx" to OnnxTensor.createTensor(env, FloatBuffer.wrap(tapx), longArrayOf(n.toLong(), t.toLong(), TAP_INPUTS.toLong())),
            "tapi" to OnnxTensor.createTensor(env, LongBuffer.wrap(tapi), shape),
            "alk" to OnnxTensor.createTensor(env, LongBuffer.wrap(alk), shape),
            "ali" to OnnxTensor.createTensor(env, LongBuffer.wrap(ali), shape),
            "alx" to OnnxTensor.createTensor(env, FloatBuffer.wrap(alx), longArrayOf(n.toLong(), t.toLong(), 2)),
            "last" to OnnxTensor.createTensor(env, LongBuffer.wrap(last), longArrayOf(n.toLong())),
            "past_k" to OnnxTensor.createTensor(env, FloatBuffer.wrap(past.keys), cache),
            "past_v" to OnnxTensor.createTensor(env, FloatBuffer.wrap(past.values), cache),
        )
        if (newer) {
            inputs["tsrc"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n * t)), shape)
            inputs["style"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), longArrayOf(n.toLong()))
        }
        try {
            // Scoring candidates needs only their scores and capitals: the keys, values and wheel aren't asked for.
            val wanted = if (scoring) SCORING else session.outputNames
            (if (options != null) session.run(inputs, wanted, options) else session.run(inputs, wanted)).use { out ->
                fun floats(name: String) = (out.get(name).get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                val score = floats("score")
                val capital = floats("capital")
                if (scoring) return Output(null, null, score, capital)
                val k = floats("k")
                val v = floats("v")
                return Output(Reading(past.tokens + t, grow(past.keys, past.tokens, k, t), grow(past.values, past.tokens, v, t)),
                    floats("wheel"), score, capital)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** The reading's [layers, 1, heads, old, dim] with [layers, 1, heads, new, dim] appended along tokens. */
    private fun grow(old: FloatArray, oldTokens: Int, new: FloatArray, newTokens: Int): FloatArray {
        val total = oldTokens + newTokens
        val out = FloatArray(LAYERS * HEADS * total * DIM)
        for (block in 0 until LAYERS * HEADS) {
            System.arraycopy(old, block * oldTokens * DIM, out, block * total * DIM, oldTokens * DIM)
            System.arraycopy(new, block * newTokens * DIM, out, (block * total + oldTokens) * DIM, newTokens * DIM)
        }
        return out
    }

    override fun close() = session.close()

    companion object {
        const val LAYERS = 11
        const val HEADS = 9
        const val DIM = 64
        const val TAP_INPUTS = 56
        private val SCORING = setOf("score", "capital")

        /** Probabilities from scores, as a softmax. */
        fun softmax(score: FloatArray): FloatArray {
            val top = score.maxOrNull() ?: return score
            val e = FloatArray(score.size) { exp((score[it] - top).toDouble()).toFloat() }
            val sum = e.sum()
            for (i in e.indices) e[i] /= sum
            return e
        }

        /** Per tap: how close each key is (near 1 on it) and the clipped distance itself (teacher.tap_inputs). */
        fun tapInputs(features: FloatArray): FloatArray = FloatArray(TAP_INPUTS) { i ->
            val d = features[i % TapSearch.FEATURES]
            if (i < TapSearch.FEATURES) exp(-d / .5f) else min(d, 9f) / 9f
        }
    }
}

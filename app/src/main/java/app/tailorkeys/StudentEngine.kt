package app.tailorkeys

import android.content.Context
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The autocorrect model for the keyboard (Settings, Model autocorrect): loaded once per process, off the UI thread.
 * One worker runs every load and decision in order, so a decision never overlaps another or a reload.
 */
internal object StudentEngine {
    val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "TailorKeys-Student").apply { isDaemon = true }
    }

    /** Something waits for the worker: work that can wait (rechecks) lets it go first. */
    fun busy() = worker.queue.isNotEmpty()
    private const val ASSET = "model/step.int8.onnx"

    /** Ready once loaded; read and used on the worker. */
    @Volatile var corrector: StudentCorrector? = null
        private set
    /** The last load failed (no model file in this build, or it would not open): the keyboard corrects without it. */
    @Volatile var failed = false
        private set
    private var model: StudentModel? = null
    private var slot: StudentSlot? = null
    private var searchOffensive: Boolean? = null
    private var search: TapSearch? = null
    private var heads: List<String>? = null

    /** Loads the model (on the worker) with offensive words searched or not, then calls [done] there. */
    fun load(context: Context, offensive: Boolean, done: () -> Unit = {}) = worker.execute {
        try {
            val app = context.applicationContext
            val open = { name: String -> app.assets.open(name) }
            if (model == null) model = StudentModel(copied(app).path)
            if (slot == null) slot = StudentSlot(StudentTokenizer.load(open))
            if (search == null || searchOffensive != offensive) {
                search = TapSearch(TapSearch.load(open, offensive), TapSearch.loadMisspellings(open), TapSearch.loadCasual(open))
                searchOffensive = offensive
            }
            if (heads == null) heads = open("model/head_words.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val layout = corrector?.layout
            corrector = StudentCorrector(model!!, search!!, slot!!, heads!!).also { it.layout = layout }
            failed = false
        } catch (e: Throwable) {
            android.util.Log.w("TailorKeys", "autocorrect model not loaded", e)
            corrector = null
            failed = true
        }
        done()
    }

    /** Frees the model's memory when the setting is turned off. */
    fun release() = worker.execute {
        corrector = null
        model?.close(); model = null
        search = null; searchOffensive = null
    }

    /**
     * The model file out of the APK (stored uncompressed), copied again after each install or update of the app: a
     * retrained model of the same shape has the same size, so the size alone would keep the old one.
     */
    private fun copied(context: Context): File {
        val out = File(context.noBackupFilesDir, ASSET)
        val stamp = File(out.path + ".installed")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val size = context.assets.openFd(ASSET).use { it.length }
        if (!out.exists() || out.length() != size || !stamp.exists() || stamp.readText() != installed) {
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(out)) throw java.io.IOException("autocorrect model not moved into place")
            stamp.writeText(installed)
        }
        return out
    }
}

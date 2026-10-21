package app.tailorkeys.insert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** One recognition session at a time. Late callbacks from cancelled sessions cannot commit text. */
class VoiceInput(private val context: Context, private val repository: InsertRepository,
    private val state: (String, String, Float, Boolean) -> Unit, compose: (String) -> Unit, commit: (String) -> Unit, discard: () -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private val draft = VoiceDraft(compose, commit, discard)
    private var mode = "On device"
    val permitted get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val onDevice get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    val deviceOnly get() = repository.prefs.getBoolean(InsertPrefs.DEVICE_ONLY, true)
    fun start(language: String) {
        cancel()
        if (!permitted || !available || !repository.prefs.getBoolean(InsertPrefs.VOICE, true)) return
        val local = onDevice
        if (deviceOnly && !local) {
            state("On-device recognition is unavailable. Change Voice settings or use the system voice keyboard.", "On-device only", 0f, false); return
        }
        val token = generation
        mode = if (local) "On device" else "Online"
        draft.start()
        try {
            val engine = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                private fun live() = token == generation
                override fun onReadyForSpeech(params: Bundle?) { if (live()) state("Listening…", mode, 0f, true) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { if (live()) state("", mode, rmsdB.coerceIn(0f, 10f), true) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (live()) state("", mode, 0f, true) }
                override fun onError(error: Int) { if (live()) { draft.cancel(); state(when (error) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap Start to try again."; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone in TailorKeys"; else -> "Voice recognition stopped ($error). Tap Start to try again." }, mode, 0f, false); release() } }
                override fun onResults(results: Bundle?) {
                    if (!live()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    draft.finish(text.ifBlank { draft.text })
                    state(if (text.isBlank()) "No speech heard." else text, mode, 0f, false); release()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!live()) return
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    draft.partial(text); state(text, mode, 0f, true)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            state("Listening…", mode, 0f, true)
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(language).toLanguageTag()).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        } catch (_: Exception) { cancel(); state("Voice recognition is unavailable. Try the system voice keyboard.", mode, 0f, false) }
    }
    fun stop() {
        val text = draft.text; draft.finish(); release(); state(text, mode, 0f, false)
    }
    private fun release() { generation++; recognizer?.destroy(); recognizer = null }
    fun cancel() { draft.cancel(); generation++; recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
}

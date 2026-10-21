package app.tailorkeys.insert

import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences

/** Observe changes only. Opening the panel never reads the system clipboard. */
class ClipboardCapture(context: Context, private val repository: InsertRepository, private val changed: () -> Unit) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    var password = true
    private val handler = Handler(Looper.getMainLooper())
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!password && repository.prefs.getBoolean(InsertPrefs.HISTORY, false)) {
            val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return@OnPrimaryClipChangedListener
            val flag = if (android.os.Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            val sensitive = clip.description.extras?.getBoolean(flag, false) == true
            val text = if (!sensitive && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            if (text != null) repository.change { it.capture(text, true, sensitive, password) }
            changed()
        }
    }
    private val prune = object : Runnable {
        override fun run() { repository.change { }; changed(); handler.postDelayed(this, 60_000) }
    }
    private val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == InsertPrefs.KEEP) { repository.change { }; changed() }
    }
    init { clipboard.addPrimaryClipChangedListener(listener); repository.prefs.registerOnSharedPreferenceChangeListener(preferences); handler.post(prune) }
    fun close() { clipboard.removePrimaryClipChangedListener(listener); repository.prefs.unregisterOnSharedPreferenceChangeListener(preferences); handler.removeCallbacksAndMessages(null) }
}

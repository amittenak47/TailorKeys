package app.tailorkeys

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tailorkeys.settings.SettingsScreen
import app.tailorkeys.settings.SettingsTheme
import app.tailorkeys.settings.SetupStatus

class SettingsActivity : ComponentActivity() {
    private lateinit var settings: KeyboardSettings
    private var setupStatus by mutableStateOf(SetupStatus.HIDDEN)
    private var wasDefault: Boolean? = null
    private var readyUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideReady = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        LayoutLibrary(settings).restoreTrial()
        if (!settings.bool(Prefs.ONBOARDED)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState?.containsKey("wasDefault") == true) {
            wasDefault = savedInstanceState.getBoolean("wasDefault")
            readyUntil = savedInstanceState.getLong("readyUntil")
        }
        enableEdgeToEdge()
        refreshStatus()
        setContent {
            SettingsTheme {
                SettingsScreen(settings, setupStatus, ::openSetup)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::settings.isInitialized) refreshStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        wasDefault?.let { outState.putBoolean("wasDefault", it) }
        outState.putLong("readyUntil", readyUntil)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideReady)
        super.onDestroy()
    }

    override fun onStop() {
        if (::settings.isInitialized) LayoutLibrary(settings).restoreTrial()
        super.onStop()
    }

    private fun refreshStatus() {
        val enabled = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val now = SystemClock.elapsedRealtime()
        if (selected && wasDefault == false) readyUntil = now + 2_000L
        if (!selected) readyUntil = 0L
        wasDefault = selected
        setupStatus = when {
            !enabled -> SetupStatus.TURN_ON
            !selected -> SetupStatus.MAKE_DEFAULT
            now < readyUntil -> SetupStatus.READY
            else -> SetupStatus.HIDDEN
        }
        handler.removeCallbacks(hideReady)
        if (setupStatus == SetupStatus.READY) handler.postDelayed(hideReady, readyUntil - now)
    }

    private fun openSetup() {
        when (setupStatus) {
            SetupStatus.TURN_ON -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            SetupStatus.MAKE_DEFAULT -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            else -> Unit
        }
    }
}

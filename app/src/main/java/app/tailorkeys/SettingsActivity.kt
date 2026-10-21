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
import androidx.activity.result.contract.ActivityResultContracts
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
    private var panelRequest by mutableStateOf<Pair<Long, String>?>(null)
    private fun routePanel(intent: Intent?) {
        val page = intent?.getStringExtra("insert_settings_page") ?: return
        if (page in listOf("Emoji", "Clipboard and notes", "Voice typing")) panelRequest = SystemClock.elapsedRealtimeNanos() to page
        intent.removeExtra("insert_settings_page")
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun requestMicrophone(intent: Intent?) {
        if (intent?.getBooleanExtra("request_microphone", false) == true) {
            intent.removeExtra("request_microphone")
            val permission = android.Manifest.permission.RECORD_AUDIO
            if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
            if (settings.prefs.getBoolean("insert_microphone_requested", false) && !shouldShowRequestPermissionRationale(permission)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            } else {
                settings.prefs.edit().putBoolean("insert_microphone_requested", true).apply()
                microphone.launch(permission)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); routePanel(intent); requestMicrophone(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        routePanel(intent)
        requestMicrophone(intent)
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
                SettingsScreen(settings, setupStatus, ::openSetup, panelRequest)
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

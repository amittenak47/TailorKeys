package app.tailorkeys

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * First-run guide, one slide per idea. Each slide has an illustration, a title, text and sometimes a control.
 * All wording lives in res/values/onboarding.xml. Swipe or use the buttons to move between slides.
 * Settings opens it on first launch and from "Show the guide".
 */
class OnboardingActivity : Activity() {

    private class Page(val title: Int, val body: Int, val art: () -> View, val extra: (() -> View)? = null)

    private lateinit var settings: KeyboardSettings
    private lateinit var holder: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView
    private var index = 0
    /** Re-checks the "turned on" and "selected" lines when returning from system screens. */
    private var refreshStatus: (() -> Unit)? = null

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val pages by lazy {
        listOf(
            Page(R.string.onboarding_1_title, R.string.onboarding_1_body, { logo() }),
            Page(R.string.onboarding_2_title, R.string.onboarding_2_body, { Illustration(this, Illustration.Kind.TOGGLES) }) {
                statusButton(getString(R.string.onboarding_open_keyboard_settings), { enabled() }, getString(R.string.onboarding_enabled_check)) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
            Page(R.string.onboarding_3_title, R.string.onboarding_3_body, { Illustration(this, Illustration.Kind.LIST) }) {
                statusButton(getString(R.string.onboarding_choose_keyboard), { selected() }, getString(R.string.onboarding_selected_check)) {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            },
            Page(R.string.onboarding_4_title, R.string.onboarding_4_body, { keyboardArt(editing = true) }),
            Page(R.string.onboarding_5_title, R.string.onboarding_5_body, { keyboardArt(editing = false) }),
            Page(R.string.onboarding_6_title, R.string.onboarding_6_body, { Illustration(this, Illustration.Kind.FIELD) }) {
                EditText(this).apply {
                    hint = getString(R.string.onboarding_try_hint)
                    minLines = 3
                    gravity = Gravity.TOP
                    textSize = 16f
                    setTextColor(getColor(R.color.ink))
                    setHintTextColor(getColor(R.color.ink2))
                    background = rounded(getColor(R.color.surface), 14f)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        index = savedInstanceState?.getInt(STATE_INDEX, 0)?.coerceIn(0, pages.lastIndex) ?: 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        root.fitsSystemWindows = true
        root.addView(TextView(this).apply {
            text = getString(R.string.onboarding_skip)
            textSize = 16f
            typeface = mediumFont
            setTextColor(getColor(R.color.ink2))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rippled(rounded(0, 18f))
            setOnClickListener { finishGuide() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; rightMargin = -dp(14) })
        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16)) }
        root.addView(dots)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        back = pillButton(getString(R.string.onboarding_back), filled = false) { go(index - 1) }
        next = pillButton(getString(R.string.onboarding_next)) { if (index == pages.lastIndex) finishGuide() else go(index + 1) }
        nav.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        nav.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav)
        setContentView(root)
        show(index, 0f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus?.invoke()
    }

    // The keyboard picker is a dialog, so returning from it doesn't trigger onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus?.invoke()
    }

    // ---- Paging ----

    // Lazy: a GestureDetector needs the activity's resources, which don't exist yet while it's being constructed.
    private val swipes by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) * 1.5f || abs(vx) < 600) return false
            go(if (vx < 0) index + 1 else index - 1)
            return true
        }
    }) }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        swipes.onTouchEvent(e)
        return super.dispatchTouchEvent(e)
    }

    private fun go(to: Int) {
        if (to !in pages.indices || to == index) return
        val dir = if (to > index) -1 else 1
        val w = holder.width.toFloat().coerceAtLeast(1f)
        holder.animate().translationX(dir * w * 0.3f).alpha(0f).setDuration(120).withEndAction {
            show(to, -dir * w * 0.3f)
            holder.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun show(i: Int, startX: Float) {
        index = i
        refreshStatus = null
        holder.removeAllViews()
        holder.translationX = startX
        val p = pages[i]

        // Illustration in a rounded slot.
        val slot = FrameLayout(this).apply {
            background = rounded(getColor(R.color.surface), 24f)
            clipToOutline = true
            addView(p.art(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            this.text = getString(p.title)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
            setPadding(0, 0, 0, dp(12))
        })
        for (line in getString(p.body).split("\n")) text.addView(TextView(this).apply {
            this.text = line
            textSize = 17f
            setLineSpacing(dpf(3f), 1f)
            setTextColor(getColor(R.color.ink2))
            setPadding(0, dp(4), 0, dp(4))
        })
        p.extra?.let { text.addView(it(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }) }

        val col = LinearLayout(this)
        if (landscape) {
            col.orientation = LinearLayout.HORIZONTAL
            col.gravity = Gravity.CENTER_VERTICAL
            col.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(24) })
            col.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            col.orientation = LinearLayout.VERTICAL
            col.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { bottomMargin = dp(28); topMargin = dp(8) })
            col.addView(text)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dots.removeAllViews()
        for (j in pages.indices) {
            val on = j == i
            dots.addView(View(this).apply {
                background = rounded(if (on) getColor(R.color.accent) else getColor(R.color.divider), 4f)
            }, LinearLayout.LayoutParams(dp(if (on) 22 else 8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        back.visibility = if (i == 0) View.INVISIBLE else View.VISIBLE
        next.text = getString(if (i == pages.lastIndex) R.string.onboarding_finish else R.string.onboarding_next)
    }

    private fun finishGuide() {
        settings.set(Prefs.ONBOARDED, true)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    // ---- Illustrations ----

    private fun logo(): View = FrameLayout(this).apply {
        val size = dp(132)
        addView(ImageView(this@OnboardingActivity).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(getColor(R.color.ic_launcher_background), 30f)
        }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        setPadding(0, dp(24), 0, dp(24))
    }

    /** A non-interactive picture of the real keyboard, shrunk to fit the slot. */
    private fun keyboardArt(editing: Boolean): View {
        val view = KeyboardView(this, settings).apply {
            grid = settings.loadGrid()
            setSuggestions(listOf("helpful", "helicopter", "helmet"), listOf("hello", "help", "held"), "hel")
            editMode = editing
        }
        return MiniatureBox(this, view, 0.62f, dp(232))
    }

    // ---- Setup checks ----

    private fun enabled() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    private fun selected() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    /** A button for a setup step, with a line under it that turns into a check mark once the step is done. */
    private fun statusButton(label: String, done: () -> Boolean, doneText: String, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pillButton(label) { onClick() })
        val status = TextView(this).apply { textSize = 15f; typeface = mediumFont; setTextColor(getColor(R.color.accent)); setPadding(dp(4), dp(12), 0, 0) }
        col.addView(status)
        refreshStatus = { status.text = if (done()) doneText else "" }
        refreshStatus?.invoke()
        return col
    }

    private companion object {
        const val STATE_INDEX = "index"
    }
}

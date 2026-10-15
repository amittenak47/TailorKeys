package app.tailorkeys

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * First-run guide, one idea per page: what TailorKeys is, turning it on, switching to it, arranging keys,
 * swipe keys and wheels, and a place to try it. Swipe or use the buttons to move between pages.
 * Settings opens it on first launch and from "Show the guide".
 */
class OnboardingActivity : Activity() {

    private class Page(val title: String, val lines: List<String>, val extra: (() -> View)? = null)

    private lateinit var settings: KeyboardSettings
    private lateinit var holder: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var back: Button
    private lateinit var next: Button
    private var index = 0
    /** Re-checks the "turned on" and "selected" lines when returning from system screens. */
    private var refreshStatus: (() -> Unit)? = null

    private val pages by lazy {
        listOf(
            Page("TailorKeys", listOf(
                "A keyboard you arrange yourself. Size it, split it, tilt it, and put every key where your thumbs want it.",
                "Suggestions never change what you type. They wait in two wheels above the keys until you pick one.",
            )),
            Page("Turn it on", listOf(
                "Android keeps a list of keyboards in its settings. Turn on TailorKeys there, then come back here.",
                "Android warns that keyboards can collect what you type. TailorKeys has no internet permission, so nothing you type can leave your phone.",
            )) { statusButton("Open keyboard settings", { enabled() }, "✓ TailorKeys is turned on") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } },
            Page("Switch to it", listOf(
                "Pick TailorKeys from the list of keyboards.",
                "Later, hold the spacebar to switch keyboards.",
            )) { statusButton("Choose keyboard", { selected() }, "✓ TailorKeys is your keyboard") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            } },
            Page("Put keys anywhere", listOf(
                "Tap the gear between the suggestions, then drag keys around.",
                "Drop a key on an empty cell to move it there.",
                "Drop it on another key's edge to insert it. The keys beside it shift over.",
                "Drop it on a key's middle to combine them into one swipe key.",
                "Tap undo if you didn't mean it. Templates and layout sharing are in settings.",
            )),
            Page("Swipe keys and wheels", listOf(
                "A swipe key holds several keys. Swipe up or down once to change it.",
                "abc: tap for one capital letter, double-tap for caps lock.",
                "Spin a wheel sideways to look through suggestions and tap to use one. The left wheel explores, the right stays close to what you typed.",
                "Hold a letter to type the number in its corner.",
            )),
            Page("Try it", listOf("Size, split, tilt, themes and the full layout editor are in settings.")) {
                EditText(this).apply { hint = "Type here"; minLines = 3; gravity = Gravity.TOP }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        root.fitsSystemWindows = true
        root.addView(TextView(this).apply {
            text = "Skip"
            textSize = 15f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { finishGuide() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(dots)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        back = Button(this).apply { text = "Back"; isAllCaps = false; setOnClickListener { go(index - 1) } }
        next = Button(this).apply { isAllCaps = false; setOnClickListener { if (index == pages.lastIndex) finishGuide() else go(index + 1) } }
        nav.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        nav.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav)
        setContentView(root)
        show(0, 0f)
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(TextView(this).apply {
            text = p.title
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        })
        for (line in p.lines) col.addView(TextView(this).apply { text = line; textSize = 17f; setPadding(0, dp(6), 0, dp(6)) })
        p.extra?.let { col.addView(it(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }) }
        holder.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dots.removeAllViews()
        for (j in pages.indices) dots.addView(TextView(this).apply {
            text = "●"
            textSize = 10f
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(getColor(R.color.accent))
            alpha = if (j == i) 1f else 0.3f
        })
        back.visibility = if (i == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (i == pages.lastIndex) "Finish" else "Next"
    }

    private fun finishGuide() {
        settings.set(Prefs.ONBOARDED, true)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    // ---- Setup checks ----

    private fun enabled() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    private fun selected() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    /** A button for a setup step, with a line under it that turns into a check mark once the step is done. */
    private fun statusButton(label: String, done: () -> Boolean, doneText: String, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } })
        val status = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(8), 0, 0) }
        col.addView(status)
        refreshStatus = { status.text = if (done()) doneText else "" }
        refreshStatus?.invoke()
        return col
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

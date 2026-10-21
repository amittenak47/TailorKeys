package app.tailorkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.roundToInt

/** Shared by the app and IME. Only a new grid/geometry signature runs an offscreen layout. */
internal class LayoutThumbnailCache(private val context: Context, private val settings: KeyboardSettings) {
    private val view by lazy { KeyboardView(context, settings) }
    private val cache = object : LinkedHashMap<String, List<KeyboardView.ThumbnailKey>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<KeyboardView.ThumbnailKey>>) = size > 48
    }
    fun signature(entry: LayoutEntry): String {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val profile = settings.profile(landscape).copy(split = entry.split)
        val display = context.resources.displayMetrics
        val width = (display.widthPixels - 8 * display.density).roundToInt().coerceAtLeast(1)
        return "${entry.json}|$profile|$landscape|$width|${display.heightPixels}|${display.density}|${settings.bool(Prefs.SAME_ORIENTATIONS)}|${settings.int(Prefs.CURVE)}|${settings.int(Prefs.KEY_GAP)}|${settings.int(Prefs.CORNER_RADIUS)}"
    }
    fun cached(entry: LayoutEntry) = cache[signature(entry)]
    fun keys(entry: LayoutEntry): List<KeyboardView.ThumbnailKey> {
        val key = signature(entry)
        return cache.getOrPut(key) {
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width = (context.resources.displayMetrics.widthPixels - 8 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            view.grid = entry.grid()
            view.reloadSettings()
            view.thumbnailKeys(width, settings.profile(landscape).copy(split = entry.split))
        }
    }
}

/** One renderer for Compose tiles and native keyboard tiles. Paths come from the real layout solver. */
internal class ThumbnailArtwork(private val keys: List<KeyboardView.ThumbnailKey>) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val left = keys.minOfOrNull { it.bounds.left } ?: 0f
    private val top = keys.minOfOrNull { it.bounds.top } ?: 0f
    private val w = (keys.maxOfOrNull { it.bounds.right } ?: 1f) - left
    private val h = (keys.maxOfOrNull { it.bounds.bottom } ?: 1f) - top
    fun draw(canvas: Canvas, width: Float, height: Float, palette: Palette, padding: Float) {
        if (w <= 0 || h <= 0 || width <= 2 * padding || height <= 2 * padding) return
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawRoundRect(0f, 0f, width, height, padding, padding, paint)
        val scale = minOf((width - padding * 2) / w, (height - padding * 2) / h)
        canvas.save()
        canvas.translate((width - w * scale) / 2, (height - h * scale) / 2)
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        keys.forEach { key ->
            paint.style = Paint.Style.FILL
            paint.color = when (key.kind) { 2 -> palette.accent; 1 -> palette.functionKey; else -> palette.key }
            canvas.drawPath(key.path, paint)
            if (palette.key == palette.background) {
                paint.color = palette.text
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f / scale
                canvas.drawPath(key.path, paint)
            }
        }
        canvas.restore()
    }
}

internal class LayoutThumbnailView(context: Context) : View(context) {
    var artwork: ThumbnailArtwork? = null
    var palette: Palette? = null
    override fun onDraw(canvas: Canvas) {
        palette?.let { artwork?.draw(canvas, width.toFloat(), height.toFloat(), it, 4 * resources.displayMetrics.density) }
    }
}

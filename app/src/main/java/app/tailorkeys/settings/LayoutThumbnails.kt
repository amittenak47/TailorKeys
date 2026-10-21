package app.tailorkeys.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Stable
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.KeyboardView
import app.tailorkeys.Prefs
import app.tailorkeys.layout.Template
import kotlin.math.roundToInt

@Stable
internal class LayoutThumbnails(private val context: Context, private val settings: KeyboardSettings, private val revisions: PreferenceRevisions) {
    private val view by lazy { KeyboardView(context, settings) }
    private val cache = object : LinkedHashMap<String, List<KeyboardView.ThumbnailKey>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<KeyboardView.ThumbnailKey>>) = size > 48
    }
    fun keys(entry: LayoutEntry): List<KeyboardView.ThumbnailKey> {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        revisions.read(Prefs.CURVE.key, Prefs.KEY_GAP.key, Prefs.CORNER_RADIUS.key, Prefs.SAME_ORIENTATIONS.key,
            Prefs.height(landscape).key, Prefs.width(landscape).key, Prefs.alignKey(landscape),
            Prefs.bottomPadding(landscape).key, Prefs.splitKey(landscape), Prefs.splitGap(landscape).key,
            Prefs.tilt(landscape).key, Prefs.spaceTilt(landscape).key)
        val profile = settings.profile(landscape).copy(split = entry.preset?.let { it == Template.SPLIT } ?: settings.split(landscape))
        val display = context.resources.displayMetrics
        val width = (display.widthPixels - 8 * display.density).roundToInt().coerceAtLeast(1)
        val key = "${entry.json}|$profile|$landscape|$width|${display.heightPixels}|${display.density}|${settings.bool(Prefs.SAME_ORIENTATIONS)}|${settings.int(Prefs.CURVE)}|${settings.int(Prefs.KEY_GAP)}|${settings.int(Prefs.CORNER_RADIUS)}"
        return cache.getOrPut(key) {
            view.grid = entry.grid()
            view.reloadSettings()
            view.thumbnailKeys(width, profile)
        }
    }
}

@Composable
internal fun LayoutThumbnail(entry: LayoutEntry, cache: LayoutThumbnails, modifier: Modifier) {
    LocalConfiguration.current // Rotation invalidates the thumbnail, including a retained tab.
    val keys = cache.keys(entry)
    val drawn = remember(keys) { keys.map { it.path.asComposePath() to it.kind } }
    val paths = remember(keys) {
        val left = keys.minOf { it.bounds.left }
        val top = keys.minOf { it.bounds.top }
        val width = keys.maxOf { it.bounds.right } - left
        val height = keys.maxOf { it.bounds.bottom } - top
        floatArrayOf(left, top, width, height)
    }
    Canvas(modifier) {
        val padding = 6.dp.toPx()
        drawRoundRect(Color(0xFFEAE3D7), cornerRadius = CornerRadius(12.dp.toPx()))
        val scale = minOf((size.width - padding * 2) / paths[2], (size.height - padding * 2) / paths[3])
        val x = (size.width - paths[2] * scale) / 2
        val y = (size.height - paths[3] * scale) / 2
        drawContext.canvas.save()
        drawContext.canvas.translate(x, y)
        drawContext.canvas.scale(scale, scale)
        drawContext.canvas.translate(-paths[0], -paths[1])
        drawn.forEach { (path, kind) -> drawPath(path, when (kind) { 2 -> Color(0xFFC55527); 1 -> Color(0xFFD4CBB9); else -> Color(0xFFFBF8F2) }) }
        drawContext.canvas.restore()
    }
}

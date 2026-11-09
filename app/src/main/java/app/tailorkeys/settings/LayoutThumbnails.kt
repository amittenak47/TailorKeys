package app.tailorkeys.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.tailorkeys.*

@Stable
internal class LayoutThumbnails(private val context: Context, settings: KeyboardSettings, private val revisions: PreferenceRevisions) {
    private val cache = LayoutThumbnailCache(context, settings)
    private val pending = Mutex()
    fun signature(entry: LayoutEntry): String {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        revisions.read(Prefs.CURVE.key, Prefs.KEY_GAP.key, Prefs.CORNER_RADIUS.key, Prefs.SAME_ORIENTATIONS.key,
            Prefs.height(landscape).key, Prefs.width(landscape).key, Prefs.alignKey(landscape),
            Prefs.bottomPadding(landscape).key, Prefs.splitKey(landscape), Prefs.splitGap(landscape).key,
            Prefs.tilt(landscape).key, Prefs.splitLeft(landscape).key, Prefs.splitRight(landscape).key, LayoutKeys.TRIAL, LayoutKeys.TRIAL_SPLIT)
        revisions.read(*(Prefs.halfShape(landscape, 0) + Prefs.halfShape(landscape, 1)).map { it.key }.toTypedArray())
        return cache.signature(entry)
    }
    suspend fun keys(entry: LayoutEntry): List<KeyboardView.ThumbnailKey> {
        cache.cached(entry)?.let { return it }
        return pending.withLock {
            cache.cached(entry) ?: run {
                // Views stay on the UI thread. Wait until the placeholder has drawn, then
                // resolve only one visible tile at a time, yielding frames between tiles.
                withFrameNanos { }
                withFrameNanos { }
                cache.keys(entry)
            }
        }
    }
}

@Composable
internal fun LayoutThumbnail(entry: LayoutEntry, cache: LayoutThumbnails, modifier: Modifier) {
    LocalConfiguration.current
    val signature = cache.signature(entry)
    val keys by produceState<List<KeyboardView.ThumbnailKey>?>(null, cache, signature) {
        value = null
        value = cache.keys(entry)
    }
    val artwork = remember(keys) { keys?.let { ThumbnailArtwork(it) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = remember(context) { Palette.of(context, ThemeName.WARM) }
    Canvas(modifier) {
        drawRect(androidx.compose.ui.graphics.Color(palette.background))
        drawIntoCanvas { artwork?.draw(it.nativeCanvas, size.width, size.height, palette, 6.dp.toPx()) }
    }
}

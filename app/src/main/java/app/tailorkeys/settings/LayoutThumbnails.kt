package app.tailorkeys.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.tailorkeys.*

@Stable
internal class LayoutThumbnails(private val context: Context, settings: KeyboardSettings, private val revisions: PreferenceRevisions) {
    private val cache = LayoutThumbnailCache(context, settings)
    fun keys(entry: LayoutEntry): List<KeyboardView.ThumbnailKey> {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        revisions.read(Prefs.CURVE.key, Prefs.KEY_GAP.key, Prefs.CORNER_RADIUS.key, Prefs.SAME_ORIENTATIONS.key,
            Prefs.height(landscape).key, Prefs.width(landscape).key, Prefs.alignKey(landscape),
            Prefs.bottomPadding(landscape).key, Prefs.splitKey(landscape), Prefs.splitGap(landscape).key,
            Prefs.tilt(landscape).key, Prefs.spaceTilt(landscape).key)
        return cache.keys(entry)
    }
}

@Composable
internal fun LayoutThumbnail(entry: LayoutEntry, cache: LayoutThumbnails, modifier: Modifier) {
    LocalConfiguration.current
    val keys = cache.keys(entry)
    val artwork = remember(keys) { ThumbnailArtwork(keys) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = remember(context) { Palette.of(context, ThemeName.WARM) }
    Canvas(modifier) {
        drawIntoCanvas { artwork.draw(it.nativeCanvas, size.width, size.height, palette, 6.dp.toPx()) }
    }
}

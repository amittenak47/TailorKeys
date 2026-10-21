package app.tailorkeys.settings

import app.tailorkeys.LayoutEntry
import app.tailorkeys.TypingResult

import android.provider.Settings
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.tailorkeys.DictionaryWords
import app.tailorkeys.KeyboardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypingTestPage(state: SettingsState, entry: LayoutEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    val reduced = LocalLayoutReducedMotion.current
    var mode by remember { mutableStateOf(TypingMode.FREE) }
    var sentenceIndex by remember { mutableIntStateOf(SentenceBank.sentences.indices.random()) }
    var generation by remember { mutableIntStateOf(0) }
    val session = remember(mode, generation) { TypingSession(mode, SentenceBank.sentences[sentenceIndex]) }
    var fieldValue by remember(session) { mutableStateOf(TextFieldValue()) }
    var tick by remember(session) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var finished by remember(session) { mutableStateOf(false) }
    var applied by remember { mutableStateOf(entry.key == state.library.inUse) }
    val average = remember(entry.key, generation, mode) { state.library.results(entry.key).map { it.wpm }.average().takeIf { it.isFinite() } }
    val recorded = remember(session) { booleanArrayOf(false) }
    fun recordFreeResult() {
        val result = session.metrics(SystemClock.elapsedRealtime())
        if (!recorded[0] && session.mode == TypingMode.FREE && session.text.isNotBlank() && result.elapsed > 0) {
            state.library.record(entry.key, TypingResult(result.wpm, result.accuracy, System.currentTimeMillis()))
            recorded[0] = true
        }
    }
    val words by produceState<Set<String>>(emptySet(), state.settings.currentLanguage()) {
        value = withContext(Dispatchers.IO) {
            DictionaryWords.read({ context.assets.open(it) }, state.settings.currentLanguage(), unique = true).toHashSet()
        }
    }
    var tailorkeys by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            tailorkeys = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/') == context.packageName
            delay(750)
        }
    }
    LaunchedEffect(session) {
        delay(if (reduced) 120 else 360)
        if (!session.complete) {
            focus.requestFocus()
            keyboard?.show()
        }
        view.announceForAccessibility("Typing test, ${entry.name}")
        while (!session.complete) {
            delay(1_000)
            tick = SystemClock.elapsedRealtime()
            session.sample(tick)
        }
    }
    val metrics = session.metrics(tick)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    DisposableEffect(session) {
        onDispose { recordFreeResult() }
    }
    val colors = MaterialTheme.colorScheme
    fun restart() {
        recordFreeResult()
        sentenceIndex = (sentenceIndex + 1) % SentenceBank.sentences.size
        generation++
    }
    Column(Modifier.fillMaxSize().background(colors.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back to layouts" }) { Text("←", fontSize = 24.sp) }
            Column { Text("Typing test", style = MaterialTheme.typography.titleLarge); Text(entry.name, style = MaterialTheme.typography.labelMedium) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().layoutMorph(entry.key),
                shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, shadowElevation = 8.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!tailorkeys) Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
                        Column(Modifier.padding(12.dp)) {
                            Text("TailorKeys isn’t the current keyboard.", style = MaterialTheme.typography.bodyMedium)
                            TextButton({ context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) { Text("Switch keyboard") }
                        }
                    }
                    if (finished) {
                        Text("✓ Test complete", color = Color(0xFF8ED5BE), fontWeight = FontWeight.Medium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${metrics.wpm.roundToInt()}", fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "${metrics.wpm.roundToInt()} words per minute" })
                            Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                                Text("words per minute")
                                average?.let { Text("${if (metrics.wpm >= it) "↑" else "↓"} ${kotlin.math.abs(metrics.wpm - it).roundToInt()} ${if (metrics.wpm >= it) "above" else "below"} your average (${it.roundToInt()})", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        SpeedChart(session.samples.toList(), average, Modifier.fillMaxWidth().height(80.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0:00", style = MaterialTheme.typography.labelMedium)
                            Text("Dots mark mistakes", style = MaterialTheme.typography.labelMedium)
                            Text(formatTime(metrics.elapsed), style = MaterialTheme.typography.labelMedium)
                        }
                        MetricRow(metrics, session.text.length, null)
                        Surface(shape = RoundedCornerShape(16.dp), color = colors.background) {
                            Column(Modifier.padding(14.dp)) {
                                Text("WHERE IT SLIPPED", style = MaterialTheme.typography.labelMedium)
                                Text(AnnotatedString.Builder(session.sentence).apply {
                                    session.slips.filter { it < length }.forEach { addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = colors.error), it, it + 1) }
                                }.toAnnotatedString())
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(::restart, Modifier.weight(1f)) { Text("New sentence") }
                            FilledTonalButton({ state.useLayout(entry); applied = true }, Modifier.weight(1f), enabled = !applied) { Text(if (applied) "✓ In use" else "✓ Use layout") }
                        }
                        TextButton(onBack, Modifier.align(Alignment.CenterHorizontally)) { Text("← Back to layouts") }
                    } else {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            TypingMode.entries.forEachIndexed { i, option ->
                                SegmentedButton(mode == option, { if (mode != option) { recordFreeResult(); mode = option } }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(if (option == TypingMode.FREE) "Free typing" else "Test sentence")
                                }
                            }
                        }
                        val transformation = remember(mode, words, colors, session) { VisualTransformation { text ->
                            val styled = AnnotatedString.Builder(text)
                            if (mode == TypingMode.TEST) {
                                text.text.indices.forEach { i -> if (i >= session.sentence.length || !text.text[i].equals(session.sentence[i], true))
                                    styled.addStyle(SpanStyle(color = colors.error, textDecoration = TextDecoration.Underline), i, i + 1) }
                                if (text.length < session.sentence.length) {
                                    styled.append(AnnotatedString(session.sentence.substring(text.length), SpanStyle(color = colors.onSurfaceVariant.copy(alpha = .65f))))
                                }
                            } else if (words.isNotEmpty()) Regex("[\\p{L}']+").findAll(text.text).forEach { word ->
                                if (word.range.last < text.length - 1 && word.value.lowercase() !in words)
                                    styled.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = colors.error), word.range.first, word.range.last + 1)
                            }
                            TransformedText(styled.toAnnotatedString(), object : OffsetMapping {
                                override fun originalToTransformed(offset: Int) = offset
                                override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(text.length)
                            })
                        } }
                        Column {
                            if (mode == TypingMode.FREE) Text("Type anything", style = MaterialTheme.typography.labelMedium)
                            BasicTextField(fieldValue, { next ->
                                fieldValue = next
                                tick = SystemClock.elapsedRealtime()
                                if (next.text.isNotEmpty()) session.startTyping(tick)
                                if (next.composition == null) {
                                    session.update(next.text, tick)
                                    if (session.complete) {
                                        finished = true
                                        val result = session.metrics(tick)
                                        state.library.record(entry.key, TypingResult(result.wpm, result.accuracy, System.currentTimeMillis()))
                                    }
                                }
                            }, Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus)
                                .clip(RoundedCornerShape(16.dp)).background(colors.background).padding(16.dp)
                                .semantics { contentDescription = if (mode == TypingMode.FREE) "Type anything" else "Copy this sentence: ${session.sentence}" },
                                textStyle = TextStyle(color = colors.onSurface, fontSize = 20.sp, lineHeight = 28.sp),
                                cursorBrush = SolidColor(colors.primary), visualTransformation = transformation,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text, imeAction = ImeAction.None))
                        }
                        if (mode == TypingMode.TEST) LinearProgressIndicator(progress = { (session.text.length.toFloat() / session.sentence.length).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        BoxWithConstraints {
                            val wide = maxWidth >= 480.dp
                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                SpeedMetric(metrics, session.samples.toList(), average, mode, Modifier.weight(1.3f))
                                MetricRow(metrics, session.text.length, if (mode == TypingMode.TEST) session.sentence.length else null, Modifier.weight(2f))
                            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SpeedMetric(metrics, session.samples.toList(), average, mode, Modifier.fillMaxWidth())
                                HorizontalDivider()
                                MetricRow(metrics, session.text.length, if (mode == TypingMode.TEST) session.sentence.length else null)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(::restart) { Text(if (mode == TypingMode.FREE) "↻ Clear" else "↻ New sentence") }
                            Spacer(Modifier.weight(1f))
                            Text(if (mode == TypingMode.FREE) "Text stays on this device" else "Type on the keys below", style = MaterialTheme.typography.labelMedium)
                        }
                        if (mode == TypingMode.FREE) FilledTonalButton({
                            if (!applied) { state.useLayout(entry); applied = true }
                        }, Modifier.align(Alignment.End), enabled = !applied) { Text(if (applied) "✓ In use" else "Use layout") }
                    }
                }
            }
        }
        // Hand the moving preview over to the real system IME when focus arrives.
        if (!imeVisible && !finished) AndroidView(factory = { KeyboardView(context, state.settings).apply { grid = entry.grid() } },
            modifier = Modifier.fillMaxWidth().layoutMorph("keyboard-preview").wrapContentHeight(unbounded = true)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)))
    }
}

@Composable
private fun SpeedMetric(metrics: TypingMetrics, samples: List<SpeedSample>, average: Double?, mode: TypingMode, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${metrics.wpm.roundToInt()} words per minute, average ${average?.roundToInt() ?: 0}" }) {
        Text(if (mode == TypingMode.FREE) "SPEED NOW" else "WORDS PER MINUTE", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${metrics.wpm.roundToInt()}", fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
            Text("wpm", style = MaterialTheme.typography.bodyMedium)
            SpeedChart(samples, average, Modifier.weight(1f).height(36.dp))
        }
        Text(if (average == null) "Average appears after your first result" else "avg ${average.roundToInt()} · peak ${(samples.maxOfOrNull { it.wpm } ?: 0.0).roundToInt()}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetricRow(metrics: TypingMetrics, characters: Int, target: Int?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Accuracy ${(metrics.accuracy * 100).roundToInt()} percent" }) {
            Text("ACCURACY", style = MaterialTheme.typography.labelMedium)
            val ring = MaterialTheme.colorScheme.tertiary
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    drawCircle(ring.copy(alpha = .15f), style = Stroke(4.dp.toPx()))
                    drawArc(Color(0xFF8ED5BE), -90f, (metrics.accuracy * 360).toFloat(), false, style = Stroke(4.dp.toPx()))
                }
                Text("${(metrics.accuracy * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${metrics.mistakes} mistakes" }) {
            Text("MISTAKES", style = MaterialTheme.typography.labelMedium)
            Text("${metrics.mistakes}", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Time ${metrics.elapsed / 1_000} seconds, $characters characters" }) {
            Text("TIME", style = MaterialTheme.typography.labelMedium)
            Text(formatTime(metrics.elapsed), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(if (target == null) "$characters characters" else "$characters / $target characters", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SpeedChart(samples: List<SpeedSample>, average: Double?, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier.clearAndSetSemantics { contentDescription = "Speed history; dots mark mistakes" }) {
        if (samples.isEmpty()) return@Canvas
        val maxWpm = maxOf(1.0, samples.maxOf { it.wpm }, average ?: 0.0) * 1.15
        val maxTime = samples.last().elapsed.coerceAtLeast(1)
        fun point(s: SpeedSample) = Offset((s.elapsed.toFloat() / maxTime * (size.width - 8.dp.toPx()) + 4.dp.toPx()).coerceAtLeast(0f),
            (size.height - 4.dp.toPx()) * (1 - (s.wpm / maxWpm).toFloat()))
        val path = Path().apply { samples.forEachIndexed { i, sample -> val p = point(sample); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val area = Path().apply { addPath(path); lineTo(point(samples.last()).x, size.height); lineTo(point(samples.first()).x, size.height); close() }
        drawPath(area, color.copy(alpha = .15f))
        average?.let { val y = (size.height - 4.dp.toPx()) * (1 - (it / maxWpm).toFloat()); drawLine(color.copy(alpha = .4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        samples.filter { it.mistake }.forEach { drawCircle(error, 4.dp.toPx(), point(it)); drawCircle(Color(0xFF2D281F), 2.dp.toPx(), point(it)) }
        drawCircle(color, 3.dp.toPx(), point(samples.last()))
    }
}

private fun formatTime(ms: Long): String = "${ms / 60_000}:${((ms / 1_000) % 60).toString().padStart(2, '0')}"

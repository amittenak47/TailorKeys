package app.tailorkeys.settings

import app.tailorkeys.*
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import android.provider.Settings
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tailorkeys.DictionaryWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TypingTestPage(state: SettingsState, entry: LayoutEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    var mode by remember { mutableStateOf(TypingMode.FREE) }
    var sentenceIndex by remember { mutableIntStateOf(SentenceBank.sentences.indices.random()) }
    var generation by remember { mutableIntStateOf(0) }
    fun currentIme() = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "unknown"
    var keyboardId by remember { mutableStateOf(currentIme()) }
    val tailorkeys = isTailorKeysIme(keyboardId)
    var comparing by remember { mutableStateOf(false) }
    var session by remember(mode, generation) { mutableStateOf(TypingSession(mode, SentenceBank.sentences[sentenceIndex], keyboardId)) }
    var fieldValue by remember(session) { mutableStateOf(TextFieldValue()) }
    var tick by remember(session) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var finished by remember(session) { mutableStateOf(false) }
    var thisTest by remember(session) { mutableStateOf<TypingResult?>(null) }
    var applied by remember { mutableStateOf(entry.key == state.library.inUse) }
    // Opening runs as a pipeline, one stage at a time: the morph alone, then the trial layout, then the IME,
    // and the dictionary only once you type.
    val settled = rememberLayoutSettled()
    var wantWords by remember { mutableStateOf(false) }
    fun keyboardLabel(id: String): String = if (isTailorKeysIme(id)) "TailorKeys" else
        context.getSystemService(InputMethodManager::class.java).inputMethodList.firstOrNull { it.id == id }?.loadLabel(context.packageManager)?.toString() ?: id.substringBefore('/').substringAfterLast('.').ifBlank { "Keyboard" }
    val label = remember(keyboardId) { keyboardLabel(keyboardId) }
    val average = remember(entry.key, generation, mode, keyboardId) { state.library.results(entry.key).filter { keyboardGroup(it.keyboard) == keyboardGroup(keyboardId) }.map { it.wpm }.average().takeIf { it.isFinite() } }
    val words by produceState<Set<String>>(emptySet(), state.settings.currentLanguage(), wantWords) {
        if (!wantWords) return@produceState
        value = withContext(Dispatchers.IO) {
            DictionaryWords.read({ context.assets.open(it) }, state.settings.currentLanguage(), unique = true).toHashSet()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState({ val id = currentIme(); session = session.forKeyboard(id); keyboardId = id })
    DisposableEffect(view, lifecycle) {
        val window = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        val focusChanged = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> refresh() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { refresh() } }
        val events = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(window)
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChanged)
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
        lifecycle.addObserver(events)
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(window)
                view.viewTreeObserver.removeOnGlobalFocusChangeListener(focusChanged)
            }
            context.contentResolver.unregisterContentObserver(observer); lifecycle.removeObserver(events)
        }
    }
    // The trial writes preferences synchronously and makes both keyboards reload, so it waits for the morph.
    DisposableEffect(tailorkeys, entry.key, settled) {
        if (settled) { if (tailorkeys) state.library.beginTrial(entry) else state.library.restoreTrial() }
        onDispose { state.library.restoreTrial() }
    }
    LaunchedEffect(session, comparing, settled) {
        if (!settled) return@LaunchedEffect
        // Give the trial layout's reload its own frame, then slide the keyboard in once, already showing it.
        withFrameNanos { }
        if (!session.complete && !comparing) {
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
    val progress = session.progress
    val colors = MaterialTheme.colorScheme
    fun restart() {
        sentenceIndex = (sentenceIndex + 1) % SentenceBank.sentences.size
        generation++
    }
    Column(Modifier.fillMaxSize().background(colors.background)
        // safeDrawing without the IME, whose bottom-only animation would still invalidate this padding every frame.
        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        // The keyboard's final height, not its animated one: the card is top-aligned, so following the slide
        // frame by frame only relaid out the page on every frame of it.
        .windowInsetsPadding(WindowInsets.imeAnimationTarget.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back to layouts" }) { Text("←", fontSize = 24.sp) }
            Column(Modifier.weight(1f)) {
                Text("Typing test", color = colors.onSurface, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (tailorkeys) "TailorKeys · ${entry.name}" else "Typing with $label", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!finished) TextButton({ comparing = !comparing; if (comparing) keyboard?.hide() }) { Text(if (comparing) "Back to test" else "Compare") }
            TextButton({ context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) { Text("Switch keyboard") }
        }
        // Top-aligned, so the keyboard opening or closing only changes the space below the card.
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().then(if (tailorkeys) Modifier.layoutMorph(entry.key) else Modifier),
                shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, shadowElevation = 8.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (comparing) {
                        KeyboardComparison(state.library.keyboardAverages(), ::keyboardLabel, thisTest)
                    } else if (finished) {
                        Text("✓ Test complete", color = Color(if (colors.background.luminance() < .5f) 0xFF8ED1B8 else 0xFF1E6B52), fontWeight = FontWeight.Medium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${metrics.wpm.roundToInt()}", fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "${metrics.wpm.roundToInt()} words per minute" })
                            Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                                Text("words per minute")
                                average?.let { Text("${kotlin.math.abs(metrics.wpm - it).roundToInt()} ${if (metrics.wpm >= it) "above" else "below"} your $label average (${it.roundToInt()})", style = MaterialTheme.typography.labelMedium) }
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
                        KeyboardComparison(state.library.keyboardAverages(), ::keyboardLabel, thisTest)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(::restart, Modifier.weight(1f)) { Text("New sentence") }
                            FilledTonalButton({ state.useLayout(entry); applied = true }, Modifier.weight(1f), enabled = tailorkeys && !applied) { Text(if (applied) "✓ In use" else "✓ Use layout") }
                        }
                        TextButton(onBack, Modifier.align(Alignment.CenterHorizontally)) { Text("← Back to layouts") }
                    } else {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            TypingMode.entries.forEachIndexed { i, option ->
                                SegmentedButton(mode == option, { if (mode != option) { mode = option } }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
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
                            NoPersonalizedLearning {
                                BasicTextField(fieldValue, { next ->
                                    val active = currentIme()
                                    if (active != keyboardId) { session = session.forKeyboard(active); keyboardId = active; return@BasicTextField }
                                    fieldValue = next
                                    if (next.text.isNotEmpty()) wantWords = true
                                    tick = SystemClock.elapsedRealtime()
                                    if (next.text.isNotEmpty()) session.startTyping(tick)
                                    if (next.composition == null) {
                                        session.update(next.text, tick)
                                        if (session.complete) {
                                            finished = true
                                            session.takeResult(System.currentTimeMillis())?.let { thisTest = it; state.library.record(entry.key, it) }
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
                        }
                        if (mode == TypingMode.TEST) LinearProgressIndicator(progress = { progress },
                            color = colors.primary, trackColor = colors.onSurface.copy(alpha = .12f),
                            drawStopIndicator = {}, modifier = Modifier.fillMaxWidth())
                        BoxWithConstraints {
                            val wide = maxWidth >= 480.dp
                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                SpeedMetric(metrics, session.samples.toList(), session.peakWpm, average, mode, Modifier.weight(1.3f))
                                MetricRow(metrics, session.text.length, if (mode == TypingMode.TEST) session.sentence.length else null, Modifier.weight(2f))
                            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SpeedMetric(metrics, session.samples.toList(), session.peakWpm, average, mode, Modifier.fillMaxWidth())
                                HorizontalDivider()
                                MetricRow(metrics, session.text.length, if (mode == TypingMode.TEST) session.sentence.length else null)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(::restart) { Text(if (mode == TypingMode.FREE) "↻ Clear" else "↻ New sentence") }
                            Spacer(Modifier.weight(1f))
                            Text(if (mode == TypingMode.FREE) "Text stays on this device" else if (tailorkeys) "Type on the keys below" else "Results save under $label", style = MaterialTheme.typography.labelMedium)
                        }
                        if (mode == TypingMode.FREE) FilledTonalButton({
                            if (!applied) { state.useLayout(entry); applied = true }
                        }, Modifier.align(Alignment.End), enabled = tailorkeys && !applied) { Text(if (applied) "✓ In use" else "Use layout") }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardComparison(averages: List<KeyboardAverage>, label: (String) -> String, thisTest: TypingResult?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Compare keyboards", style = MaterialTheme.typography.titleMedium)
        Text("All Test sentence results", style = MaterialTheme.typography.labelSmall)
        if (averages.isEmpty()) Text("Finish a test sentence to compare keyboards.")
        Row { Text("Keyboard", Modifier.weight(1.4f)); Text("Avg wpm", Modifier.weight(1f)); Text("Accuracy", Modifier.weight(1f)); Text("Tests", Modifier.weight(.6f)) }
        averages.forEach { row ->
            Surface(color = if (isTailorKeysIme(row.keyboard)) colors.secondaryContainer else colors.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.4f)) {
                        Text(label(row.keyboard), fontWeight = if (isTailorKeysIme(row.keyboard)) FontWeight.Bold else FontWeight.Normal)
                        if (thisTest != null && keyboardGroup(row.keyboard) == keyboardGroup(thisTest.keyboard)) {
                            Text("✓ This test", color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.background(colors.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("${row.wpm.roundToInt()}")
                        LinearProgressIndicator(progress = { (row.wpm / (averages.maxOfOrNull { it.wpm } ?: 1.0).coerceAtLeast(1.0)).toFloat() },
                            modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    }
                    Text("${(row.accuracy * 100).roundToInt()}%", Modifier.weight(1f))
                    Text("${row.tests}", Modifier.weight(.6f))
                }
            }
        }
        val summaries = averages.comparisonSummaries()
        summaries.forEach { summary ->
            Text(summary.description() + if (summaries.size > 1) " than ${label(summary.other)}" else "", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SpeedMetric(metrics: TypingMetrics, samples: List<SpeedSample>, peak: Double, average: Double?, mode: TypingMode, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${metrics.wpm.roundToInt()} words per minute, average ${average?.roundToInt() ?: 0}" }) {
        Text(if (mode == TypingMode.FREE) "SPEED NOW" else "WORDS PER MINUTE", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${metrics.wpm.roundToInt()}", fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
            Text("wpm", style = MaterialTheme.typography.bodyMedium)
            SpeedChart(samples, average, Modifier.weight(1f).height(36.dp))
        }
        Text(if (average == null) "Average appears after your first result" else "avg ${average.roundToInt()} · peak ${peak.roundToInt()}", style = MaterialTheme.typography.labelMedium)
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

/** Compose's PlatformImeOptions only carries private options, so add the public flag after
 * Compose populates EditorInfo. TailorKeysService checks it for direct and deferred learning. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = { request, next ->
        next.startInputMethod(object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                return connection
            }
        })
    }, content = content)
}

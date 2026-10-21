package app.tailorkeys.settings

import app.tailorkeys.LayoutEntry

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import android.animation.ValueAnimator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.R

/** HIDDEN includes an already configured keyboard; READY is only a transition observed by the activity. */
enum class SetupStatus { TURN_ON, MAKE_DEFAULT, READY, HIDDEN }
private val Tabs = listOf("Layout", "Size", "Look", "Settings")
private val TabIcons = listOf(R.drawable.ic_settings_layout, R.drawable.ic_settings_size,
    R.drawable.ic_settings_look, R.drawable.ic_settings_tune)

@Composable
fun SettingsScreen(settings: KeyboardSettings, setupStatus: SetupStatus, onSetup: () -> Unit) {
    val context = LocalContext.current
    val state = remember(settings) { SettingsState(context, settings) }
    DisposableEffect(state) {
        state.observe()
        onDispose { state.dispose() }
    }
    var testEntry by remember { mutableStateOf<LayoutEntry?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeTest = {
        state.library.restoreTrial()
        keyboard?.hide()
        testEntry = null
        state.showSelected()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) closeTest() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.library.restoreTrial() }
    }
    BackHandler(testEntry != null) { closeTest() }
    val reduced = !ValueAnimator.areAnimatorsEnabled()
    val tab = rememberSaveable { mutableIntStateOf(0) }
    val listStates = List(Tabs.size) { rememberLazyListState() }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalLayoutSharedScope provides this, LocalLayoutReducedMotion provides reduced,
            LocalLayoutEntering provides (testEntry != null)) {
            if (reduced) ReducedLayoutCrossfade(testEntry) { entry ->
                if (entry == null) SettingsContent(state, setupStatus, onSetup, tab, listStates) {
                    if (testEntry == null) { testEntry = it }
                } else TypingTestPage(state, entry, closeTest)
            } else AnimatedContent(testEntry, contentKey = { it != null }, modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState != null) fadeIn(tween(190, 150)) togetherWith fadeOut(tween(140))
                    else fadeIn(tween(180, 120)) togetherWith fadeOut(tween(90))
                }, label = "Layout typing test") { entry ->
                CompositionLocalProvider(LocalLayoutVisibility provides this) {
                    if (entry == null) SettingsContent(state, setupStatus, onSetup, tab, listStates) {
                        if (testEntry == null) { testEntry = it }
                    } else TypingTestPage(state, entry, closeTest)
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(state: SettingsState, setupStatus: SetupStatus, onSetup: () -> Unit,
    tab: MutableIntState, listStates: List<LazyListState>, onTry: (LayoutEntry) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val selectTab: (Int) -> Unit = remember(tab, listStates) {
        { index ->
            if (tab.intValue != index) {
                // Tabs have always reopened at the top. Reset while leaving, keeping their composed controls.
                listStates[tab.intValue].requestScrollToItem(0)
                tab.intValue = index
                editing = false
                state.preview.editMode = false
                if (index == 0) state.showSelected() else state.showApplied()
            }
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var page by remember(landscape) { mutableStateOf(landscape) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsBackHandler(tab, editing, { editing = false }, selectTab)
    Scaffold(modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            SettingsNavigation(tab, selectTab)
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            var previewHeight by remember { mutableStateOf(0.dp) }
            // A full-size KeyboardView can exceed a short window. Keep the controls reachable without scaling it.
            val compact = maxHeight < previewHeight + 200.dp
            val overflowScroll = rememberScrollState()
            val optionsRequester = remember { BringIntoViewRequester() }
            BringOptionsIntoView(compact, previewHeight, tab, optionsRequester)
            Column(if (compact) Modifier.fillMaxWidth().verticalScroll(overflowScroll) else Modifier.fillMaxSize()) {
                SettingsHeader(setupStatus, onSetup)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).layoutMorph("keyboard-preview").clip(RoundedCornerShape(24.dp))) {
                    AndroidView(factory = { state.preview }, update = { it.editMode = editing },
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
                            .onSizeChanged { previewHeight = with(density) { it.height.toDp() } })
                    if (state.importing && !state.importPreviewValid) Box(Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = .94f)), contentAlignment = Alignment.Center) {
                        Text("No preview for this code", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BoxWithConstraints((if (compact) Modifier.fillMaxWidth().height(200.dp) else Modifier.fillMaxWidth().weight(1f))
                    .bringIntoViewRequester(optionsRequester)) {
                    // Retain the four lazy lists. Only the selected list is measured/placed, so hidden
                    // controls neither draw nor receive input/accessibility focus. Read tab in measure
                    // to keep navigation changes out of the preview/header composition.
                    Layout(content = {
                        Tabs.indices.forEach { index ->
                            key(index) {
                                SettingsTab(index, listStates[index], state, editing,
                                    { editing = !editing }, page, { page = it },
                                    onAbout = { dialog = "about" }, onReset = { dialog = "reset" }, onTry = onTry)
                            }
                        }
                    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                        val selected = measurables[tab.intValue].measure(constraints)
                        layout(selected.width, selected.height) { selected.placeRelative(0, 0) }
                    }
                }
            }
        }
    }
    when (dialog) {
        "reset" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Reset settings?") },
            text = { Text("Restore the default layout and all keyboard settings?") },
            confirmButton = { TextButton({
                editing = false
                state.reset()
                dialog = null
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reset settings") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } })
        "about" -> AboutDialog { dialog = null }
    }
}

@Composable
private fun SettingsBackHandler(tab: IntState, editing: Boolean, stopEditing: () -> Unit, selectTab: (Int) -> Unit) {
    BackHandler(editing || tab.intValue != 0) {
        if (editing) stopEditing() else selectTab(0)
    }
}

@Composable
private fun SettingsNavigation(tab: IntState, selectTab: (Int) -> Unit) {
    val visibility = LocalLayoutVisibility.current
    val reduced = LocalLayoutReducedMotion.current
    val motion = if (visibility != null && !reduced) with(visibility) {
        Modifier.animateEnterExit(enter = fadeIn(tween(180, 120)) + slideInVertically(tween(300, easing = LayoutEmphasis)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(220, easing = LayoutEmphasis)) { it })
    } else Modifier
    NavigationBar(modifier = motion, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Tabs.forEachIndexed { index, label ->
            NavigationBarItem(selected = tab.intValue == index, onClick = { selectTab(index) },
                icon = { SettingsIcon(TabIcons[index]) }, label = {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (tab.intValue == index) FontWeight.Bold else FontWeight.Medium)
                }, colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ))
        }
    }
}

@Composable
private fun BringOptionsIntoView(compact: Boolean, previewHeight: androidx.compose.ui.unit.Dp,
    tab: IntState, requester: BringIntoViewRequester) {
    LaunchedEffect(compact, previewHeight, tab.intValue) {
        if (compact) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
}

@Composable
private fun SettingsTab(index: Int, listState: LazyListState, state: SettingsState, editing: Boolean,
    onEdit: () -> Unit, page: Boolean, onPage: (Boolean) -> Unit,
    onAbout: () -> Unit, onReset: () -> Unit, onTry: (LayoutEntry) -> Unit) {
    if (index == 0) {
        LayoutLibraryPage(state, listState, editing, onEdit, onTry)
        return
    }
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clipToBounds(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (index == 0) 20.dp else 16.dp,
                bottom = if (index == 0) 8.dp else 44.dp),
            verticalArrangement = Arrangement.spacedBy(if (index == 0) 16.dp else 12.dp)) {
            when (index) {
                1 -> sizeOptions(state, page, onPage)
                2 -> lookOptions(state)
                3 -> typingOptions(state, onAbout, onReset)
            }
        }
        if (listState.canScrollBackward) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
            .background(Brush.verticalGradient(listOf(background, Color.Transparent))))
        if (listState.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, background))))
    }
}

@Composable
private fun SettingsHeader(status: SetupStatus, onSetup: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_tailorkeys_mark), null, Modifier.size(32.dp), tint = Color.Unspecified)
        Text("TailorKeys", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        AnimatedVisibility(status != SetupStatus.HIDDEN, enter = fadeIn(), exit = fadeOut()) {
            val ready = status == SetupStatus.READY || status == SetupStatus.HIDDEN
            val colors = MaterialTheme.colorScheme
            val dark = colors.background.luminance() < 0.5f
            val description = when (status) {
                SetupStatus.TURN_ON -> "TailorKeys is turned off. Turn on"
                SetupStatus.MAKE_DEFAULT -> "TailorKeys isn't your default keyboard. Make default"
                else -> "TailorKeys is ready"
            }
            Box(Modifier.heightIn(min = 48.dp)
                .clickable(enabled = !ready, role = Role.Button, onClick = onSetup)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (ready) liveRegion = LiveRegionMode.Polite
                }, contentAlignment = Alignment.Center) {
                Box {
                    Row(Modifier.height(34.dp).background(
                        if (ready) { if (dark) Color(0xFF203C2A) else Color(0xFFDDEFE0) } else colors.primary,
                        RoundedCornerShape(17.dp)).padding(horizontal = if (ready) 12.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val ink = if (ready) { if (dark) Color(0xFFB7E0BF) else Color(0xFF1B5E30) } else colors.onPrimary
                        if (ready) Icon(painterResource(R.drawable.ic_settings_check), null, Modifier.size(16.dp), tint = ink)
                        Text(when (status) { SetupStatus.TURN_ON -> "Turn on"; SetupStatus.MAKE_DEFAULT -> "Make default"; else -> "Ready" },
                            color = ink, style = MaterialTheme.typography.labelLarge)
                    }
                    if (!ready) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(12.dp)
                        .background(Color(0xFFFFB020), CircleShape).border(2.dp, colors.background, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val credit = remember(context) {
        context.assets.open("NOTICE-dictionary.txt").bufferedReader().use { it.readText() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("TailorKeys") }, text = {
        SelectionContainer {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Version $version")
                Text(credit, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

package app.tailorkeys.settings

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import app.tailorkeys.layout.KeyGrid

/** HIDDEN includes an already configured keyboard; READY is only a transition observed by the activity. */
enum class SetupStatus { TURN_ON, MAKE_DEFAULT, READY, HIDDEN }
private val Tabs = listOf("Layout", "Size", "Look", "Typing")
private val TabIcons = listOf(R.drawable.ic_settings_layout, R.drawable.ic_settings_size,
    R.drawable.ic_settings_look, R.drawable.ic_settings_typing)

@Composable
fun SettingsScreen(settings: KeyboardSettings, setupStatus: SetupStatus, onSetup: () -> Unit) {
    val context = LocalContext.current
    val state = remember(settings) { SettingsState(context, settings) }
    DisposableEffect(state) {
        state.observe()
        onDispose { state.dispose() }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var page by remember(landscape) { mutableStateOf(landscape) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var importCode by rememberSaveable { mutableStateOf("") }
    BackHandler(editing || tab != 0) {
        if (editing) editing = false else tab = 0
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Tabs.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { SettingsIcon(TabIcons[index]) }, label = {
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Medium)
                        }, colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ))
                }
            }
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            var previewHeight by remember { mutableStateOf(0.dp) }
            // A full-size KeyboardView can exceed a short window. Keep the controls reachable without scaling it.
            val compact = maxHeight < previewHeight + 200.dp
            val overflowScroll = rememberScrollState()
            val optionsRequester = remember { BringIntoViewRequester() }
            LaunchedEffect(compact, previewHeight, tab) {
                if (compact) {
                    withFrameNanos { }
                    optionsRequester.bringIntoView()
                }
            }
            Column(if (compact) Modifier.fillMaxWidth().verticalScroll(overflowScroll) else Modifier.fillMaxSize()) {
                SettingsHeader(setupStatus, onSetup)
                Spacer(Modifier.height(16.dp))
                AndroidView(factory = { state.preview }, update = { it.editMode = editing },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).wrapContentHeight(unbounded = true)
                        .onSizeChanged { previewHeight = with(density) { it.height.toDp() } }.clip(RoundedCornerShape(24.dp)))
                BoxWithConstraints((if (compact) Modifier.fillMaxWidth().height(200.dp) else Modifier.fillMaxWidth().weight(1f))
                    .bringIntoViewRequester(optionsRequester)) {
                    val listState = key(tab) { rememberLazyListState() }
                    val background = MaterialTheme.colorScheme.background
                    val layoutSpacer = (maxHeight - 232.dp).coerceAtLeast(0.dp)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clipToBounds(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (tab == 0) 20.dp else 16.dp,
                            bottom = if (tab == 0) 8.dp else 44.dp),
                        verticalArrangement = Arrangement.spacedBy(if (tab == 0) 16.dp else 12.dp)) {
                        when (tab) {
                            0 -> layoutOptions(state, editing, layoutSpacer, { editing = !editing }, onShare = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "My TailorKeys layout: ${state.preview.grid.toCode()}"), "Share layout"))
                            }, onImport = {
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                importCode = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                    ?.takeIf { KeyGrid.fromCode(it) != null }.orEmpty()
                                dialog = "import"
                            })
                            1 -> sizeOptions(state, page) { page = it }
                            2 -> lookOptions(state)
                            3 -> typingOptions(state, { dialog = "about" }, { dialog = "reset" })
                        }
                    }
                    if (listState.canScrollBackward) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                        .background(Brush.verticalGradient(listOf(background, Color.Transparent))))
                    if (listState.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, background))))
                }
            }
        }
    }
    when (dialog) {
        "import" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Import layout") }, text = {
            OutlinedTextField(importCode, { importCode = it }, label = { Text("Layout code") },
                placeholder = { Text("Paste a layout code (starts with TK1-)") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton({
            val grid = KeyGrid.fromCode(importCode)
            if (grid == null) Toast.makeText(context, "That isn't a TailorKeys layout code", Toast.LENGTH_SHORT).show()
            else { state.preview.replaceGrid(grid); dialog = null }
        }) { Text("Import") } }, dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } })
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

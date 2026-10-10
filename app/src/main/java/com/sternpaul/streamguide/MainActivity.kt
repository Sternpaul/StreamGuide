@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:androidx.media3.common.util.UnstableApi

package com.sternpaul.streamguide

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sternpaul.streamguide.core.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(application as StreamGuideApp) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { StreamGuideTheme { StreamGuideRoot(viewModel) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshGuideForCurrentTime(onResume = true)
                viewModel.appUpdates.onAppOpen(this@MainActivity)
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    viewModel.refreshGuideForCurrentTime()
                }
            }
        }
    }
}

@Composable private fun StreamGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Focus, background = Bg, surface = Panel, onBackground = TextPrimary, onSurface = TextPrimary, error = Color(0xFFFF6B6B)),
        typography = Typography(bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)),
        content = content
    )
}

@Composable private fun StreamGuideRoot(vm: MainViewModel) {
    val state = vm.state
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val playback = remember { PlaybackController(activity.applicationContext, scope, vm::recordDiagnosticError) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    // The stream must not keep running behind the Fire TV home screen.
    var started by remember { mutableStateOf(true) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) started = true else if (event == Lifecycle.Event.ON_STOP) started = false
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val playingChannel = state.playingChannel
    val playingUrl = state.playingUrl
    val playbackWanted = started && playingChannel != null && playingUrl != null && ScreenAwakePolicy.hostsPlayback(state.screen)
    LaunchedEffect(playbackWanted, playingUrl, state.provider) {
        if (playbackWanted && playingUrl != null && playingChannel != null) playback.play(playingUrl, playingChannel.displayName, state.provider)
        else playback.stop()
    }
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_000); exitArmed = false } }
    LaunchedEffect(state.screen) { exitArmed = false }
    LaunchedEffect(state.error) { if (state.error != null && state.screen != AppScreen.IMPORT_STATUS) { kotlinx.coroutines.delay(6_000); vm.clearError() } }
    val keepScreenOn = ScreenAwakePolicy.keepScreenOn(state.screen, playbackWanted && !playback.paused && !playback.failed)
    DisposableEffect(keepScreenOn, activity) {
        val window = activity.window
        if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepScreenOn) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(enabled = state.screen !in setOf(AppScreen.PLAYER, AppScreen.SETUP, AppScreen.IMPORT_STATUS)) {
        if (state.overlayMenu != OverlayMenu.NONE) {
            vm.closeOverlayMenu()
        } else {
            when (BackNavigationPolicy.action(state.screen, exitArmed)) {
                BackAction.GO_TO_LIVE_TV -> vm.navigate(AppScreen.GUIDE)
                BackAction.GO_TO_SETTINGS -> vm.navigate(AppScreen.SETTINGS)
                BackAction.ARM_EXIT -> exitArmed = true
                BackAction.EXIT -> activity.finish()
            }
        }
    }
    val dialogOpen = state.overlayMenu != OverlayMenu.NONE || state.pendingPinChannelId != null || vm.appUpdates.state.showDialog
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU &&
                vm.onRemoteOptionsPressed()
        }) {
            when (state.screen) {
                AppScreen.SETUP -> SetupScreen(null, vm::saveProvider)
                AppScreen.EDIT_PROVIDER -> SetupScreen(state.provider, vm::saveProvider)
                AppScreen.IMPORT_STATUS -> ImportStatusScreen(state, vm)
                AppScreen.PLAYER -> Box(Modifier.fillMaxSize().background(Color.Black))
                AppScreen.GUIDE -> GuideScreen(state, vm, playback, dialogOpen)
                AppScreen.SEARCH -> SearchScreen(state, vm)
                AppScreen.MULTIVIEW -> MultiviewScreen(state, vm)
                AppScreen.SETTINGS -> SettingsScreen(state, vm)
                AppScreen.DIAGNOSTICS -> DiagnosticsScreen(state, vm)
                AppScreen.ORGANIZE -> OrganizeScreen(state, vm)
            }
            // One video view for the whole app: it only changes size, so going back to the
            // guide shrinks the running picture into the corner instead of restarting it.
            if (playbackWanted) {
                val fullScreen = state.screen == AppScreen.PLAYER
                val frame = if (fullScreen) Modifier.fillMaxSize() else Modifier.align(Alignment.TopEnd).size(PreviewWidth, PreviewHeight)
                VideoSurface(playback.player, frame)
                if (!fullScreen) PreviewStatus(playback, frame)
            }
            if (state.screen == AppScreen.PLAYER) PlayerOverlay(state, vm, playback, dialogOpen)
            if (state.overlayMenu == OverlayMenu.CHANNEL) ChannelActionsMenu(state, vm)
            state.pendingPinChannelId?.let { ParentalPinDialog(vm::submitParentalPin, vm::cancelParentalPin) }
            if (vm.appUpdates.state.showDialog) AppUpdateDialog(vm.appUpdates)
            if (state.screen != AppScreen.IMPORT_STATUS) state.error?.let { ErrorBanner(it) }
            if (exitArmed) ExitConfirmationBanner()
        }
    }
}

@Composable private fun VideoSurface(player: ExoPlayer?, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }
        },
        modifier = modifier.background(Color.Black),
        onRelease = { it.player = null },
        update = { if (it.player !== player) it.player = player }
    )
}

@Composable private fun PreviewStatus(playback: PlaybackController, modifier: Modifier) {
    val label = when { playback.failed -> "Playback unavailable"; playback.paused -> "Paused"; else -> playback.info }
    if (label.isNotBlank()) Box(modifier, contentAlignment = Alignment.Center) {
        Text(label, Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xCC0A0D12)).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun ChannelActionsMenu(state: UiState, vm: MainViewModel) {
    val channel = state.selectedChannelId?.let { id -> state.channels.firstOrNull { it.id == id } }
    val group = state.focusedGroup ?: state.selectedGroup
    val firstFocus = remember { FocusRequester() }
    val hasGroupAction = state.optionsContext == OptionsContext.GROUP && group !in setOf("Favorites", "Recently watched")
    LaunchedEffect(state.optionsContext, channel?.id, group) { if (state.optionsContext == OptionsContext.CHANNEL && channel != null || hasGroupAction) firstFocus.requestFocus() }
    OverlayMenuPanel(Alignment.CenterEnd, if (state.optionsContext == OptionsContext.GROUP) "CATEGORY OPTIONS" else "CHANNEL OPTIONS", Modifier.width(350.dp), vm::closeOverlayMenu) {
        if (state.optionsContext == OptionsContext.GROUP) {
            Text(group, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
            if (hasGroupAction) {
                MenuDestination("Move category to top", Icons.Default.VerticalAlignTop, modifier = Modifier.focusRequester(firstFocus)) { vm.moveFocusedGroupToTop() }
                MenuDestination("Move category up", Icons.Default.KeyboardArrowUp) { vm.moveFocusedGroup(-1) }
                MenuDestination("Move category down", Icons.Default.KeyboardArrowDown) { vm.moveFocusedGroup(1) }
            } else {
                Text("No actions", color = TextMuted, modifier = Modifier.padding(10.dp))
            }
        } else if (channel == null) {
            Text("No channel", color = TextMuted, modifier = Modifier.padding(12.dp))
        } else {
            Text(channel.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
            MenuDestination(if (channel.favorite) "Remove favorite" else "Add favorite", if (channel.favorite) Icons.Default.Star else Icons.Default.StarBorder, modifier = Modifier.focusRequester(firstFocus)) { vm.toggleFavorite(channel.id); vm.closeOverlayMenu() }
            MenuDestination("Move channel to top", Icons.Default.VerticalAlignTop) { vm.moveChannelTo(channel.id, 1); vm.closeOverlayMenu() }
            MenuDestination("Move up", Icons.Default.KeyboardArrowUp) { vm.moveChannel(channel.id, -1); vm.closeOverlayMenu() }
            MenuDestination("Move down", Icons.Default.KeyboardArrowDown) { vm.moveChannel(channel.id, 1); vm.closeOverlayMenu() }
            MenuDestination(if (channel.locked) "Unlock channel" else "Lock channel", if (channel.locked) Icons.Default.LockOpen else Icons.Default.Lock) { vm.toggleLock(channel.id); vm.closeOverlayMenu() }
            MenuDestination("Add to Multiview", Icons.Default.GridView) { vm.addToMultiview(channel.id) }
        }
    }
}

@Composable private fun OverlayMenuPanel(alignment: Alignment, title: String, modifier: Modifier, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = alignment) {
        Column(modifier.fillMaxHeight().background(Panel).padding(horizontal = 18.dp, vertical = 28.dp)) {
            Text(title, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            Spacer(Modifier.height(8.dp)); content()
        }
    }
    }
}

@Composable private fun MenuDestination(label: String, icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    TvButton(onClick, selected = selected, modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f))
    }
}

@Composable private fun SearchRow(channel: Channel, programs: List<Program>, vm: MainViewModel) {
    LaunchedEffect(channel.id) { vm.ensurePrograms(channel.id) }
    var focused by remember { mutableStateOf(false) }
    val now = rememberNow()
    val upcoming = currentAndNext(channel, programs, now)
    val current = upcoming.firstOrNull()?.takeIf { it.startEpochMs <= now }
    val next = upcoming.firstOrNull { it !== current }
    val content = if (focused) OnFocus else TextPrimary
    val muted = if (focused) Color(0xFF3A4350) else TextMuted
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(if (focused) FocusFill else Cell)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.selectChannel(channel.id) }
            .combinedClickable(onClick = { vm.play(channel.id) }, onLongClick = { vm.toggleFavorite(channel.id) })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text((channel.providerOrder + 1).toString(), Modifier.width(44.dp), color = muted, fontSize = 12.sp, maxLines = 1)
        ChannelLogo(channel, 44.dp, 26.dp)
        Spacer(Modifier.width(12.dp)); Text(channel.displayName, Modifier.width(230.dp), color = content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(22.dp)) { if (channel.favorite) Icon(Icons.Default.Star, null, tint = Warning, modifier = Modifier.size(13.dp)) }
        Column(Modifier.weight(1.2f)) {
            Text(current?.title.orEmpty(), color = content, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current != null) ProgressLine(progress(current, now), Modifier.fillMaxWidth(0.9f).padding(top = 3.dp).height(2.dp), track = if (focused) Color(0x33000000) else Color(0x33FFFFFF))
        }
        Text(next?.let { "${time(it.startEpochMs)}  ${it.title}" }.orEmpty(), Modifier.weight(1f), color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SearchScreen(state: UiState, vm: MainViewModel) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvTextField(state.query, vm::setQuery, Modifier.weight(1f).focusRequester(fieldFocus), placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Spacer(Modifier.width(16.dp)); Text(state.visibleChannels.size.toString(), color = TextMuted, fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) { items(state.visibleChannels, key = { it.id }) { channel -> SearchRow(channel, state.programsFor(channel), vm) } }
    }
}

@Composable private fun MultiviewScreen(state: UiState, vm: MainViewModel) {
    val channels = state.multiviewIds.mapNotNull { id -> state.channels.firstOrNull { it.id == id } }
    var activeId by remember(channels) { mutableStateOf(channels.firstOrNull()?.id) }
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        if (channels.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.GridView, null, Modifier.size(56.dp), tint = TextMuted); Spacer(Modifier.height(14.dp)); Text("No channels", color = TextMuted, fontSize = 18.sp)
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                channels.chunked(2).forEach { rowChannels ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChannels.forEach { channel -> MultiTile(channel, state.provider, channel.id == activeId, { activeId = channel.id }, { vm.removeFromMultiview(channel.id) }, Modifier.weight(1f)) }
                        if (rowChannels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (channels.size < 4) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { state.visibleChannels.filter { it.id !in state.multiviewIds }.take(6).forEach { channel -> TvButton({vm.addToMultiview(channel.id)}) { Icon(Icons.Default.Add,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(channel.displayName,maxLines=1,overflow=TextOverflow.Ellipsis) } } }
        }
    }
}

@Composable private fun MultiTile(channel: Channel, provider: ProviderConfig?, active: Boolean, onActivate: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val player = remember(channel.url, provider) { PlaybackPlayerFactory.create(context, provider).apply { setMediaItem(MediaItem.fromUri(channel.url)); volume = if(active) 1f else 0f; prepare(); playWhenReady = true } }
    LaunchedEffect(active) { player.volume = if(active) 1f else 0f }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.background(Color.Black).onFocusChanged { focused=it.isFocused;if(it.isFocused)onActivate() }.combinedClickable(onClick=onActivate,onLongClick=onRemove)) {
        AndroidView(factory={ PlayerView(it).apply { this.player=player;useController=false;layoutParams=ViewGroup.LayoutParams(-1,-1) } },update={it.player=player},modifier=Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC10151C)).padding(11.dp),verticalAlignment=Alignment.CenterVertically) { if(active) Icon(Icons.AutoMirrored.Filled.VolumeUp,null,tint=Focus,modifier=Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(channel.displayName,Modifier.weight(1f),fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis) }
        if(focused || active) Box(Modifier.matchParentSize().border(3.dp, if(focused) FocusFill else Focus))
    }
}

@Composable private fun OrganizeScreen(state: UiState, vm: MainViewModel) {
    var filter by remember { mutableStateOf("") }
    var targetPosition by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editGroup by remember { mutableStateOf("") }
    val manual = remember(state.channels) { state.channels.sortedWith(ChannelOrdering.manual) }
    val positions = remember(manual) { HashMap<String, Int>(manual.size * 2).apply { manual.forEachIndexed { index, channel -> put(channel.id, index + 1) } } }
    val ordered = remember(manual, filter) { manual.filter { filter.isBlank() || it.displayName.contains(filter, true) } }
    val selectedId = state.selectedChannelId ?: ordered.firstOrNull()?.id
    val selectedChannel = state.channels.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId) { editName = selectedChannel?.customName.orEmpty(); editGroup = selectedChannel?.customGroup.orEmpty() }
    val selectedIndex = (positions[selectedId] ?: 0) - 1
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { filterFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        TvTextField(filter, { filter = it }, Modifier.fillMaxWidth().focusRequester(filterFocus), placeholder = { Text("Filter channels") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedIndex >= 0) "${selectedChannel?.displayName.orEmpty()}  ·  ${selectedIndex + 1} / ${state.channels.size}" else "", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TvButton({ selectedId?.let { vm.moveChannelTo(it, 1) } }) { Icon(Icons.Default.VerticalAlignTop, null); Text("Top") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, -10) } }) { Text("−10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, 10) } }) { Text("+10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannelTo(it, state.channels.size) } }) { Icon(Icons.Default.VerticalAlignBottom, null); Text("Bottom") }
            Spacer(Modifier.width(10.dp)); TvTextField(targetPosition, { targetPosition = it.filter(Char::isDigit).take(5) }, Modifier.width(120.dp), placeholder = { Text("Position") })
            Spacer(Modifier.width(6.dp)); TvButton({ val target = targetPosition.toIntOrNull(); if (target != null) selectedId?.let { vm.moveChannelTo(it, target) } }, selected = true) { Text("Move") }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(editName, { editName = it.take(80) }, Modifier.weight(1f), label = { Text("Custom channel name") }, placeholder = { Text(selectedChannel?.name.orEmpty()) })
            Spacer(Modifier.width(8.dp)); TvTextField(editGroup, { editGroup = it.take(80) }, Modifier.weight(1f), label = { Text("Custom group") }, placeholder = { Text(selectedChannel?.group.orEmpty()) })
            Spacer(Modifier.width(8.dp)); TvButton({ selectedId?.let { vm.customizeChannel(it, editName, editGroup) } }, selected = true) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(5.dp)); Text("Apply") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(ordered, key = { it.id }) { channel ->
                val actualPosition = positions[channel.id] ?: 0
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(52.dp).background(if (focused || selectedId == channel.id) RowActive else Cell)
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.selectChannel(channel.id) }.combinedClickable(onClick = { vm.selectChannel(channel.id) }).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actualPosition.toString(), Modifier.width(52.dp), color = TextMuted)
                    ChannelLogo(channel, 40.dp, 24.dp)
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(channel.displayName, color = if (channel.hidden) TextMuted else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.displayGroup, color = TextMuted, fontSize = 11.sp) }
                    TvButton({ vm.moveChannel(channel.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.moveChannel(channel.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.toggleHidden(channel.id) }, selected = !channel.hidden) { Icon(if (channel.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null); Spacer(Modifier.width(5.dp)); Text(if (channel.hidden) "Hidden" else "Visible") }
                }
            }
        }
    }
}

@Composable private fun AppUpdateDialog(updates: AppUpdateController) {
    val state = updates.state
    val activity = LocalContext.current as ComponentActivity
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.downloading) { firstFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = updates::dismiss,
        title = { Text("StreamGuide ${state.release?.version.orEmpty()} is available") },
        text = {
            Column {
                Text(if (state.downloading) "Downloading update · ${state.progress}%" else
                    "Install the latest version. Your playlist, favorites and settings will be kept.")
                if (state.downloading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                else Text("Confirm Install on the next screen, then choose Open to return to StreamGuide.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            if (!state.downloading) TvButton({ updates.update(activity) }, modifier = Modifier.focusRequester(firstFocus)) {
                Text(if (state.installer != null) "Install" else "Update now")
            }
        },
        dismissButton = {
            TvButton(updates::dismiss, modifier = if (state.downloading) Modifier.focusRequester(firstFocus) else Modifier) {
                Text(if (state.downloading) "Cancel download" else "Later")
            }
        }
    )
}

@Composable private fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val updates = vm.appUpdates.state
    val firstFocus = remember { FocusRequester() }
    // Lazy items attach during layout, one step after this effect could first run.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SettingsCard("APP UPDATES · ${BuildConfig.VERSION_NAME}") {
                SettingToggleRow(Icons.Default.SystemUpdate, "Automatic update checks", "", updates.automatic, Modifier.focusRequester(firstFocus)) {
                    vm.appUpdates.setAutomatic(!updates.automatic)
                }
                SettingRow(Icons.Default.Refresh, "Check for app updates",
                    updates.message, vm.appUpdates::check)
            }
        }
        item {
            SettingsCard("GUIDE WIDTH") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.allowedTimelineHours.sorted().forEach { hours ->
                        TvButton({ vm.setTimelineHours(hours) }, selected = state.timelineHours == hours) { Text("${hours} hours") }
                    }
                }
            }
        }
        item {
            SettingsCard("TV GUIDE UPDATES") {
                SettingToggleRow(
                    Icons.Default.Update,
                    "Automatic EPG updates",
                    "Every ${state.epgHours}h in the background",
                    state.epgAutoUpdate
                ) { vm.setEpgAutoUpdate(!state.epgAutoUpdate) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSettings.allowedEpgHours.sorted().forEach { hours ->
                            TvButton({ vm.setEpgHours(hours) }, selected = state.epgHours == hours) { Text("Every ${hours}h") }
                        }
                    }
                }
                SettingRow(Icons.Default.Refresh, "Update EPG now", "", vm::refreshEpgOnly)
            }
        }
        item {
            SettingsCard("DIAGNOSTICS") {
                SettingRow(Icons.Default.Analytics, "Diagnostics", "") { vm.navigate(AppScreen.DIAGNOSTICS) }
            }
        }
        item {
            SettingsCard("PLAYLIST") {
                SettingRow(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    state.provider?.name ?: "No playlist",
                    when (state.provider?.type) { ProviderType.XTREAM -> "Xtream Codes"; ProviderType.M3U -> "M3U / M3U8"; else -> "" }
                ) { vm.navigate(AppScreen.EDIT_PROVIDER) }
                SettingToggleRow(
                    Icons.Default.PowerSettingsNew,
                    "Update playlist on start",
                    "",
                    state.updatePlaylistOnStart
                ) { vm.setUpdatePlaylistOnStart(!state.updatePlaylistOnStart) }
                SettingRow(Icons.Default.Refresh, "Update playlist and EPG now", state.status.message, vm::refresh)
                SettingRow(Icons.Default.Tune, "Manage channels", "") { vm.navigate(AppScreen.ORGANIZE) }
            }
        }
        item {
            TvButton(vm::clearProvider, danger = true) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text("Remove playlist and local data") }
        }
    }
}

@Composable private fun DiagnosticsScreen(state: UiState, vm: MainViewModel) {
    var errorLogOpen by remember { mutableStateOf(false) }
    if (errorLogOpen) {
        DiagnosticErrorLog(state.diagnosticErrors, vm::clearDiagnosticErrors) { errorLogOpen = false }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.refreshEpgDiagnostics(); withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(vm::refreshEpgDiagnostics, modifier = Modifier.focusRequester(firstFocus)) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(7.dp)); Text("Refresh")
                }
                TvButton({ errorLogOpen = true }) {
                    Icon(Icons.Default.History, null); Spacer(Modifier.width(7.dp)); Text("Error log · ${state.diagnosticErrors.size}")
                }
            }
        }
        item {
            SettingsCard("STATUS") {
                Column(Modifier.padding(16.dp)) {
                    StatusLine("Channels", state.channels.size.toString())
                    StatusLine("Programmes", state.status.programCount.toString())
                    StatusLine("EPG automatic update", if (state.epgAutoUpdate) "On · every ${state.epgHours}h" else "Off")
                    StatusLine("Last successful update", diagnosticTime(state.status.lastSuccessEpochMs))
                }
            }
        }
        item {
            SettingsCard("EPG MAPPING") {
                when {
                    state.diagnosticsLoading && state.epgDiagnostics == null -> Text("Calculating EPG mapping…", color = TextMuted, modifier = Modifier.padding(16.dp))
                    state.diagnosticsError.isNotBlank() && state.epgDiagnostics == null -> Text(state.diagnosticsError, color = Color(0xFFFF9AA5), modifier = Modifier.padding(16.dp))
                    state.epgDiagnostics != null -> EpgDiagnosticsContent(state.epgDiagnostics)
                    else -> Text("No EPG mapping diagnostics available yet.", color = TextMuted, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable private fun SettingsCard(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().background(Panel)){Text(title,color=TextMuted,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=16.dp,top=10.dp,bottom=6.dp));content()}}
@Composable private fun SettingRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){TvButton(onClick,modifier=Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title);if(subtitle.isNotBlank())Text(subtitle,color=LocalMutedColor.current,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}}}
@Composable private fun SettingToggleRow(icon:ImageVector,title:String,subtitle:String,enabled:Boolean,modifier:Modifier=Modifier,onToggle:()->Unit){TvButton(onToggle,modifier=modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title);if(subtitle.isNotBlank())Text(subtitle,color=LocalMutedColor.current,fontSize=12.sp,maxLines=2)};Text(if(enabled) "ON" else "OFF",color=if(enabled) Color(0xFF1F9D6B) else LocalMutedColor.current,fontWeight=FontWeight.Bold)}}
@Composable private fun StatusLine(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(label,color=TextMuted);Spacer(Modifier.weight(1f));Text(value,fontWeight=FontWeight.Medium)}}
@Composable private fun EpgDiagnosticsContent(diagnostics: EpgDiagnostics) {
    Column(Modifier.padding(16.dp)) {
        StatusLine("Channels with guide", "${diagnostics.channelsWithEpg} / ${diagnostics.totalChannels} (${diagnostics.channelCoveragePercent}%)")
        StatusLine("Mapped programmes", "${diagnostics.matchedPrograms} / ${diagnostics.totalPrograms} (${diagnostics.programMatchPercent}%)")
        StatusLine("Recognized EPG channel IDs", "${diagnostics.matchedEpgChannelIds} / ${diagnostics.totalEpgChannelIds} (${diagnostics.epgIdMatchPercent}%)")
        StatusLine("Currently airing", diagnostics.currentlyAiringPrograms.toString())
        StatusLine("Starting in next 24 hours", diagnostics.upcomingPrograms24h.toString())
        StatusLine("Guide data range", guideRange(diagnostics.guideStartEpochMs, diagnostics.guideEndEpochMs))
        StatusLine("Channels missing TVG-ID", diagnostics.channelsWithoutTvgId.toString())
        StatusLine("Duplicated TVG-IDs", diagnostics.duplicateTvgIds.toString())
        StatusLine("Last EPG success", diagnosticTime(diagnostics.lastEpgSuccessEpochMs))
        StatusLine("Last EPG duration", diagnosticDuration(diagnostics.lastEpgDurationMs))
        StatusLine("Last full-update duration", diagnosticDuration(diagnostics.lastFullRefreshDurationMs))
        if (diagnostics.unmatchedEpgIds.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF27303C))
            Text("UNMATCHED EPG IDS", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(diagnostics.unmatchedEpgIds.joinToString(" · ") { "${it.id} (${it.count})" }, fontSize = 12.sp)
        }
        if (diagnostics.channelsWithoutEpg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("CHANNELS WITHOUT GUIDE DATA", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(diagnostics.channelsWithoutEpg.joinToString(" · "), fontSize = 12.sp)
        }
        if (diagnostics.lastError.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("LAST UPDATE WARNING", color = Color(0xFFFF9AA5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(diagnostics.lastError, color = Color(0xFFFFC1C7), fontSize = 12.sp)
        }
    }
}
@Composable private fun DiagnosticErrorLog(entries: List<DiagnosticLogEntry>, onClear: () -> Unit, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val logFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 80.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { logFocus.requestFocus() }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 32.dp, vertical = 22.dp)) {
            Row(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) logFocus.requestFocus()
                    true
                } else false
            }, verticalAlignment = Alignment.CenterVertically) {
                TvButton(onClose, modifier = Modifier.focusRequester(backFocus)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(7.dp)); Text("Back")
                }
                Spacer(Modifier.width(18.dp))
                Text("Error log", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    TvButton({ onClear(); logFocus.requestFocus() }, danger = true) {
                        Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(7.dp)); Text("Clear log")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // A single focusable viewport scrolls by pixels, so even an entry taller
            // than the screen can be read without focus skipping to its end.
            Column(Modifier.weight(1f).fillMaxWidth()
                .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(8.dp))
                .focusRequester(logFocus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent.keyCode
                    if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            if (key == KeyEvent.KEYCODE_DPAD_UP && scroll.value == 0) backFocus.requestFocus()
                            else scope.launch { scroll.scrollBy(if (key == KeyEvent.KEYCODE_DPAD_UP) -scrollStep else scrollStep) }
                        }
                        true
                    } else false
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(18.dp)) {
                if (entries.isEmpty()) Text("No recorded errors.", color = TextMuted)
                entries.forEachIndexed { index, entry ->
                    Text("${diagnosticTime(entry.timestampEpochMs)} · ${entry.area}", color = Color(0xFFFF9AA5), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.message, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(bottom = 16.dp), color = Panel2)
                }
            }
        }
    }
}
private fun diagnosticTime(epochMs: Long): String = if (epochMs <= 0) "Never" else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
private fun diagnosticDuration(durationMs: Long): String = when {
    durationMs <= 0 -> "Not measured"
    durationMs < 1_000 -> "${durationMs} ms"
    else -> String.format(Locale.US, "%.1f s", durationMs / 1_000.0)
}
private fun guideRange(startMs: Long, endMs: Long): String = if (startMs <= 0 || endMs <= 0) "No data" else "${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(startMs))} – ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(endMs))}"

@Composable private fun ImportStatusScreen(state: UiState, vm: MainViewModel) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.importLog.size) { if (state.importLog.isNotEmpty()) listState.animateScrollToItem(state.importLog.lastIndex) }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(state.importFinished, state.error != null) { if (state.importFinished || state.error != null) { withFrameNanos { }; actionFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).background(if(state.error==null) Focus else Color(0xFF8A3038)), contentAlignment = Alignment.Center) { Icon(if(state.error==null) Icons.Default.CloudDownload else Icons.Default.Error, null, Modifier.size(30.dp)) }
            Spacer(Modifier.width(16.dp)); Column { Text(if(state.importFinished) "Your TV is ready" else if(state.error!=null) "Setup needs attention" else "Setting up ${state.provider?.name.orEmpty()}", fontSize=29.sp, fontWeight=FontWeight.SemiBold); if(state.importFinished) Text("${state.channels.size} channels", color=TextMuted) }
            Spacer(Modifier.weight(1f)); if(state.loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth=3.dp)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).fillMaxWidth().background(Panel).padding(18.dp)) {
            LazyColumn(state=listState, verticalArrangement=Arrangement.spacedBy(7.dp)) { itemsIndexed(state.importLog) { index, message -> Row(verticalAlignment=Alignment.CenterVertically) { Icon(if(message.startsWith("ERROR")) Icons.Default.Error else if(index==state.importLog.lastIndex && state.loading) Icons.Default.Sync else Icons.Default.CheckCircle, null, Modifier.size(17.dp), tint=if(message.startsWith("ERROR")) MaterialTheme.colorScheme.error else if(index==state.importLog.lastIndex && state.loading) Focus else Success);Spacer(Modifier.width(10.dp));Text(message,color=if(message.startsWith("ERROR")) MaterialTheme.colorScheme.error else TextPrimary,fontSize=14.sp) } } }
        }
        state.error?.let { Spacer(Modifier.height(12.dp)); Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFF632A32),border=BorderStroke(1.dp,Color(0xFFFF7785))){Column(Modifier.fillMaxWidth().padding(14.dp)){Text(it,fontWeight=FontWeight.SemiBold)}} }
        Spacer(Modifier.height(16.dp)); Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            if(state.importFinished) TvButton(vm::finishImport, modifier=Modifier.focusRequester(actionFocus), selected=true) { Icon(Icons.Default.LiveTv,null);Spacer(Modifier.width(7.dp));Text("Open Live TV") }
            if(state.error!=null) { TvButton(vm::retryImport,modifier=Modifier.focusRequester(actionFocus),selected=true){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Try again")};TvButton(vm::editProviderFromImport){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(7.dp));Text("Edit details")} }
        }
    }
}

@Composable private fun SetupScreen(existing: ProviderConfig?, onSave: (ProviderConfig)->Unit) {
    var type by remember(existing) { mutableStateOf(existing?.type ?: ProviderType.XTREAM) }; var name by remember(existing) { mutableStateOf(existing?.name ?: "My TV") }; var playlist by remember(existing) { mutableStateOf(existing?.playlistUrl.orEmpty()) }; var server by remember(existing) { mutableStateOf(existing?.serverUrl.orEmpty()) }; var username by remember(existing) { mutableStateOf(existing?.username.orEmpty()) }; var password by remember(existing) { mutableStateOf(existing?.password.orEmpty()) }; var epg by remember(existing) { mutableStateOf(existing?.epgUrl.orEmpty()) }; var userAgent by remember(existing) { mutableStateOf(existing?.userAgent.orEmpty()) }; var referer by remember(existing) { mutableStateOf(existing?.referer.orEmpty()) }; var validation by remember { mutableStateOf<String?>(null) }
    var additionalOpen by remember { mutableStateOf(false) }
    val providerTypeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { providerTypeFocus.requestFocus() }
    val context = LocalContext.current
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; playlist = uri.toString() }
    }
    Row(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 120.dp, vertical = 28.dp)) {
            Text(if (existing == null) "Add playlist" else "Edit playlist", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton({ type = ProviderType.XTREAM }, modifier = Modifier.focusRequester(providerTypeFocus), selected = type == ProviderType.XTREAM) { Text("Xtream Codes") }
                TvButton({ type = ProviderType.M3U }, selected = type == ProviderType.M3U) { Text("M3U / M3U8") }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SetupField("Playlist name", name, { name = it })
                if (type == ProviderType.M3U) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SetupField("M3U playlist URL or local file", playlist, { playlist = it }) }
                        Spacer(Modifier.width(8.dp))
                        TvButton({ filePicker.launch(arrayOf("application/x-mpegURL", "audio/x-mpegurl", "text/plain", "*/*")) }) { Text("Choose file") }
                    }
                } else {
                    SetupField("Server URL", server, { server = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { SetupField("Username", username, { username = it }) }
                        Box(Modifier.weight(1f)) { SetupField("Password", password, { password = it }, true) }
                    }
                }
                TvButton({ additionalOpen = !additionalOpen }) { Icon(if (additionalOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null); Spacer(Modifier.width(6.dp)); Text("EPG and headers") }
                AnimatedVisibility(additionalOpen) {
                    Column(Modifier.padding(top = 8.dp)) {
                        SetupField("XMLTV EPG URL (optional)", epg, { epg = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { SetupField("Custom user agent (optional)", userAgent, { userAgent = it }) }
                            Box(Modifier.weight(1f)) { SetupField("HTTP referer (optional)", referer, { referer = it }) }
                        }
                    }
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TvButton({
                    val valid = if (type == ProviderType.M3U) ProviderValidation.isM3uValid(playlist) else ProviderValidation.isXtreamValid(server, username, password)
                    if (!valid) validation = if (type == ProviderType.XTREAM) "Enter an HTTP or HTTPS server address, username, and password" else "Enter an HTTP/HTTPS playlist URL or choose a local file"
                    else onSave(ProviderConfig(type, name.ifBlank { "My TV" }, playlist, server, username, password, epg, userAgent, referer))
                }, selected = true, modifier = Modifier.width(240.dp)) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SetupField(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false) {
    TvTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        label = { Text(label) },
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    val activation = remember { PressToEditState() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { activation.onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isSelect = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                event.nativeKeyEvent.action == KeyEvent.ACTION_UP && isSelect && activation.onPress()
            },
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        singleLine = true,
        readOnly = activation.isReadOnly,
        visualTransformation = visualTransformation
    )
}


@Composable private fun ParentalPinDialog(onSubmit:(String)->Unit,onCancel:()->Unit){var pin by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onCancel,icon={Icon(Icons.Default.Lock,null)},title={Text("Parental control")},text={Column{Text("Enter the PIN to watch this channel.",color=TextMuted);Spacer(Modifier.height(12.dp));TvTextField(pin,{pin=it.filter(Char::isDigit).take(12)},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),placeholder={Text("PIN")})}},confirmButton={TvButton({onSubmit(pin)},selected=true){Text("Unlock")}},dismissButton={TvButton(onCancel){Text("Cancel")}})}
@Composable private fun ExitConfirmationBanner(){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(Modifier.padding(24.dp),shape=RoundedCornerShape(8.dp),color=Panel2,border=BorderStroke(1.dp,Focus)){Text("Press Back again to exit",modifier=Modifier.padding(horizontal=18.dp,vertical=12.dp),fontWeight=FontWeight.SemiBold)}}}
@Composable private fun ErrorBanner(message:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(Modifier.padding(24.dp),shape=RoundedCornerShape(6.dp),color=Color(0xFF632A32),border=BorderStroke(1.dp,Color(0xFFFF7785))){Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Error,null);Spacer(Modifier.width(10.dp));Text(message)}}}}


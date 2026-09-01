@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.sternpaul.streamguide.core.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

private val Bg = Color(0xFF080B0F)
private val Panel = Color(0xFF10151C)
private val Panel2 = Color(0xFF171E28)
private val Focus = Color(0xFF2F80ED)
private val TextPrimary = Color(0xFFF3F6FA)
private val TextMuted = Color(0xFFA9B4C4)
private val Success = Color(0xFF36B37E)
private val Warning = Color(0xFFF4B740)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(application as StreamGuideApp) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { StreamGuideTheme { StreamGuideRoot(viewModel) } }
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
    val activity = LocalContext.current as? android.app.Activity
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_000); exitArmed = false } }
    LaunchedEffect(state.screen) { exitArmed = false }
    BackHandler(enabled = state.screen !in setOf(AppScreen.PLAYER, AppScreen.SETUP, AppScreen.EDIT_PROVIDER, AppScreen.IMPORT_STATUS)) {
        if (state.overlayMenu != OverlayMenu.NONE) {
            vm.closeOverlayMenu()
        } else {
            when (BackNavigationPolicy.action(state.screen, exitArmed)) {
                BackAction.GO_TO_LIVE_TV -> vm.navigate(AppScreen.GUIDE)
                BackAction.ARM_EXIT -> exitArmed = true
                BackAction.EXIT -> activity?.finish()
            }
        }
    }
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
                AppScreen.PLAYER -> PlayerScreen(state, vm)
                else -> Column {
                    AppHeader(state, vm)
                    when (state.screen) {
                        AppScreen.GUIDE -> GuideScreen(state, vm)
                        AppScreen.SEARCH -> SearchScreen(state, vm)
                        AppScreen.MULTIVIEW -> MultiviewScreen(state, vm)
                        AppScreen.SETTINGS -> SettingsScreen(state, vm)
                        AppScreen.ORGANIZE -> OrganizeScreen(state, vm)
                        else -> Unit
                    }
                }
            }
            when (state.overlayMenu) {
                OverlayMenu.APP -> AppNavigationMenu(state, vm)
                OverlayMenu.CHANNEL -> ChannelActionsMenu(state, vm)
                OverlayMenu.NONE -> Unit
            }
            state.pendingPinChannelId?.let { ParentalPinDialog(vm::submitParentalPin, vm::cancelParentalPin) }
            if (state.screen != AppScreen.IMPORT_STATUS) state.error?.let { ErrorBanner(it, vm::clearError) }
            if (exitArmed) ExitConfirmationBanner()
        }
    }
}

@Composable private fun AppHeader(state: UiState, vm: MainViewModel) {
    val title = when (state.screen) {
        AppScreen.GUIDE -> "Live TV"
        AppScreen.SEARCH -> "Search"
        AppScreen.MULTIVIEW -> "Multiview"
        AppScreen.SETTINGS -> "Settings"
        AppScreen.ORGANIZE -> "Manage channels"
        else -> "StreamGuide"
    }
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(Color(0xF20B0F14)).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvButton(vm::toggleAppMenu, selected = state.overlayMenu == OverlayMenu.APP) {
            Icon(Icons.Default.Menu, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Menu")
        }
        Spacer(Modifier.width(18.dp)); Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (state.loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Updating", color = TextMuted, fontSize = 14.sp); Spacer(Modifier.width(18.dp)) }
        Clock()
    }
}

@Composable private fun AppNavigationMenu(state: UiState, vm: MainViewModel) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    OverlayMenuPanel(Alignment.CenterStart, "STREAMGUIDE", Modifier.width(310.dp)) {
        MenuDestination("Live TV", Icons.Default.LiveTv, selected = state.screen == AppScreen.GUIDE, modifier = Modifier.focusRequester(firstFocus)) { vm.navigate(AppScreen.GUIDE) }
        MenuDestination("Search", Icons.Default.Search, selected = state.screen == AppScreen.SEARCH) { vm.navigate(AppScreen.SEARCH) }
        MenuDestination("Multiview", Icons.Default.GridView, selected = state.screen == AppScreen.MULTIVIEW) { vm.navigate(AppScreen.MULTIVIEW) }
        MenuDestination("Settings", Icons.Default.Settings, selected = state.screen == AppScreen.SETTINGS || state.screen == AppScreen.ORGANIZE) { vm.navigate(AppScreen.SETTINGS) }
    }
}

@Composable private fun ChannelActionsMenu(state: UiState, vm: MainViewModel) {
    val channel = state.selectedChannelId?.let { id -> state.channels.firstOrNull { it.id == id } }
    val group = state.focusedGroup ?: state.selectedGroup
    val firstFocus = remember { FocusRequester() }
    val hasGroupAction = state.optionsContext == OptionsContext.GROUP && group !in setOf("All channels", "Favorites")
    LaunchedEffect(state.optionsContext, channel?.id, group) { if (state.optionsContext == OptionsContext.CHANNEL && channel != null || hasGroupAction) firstFocus.requestFocus() }
    OverlayMenuPanel(Alignment.CenterEnd, if (state.optionsContext == OptionsContext.GROUP) "CATEGORY OPTIONS" else "CHANNEL OPTIONS", Modifier.width(350.dp)) {
        if (state.optionsContext == OptionsContext.GROUP) {
            Text(group, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
            if (hasGroupAction) {
                MenuDestination("Move category to top", Icons.Default.VerticalAlignTop, modifier = Modifier.focusRequester(firstFocus)) { vm.moveFocusedGroupToTop() }
                MenuDestination("Move category up", Icons.Default.KeyboardArrowUp) { vm.moveFocusedGroup(-1) }
                MenuDestination("Move category down", Icons.Default.KeyboardArrowDown) { vm.moveFocusedGroup(1) }
            } else {
                Text("This built-in category cannot be reordered.", color = TextMuted, modifier = Modifier.padding(10.dp))
            }
        } else if (channel == null) {
            Text("Select a channel first", color = TextMuted, modifier = Modifier.padding(12.dp))
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

@Composable private fun OverlayMenuPanel(alignment: Alignment, title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = alignment) {
        Column(modifier.fillMaxHeight().background(Panel).padding(horizontal = 18.dp, vertical = 28.dp)) {
            Text(title, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            Spacer(Modifier.height(8.dp)); content()
            Spacer(Modifier.weight(1f)); Text("Back closes this menu", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable private fun MenuDestination(label: String, icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    TvButton(onClick, selected = selected, modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f))
    }
}

@Composable private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = Date() } }
    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
}

@Composable private fun GuideScreen(state: UiState, vm: MainViewModel) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
        GroupRail(state, vm)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GuideToolbar(state)
            Spacer(Modifier.height(10.dp))
            GuideHeader(state.timelineStart, state.timelineHours)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(state.visibleChannels, key = { it.id }) { channel ->
                    TimelineChannelRow(channel, state.programsFor(channel), state.timelineStart, state.timelineHours, channel.id == state.selectedChannelId, vm)
                }
                if (state.visibleChannels.isEmpty()) item { EmptyGuide(state.provider != null, vm::refresh) }
            }
            state.selectedChannelId?.let { id ->
                state.channels.firstOrNull { it.id == id }?.let { channel ->
                    val channelPrograms = state.programsFor(channel)
                    val selected = state.selectedProgram?.takeIf { it.channelId == channel.id || it.channelId == channel.tvgId }
                    if (selected != null) ProgrammeDetailsBar(channel, selected, vm)
                    else SelectedChannelBar(channel, currentAndNext(channel, channelPrograms).firstOrNull(), lastCatchup(channel, channelPrograms), vm)
                }
            }
        }
    }
}

@Composable private fun GroupRail(state: UiState, vm: MainViewModel) {
    Column(Modifier.width(225.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Panel).padding(10.dp)) {
        Text(state.provider?.name ?: "PLAYLIST", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.groups) { group ->
                val count = state.channelCountForGroup(group)
                TvButton({ vm.selectGroup(group) }, selected = state.selectedGroup == group, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.focusGroup(group) }) {
                    Icon(if(group=="Favorites") Icons.Default.Star else Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(group, Modifier.weight(1f), maxLines=1, overflow=TextOverflow.Ellipsis); Text(count.toString(), color=TextMuted, fontSize=12.sp)
                }
            }
        }
    }
}

@Composable private fun GuideToolbar(state: UiState) {
    Column { Text(state.selectedGroup, fontSize = 27.sp, fontWeight = FontWeight.SemiBold); Text("${state.visibleChannels.size} channels", color = TextMuted, fontSize = 14.sp) }
}

@Composable private fun GuideHeader(windowStart: Long, hours: Int) {
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(300.dp))
        repeat(hours * 2) { index ->
            val instant = windowStart + index * 1_800_000L
            val isNow = System.currentTimeMillis() in instant until instant + 1_800_000L
            Text(if (isNow) "NOW  ${time(instant)}" else time(instant), modifier = Modifier.weight(1f).padding(start = 7.dp), color = if (isNow) Focus else TextMuted, fontSize = 13.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable private fun TimelineChannelRow(channel: Channel, programs: List<Program>, windowStart: Long, hours: Int, selected: Boolean, vm: MainViewModel) {
    val windowEnd = windowStart + hours * 3_600_000L
    val slices = GuideTimeline.slices(channel, programs, windowStart, windowEnd)
    Row(Modifier.fillMaxWidth().height(74.dp), verticalAlignment = Alignment.CenterVertically) {
        var channelFocused by remember { mutableStateOf(false) }
        Row(
            Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(if (channelFocused || selected) Color(0xFF1C2D43) else Panel)
                .onFocusChanged { channelFocused = it.isFocused; if (it.isFocused) vm.selectProgram(channel.id, null) }
                .focusable().combinedClickable(onClick = { vm.play(channel.id) }, onLongClick = { vm.toggleFavorite(channel.id) })
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (channelFocused || selected) Focus else Color.Transparent)); Spacer(Modifier.width(9.dp))
            Text((channel.providerOrder + 1).toString(), Modifier.width(32.dp), color = TextMuted, fontSize = 12.sp)
            if (channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(36.dp).padding(3.dp)) else Box(Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment = Alignment.Center) { Text(channel.displayName.take(1), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(9.dp)); Text(channel.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.favorite) Icon(Icons.Default.Star, null, tint = Warning, modifier = Modifier.size(14.dp))
            if (channel.locked) Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(3.dp))
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            slices.forEach { slice ->
                val weight = (slice.visibleEnd - slice.visibleStart).coerceAtLeast(1L).toFloat()
                if (slice.program == null) {
                    Box(Modifier.weight(weight).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Panel))
                } else {
                    TimelineProgramCell(channel, slice.program, weight, vm)
                }
            }
        }
    }
}

@Composable private fun RowScope.TimelineProgramCell(channel: Channel, program: Program, weight: Float, vm: MainViewModel) {
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val active = now in program.startEpochMs until program.endEpochMs
    val past = program.endEpochMs <= now
    Column(
        Modifier.weight(weight).fillMaxHeight().clip(RoundedCornerShape(5.dp))
            .background(if (focused) Color(0xFF29496F) else if (active) Color(0xFF1A3658) else Panel2)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.selectProgram(channel.id, program) }
            .focusable().combinedClickable(onClick = { vm.selectProgram(channel.id, program) })
            .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center
    ) {
        Text(program.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${time(program.startEpochMs)}–${time(program.endEpochMs)}", color = TextMuted, fontSize = 10.sp)
            if (past && channel.catchupSource.isNotBlank()) { Spacer(Modifier.width(5.dp)); Icon(Icons.Default.Replay, null, tint = Focus, modifier = Modifier.size(12.dp)) }
        }
        if (active) LinearProgressIndicator(progress = { ((now - program.startEpochMs).toFloat() / (program.endEpochMs - program.startEpochMs)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp), color = Focus, trackColor = Color(0xFF32465E))
    }
}

@Composable private fun GuideChannelRow(channel: Channel, programs: List<Program>, selected: Boolean, vm: MainViewModel) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused || selected) Focus else Color.Transparent
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(7.dp)).background(if(focused) Color(0xFF1C2D43) else Panel)
            .onFocusChanged { focused = it.isFocused; if(it.isFocused) vm.selectChannel(channel.id) }.focusable()
            .combinedClickable(onClick = { vm.play(channel.id) }, onLongClick = { vm.toggleFavorite(channel.id) })
            .then(Modifier.padding(1.dp)).background(Color.Transparent).padding(horizontal=12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(borderColor)); Spacer(Modifier.width(10.dp))
        Text((channel.providerOrder + 1).toString(), Modifier.width(34.dp), color=TextMuted, fontSize=13.sp)
        if(channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(38.dp).padding(3.dp)) else Box(Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment=Alignment.Center){Text(channel.displayName.take(1),fontWeight=FontWeight.Bold)}
        Spacer(Modifier.width(10.dp)); Text(channel.displayName, Modifier.width(155.dp), fontWeight=FontWeight.Medium, maxLines=1, overflow=TextOverflow.Ellipsis)
        if(channel.favorite) Icon(Icons.Default.Star,null,tint=Warning,modifier=Modifier.size(15.dp)); if(channel.locked) Icon(Icons.Default.Lock,null,tint=TextMuted,modifier=Modifier.size(15.dp)); Spacer(Modifier.width(8.dp))
        val now = System.currentTimeMillis()
        repeat(2) { index ->
            val p = programs.getOrNull(index)
            ProgramCell(p, now, Modifier.weight(if(index==0) 1.25f else 1f))
            if(index==0) Spacer(Modifier.width(3.dp))
        }
    }
}

@Composable private fun ProgramCell(program: Program?, now: Long, modifier: Modifier) {
    val active = program != null && now in program.startEpochMs until program.endEpochMs
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if(active) Color(0xFF1A3658) else Panel2).padding(horizontal=13.dp, vertical=9.dp), verticalArrangement=Arrangement.Center) {
        Text(program?.title ?: "No programme information", color=if(program==null) TextMuted else TextPrimary, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=15.sp)
        if(program!=null) Text("${time(program.startEpochMs)} – ${time(program.endEpochMs)}", color=TextMuted, fontSize=11.sp)
        if(active) LinearProgressIndicator(progress={((now-program!!.startEpochMs).toFloat()/(program.endEpochMs-program.startEpochMs)).coerceIn(0f,1f)}, Modifier.fillMaxWidth().padding(top=5.dp).height(2.dp), color=Focus, trackColor=Color(0xFF32465E))
    }
}

@Composable private fun ProgrammeDetailsBar(channel: Channel, program: Program, vm: MainViewModel) {
    val now = System.currentTimeMillis()
    val past = program.endEpochMs <= now
    val live = now in program.startEpochMs until program.endEpochMs
    Row(Modifier.fillMaxWidth().height(88.dp).padding(top = 8.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(program.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp)); Text("${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(program.startEpochMs))}  ${time(program.startEpochMs)}–${time(program.endEpochMs)}", color = if (live) Focus else TextMuted, fontSize = 12.sp)
            }
            Text(program.description.ifBlank { channel.displayName }, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        if (past && channel.catchupSource.isNotBlank() && channel.catchupDays > 0) {
            TvButton({ vm.playCatchup(channel, program) }, selected = true) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text("Play catch-up") }
        } else if (live) {
            TvButton({ vm.play(channel.id) }, selected = true) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Watch live") }
        } else {
            Text("Upcoming", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable private fun SelectedChannelBar(channel: Channel, program: Program?, previous: Program?, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(top=8.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(program?.title ?: channel.displayName, fontWeight=FontWeight.SemiBold, maxLines=1); Text(program?.description?.ifBlank { channel.displayGroup } ?: channel.displayGroup, color=TextMuted, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=12.sp) }
        if(previous != null && channel.catchupSource.isNotBlank()) { TvButton({vm.playCatchup(channel,previous)}) { Icon(Icons.Default.Replay,null); Spacer(Modifier.width(4.dp)); Text("Catch-up") }; Spacer(Modifier.width(6.dp)) }
        TvButton({vm.play(channel.id)}, selected=true) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(4.dp)); Text("Watch") }
    }
}

@Composable private fun SearchScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(30.dp)) {
        Text("Search", fontSize=28.sp, fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(16.dp))
        TvTextField(state.query, vm::setQuery, Modifier.fillMaxWidth(), placeholder={Text("Channels and programmes")}, leadingIcon={Icon(Icons.Default.Search,null)})
        Spacer(Modifier.height(18.dp)); Text("${state.visibleChannels.size} results", color=TextMuted)
        Spacer(Modifier.height(10.dp)); LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) { items(state.visibleChannels,key={it.id}) { channel -> GuideChannelRow(channel,currentAndNext(channel,state.programsFor(channel)),false,vm) } }
    }
}

@Composable private fun MultiviewScreen(state: UiState, vm: MainViewModel) {
    val channels = state.multiviewIds.mapNotNull { id -> state.channels.firstOrNull { it.id == id } }
    var activeId by remember(channels) { mutableStateOf(channels.firstOrNull()?.id) }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Multiview", fontSize = 26.sp, fontWeight = FontWeight.SemiBold); Text("Up to four live channels · select a tile for audio", color = TextMuted, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f)); Text("${channels.size}/4", color = TextMuted)
        }
        Spacer(Modifier.height(12.dp))
        if (channels.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.GridView, null, Modifier.size(56.dp), tint = TextMuted); Spacer(Modifier.height(14.dp)); Text("Add channels from the guide", fontSize = 21.sp); Text("Highlight a channel and choose Multi", color = TextMuted)
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.chunked(2).forEach { rowChannels ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowChannels.forEach { channel -> MultiTile(channel, state.provider, channel.id == activeId, { activeId = channel.id }, { vm.removeFromMultiview(channel.id) }, Modifier.weight(1f)) }
                        if (rowChannels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (channels.size < 4) {
            Spacer(Modifier.height(10.dp)); Text("ADD CHANNEL", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { state.visibleChannels.filter { it.id !in state.multiviewIds }.take(6).forEach { channel -> TvButton({vm.addToMultiview(channel.id)}) { Icon(Icons.Default.Add,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(channel.displayName,maxLines=1,overflow=TextOverflow.Ellipsis) } } }
        }
    }
}

@Composable private fun MultiTile(channel: Channel, provider: ProviderConfig?, active: Boolean, onActivate: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val player = remember(channel.url, provider) { PlaybackPlayerFactory.create(context, provider).apply { setMediaItem(MediaItem.fromUri(channel.url)); volume = if(active) 1f else 0f; prepare(); playWhenReady = true } }
    LaunchedEffect(active) { player.volume = if(active) 1f else 0f }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color.Black).onFocusChanged { focused=it.isFocused;if(it.isFocused)onActivate() }.focusable().combinedClickable(onClick=onActivate,onLongClick=onRemove)) {
        AndroidView(factory={ PlayerView(it).apply { this.player=player;useController=false;layoutParams=ViewGroup.LayoutParams(-1,-1) } },update={it.player=player},modifier=Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC10151C)).padding(11.dp),verticalAlignment=Alignment.CenterVertically) { if(active) Icon(Icons.AutoMirrored.Filled.VolumeUp,null,tint=Focus,modifier=Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(channel.displayName,Modifier.weight(1f),fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text("Hold to remove",color=TextMuted,fontSize=10.sp) }
        if(focused || active) Box(Modifier.matchParentSize().border(if(focused) 3.dp else 2.dp, if(focused) Color.White else Focus, RoundedCornerShape(9.dp)))
    }
}

@Composable private fun OrganizeScreen(state: UiState, vm: MainViewModel) {
    var filter by remember { mutableStateOf("") }
    var targetPosition by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editGroup by remember { mutableStateOf("") }
    val ordered = state.channels.sortedWith(ChannelOrdering.manual).filter { filter.isBlank() || it.displayName.contains(filter, true) }
    val selectedId = state.selectedChannelId ?: ordered.firstOrNull()?.id
    val selectedChannel = state.channels.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId) { editName = selectedChannel?.customName.orEmpty(); editGroup = selectedChannel?.customGroup.orEmpty() }
    val selectedIndex = state.channels.sortedWith(ChannelOrdering.manual).indexOfFirst { it.id == selectedId }
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Manage channels", fontSize = 27.sp, fontWeight = FontWeight.SemiBold); Text("Manual order and visibility are retained after provider updates", color = TextMuted, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f)); TvTextField(filter, { filter = it }, Modifier.width(300.dp), placeholder = { Text("Filter channels") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Spacer(Modifier.width(8.dp)); TvButton({ vm.navigate(AppScreen.SETTINGS) }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text("Done") }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedIndex >= 0) "Selected position ${selectedIndex + 1} of ${state.channels.size}" else "Select a channel", color = TextMuted, modifier = Modifier.weight(1f))
            TvButton({ selectedId?.let { vm.moveChannelTo(it, 1) } }) { Icon(Icons.Default.VerticalAlignTop, null); Text("Top") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, -10) } }) { Text("−10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, 10) } }) { Text("+10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannelTo(it, state.channels.size) } }) { Icon(Icons.Default.VerticalAlignBottom, null); Text("Bottom") }
            Spacer(Modifier.width(10.dp)); TvTextField(targetPosition, { targetPosition = it.filter(Char::isDigit).take(5) }, Modifier.width(120.dp), placeholder = { Text("Position") })
            Spacer(Modifier.width(6.dp)); TvButton({ val target = targetPosition.toIntOrNull(); if (target != null) selectedId?.let { vm.moveChannelTo(it, target) } }, selected = true) { Text("Move") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(editName, { editName = it.take(80) }, Modifier.weight(1f), label = { Text("Custom channel name") }, placeholder = { Text(selectedChannel?.name.orEmpty()) })
            Spacer(Modifier.width(8.dp)); TvTextField(editGroup, { editGroup = it.take(80) }, Modifier.weight(1f), label = { Text("Custom group") }, placeholder = { Text(selectedChannel?.group.orEmpty()) })
            Spacer(Modifier.width(8.dp)); TvButton({ selectedId?.let { vm.customizeChannel(it, editName, editGroup) } }, selected = true) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(5.dp)); Text("Apply") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ordered, key = { it.id }) { channel ->
                val actualPosition = state.channels.sortedWith(ChannelOrdering.manual).indexOfFirst { it.id == channel.id } + 1
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(7.dp)).background(if (focused || selectedId == channel.id) Color(0xFF1C2D43) else Panel)
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.selectChannel(channel.id) }.focusable().combinedClickable(onClick = { vm.selectChannel(channel.id) }).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actualPosition.toString(), Modifier.width(52.dp), color = TextMuted)
                    if (channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(34.dp).padding(3.dp)) else Box(Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment = Alignment.Center) { Text(channel.displayName.take(1)) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(channel.displayName, color = if (channel.hidden) TextMuted else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.displayGroup, color = TextMuted, fontSize = 11.sp) }
                    TvButton({ vm.moveChannel(channel.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.moveChannel(channel.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.toggleHidden(channel.id) }, selected = !channel.hidden) { Icon(if (channel.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null); Spacer(Modifier.width(5.dp)); Text(if (channel.hidden) "Hidden" else "Visible") }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(state: UiState, vm: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Use Up/Down to choose a setting, then press Select to change it.", color = TextMuted, fontSize = 13.sp)
        }
        item {
            SettingsCard("GUIDE DISPLAY") {
                Column(Modifier.padding(16.dp)) {
                    Text("Visible guide width", fontWeight = FontWeight.Medium)
                    Text("Choose how many hours are shown across the Live TV guide.", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSettings.allowedTimelineHours.sorted().forEach { hours ->
                            TvButton({ vm.setTimelineHours(hours) }, selected = state.timelineHours == hours) { Text("${hours} hours") }
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("TV GUIDE UPDATES") {
                SettingToggleRow(
                    Icons.Default.Update,
                    "Automatic EPG updates",
                    "Allow periodic guide updates in the background",
                    state.epgAutoUpdate
                ) { vm.setEpgAutoUpdate(!state.epgAutoUpdate) }
                HorizontalDivider(color = Color(0xFF27303C))
                SettingToggleRow(
                    Icons.Default.PowerSettingsNew,
                    "Update EPG when StreamGuide opens",
                    "Refresh the guide on launch when its data is stale",
                    state.updateEpgOnStart
                ) { vm.setUpdateEpgOnStart(!state.updateEpgOnStart) }
                HorizontalDivider(color = Color(0xFF27303C))
                Column(Modifier.padding(16.dp)) {
                    Text("Automatic update interval", fontWeight = FontWeight.Medium)
                    Text("Fire OS schedules background work approximately; it may run later to preserve resources.", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSettings.allowedEpgHours.sorted().forEach { hours ->
                            TvButton({ vm.setEpgHours(hours) }, selected = state.epgHours == hours) { Text("Every ${hours}h") }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF27303C))
                SettingRow(Icons.Default.Refresh, "Update EPG now", "Guide only · keeps the current channel list", vm::refreshEpgOnly)
            }
        }
        item {
            SettingsCard("PLAYLIST") {
                SettingRow(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    state.provider?.name ?: "No playlist",
                    when (state.provider?.type) { ProviderType.XTREAM -> "Xtream Codes · edit connection"; ProviderType.M3U -> "M3U / M3U8 · edit connection"; else -> "" }
                ) { vm.navigate(AppScreen.EDIT_PROVIDER) }
                HorizontalDivider(color = Color(0xFF27303C))
                SettingToggleRow(
                    Icons.Default.PowerSettingsNew,
                    "Update playlist when StreamGuide opens",
                    "Slower startup; reloads channels and EPG from the provider",
                    state.updatePlaylistOnStart
                ) { vm.setUpdatePlaylistOnStart(!state.updatePlaylistOnStart) }
                HorizontalDivider(color = Color(0xFF27303C))
                SettingRow(Icons.Default.Refresh, "Update playlist and EPG now", state.status.message, vm::refresh)
                HorizontalDivider(color = Color(0xFF27303C))
                SettingRow(Icons.Default.Tune, "Manage channels", "Reorder, rename, group, hide and restore") { vm.navigate(AppScreen.ORGANIZE) }
            }
        }
        item {
            SettingsCard("STATUS") {
                Column(Modifier.padding(16.dp)) {
                    StatusLine("Channels", state.channels.size.toString())
                    StatusLine("Programmes", state.programs.size.toString())
                    StatusLine("EPG automatic update", if (state.epgAutoUpdate) "On · every ${state.epgHours}h" else "Off")
                    StatusLine("Last successful update", if (state.status.lastSuccessEpochMs > 0) SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(state.status.lastSuccessEpochMs)) else "Never")
                }
            }
        }
        item {
            TvButton(vm::clearProvider, danger = true) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text("Remove playlist and local data") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun SettingsCard(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)){Text(title,color=TextMuted,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=16.dp,top=12.dp,bottom=6.dp));content()}}
@Composable private fun SettingRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){TvButton(onClick,modifier=Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title);Text(subtitle,color=TextMuted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}}}
@Composable private fun SettingToggleRow(icon:ImageVector,title:String,subtitle:String,enabled:Boolean,onToggle:()->Unit){TvButton(onToggle,selected=enabled,modifier=Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title);Text(subtitle,color=TextMuted,fontSize=12.sp,maxLines=2)};Text(if(enabled) "ON" else "OFF",color=if(enabled) Success else TextMuted,fontWeight=FontWeight.Bold)}}
@Composable private fun StatusLine(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(label,color=TextMuted);Spacer(Modifier.weight(1f));Text(value,fontWeight=FontWeight.Medium)}}

@Composable private fun ImportStatusScreen(state: UiState, vm: MainViewModel) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.importLog.size) { if (state.importLog.isNotEmpty()) listState.animateScrollToItem(state.importLog.lastIndex) }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 90.dp, vertical = 54.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).background(if(state.error==null) Focus else Color(0xFF8A3038)), contentAlignment = Alignment.Center) { Icon(if(state.error==null) Icons.Default.CloudDownload else Icons.Default.Error, null, Modifier.size(30.dp)) }
            Spacer(Modifier.width(16.dp)); Column { Text(if(state.importFinished) "Your TV is ready" else if(state.error!=null) "Setup needs attention" else "Setting up ${state.provider?.name.orEmpty()}", fontSize=29.sp, fontWeight=FontWeight.SemiBold); Text(if(state.importFinished) "${state.channels.size} channels · ${state.programs.size} programmes" else "You can follow each import step below.", color=TextMuted) }
            Spacer(Modifier.weight(1f)); if(state.loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth=3.dp)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(18.dp)) {
            Text("IMPORT LOG", color=TextMuted, fontSize=11.sp, fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp))
            LazyColumn(state=listState, verticalArrangement=Arrangement.spacedBy(7.dp)) { itemsIndexed(state.importLog) { index, message -> Row(verticalAlignment=Alignment.CenterVertically) { Icon(if(message.startsWith("ERROR")) Icons.Default.Error else if(index==state.importLog.lastIndex && state.loading) Icons.Default.Sync else Icons.Default.CheckCircle, null, Modifier.size(17.dp), tint=if(message.startsWith("ERROR")) MaterialTheme.colorScheme.error else if(index==state.importLog.lastIndex && state.loading) Focus else Success);Spacer(Modifier.width(10.dp));Text(message,color=if(message.startsWith("ERROR")) MaterialTheme.colorScheme.error else TextPrimary,fontSize=14.sp) } } }
        }
        state.error?.let { Spacer(Modifier.height(12.dp)); Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFF632A32),border=BorderStroke(1.dp,Color(0xFFFF7785))){Column(Modifier.fillMaxWidth().padding(14.dp)){Text(it,fontWeight=FontWeight.SemiBold);Text("Check the server address, port, username and password. HTTP and HTTPS are both supported.",color=TextMuted,fontSize=12.sp)}} }
        Spacer(Modifier.height(16.dp)); Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            if(state.importFinished) TvButton(vm::finishImport, selected=true) { Icon(Icons.Default.LiveTv,null);Spacer(Modifier.width(7.dp));Text("Open Live TV") }
            if(state.error!=null) { TvButton(vm::retryImport,selected=true){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Try again")};TvButton(vm::editProviderFromImport){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(7.dp));Text("Edit details")} }
            if(state.loading) Text("Please keep StreamGuide open during the first import.",color=TextMuted,modifier=Modifier.align(Alignment.CenterVertically))
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
        Column(Modifier.width(420.dp).fillMaxHeight().background(Panel).padding(48.dp),verticalArrangement=Arrangement.Center) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Focus),contentAlignment=Alignment.Center){Icon(Icons.Default.PlayArrow,null,Modifier.size(42.dp))};Spacer(Modifier.height(22.dp));Text("StreamGuide",fontSize=34.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text("Live TV, organized your way.",fontSize=19.sp,color=TextMuted);Spacer(Modifier.height(34.dp));FeatureLine("Fast, remote-first TV guide");FeatureLine("Favorites and durable ordering");FeatureLine("Automatic XMLTV updates");FeatureLine("Private and local-only")
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 70.dp, vertical = 32.dp)) {
            Text(if (existing == null) "Add your playlist" else "Edit playlist", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("StreamGuide does not provide channels. Add your own provider.", color = TextMuted)
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
                TvButton({ additionalOpen = !additionalOpen }) { Text(if (additionalOpen) "Hide additional settings" else "Additional settings (optional)") }
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
            HorizontalDivider(color = Color(0xFF27303C))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Press Select on a field to open the keyboard.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TvButton({
                    val valid = if (type == ProviderType.M3U) ProviderValidation.isM3uValid(playlist) else ProviderValidation.isXtreamValid(server, username, password)
                    if (!valid) validation = if (type == ProviderType.XTREAM) "Enter an HTTP or HTTPS server address, username, and password" else "Enter an HTTP/HTTPS playlist URL or choose a local file"
                    else onSave(ProviderConfig(type, name.ifBlank { "My TV" }, playlist, server, username, password, epg, userAgent, referer))
                }, selected = true, modifier = Modifier.width(240.dp)) { Text("Finish setup") }
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
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        showEditHint = true
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
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    showEditHint: Boolean = false
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
        supportingText = if (showEditHint && !activation.isEditing) ({ Text("Press Select to edit", color = TextMuted, fontSize = 11.sp) }) else null,
        singleLine = true,
        readOnly = activation.isReadOnly,
        visualTransformation = visualTransformation
    )
}
@Composable private fun FeatureLine(text:String){Row(Modifier.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.CheckCircle,null,tint=Success,modifier=Modifier.size(18.dp));Spacer(Modifier.width(10.dp));Text(text,color=TextMuted)}}

@Composable private fun PlayerScreen(state: UiState, vm: MainViewModel) {
    val channel = state.playingChannel ?: return
    val context = LocalContext.current
    val streamUrl = state.playingUrl ?: channel.url
    val playerFocus = remember { FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }
    var playbackInfo by remember { mutableStateOf("Connecting…") }
    var playbackDiagnostic by remember(streamUrl) { mutableStateOf("") }
    var playbackFailed by remember(streamUrl) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var retries by remember(streamUrl) { mutableIntStateOf(0) }
    var retryJob by remember(streamUrl) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val player = remember(streamUrl, state.provider) {
        PlaybackPlayerFactory.create(context, state.provider)
            .apply { setMediaItem(MediaItem.fromUri(streamUrl)); prepare(); playWhenReady = true }
    }
    val restartPlayback: (Boolean) -> Unit = { manual ->
        retryJob?.cancel()
        retryJob = null
        if (manual) retries = 0
        playbackFailed = false
        playbackDiagnostic = ""
        playbackInfo = "Connecting…"
        player.prepare()
        player.play()
        overlayVisible = true
    }
    val scheduleRecovery: (String) -> Unit = recovery@{ diagnostic ->
        if (retryJob?.isActive == true) return@recovery
        val attempt = retries + 1
        val delayMs = PlaybackRecoveryPolicy.delayForRetry(attempt)
        playbackDiagnostic = diagnostic
        overlayVisible = true
        if (delayMs == null) {
            playbackFailed = true
            playbackInfo = "Playback unavailable"
        } else {
            retries = attempt
            playbackInfo = "Reconnecting · $attempt/${PlaybackRecoveryPolicy.maxAutomaticRetries}"
            retryJob = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                player.prepare()
                player.play()
                retryJob = null
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (player.playbackState == androidx.media3.common.Player.STATE_READY) playbackInfo = if (value) "Playing" else "Paused"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackInfo = when (playbackState) {
                    androidx.media3.common.Player.STATE_BUFFERING -> "Buffering"
                    androidx.media3.common.Player.STATE_READY -> if (player.isPlaying) "Playing" else "Paused"
                    androidx.media3.common.Player.STATE_ENDED -> "Stream ended"
                    else -> "Connecting…"
                }
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    playbackFailed = false
                    playbackDiagnostic = ""
                } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    scheduleRecovery("The live stream ended unexpectedly")
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                scheduleRecovery(PlaybackRecoveryPolicy.diagnostic(error))
            }
        }
        player.addListener(listener)
        onDispose { retryJob?.cancel(); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(playbackInfo, streamUrl) {
        if (playbackInfo == "Buffering") {
            kotlinx.coroutines.delay(PlaybackRecoveryPolicy.bufferingTimeoutMs)
            if (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) scheduleRecovery("Buffering timed out")
        }
    }
    LaunchedEffect(isPlaying, streamUrl) {
        if (isPlaying) {
            kotlinx.coroutines.delay(30_000)
            if (player.isPlaying) retries = 0
        }
    }
    LaunchedEffect(Unit) { playerFocus.requestFocus() }
    LaunchedEffect(overlayVisible, isPlaying) { if (overlayVisible && isPlaying && !playbackFailed) { kotlinx.coroutines.delay(4_000); overlayVisible = false } }
    BackHandler { vm.closePlayer() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(playerFocus)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_UP) return@onPreviewKeyEvent false
                when (PlayerRemotePolicy.action(event.nativeKeyEvent.keyCode)) {
                    PlayerRemoteAction.PREVIOUS_CHANNEL -> vm.playAdjacent(-1)
                    PlayerRemoteAction.NEXT_CHANNEL -> vm.playAdjacent(1)
                    PlayerRemoteAction.SEEK_BACK -> { player.seekBack(); overlayVisible = true }
                    PlayerRemoteAction.SEEK_FORWARD -> { player.seekForward(); overlayVisible = true }
                    PlayerRemoteAction.PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); overlayVisible = true }
                    PlayerRemoteAction.PLAY -> { player.play(); overlayVisible = true }
                    PlayerRemoteAction.PAUSE -> { player.pause(); overlayVisible = true }
                    PlayerRemoteAction.TOGGLE_OVERLAY -> if (playbackFailed) restartPlayback(true) else overlayVisible = !overlayVisible
                    PlayerRemoteAction.NONE -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable()
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; isFocusable = false; layoutParams = ViewGroup.LayoutParams(-1, -1) } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(overlayVisible || !isPlaying, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000)).padding(42.dp)) {
                Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Focus), contentAlignment = Alignment.Center) { Text((channel.providerOrder + 1).toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    Spacer(Modifier.width(15.dp)); Column { Text(channel.displayName, fontSize = 27.sp, fontWeight = FontWeight.SemiBold); Text("${channel.displayGroup} · $playbackInfo", color = TextMuted, fontSize = 14.sp) }
                }
                if (playbackFailed) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(70.dp), tint = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Text("Playback unavailable", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        if (playbackDiagnostic.isNotBlank()) Text(playbackDiagnostic, color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Press Select to retry", color = Focus, fontWeight = FontWeight.Bold)
                    }
                } else if (!isPlaying) Icon(Icons.Default.PauseCircle, null, Modifier.align(Alignment.Center).size(76.dp), tint = Color.White)
                val current = currentAndNext(channel, state.programsFor(channel)).firstOrNull()
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                    Text(current?.title ?: "Live TV", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                    if (!current?.description.isNullOrBlank()) Text(current?.description.orEmpty(), color = TextMuted, maxLines = 2, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("← 10 seconds    30 seconds →     ▲▼ Channel     Play/Pause button", color = TextMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(if (focused) 1.035f else 1f, label = "tv-button-focus")
    val bg = when {
        danger && focused -> Color(0xFF7A2630)
        selected -> Focus
        focused -> Color(0xFF29496F)
        else -> Panel2
    }
    Surface(
        modifier.scale(focusScale).onFocusChanged { focused = it.isFocused }.focusable().combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = if (focused) BorderStroke(2.dp, Color.White) else null
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
@Composable private fun EmptyGuide(hasProvider:Boolean,onRefresh:()->Unit){Column(Modifier.fillMaxWidth().padding(70.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.LiveTv,null,Modifier.size(50.dp),tint=TextMuted);Spacer(Modifier.height(15.dp));Text(if(hasProvider)"No channels loaded" else "Add a playlist",fontSize=21.sp);Text("Update your playlist to populate the guide",color=TextMuted);Spacer(Modifier.height(16.dp));TvButton(onRefresh,selected=true){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Update now")}}}
@Composable private fun ParentalPinDialog(onSubmit:(String)->Unit,onCancel:()->Unit){var pin by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onCancel,icon={Icon(Icons.Default.Lock,null)},title={Text("Parental control")},text={Column{Text("Enter the PIN to watch this channel.",color=TextMuted);Spacer(Modifier.height(12.dp));TvTextField(pin,{pin=it.filter(Char::isDigit).take(12)},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),placeholder={Text("PIN")})}},confirmButton={TvButton({onSubmit(pin)},selected=true){Text("Unlock")}},dismissButton={TvButton(onCancel){Text("Cancel")}})}
@Composable private fun ExitConfirmationBanner(){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(Modifier.padding(24.dp),shape=RoundedCornerShape(8.dp),color=Panel2,border=BorderStroke(1.dp,Focus)){Text("Press Back again to exit",modifier=Modifier.padding(horizontal=18.dp,vertical=12.dp),fontWeight=FontWeight.SemiBold)}}}
@Composable private fun ErrorBanner(message:String,onDismiss:()->Unit){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(Modifier.padding(24.dp).combinedClickable(onClick=onDismiss),shape=RoundedCornerShape(8.dp),color=Color(0xFF632A32),border=BorderStroke(1.dp,Color(0xFFFF7785))){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Error,null);Spacer(Modifier.width(10.dp));Text(message);Spacer(Modifier.width(18.dp));Text("Dismiss",fontWeight=FontWeight.Bold)}}}}
private fun currentAndNext(channel:Channel,programs:List<Program>):List<Program>{val now=System.currentTimeMillis();val ids=setOf(channel.id,channel.tvgId).filter{it.isNotBlank()}.toSet();return programs.asSequence().filter{it.channelId in ids&&it.endEpochMs>now}.sortedBy{it.startEpochMs}.take(2).toList()}
private fun lastCatchup(channel:Channel,programs:List<Program>):Program?{if(channel.catchupDays<=0)return null;val now=System.currentTimeMillis();val cutoff=now-channel.catchupDays*86_400_000L;val ids=setOf(channel.id,channel.tvgId).filter{it.isNotBlank()}.toSet();return programs.asSequence().filter{it.channelId in ids&&it.endEpochMs in cutoff..now}.maxByOrNull{it.endEpochMs}}
private fun time(epoch:Long)=SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(epoch))

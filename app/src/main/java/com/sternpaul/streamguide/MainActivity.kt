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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.sternpaul.streamguide.core.*
import java.text.SimpleDateFormat
import java.util.*

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
    Surface(Modifier.fillMaxSize(), color = Bg) {
        when (state.screen) {
            AppScreen.SETUP -> SetupScreen(vm::saveProvider)
            AppScreen.PLAYER -> PlayerScreen(state, vm)
            else -> Column {
                AppTopBar(state, vm)
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
        state.pendingPinChannelId?.let { ParentalPinDialog(vm::submitParentalPin, vm::cancelParentalPin) }
        state.error?.let { ErrorBanner(it, vm::clearError) }
    }
}

@Composable private fun AppTopBar(state: UiState, vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(Color(0xF20B0F14)).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(Focus), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp)); Text("StreamGuide", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(38.dp))
        NavButton("Live TV", Icons.Default.LiveTv, state.screen == AppScreen.GUIDE) { vm.navigate(AppScreen.GUIDE) }
        NavButton("Search", Icons.Default.Search, state.screen == AppScreen.SEARCH) { vm.navigate(AppScreen.SEARCH) }
        NavButton("Multiview", Icons.Default.GridView, state.screen == AppScreen.MULTIVIEW) { vm.navigate(AppScreen.MULTIVIEW) }
        NavButton("Settings", Icons.Default.Settings, state.screen == AppScreen.SETTINGS || state.screen == AppScreen.ORGANIZE) { vm.navigate(AppScreen.SETTINGS) }
        Spacer(Modifier.weight(1f))
        if (state.loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Updating", color = TextMuted, fontSize = 14.sp) }
        Spacer(Modifier.width(22.dp)); Clock()
    }
}

@Composable private fun NavButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    TvButton(onClick, selected = selected, modifier = Modifier.padding(end = 8.dp)) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
}

@Composable private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = Date() } }
    Column(horizontalAlignment = Alignment.End) {
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable private fun GuideScreen(state: UiState, vm: MainViewModel) {
    Row(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)) {
        GroupRail(state, vm)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GuideToolbar(state, vm)
            Spacer(Modifier.height(10.dp))
            GuideHeader(state.timelineStart, vm)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(state.visibleChannels, key = { it.id }) { channel ->
                    TimelineChannelRow(channel, state.programs, state.timelineStart, channel.id == state.selectedChannelId, vm)
                }
                if (state.visibleChannels.isEmpty()) item { EmptyGuide(state.provider != null, vm::refresh) }
            }
            state.selectedChannelId?.let { id ->
                state.channels.firstOrNull { it.id == id }?.let { channel ->
                    val selected = state.selectedProgram?.takeIf { it.channelId == channel.id || it.channelId == channel.tvgId }
                    if (selected != null) ProgrammeDetailsBar(channel, selected, vm)
                    else SelectedChannelBar(channel, currentAndNext(channel, state.programs).firstOrNull(), lastCatchup(channel, state.programs), vm)
                }
            }
        }
    }
}

@Composable private fun GroupRail(state: UiState, vm: MainViewModel) {
    Column(Modifier.width(205.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Panel).padding(10.dp)) {
        Text(state.provider?.name ?: "PLAYLIST", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.groups) { group ->
                val count = when(group) { "All channels" -> state.channels.count { !it.hidden }; "Favorites" -> state.channels.count { it.favorite && !it.hidden }; else -> state.channels.count { it.group == group && !it.hidden } }
                TvButton({ vm.selectGroup(group) }, selected = state.selectedGroup == group, modifier = Modifier.fillMaxWidth()) {
                    Icon(if(group=="Favorites") Icons.Default.Star else Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(group, Modifier.weight(1f), maxLines=1, overflow=TextOverflow.Ellipsis); Text(count.toString(), color=TextMuted, fontSize=12.sp)
                }
            }
        }
    }
}

@Composable private fun GuideToolbar(state: UiState, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column { Text(state.selectedGroup, fontSize = 24.sp, fontWeight = FontWeight.SemiBold); Text("${state.visibleChannels.size} channels", color = TextMuted, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        TvButton({ vm.setSort(when(state.sort){ChannelSort.MANUAL->ChannelSort.ALPHABETICAL;ChannelSort.ALPHABETICAL->ChannelSort.PROVIDER;ChannelSort.PROVIDER->ChannelSort.MANUAL}) }) { Icon(Icons.AutoMirrored.Filled.Sort,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(state.sort.name.lowercase().replaceFirstChar(Char::uppercase)) }
        Spacer(Modifier.width(8.dp)); TvButton(vm::refresh) { Icon(Icons.Default.Refresh,null,Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Update") }
    }
}

@Composable private fun GuideHeader(windowStart: Long, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(270.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton({ vm.shiftTimeline(-24) }) { Icon(Icons.Default.KeyboardDoubleArrowLeft, null, Modifier.size(17.dp)) }
            Spacer(Modifier.width(4.dp)); TvButton({ vm.shiftTimeline(-2) }) { Icon(Icons.Default.ChevronLeft, null, Modifier.size(17.dp)) }
            Spacer(Modifier.width(4.dp)); TvButton(vm::jumpTimelineToNow, selected = true) { Text("Now", fontSize = 12.sp) }
            Spacer(Modifier.width(4.dp)); TvButton({ vm.shiftTimeline(2) }) { Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp)) }
            Spacer(Modifier.width(4.dp)); TvButton({ vm.shiftTimeline(24) }) { Icon(Icons.Default.KeyboardDoubleArrowRight, null, Modifier.size(17.dp)) }
        }
        repeat(6) { index ->
            val instant = windowStart + index * 1_800_000L
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                if (index == 0) Text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(instant)), color = Focus, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(time(instant), color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun TimelineChannelRow(channel: Channel, programs: List<Program>, windowStart: Long, selected: Boolean, vm: MainViewModel) {
    val windowEnd = windowStart + 3 * 3_600_000L
    val slices = GuideTimeline.slices(channel, programs, windowStart, windowEnd)
    Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
        var channelFocused by remember { mutableStateOf(false) }
        Row(
            Modifier.width(270.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(if (channelFocused || selected) Color(0xFF1C2D43) else Panel)
                .onFocusChanged { channelFocused = it.isFocused; if (it.isFocused) vm.selectProgram(channel.id, null) }
                .focusable().combinedClickable(onClick = { vm.play(channel.id) }, onLongClick = { vm.toggleFavorite(channel.id) })
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (channelFocused || selected) Focus else Color.Transparent)); Spacer(Modifier.width(9.dp))
            Text((channel.providerOrder + 1).toString(), Modifier.width(32.dp), color = TextMuted, fontSize = 12.sp)
            if (channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(36.dp).padding(3.dp)) else Box(Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment = Alignment.Center) { Text(channel.name.take(1), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(9.dp)); Text(channel.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if(channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(38.dp).padding(3.dp)) else Box(Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment=Alignment.Center){Text(channel.name.take(1),fontWeight=FontWeight.Bold)}
        Spacer(Modifier.width(10.dp)); Text(channel.name, Modifier.width(155.dp), fontWeight=FontWeight.Medium, maxLines=1, overflow=TextOverflow.Ellipsis)
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
            Text(program.description.ifBlank { channel.name }, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
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
        Column(Modifier.weight(1f)) { Text(program?.title ?: channel.name, fontWeight=FontWeight.SemiBold, maxLines=1); Text(program?.description?.ifBlank { channel.group } ?: channel.group, color=TextMuted, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=12.sp) }
        TvButton({vm.toggleFavorite(channel.id)}) { Icon(if(channel.favorite) Icons.Default.Star else Icons.Default.StarBorder,null,tint=if(channel.favorite) Warning else TextPrimary); Spacer(Modifier.width(6.dp)); Text("Favorite") }
        Spacer(Modifier.width(6.dp)); TvButton({vm.moveChannel(channel.id,-1)}) { Icon(Icons.Default.KeyboardArrowUp,null); Text("Move") }
        Spacer(Modifier.width(6.dp)); TvButton({vm.moveChannel(channel.id,1)}) { Icon(Icons.Default.KeyboardArrowDown,null) }
        if(previous != null && channel.catchupSource.isNotBlank()) { Spacer(Modifier.width(6.dp)); TvButton({vm.playCatchup(channel,previous)}) { Icon(Icons.Default.Replay,null); Spacer(Modifier.width(4.dp)); Text("Catch-up") } }
        Spacer(Modifier.width(6.dp)); TvButton({vm.toggleLock(channel.id)}) { Icon(if(channel.locked) Icons.Default.Lock else Icons.Default.LockOpen,null); Spacer(Modifier.width(4.dp)); Text(if(channel.locked) "Locked" else "Lock") }
        Spacer(Modifier.width(6.dp)); TvButton({vm.addToMultiview(channel.id)}) { Icon(Icons.Default.GridView,null); Spacer(Modifier.width(4.dp)); Text("Multi") }
        Spacer(Modifier.width(6.dp)); TvButton({vm.play(channel.id)}, selected=true) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(4.dp)); Text("Watch") }
    }
}

@Composable private fun SearchScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(30.dp)) {
        Text("Search", fontSize=28.sp, fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(16.dp))
        OutlinedTextField(state.query, vm::setQuery, Modifier.fillMaxWidth(), placeholder={Text("Channels and programmes")}, leadingIcon={Icon(Icons.Default.Search,null)}, singleLine=true)
        Spacer(Modifier.height(18.dp)); Text("${state.visibleChannels.size} results", color=TextMuted)
        Spacer(Modifier.height(10.dp)); LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) { items(state.visibleChannels,key={it.id}) { channel -> GuideChannelRow(channel,currentAndNext(channel,state.programs),false,vm) } }
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
                        rowChannels.forEach { channel -> MultiTile(channel, channel.id == activeId, { activeId = channel.id }, { vm.removeFromMultiview(channel.id) }, Modifier.weight(1f)) }
                        if (rowChannels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (channels.size < 4) {
            Spacer(Modifier.height(10.dp)); Text("ADD CHANNEL", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { state.visibleChannels.filter { it.id !in state.multiviewIds }.take(6).forEach { channel -> TvButton({vm.addToMultiview(channel.id)}) { Icon(Icons.Default.Add,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(channel.name,maxLines=1,overflow=TextOverflow.Ellipsis) } } }
        }
    }
}

@Composable private fun MultiTile(channel: Channel, active: Boolean, onActivate: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val player = remember(channel.url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(channel.url)); volume = if(active) 1f else 0f; prepare(); playWhenReady = true } }
    LaunchedEffect(active) { player.volume = if(active) 1f else 0f }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color.Black).onFocusChanged { focused=it.isFocused;if(it.isFocused)onActivate() }.focusable().combinedClickable(onClick=onActivate,onLongClick=onRemove)) {
        AndroidView(factory={ PlayerView(it).apply { this.player=player;useController=false;layoutParams=ViewGroup.LayoutParams(-1,-1) } },update={it.player=player},modifier=Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC10151C)).padding(11.dp),verticalAlignment=Alignment.CenterVertically) { if(active) Icon(Icons.AutoMirrored.Filled.VolumeUp,null,tint=Focus,modifier=Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(channel.name,Modifier.weight(1f),fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text("Hold to remove",color=TextMuted,fontSize=10.sp) }
        if(focused || active) Box(Modifier.matchParentSize().border(if(focused) 3.dp else 2.dp, if(focused) Color.White else Focus, RoundedCornerShape(9.dp)))
    }
}

@Composable private fun OrganizeScreen(state: UiState, vm: MainViewModel) {
    var filter by remember { mutableStateOf("") }
    var targetPosition by remember { mutableStateOf("") }
    val ordered = state.channels.sortedWith(ChannelOrdering.manual).filter { filter.isBlank() || it.name.contains(filter, true) }
    val selectedId = state.selectedChannelId ?: ordered.firstOrNull()?.id
    val selectedIndex = state.channels.sortedWith(ChannelOrdering.manual).indexOfFirst { it.id == selectedId }
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Manage channels", fontSize = 27.sp, fontWeight = FontWeight.SemiBold); Text("Manual order and visibility are retained after provider updates", color = TextMuted, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f)); OutlinedTextField(filter, { filter = it }, Modifier.width(300.dp), placeholder = { Text("Filter channels") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            Spacer(Modifier.width(8.dp)); TvButton({ vm.navigate(AppScreen.SETTINGS) }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text("Done") }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedIndex >= 0) "Selected position ${selectedIndex + 1} of ${state.channels.size}" else "Select a channel", color = TextMuted, modifier = Modifier.weight(1f))
            TvButton({ selectedId?.let { vm.moveChannelTo(it, 1) } }) { Icon(Icons.Default.VerticalAlignTop, null); Text("Top") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, -10) } }) { Text("−10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannel(it, 10) } }) { Text("+10") }
            Spacer(Modifier.width(6.dp)); TvButton({ selectedId?.let { vm.moveChannelTo(it, state.channels.size) } }) { Icon(Icons.Default.VerticalAlignBottom, null); Text("Bottom") }
            Spacer(Modifier.width(10.dp)); OutlinedTextField(targetPosition, { targetPosition = it.filter(Char::isDigit).take(5) }, Modifier.width(120.dp), placeholder = { Text("Position") }, singleLine = true)
            Spacer(Modifier.width(6.dp)); TvButton({ val target = targetPosition.toIntOrNull(); if (target != null) selectedId?.let { vm.moveChannelTo(it, target) } }, selected = true) { Text("Move") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ordered, key = { it.id }) { channel ->
                val actualPosition = state.channels.sortedWith(ChannelOrdering.manual).indexOfFirst { it.id == channel.id } + 1
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(7.dp)).background(if (focused || selectedId == channel.id) Color(0xFF1C2D43) else Panel)
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) vm.selectChannel(channel.id) }.focusable().combinedClickable(onClick = { vm.selectChannel(channel.id) }).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actualPosition.toString(), Modifier.width(52.dp), color = TextMuted)
                    if (channel.logoUrl.isNotBlank()) AsyncImage(channel.logoUrl, null, Modifier.size(34.dp).padding(3.dp)) else Box(Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(Panel2), contentAlignment = Alignment.Center) { Text(channel.name.take(1)) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(channel.name, color = if (channel.hidden) TextMuted else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group, color = TextMuted, fontSize = 11.sp) }
                    TvButton({ vm.moveChannel(channel.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.moveChannel(channel.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    Spacer(Modifier.width(5.dp)); TvButton({ vm.toggleHidden(channel.id) }, selected = !channel.hidden) { Icon(if (channel.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null); Spacer(Modifier.width(5.dp)); Text(if (channel.hidden) "Hidden" else "Visible") }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(state: UiState, vm: MainViewModel) {
    var parentalPin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal=42.dp,vertical=24.dp)) {
        Text("Settings", fontSize=28.sp,fontWeight=FontWeight.SemiBold); Text("Playlist, guide and app preferences",color=TextMuted); Spacer(Modifier.height(22.dp))
        SettingsCard("PLAYLIST") {
            SettingRow(Icons.AutoMirrored.Filled.PlaylistPlay, state.provider?.name ?: "No playlist", when(state.provider?.type){ProviderType.XTREAM->"Xtream Codes";ProviderType.M3U->"M3U / M3U8";else->""}) { }
            HorizontalDivider(color=Color(0xFF27303C)); SettingRow(Icons.Default.Tune,"Manage channels","Reorder, move to position, hide and restore") { vm.navigate(AppScreen.ORGANIZE) }
            HorizontalDivider(color=Color(0xFF27303C)); SettingRow(Icons.Default.Refresh,"Update playlist and EPG",state.status.message,vm::refresh)
        }
        Spacer(Modifier.height(16.dp)); SettingsCard("TV GUIDE") {
            Column(Modifier.padding(16.dp)) { Text("Automatic update interval",fontWeight=FontWeight.Medium); Text("Default is every 24 hours. Fire OS may run background work later to preserve battery.",color=TextMuted,fontSize=13.sp); Spacer(Modifier.height(12.dp)); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ AppSettings.allowedEpgHours.sorted().forEach{h->TvButton({vm.setEpgHours(h)},selected=state.epgHours==h){Text("${h}h")}} } }
        }
        Spacer(Modifier.height(16.dp)); SettingsCard("PARENTAL CONTROLS") {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(state.hasParentalPin) "Change parental PIN" else "Set parental PIN",fontWeight=FontWeight.Medium);Text("Lock individual channels from the guide",color=TextMuted,fontSize=13.sp)};OutlinedTextField(parentalPin,{parentalPin=it.filter(Char::isDigit).take(12)},Modifier.width(180.dp),placeholder={Text("4–12 digits")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation());Spacer(Modifier.width(8.dp));TvButton({vm.setParentalPin(parentalPin);parentalPin=""},selected=true){Icon(Icons.Default.Lock,null);Spacer(Modifier.width(5.dp));Text("Save")}}
        }
        Spacer(Modifier.height(16.dp)); SettingsCard("STATUS") {
            Column(Modifier.padding(16.dp)) { StatusLine("Channels",state.channels.size.toString());StatusLine("Programmes",state.programs.size.toString());StatusLine("Last successful update",if(state.status.lastSuccessEpochMs>0) SimpleDateFormat("d MMM, HH:mm",Locale.getDefault()).format(Date(state.status.lastSuccessEpochMs)) else "Never") }
        }
        Spacer(Modifier.weight(1f)); TvButton(vm::clearProvider,danger=true){Icon(Icons.Default.Delete,null);Spacer(Modifier.width(7.dp));Text("Remove playlist and local data")}
    }
}

@Composable private fun SettingsCard(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)){Text(title,color=TextMuted,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=16.dp,top=12.dp,bottom=6.dp));content()}}
@Composable private fun SettingRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){TvButton(onClick,modifier=Modifier.fillMaxWidth()){Icon(icon,null);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title);Text(subtitle,color=TextMuted,fontSize=12.sp,maxLines=1)}}}
@Composable private fun StatusLine(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(label,color=TextMuted);Spacer(Modifier.weight(1f));Text(value,fontWeight=FontWeight.Medium)}}

@Composable private fun SetupScreen(onSave: (ProviderConfig)->Unit) {
    var type by remember { mutableStateOf(ProviderType.M3U) }; var name by remember { mutableStateOf("My TV") }; var playlist by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var epg by remember { mutableStateOf("") }; var validation by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.width(420.dp).fillMaxHeight().background(Panel).padding(48.dp),verticalArrangement=Arrangement.Center) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Focus),contentAlignment=Alignment.Center){Icon(Icons.Default.PlayArrow,null,Modifier.size(42.dp))};Spacer(Modifier.height(22.dp));Text("StreamGuide",fontSize=34.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text("Live TV, organized your way.",fontSize=19.sp,color=TextMuted);Spacer(Modifier.height(34.dp));FeatureLine("Fast, remote-first TV guide");FeatureLine("Favorites and durable ordering");FeatureLine("Automatic XMLTV updates");FeatureLine("Private and local-only")
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=70.dp,vertical=42.dp),verticalArrangement=Arrangement.Center) {
            Text("Add your playlist",fontSize=30.sp,fontWeight=FontWeight.SemiBold);Text("StreamGuide does not provide channels. Add your own provider.",color=TextMuted);Spacer(Modifier.height(24.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){TvButton({type=ProviderType.M3U},selected=type==ProviderType.M3U){Text("M3U / M3U8")};TvButton({type=ProviderType.XTREAM},selected=type==ProviderType.XTREAM){Text("Xtream Codes")}}
            Spacer(Modifier.height(18.dp));SetupField("Playlist name",name,{name=it})
            if(type==ProviderType.M3U) SetupField("M3U playlist URL",playlist,{playlist=it}) else {SetupField("Server URL",server,{server=it});Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.weight(1f)){SetupField("Username",username,{username=it})};Box(Modifier.weight(1f)){SetupField("Password",password,{password=it},true)}}}
            SetupField("XMLTV EPG URL (optional)",epg,{epg=it});validation?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)};Spacer(Modifier.height(18.dp))
            TvButton({val valid=if(type==ProviderType.M3U) playlist.startsWith("http") else server.startsWith("http")&&username.isNotBlank()&&password.isNotBlank();if(!valid)validation="Enter valid provider details" else onSave(ProviderConfig(type,name.ifBlank{"My TV"},playlist,server,username,password,epg))},selected=true,modifier=Modifier.width(220.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Add and update")}
        }
    }
}
@Composable private fun SetupField(label:String,value:String,onChange:(String)->Unit,password:Boolean=false){OutlinedTextField(value,onChange,Modifier.fillMaxWidth().padding(bottom=10.dp),label={Text(label)},singleLine=true,visualTransformation=if(password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)}
@Composable private fun FeatureLine(text:String){Row(Modifier.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.CheckCircle,null,tint=Success,modifier=Modifier.size(18.dp));Spacer(Modifier.width(10.dp));Text(text,color=TextMuted)}}

@Composable private fun PlayerScreen(state: UiState, vm: MainViewModel) {
    val channel=state.playingChannel ?: return
    val context=LocalContext.current
    var controls by remember { mutableStateOf(true) }; var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val streamUrl = state.playingUrl ?: channel.url
    val player=remember(streamUrl){ExoPlayer.Builder(context).build().apply{setMediaItem(MediaItem.fromUri(streamUrl));prepare();playWhenReady=true}}
    DisposableEffect(player){onDispose{player.release()}}
    BackHandler{vm.closePlayer()}
    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event -> if(event.nativeKeyEvent.action!=KeyEvent.ACTION_UP)return@onPreviewKeyEvent false;when(event.nativeKeyEvent.keyCode){KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_CHANNEL_UP->{vm.playAdjacent(-1);true};KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_CHANNEL_DOWN->{vm.playAdjacent(1);true};KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{controls=!controls;true};else->false}}.focusable()) {
        AndroidView(factory={PlayerView(it).apply{this.player=player;useController=false;layoutParams=ViewGroup.LayoutParams(-1,-1)}},update={it.player=player;it.resizeMode=resizeMode},modifier=Modifier.fillMaxSize())
        AnimatedVisibility(controls,enter=fadeIn(),exit=fadeOut()) { Column(Modifier.fillMaxSize().background(Color(0x66000000)).padding(38.dp)) { Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Focus),contentAlignment=Alignment.Center){Text((channel.providerOrder+1).toString(),fontWeight=FontWeight.Bold)};Spacer(Modifier.width(14.dp));Column{Text(channel.name,fontSize=24.sp,fontWeight=FontWeight.SemiBold);Text(channel.group,color=TextMuted)}};Spacer(Modifier.weight(1f));val current=currentAndNext(channel,state.programs).firstOrNull();Text(current?.title?:"Live TV",fontSize=25.sp,fontWeight=FontWeight.SemiBold);Text(current?.description.orEmpty(),color=TextMuted,maxLines=2);Spacer(Modifier.height(16.dp));Row{TvButton({vm.closePlayer()}){Icon(Icons.AutoMirrored.Filled.List,null);Spacer(Modifier.width(7.dp));Text("Guide")};Spacer(Modifier.width(8.dp));TvButton({vm.toggleFavorite(channel.id)}){Icon(if(channel.favorite)Icons.Default.Star else Icons.Default.StarBorder,null,tint=if(channel.favorite)Warning else TextPrimary);Spacer(Modifier.width(7.dp));Text("Favorite")};Spacer(Modifier.width(8.dp));TvButton({resizeMode=if(resizeMode==AspectRatioFrameLayout.RESIZE_MODE_FIT)AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT}){Icon(Icons.Default.AspectRatio,null);Spacer(Modifier.width(7.dp));Text("Aspect")};Spacer(Modifier.weight(1f));Text("▲▼  Change channel    OK  Controls",color=TextMuted,modifier=Modifier.align(Alignment.CenterVertically))} } }
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
@Composable private fun ParentalPinDialog(onSubmit:(String)->Unit,onCancel:()->Unit){var pin by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onCancel,icon={Icon(Icons.Default.Lock,null)},title={Text("Parental control")},text={Column{Text("Enter the PIN to watch this channel.",color=TextMuted);Spacer(Modifier.height(12.dp));OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),placeholder={Text("PIN")})}},confirmButton={TvButton({onSubmit(pin)},selected=true){Text("Unlock")}},dismissButton={TvButton(onCancel){Text("Cancel")}})}
@Composable private fun ErrorBanner(message:String,onDismiss:()->Unit){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(Modifier.padding(24.dp).combinedClickable(onClick=onDismiss),shape=RoundedCornerShape(8.dp),color=Color(0xFF632A32),border=BorderStroke(1.dp,Color(0xFFFF7785))){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Error,null);Spacer(Modifier.width(10.dp));Text(message);Spacer(Modifier.width(18.dp));Text("Dismiss",fontWeight=FontWeight.Bold)}}}}
private fun currentAndNext(channel:Channel,programs:List<Program>):List<Program>{val now=System.currentTimeMillis();val ids=setOf(channel.id,channel.tvgId).filter{it.isNotBlank()}.toSet();return programs.asSequence().filter{it.channelId in ids&&it.endEpochMs>now}.sortedBy{it.startEpochMs}.take(2).toList()}
private fun lastCatchup(channel:Channel,programs:List<Program>):Program?{if(channel.catchupDays<=0)return null;val now=System.currentTimeMillis();val cutoff=now-channel.catchupDays*86_400_000L;val ids=setOf(channel.id,channel.tvgId).filter{it.isNotBlank()}.toSet();return programs.asSequence().filter{it.channelId in ids&&it.endEpochMs in cutoff..now}.maxByOrNull{it.endEpochMs}}
private fun time(epoch:Long)=SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(epoch))

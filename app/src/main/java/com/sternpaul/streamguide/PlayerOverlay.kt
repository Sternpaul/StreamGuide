package com.sternpaul.streamguide

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.ListWindow
import kotlinx.coroutines.delay

private enum class OverlayMode { HIDDEN, BANNER, LIST, GROUPS }
private val ListRowHeight = 54.dp
private val GroupRowHeight = 40.dp
private val Glass = Color(0xD90A0D12)

/**
 * Everything drawn over the full-screen picture. Like the guide it is one focus target:
 * Up/Down zap, OK opens a see-through channel list, Left inside the list switches category.
 * The stream keeps running underneath the whole time.
 */
@Composable internal fun PlayerOverlay(state: UiState, vm: MainViewModel, playback: PlaybackController, dialogOpen: Boolean) {
    val channel = state.playingChannel
    if (channel == null) { LaunchedEffect(Unit) { vm.closePlayer() }; return }
    val focus = remember { FocusRequester() }
    var mode by remember { mutableStateOf(OverlayMode.BANNER) }
    var listIndex by remember { mutableIntStateOf(0) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var activity by remember { mutableIntStateOf(0) }
    val select = remember { PressTracker() }
    val backPressedHere = remember { booleanArrayOf(false) }
    val now = rememberNow()
    val canSwitchGroups = state.playbackSourceScreen != AppScreen.SEARCH

    LaunchedEffect(dialogOpen) { if (!dialogOpen) focus.requestFocus() }
    LaunchedEffect(channel.id, state.playingUrl) {
        vm.ensurePrograms(channel.id)
        if (mode == OverlayMode.HIDDEN) mode = OverlayMode.BANNER
    }
    LaunchedEffect(mode, activity, playback.isPlaying, state.playingUrl) {
        when (mode) {
            OverlayMode.BANNER -> if (playback.isPlaying) { delay(4_000); mode = OverlayMode.HIDDEN }
            OverlayMode.LIST, OverlayMode.GROUPS -> { delay(15_000); mode = OverlayMode.HIDDEN }
            OverlayMode.HIDDEN -> Unit
        }
    }
    BackHandler(enabled = !dialogOpen) {
        when (mode) {
            OverlayMode.GROUPS -> mode = OverlayMode.LIST
            OverlayMode.LIST -> mode = OverlayMode.HIDDEN
            else -> vm.closePlayer()
        }
    }

    fun openList() {
        val s = vm.state
        val list = s.visibleChannels
        listIndex = list.indexOfFirst { it.id == s.playingChannelId }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it.id == s.selectedChannelId }.coerceAtLeast(0)
        mode = OverlayMode.LIST
    }
    fun chooseGroup() {
        val s = vm.state
        val group = s.groups.getOrNull(groupIndex) ?: return
        if (group != s.selectedGroup) vm.selectGroup(group)
        val updated = vm.state
        listIndex = updated.visibleChannels.indexOfFirst { it.id == updated.playingChannelId }.takeIf { it >= 0 }
            ?: updated.visibleChannels.indexOfFirst { it.id == updated.selectedChannelId }.coerceAtLeast(0)
        mode = OverlayMode.LIST
    }
    fun activate() {
        activity++
        when (mode) {
            OverlayMode.LIST -> vm.state.visibleChannels.getOrNull(listIndex)?.let { vm.play(it.id); mode = OverlayMode.BANNER }
            OverlayMode.GROUPS -> chooseGroup()
            else -> if (playback.failed) playback.retry() else openList()
        }
    }
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // A long Back in the guide opens this screen while the key is still held; its
            // release must not count as a Back press here and close the player again.
            if (event.action == KeyEvent.ACTION_DOWN) { if (event.repeatCount == 0) backPressedHere[0] = true; return !backPressedHere[0] }
            return (!backPressedHere[0]).also { backPressedHere[0] = false }
        }
        if (isSelectKey(event.keyCode)) return select.handle(event, ::activate)
        val action = PlayerRemotePolicy.action(event.keyCode)
        if (action == PlayerRemoteAction.NONE || action == PlayerRemoteAction.TOGGLE_OVERLAY) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val first = event.repeatCount == 0
        activity++
        when (action) {
            PlayerRemoteAction.PLAY_PAUSE -> if (first) { playback.togglePause(); if (mode == OverlayMode.HIDDEN) mode = OverlayMode.BANNER }
            PlayerRemoteAction.PLAY -> if (first) playback.pause(false)
            PlayerRemoteAction.PAUSE -> if (first) playback.pause(true)
            else -> when (mode) {
                OverlayMode.LIST -> {
                    val last = vm.state.visibleChannels.lastIndex.coerceAtLeast(0)
                    when (action) {
                        PlayerRemoteAction.PREVIOUS_CHANNEL -> listIndex = (listIndex - 1).coerceIn(0, last)
                        PlayerRemoteAction.NEXT_CHANNEL -> listIndex = (listIndex + 1).coerceIn(0, last)
                        PlayerRemoteAction.SEEK_BACK -> if (first && canSwitchGroups) {
                            groupIndex = vm.state.groups.indexOf(vm.state.selectedGroup).coerceAtLeast(0)
                            mode = OverlayMode.GROUPS
                        }
                        PlayerRemoteAction.SEEK_FORWARD -> if (first) mode = OverlayMode.HIDDEN
                        else -> Unit
                    }
                }
                OverlayMode.GROUPS -> {
                    val last = vm.state.groups.lastIndex.coerceAtLeast(0)
                    when (action) {
                        PlayerRemoteAction.PREVIOUS_CHANNEL -> groupIndex = (groupIndex - 1).coerceIn(0, last)
                        PlayerRemoteAction.NEXT_CHANNEL -> groupIndex = (groupIndex + 1).coerceIn(0, last)
                        PlayerRemoteAction.SEEK_FORWARD -> if (first) chooseGroup()
                        else -> Unit
                    }
                }
                else -> when (action) {
                    PlayerRemoteAction.PREVIOUS_CHANNEL -> if (first) vm.playAdjacent(-1)
                    PlayerRemoteAction.NEXT_CHANNEL -> if (first) vm.playAdjacent(1)
                    // Live streams usually cannot seek; Left then opens the channel list instead.
                    PlayerRemoteAction.SEEK_BACK -> if (first) { if (playback.seek(false)) mode = OverlayMode.BANNER else openList() }
                    PlayerRemoteAction.SEEK_FORWARD -> if (first) { playback.seek(true); mode = OverlayMode.BANNER }
                    else -> Unit
                }
            }
        }
        return true
    }

    Box(Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { onKey(it.nativeKeyEvent) }.focusable()) {
        AnimatedVisibility(mode == OverlayMode.BANNER, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            InfoBanner(state, channel, now)
        }
        PlaybackStatus(playback, Modifier.align(Alignment.Center))
        AnimatedVisibility(
            mode == OverlayMode.LIST || mode == OverlayMode.GROUPS, Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            if (mode == OverlayMode.GROUPS) GroupList(state, groupIndex)
            else ChannelList(state, vm, listIndex, now, canSwitchGroups)
        }
    }
}

@Composable private fun InfoBanner(state: UiState, channel: Channel, now: Long) {
    val upcoming = currentAndNext(channel, state.programsFor(channel), now)
    val current = upcoming.firstOrNull()?.takeIf { it.startEpochMs <= now }
    val next = upcoming.firstOrNull { it !== current }
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xF2000000))))
            .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 28.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        ChannelLogo(channel, 112.dp, 63.dp)
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text("${channel.providerOrder + 1}  ${channel.displayName}", color = TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current?.title ?: channel.displayName, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current != null) {
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(time(current.startEpochMs), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp)); ProgressLine(progress(current, now), Modifier.width(300.dp)); Spacer(Modifier.width(10.dp))
                    Text(time(current.endEpochMs), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (next != null) Text("${time(next.startEpochMs)}  ${next.title}", Modifier.padding(top = 5.dp), color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(22.dp))
        Clock(fontSize = 26)
    }
}

@Composable private fun PlaybackStatus(playback: PlaybackController, modifier: Modifier) {
    when {
        playback.failed -> Column(modifier.clip(RoundedCornerShape(6.dp)).background(Glass).padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(44.dp), tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Playback unavailable", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (playback.diagnostic.isNotBlank()) Text(playback.diagnostic, color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text("OK  ·  Retry", color = Focus, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        playback.paused -> Icon(Icons.Default.PauseCircle, null, modifier.size(76.dp), tint = Color.White)
        playback.info.isNotBlank() -> Row(modifier.clip(RoundedCornerShape(6.dp)).background(Glass).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(12.dp)); Text(playback.info, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable private fun GlassPanel(title: String, trailing: String, showBack: Boolean, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(400.dp).fillMaxHeight().background(Glass)) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) Icon(Icons.Default.ChevronLeft, null, tint = TextMuted, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(8.dp))
                Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trailing, color = TextMuted, fontSize = 13.sp)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
        Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Glass, Color.Transparent))))
    }
}

@Composable private fun ChannelList(state: UiState, vm: MainViewModel, focusedIndex: Int, now: Long, canSwitchGroups: Boolean) {
    val channels = state.visibleChannels
    val firstRow = remember { intArrayOf(0) }
    GlassPanel(if (canSwitchGroups) state.selectedGroup else "Search", channels.size.toString(), canSwitchGroups) {
        val count = (maxHeight / ListRowHeight).toInt().coerceAtLeast(1)
        val rowHeight = maxHeight / count
        val first = ListWindow.first(firstRow[0], focusedIndex, count, channels.size, margin = 2)
        SideEffect { firstRow[0] = first }
        val windowIds = remember(channels, first, count) {
            (maxOf(0, first - 2) until minOf(channels.size, first + 2 * count)).map { channels[it].id }
        }
        val needsLoad = windowIds.any { it !in state.programsLoadedFor }
        LaunchedEffect(windowIds, needsLoad) { if (needsLoad) vm.ensurePrograms(windowIds) }
        Column {
            for (index in first until minOf(first + count, channels.size)) {
                val channel = channels[index]
                key(channel.id) {
                    val focused = index == focusedIndex
                    val playing = channel.id == state.playingChannelId
                    val current = currentAndNext(channel, state.programsFor(channel), now).firstOrNull()?.takeIf { it.startEpochMs <= now }
                    Row(Modifier.fillMaxWidth().height(rowHeight).background(if (focused) FocusFill else Color.Transparent).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (playing) Focus else Color.Transparent))
                        Text((channel.providerOrder + 1).toString(), Modifier.width(44.dp).padding(start = 8.dp), color = if (focused) OnFocus else TextMuted, fontSize = 13.sp, maxLines = 1)
                        ChannelLogo(channel, 52.dp, 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(channel.displayName, color = if (focused) OnFocus else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current != null) {
                                Text(current.title, color = if (focused) Color(0xFF3A4350) else TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ProgressLine(progress(current, now), Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp), track = if (focused) Color(0x33000000) else Color(0x33FFFFFF))
                            }
                        }
                        if (channel.favorite) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Star, null, tint = Warning, modifier = Modifier.size(13.dp)) }
                    }
                }
            }
        }
    }
}

@Composable private fun GroupList(state: UiState, focusedIndex: Int) {
    val groups = state.groups
    val firstRow = remember { intArrayOf(0) }
    GlassPanel("Categories", groups.size.toString(), false) {
        val count = (maxHeight / GroupRowHeight).toInt().coerceAtLeast(1)
        val first = ListWindow.first(firstRow[0], focusedIndex, count, groups.size, margin = 2)
        SideEffect { firstRow[0] = first }
        Column {
            for (index in first until minOf(first + count, groups.size)) {
                val group = groups[index]
                key(group) {
                    val focused = index == focusedIndex
                    val content = when { focused -> OnFocus; group == state.selectedGroup -> Focus; else -> TextPrimary }
                    Row(Modifier.fillMaxWidth().height(GroupRowHeight).background(if (focused) FocusFill else Color.Transparent).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (group) { "Favorites" -> Icons.Default.Star; "Recently watched" -> Icons.Default.History; else -> Icons.Default.Folder }, null, tint = content, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(group, Modifier.weight(1f), color = content, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.channelCountForGroup(group).toString(), color = if (focused) OnFocus else TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

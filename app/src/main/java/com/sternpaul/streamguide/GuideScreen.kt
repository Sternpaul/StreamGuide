package com.sternpaul.streamguide

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.CursorMove
import com.sternpaul.streamguide.core.GuideCursor
import com.sternpaul.streamguide.core.GuideTimeline
import com.sternpaul.streamguide.core.ListWindow
import com.sternpaul.streamguide.core.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The live picture sits in the guide's top-right corner; the root draws the video there. */
internal val PreviewWidth = 288.dp
internal val PreviewHeight = 162.dp
private val ChannelColumn = 216.dp
private val TimeHeaderHeight = 26.dp
private val TargetRowHeight = 44.dp
private val DrawerRowHeight = 40.dp
private const val MAX_AHEAD_MS = 7 * 24 * 3_600_000L

private enum class GuideMode { GRID, GROUPS, NAV }
private class NavItem(val label: String, val icon: ImageVector, val screen: AppScreen)
private val NavItems = listOf(
    NavItem("Live TV", Icons.Default.LiveTv, AppScreen.GUIDE),
    NavItem("Search", Icons.Default.Search, AppScreen.SEARCH),
    NavItem("Multiview", Icons.Default.GridView, AppScreen.MULTIVIEW),
    NavItem("Settings", Icons.Default.Settings, AppScreen.SETTINGS)
)

/**
 * Live TV. The whole screen is a single focus target: the remote moves a (row, time) cursor
 * instead of Compose focus, so navigation cannot skip sideways, get stuck or lag behind
 * key repeats.
 */
@Composable internal fun GuideScreen(state: UiState, vm: MainViewModel, playback: PlaybackController, dialogOpen: Boolean) {
    val focus = remember { FocusRequester() }
    var mode by remember { mutableStateOf(GuideMode.GRID) }
    var navIndex by remember { mutableIntStateOf(0) }
    val select = remember { PressTracker() }
    val pageSize = remember { intArrayOf(8) }
    val now = rememberNow()

    val channels = state.visibleChannels
    val indexById = remember(channels) { HashMap<String, Int>(channels.size * 2).apply { channels.forEachIndexed { i, c -> put(c.id, i) } } }
    val row = state.selectedChannelId?.let(indexById::get) ?: 0
    val focusedChannel = channels.getOrNull(row)
    val windowStart = state.timelineStart
    val windowEnd = windowStart + state.timelineHours * 3_600_000L
    val home = now / GuideCursor.STEP_MS * GuideCursor.STEP_MS
    val cursor = vm.guide.cursorTime

    LaunchedEffect(dialogOpen) { if (!dialogOpen) focus.requestFocus() }
    LaunchedEffect(mode, focusedChannel?.id) { if (mode == GuideMode.GRID) focusedChannel?.let { vm.selectChannel(it.id) } }
    LaunchedEffect(channels.isEmpty()) { if (channels.isEmpty() && state.channels.isNotEmpty()) { mode = GuideMode.GROUPS; vm.focusGroup(state.selectedGroup) } }
    BackHandler(enabled = !dialogOpen && (mode != GuideMode.GRID || cursor != null || windowStart > home)) {
        if (mode != GuideMode.GRID) mode = GuideMode.GRID else vm.jumpTimelineToNow()
    }

    // The handler reads live state: two key repeats can arrive within one frame.
    fun rowOf(s: UiState): Int = s.selectedChannelId?.let(indexById::get) ?: 0
    fun moveRow(delta: Int) {
        val s = vm.state
        val list = s.visibleChannels
        if (list.isEmpty()) return
        val current = rowOf(s).coerceIn(0, list.lastIndex)
        val target = (current + delta).coerceIn(0, list.lastIndex)
        if (target != current || s.selectedChannelId != list[target].id) vm.selectChannel(list[target].id)
    }
    fun openDrawer() { mode = GuideMode.GROUPS; vm.focusGroup(vm.state.selectedGroup) }
    fun moveInRow(forward: Boolean) {
        val s = vm.state
        val channel = s.visibleChannels.getOrNull(rowOf(s)) ?: run { if (!forward) openDrawer(); return }
        val clock = System.currentTimeMillis()
        val start = s.timelineStart
        val end = start + s.timelineHours * 3_600_000L
        val currentHome = clock / GuideCursor.STEP_MS * GuideCursor.STEP_MS
        val slices = GuideTimeline.slices(channel, s.programsFor(channel), start, end)
        val move: CursorMove = if (forward) GuideCursor.right(slices, vm.guide.cursorTime, clock, start, end, canShift = start < currentHome + MAX_AHEAD_MS)
            else GuideCursor.left(slices, vm.guide.cursorTime, clock, start, end, canShift = start > currentHome)
        if (move.leaveGrid) { openDrawer(); return }
        vm.guide.cursorTime = move.cursor
        if (move.shiftMs != 0L) vm.shiftTimeline(move.shiftMs)
    }
    fun focusedProgram(s: UiState, channel: Channel, clock: Long): Program? {
        val start = s.timelineStart
        val end = start + s.timelineHours * 3_600_000L
        val slices = GuideTimeline.slices(channel, s.programsFor(channel), start, end)
        return slices.getOrNull(GuideCursor.focusedIndex(slices, vm.guide.cursorTime, clock, start, end))?.program
    }
    fun chooseGroup(group: String) {
        vm.selectGroup(group)
        vm.guide.firstRow = 0
        mode = if (vm.state.visibleChannels.isEmpty()) GuideMode.GROUPS else GuideMode.GRID
    }
    fun moveGroup(delta: Int) {
        val s = vm.state
        val groups = s.groups
        val current = groups.indexOf(s.focusedGroup ?: s.selectedGroup).coerceAtLeast(0)
        groups.getOrNull((current + delta).coerceIn(0, groups.lastIndex))?.let(vm::focusGroup)
    }
    fun activate() {
        val s = vm.state
        when (mode) {
            GuideMode.GRID -> {
                val channel = s.visibleChannels.getOrNull(rowOf(s))
                if (channel == null) { if (s.channels.isEmpty()) vm.refresh() else openDrawer(); return }
                val clock = System.currentTimeMillis()
                val program = focusedProgram(s, channel, clock)
                if (program != null && program.endEpochMs <= clock && channel.catchupSource.isNotBlank() && channel.catchupDays > 0) vm.playCatchup(channel, program)
                else vm.play(channel.id)
            }
            GuideMode.GROUPS -> chooseGroup(s.focusedGroup ?: s.selectedGroup)
            GuideMode.NAV -> {
                val item = NavItems[navIndex.coerceIn(0, NavItems.lastIndex)]
                if (item.screen == AppScreen.GUIDE) mode = if (s.visibleChannels.isEmpty()) GuideMode.GROUPS else GuideMode.GRID
                else vm.navigate(item.screen)
            }
        }
    }
    fun toggleFavorite() {
        if (mode != GuideMode.GRID) return
        val s = vm.state
        val list = s.visibleChannels
        val index = rowOf(s)
        val channel = list.getOrNull(index) ?: return
        // Removing a favourite inside Favorites deletes this row; keep the cursor next to it.
        if (s.selectedGroup == "Favorites" && channel.favorite) (list.getOrNull(index + 1) ?: list.getOrNull(index - 1))?.let { vm.selectChannel(it.id) }
        vm.toggleFavorite(channel.id)
    }
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isSelectKey(code)) return select.handle(event, ::activate, ::toggleFavorite)
        val handled = code in HandledKeys
        if (event.action != KeyEvent.ACTION_DOWN || !handled) return handled
        when (mode) {
            GuideMode.GRID -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveInRow(false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveInRow(true)
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-pageSize[0])
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(pageSize[0])
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (event.repeatCount == 0) playback.togglePause()
            }
            GuideMode.GROUPS -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveGroup(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveGroup(1)
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveGroup(-8)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveGroup(8)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0) { navIndex = 0; mode = GuideMode.NAV }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) activate()
            }
            GuideMode.NAV -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> navIndex = (navIndex - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> navIndex = (navIndex + 1).coerceAtMost(NavItems.lastIndex)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) openDrawer()
            }
        }
        return true
    }

    val program = focusedChannel?.let { channel ->
        val slices = GuideTimeline.slices(channel, state.programsFor(channel), windowStart, windowEnd)
        slices.getOrNull(GuideCursor.focusedIndex(slices, cursor, now, windowStart, windowEnd))?.program
    }
    val following = if (focusedChannel != null && program != null) state.programsFor(focusedChannel).firstOrNull { it.startEpochMs >= program.endEpochMs } else null

    Box(Modifier.fillMaxSize().background(Bg).focusRequester(focus).onKeyEvent { onKey(it.nativeKeyEvent) }.focusable()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(PreviewHeight)) {
                InfoPanel(focusedChannel, program, following, now, state.loading, Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.size(PreviewWidth, PreviewHeight).background(Color.Black), contentAlignment = Alignment.Center) {
                    // Shown until a stream is running; the video then covers this slot.
                    focusedChannel?.let { ChannelLogo(it, 150.dp, 84.dp) }
                }
            }
            TimeHeader(state.selectedGroup, channels.size, windowStart, state.timelineHours, now)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val count = (maxHeight / TargetRowHeight).toInt().coerceAtLeast(1)
                val rowHeight = maxHeight / count
                val first = ListWindow.first(vm.guide.firstRow, row, count, channels.size, margin = 2)
                SideEffect { vm.guide.firstRow = first; pageSize[0] = count }
                val windowIds = remember(channels, first, count) {
                    (maxOf(0, first - 2) until minOf(channels.size, first + 2 * count)).map { channels[it].id }
                }
                val needsLoad = windowIds.any { it !in state.programsLoadedFor }
                LaunchedEffect(windowIds, needsLoad) { if (needsLoad) vm.ensurePrograms(windowIds) }
                val focusTime = GuideCursor.time(cursor, now, windowStart, windowEnd)
                val channelColumnPx = with(androidx.compose.ui.platform.LocalDensity.current) { ChannelColumn.toPx() }
                Column(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    if (now in windowStart until windowEnd) {
                        val x = channelColumnPx + (now - windowStart).toFloat() / (windowEnd - windowStart) * (size.width - channelColumnPx)
                        drawLine(Live, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                }) {
                    for (index in first until minOf(first + count, channels.size)) {
                        val channel = channels[index]
                        key(channel.id) {
                            GuideRow(
                                channel, state.programsFor(channel), windowStart, windowEnd, now,
                                focusTime = if (index == row) focusTime else Long.MIN_VALUE,
                                gridActive = mode == GuideMode.GRID,
                                playing = channel.id == state.playingChannelId,
                                height = rowHeight
                            )
                        }
                    }
                }
                if (channels.isEmpty()) {
                    Text(
                        when {
                            state.channels.isEmpty() -> "No channels · press OK to update"
                            state.selectedGroup == "Favorites" -> "No favorites · hold OK on a channel"
                            else -> "No channels"
                        },
                        Modifier.align(Alignment.Center), color = TextMuted, fontSize = 16.sp
                    )
                }
            }
        }
        if (mode != GuideMode.GRID) GuideDrawer(state, mode == GuideMode.NAV, navIndex)
    }
}

private val HandledKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
)

@Composable private fun InfoPanel(channel: Channel?, program: Program?, following: Program?, now: Long, loading: Boolean, modifier: Modifier) {
    Column(modifier.clipToBounds().padding(start = 18.dp, end = 16.dp, top = 9.dp, bottom = 6.dp)) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (channel != null) {
                Text((channel.providerOrder + 1).toString(), color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.width(9.dp)); ChannelLogo(channel, 38.dp, 22.dp)
                Spacer(Modifier.width(9.dp)); Text(channel.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (channel.favorite) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Star, null, tint = Warning, modifier = Modifier.size(15.dp)) }
                if (channel.locked) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(14.dp)) }
                if (channel.catchupSource.isNotBlank() && channel.catchupDays > 0) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Replay, null, tint = Focus, modifier = Modifier.size(15.dp)) }
            }
            Spacer(Modifier.weight(1f))
            if (loading) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Focus); Spacer(Modifier.width(12.dp)) }
            Clock()
        }
        Spacer(Modifier.height(3.dp))
        Text(program?.title ?: channel?.displayName.orEmpty(), fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (program != null) {
            val live = now in program.startEpochMs until program.endEpochMs
            Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${dayPrefix(program.startEpochMs, now)}${time(program.startEpochMs)} – ${time(program.endEpochMs)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("  ·  ${(program.endEpochMs - program.startEpochMs) / 60_000} min", color = TextMuted, fontSize = 13.sp)
                if (live) {
                    Text("  ·  ${((program.endEpochMs - now) / 60_000).coerceAtLeast(0)} min left", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.width(14.dp)); ProgressLine(progress(program, now), Modifier.width(200.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            val nextLine = following?.let { "${time(it.startEpochMs)}  ${it.title}" }
            if (program.description.isNotBlank()) {
                Text(program.description, color = TextMuted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = if (nextLine == null) 4 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (nextLine != null) {
                Spacer(Modifier.weight(1f))
                Text(nextLine, color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun dayPrefix(epoch: Long, now: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return if (day.format(Date(epoch)) == day.format(Date(now))) "" else SimpleDateFormat("EEE ", Locale.getDefault()).format(Date(epoch))
}

@Composable private fun TimeHeader(group: String, channelCount: Int, windowStart: Long, hours: Int, now: Long) {
    Row(Modifier.fillMaxWidth().height(TimeHeaderHeight).background(Panel), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ChevronLeft, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Text(group, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(channelCount.toString(), color = TextMuted, fontSize = 12.sp)
        }
        repeat(hours * 2) { index ->
            val instant = windowStart + index * GuideCursor.STEP_MS
            val isNow = now in instant until instant + GuideCursor.STEP_MS
            Text(
                (if (index == 0) dayPrefix(instant, now) else "") + time(instant),
                Modifier.weight(1f).padding(start = 8.dp), color = if (isNow) TextPrimary else TextMuted,
                fontSize = 12.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, maxLines = 1
            )
        }
    }
}

@Composable private fun GuideRow(
    channel: Channel, programs: List<Program>, windowStart: Long, windowEnd: Long, now: Long,
    focusTime: Long, gridActive: Boolean, playing: Boolean, height: Dp
) {
    val slices = remember(channel, programs, windowStart, windowEnd) { GuideTimeline.slices(channel, programs, windowStart, windowEnd) }
    val rowFocused = focusTime != Long.MIN_VALUE
    Row(Modifier.fillMaxWidth().height(height).padding(bottom = 1.dp)) {
        Row(
            Modifier.width(ChannelColumn).fillMaxHeight().background(if (rowFocused) RowActive else Cell).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (playing) Focus else Color.Transparent))
            Text((channel.providerOrder + 1).toString(), Modifier.width(38.dp).padding(start = 7.dp), color = TextMuted, fontSize = 12.sp, maxLines = 1)
            ChannelLogo(channel, 44.dp, 26.dp)
            Spacer(Modifier.width(9.dp))
            Text(channel.displayName, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (rowFocused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.favorite) Icon(Icons.Default.Star, null, tint = Warning, modifier = Modifier.size(12.dp))
            if (channel.locked) Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(1.dp))
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            slices.forEach { slice ->
                val focused = rowFocused && focusTime >= slice.visibleStart && focusTime < slice.visibleEnd
                val program = slice.program
                val live = program != null && now in program.startEpochMs until program.endEpochMs
                val past = slice.visibleEnd <= now
                val background = when {
                    focused && gridActive -> FocusFill
                    focused -> RowActive
                    live -> CellLive
                    past || program == null -> CellPast
                    else -> Cell
                }
                Box(
                    Modifier.weight((slice.visibleEnd - slice.visibleStart).coerceAtLeast(1L).toFloat()).fillMaxHeight().background(background).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (program != null) Text(
                        program.title, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (focused || live) FontWeight.SemiBold else FontWeight.Normal,
                        color = when { focused && gridActive -> OnFocus; past -> TextMuted; else -> TextPrimary }
                    )
                }
            }
        }
    }
}

/** Categories slide in over the grid; one more Left reveals the app destinations. */
@Composable private fun GuideDrawer(state: UiState, navActive: Boolean, navIndex: Int) {
    val firstGroup = remember { intArrayOf(0) }
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(if (navActive) 190.dp else 52.dp).fillMaxHeight().background(Color(0xFF0C0F14)).padding(vertical = 12.dp)) {
            NavItems.forEachIndexed { index, item ->
                val focused = navActive && index == navIndex
                Row(
                    Modifier.fillMaxWidth().height(46.dp).background(if (focused) FocusFill else Color.Transparent).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tint = when { focused -> OnFocus; item.screen == AppScreen.GUIDE -> Focus; else -> TextMuted }
                    Icon(item.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    if (navActive) { Spacer(Modifier.width(14.dp)); Text(item.label, color = if (focused) OnFocus else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                }
            }
        }
        BoxWithConstraints(Modifier.width(270.dp).fillMaxHeight().background(Panel)) {
            val groups = state.groups
            val count = (maxHeight / DrawerRowHeight).toInt().coerceAtLeast(1)
            val focusedIndex = groups.indexOf(state.focusedGroup ?: state.selectedGroup).coerceAtLeast(0)
            val first = ListWindow.first(firstGroup[0], focusedIndex, count, groups.size, margin = 2)
            SideEffect { firstGroup[0] = first }
            Column {
                for (index in first until minOf(first + count, groups.size)) {
                    val group = groups[index]
                    key(group) {
                        val focused = index == focusedIndex
                        val open = group == state.selectedGroup
                        val content = when { focused && !navActive -> OnFocus; open -> Focus; else -> TextPrimary }
                        Row(
                            Modifier.fillMaxWidth().height(DrawerRowHeight)
                                .background(when { focused && !navActive -> FocusFill; focused -> RowActive; else -> Color.Transparent })
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(when (group) { "Favorites" -> Icons.Default.Star; "Recently watched" -> Icons.Default.History; else -> Icons.Default.Folder }, null, tint = content, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(group, Modifier.weight(1f), color = content, fontSize = 14.sp, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.channelCountForGroup(group).toString(), color = if (focused && !navActive) OnFocus else TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Box(Modifier.width(28.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0xB3000000), Color.Transparent))))
    }
}

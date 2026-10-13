@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sternpaul.streamguide

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Bg = Color(0xFF0A0D12)
internal val Panel = Color(0xFF12161D)
internal val Panel2 = Color(0xFF1A2029)
internal val Cell = Color(0xFF161B23)
internal val CellLive = Color(0xFF212C3B)
internal val CellPast = Color(0xFF0F1318)
internal val RowActive = Color(0xFF2A3545)
/** Accent for progress, "now" and playing markers. */
internal val Focus = Color(0xFF3D8BFD)
/** The one focus style used everywhere: a bright fill with dark content. */
internal val FocusFill = Color(0xFFE8EDF4)
internal val OnFocus = Color(0xFF0B0F14)
internal val TextPrimary = Color(0xFFF3F6FA)
internal val TextMuted = Color(0xFFA3AEBE)
internal val Success = Color(0xFF36B37E)
internal val Warning = Color(0xFFF4B740)
internal val Live = Color(0xFFE5484D)

internal fun time(epoch: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))

internal fun currentAndNext(channel: Channel, programs: List<Program>, now: Long = System.currentTimeMillis()): List<Program> {
    val ids = setOf(channel.id, channel.tvgId).filter { it.isNotBlank() }.toSet()
    return programs.asSequence().filter { it.channelId in ids && it.endEpochMs > now }.sortedBy { it.startEpochMs }.take(2).toList()
}

internal fun progress(program: Program, now: Long): Float =
    ((now - program.startEpochMs).toFloat() / (program.endEpochMs - program.startEpochMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

/** Wall clock that ticks often enough for progress bars and the "now" line. */
@Composable internal fun rememberNow(intervalMs: Long = 20_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(intervalMs); value = System.currentTimeMillis() }
    }
    return now
}

@Composable internal fun Clock(fontSize: Int = 20, color: Color = TextPrimary) {
    Text(time(rememberNow(10_000L)), fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp, color = color)
}

@Composable internal fun ChannelLogo(channel: Channel, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    if (channel.logoUrl.isNotBlank()) {
        AsyncImage(channel.logoUrl, null, modifier.size(width, height), contentScale = ContentScale.Fit)
    } else {
        Box(modifier.size(width, height).clip(RoundedCornerShape(3.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            Text(channel.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (height.value * 0.5f).sp, color = TextMuted)
        }
    }
}

@Composable internal fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Focus, track: Color = Color(0x33FFFFFF)) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
internal fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        danger && focused -> Live
        focused -> FocusFill
        selected -> Color(0xFF1F3A5F)
        else -> Panel2
    }
    Surface(
        modifier.onFocusChanged { focused = it.isFocused }.combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = bg,
        contentColor = if (focused && !danger) OnFocus else TextPrimary
    ) {
        // Secondary text inside a focused button must stay readable on the bright fill.
        CompositionLocalProvider(LocalMutedColor provides if (focused && !danger) Color(0xFF3A4350) else TextMuted) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
internal val LocalMutedColor = compositionLocalOf { TextMuted }

/**
 * Tracks one remote key so a screen with a single focus target can tell a click from a
 * long press, and never acts on a release whose press belonged to another screen.
 */
internal class PressTracker {
    private var down = false
    private var longFired = false
    /** Returns true when the event was consumed. */
    fun handle(event: KeyEvent, onClick: () -> Unit, onLongPress: (() -> Unit)? = null): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) { down = true; longFired = false }
                else if (down && !longFired && onLongPress != null) { longFired = true; onLongPress() }
            }
            KeyEvent.ACTION_UP -> {
                val wasDown = down
                down = false
                if (wasDown && !longFired) onClick()
            }
        }
        return true
    }
}

/**
 * Focusable item whose Select key distinguishes a click from a long press. Compose's own
 * combinedClickable only reports long presses from touch, never from a remote.
 */
@Composable internal fun Modifier.remoteClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val tracker = remember { PressTracker() }
    return this.onKeyEvent { event ->
        isSelectKey(event.nativeKeyEvent.keyCode) && tracker.handle(event.nativeKeyEvent, onClick, onLongClick)
    }.focusable()
}

internal fun isSelectKey(keyCode: Int) =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

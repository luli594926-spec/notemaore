package com.xnotes.video

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.formatTimestamp

/** Where a control bar sits, which decides its last buttons. */
internal enum class ControlsHost { INLINE, FLOATING, FULLSCREEN }

internal val ScrimColor = Color(0xB3000000)
private val OnScrim = Color.White

/** A 48dp, hover-aware icon button: big enough for a finger, with a hand cursor under a hovering pen or mouse. */
@Composable
internal fun VideoIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = OnScrim) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * Transport controls for [pb]: a seek bar with times over a row of buttons that scrolls sideways
 * when the window is narrow. [compact] drops the time label beside the slider (small floating
 * windows). Pausing reports to [onPaused] so the resume point can be written into the note.
 */
@Composable
internal fun VideoControls(
    session: VideoSession,
    pb: Playback,
    host: ControlsHost,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier.background(ScrimColor).padding(horizontal = 4.dp)) {
        SeekRow(pb, compact)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VideoIconButton(
                if (pb.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (pb.isPlaying) R.string.video_pause else R.string.video_play),
                onClick = {
                    if (pb.isPlaying) {
                        pb.pause()
                        session.notePaused(pb)
                    } else {
                        pb.play()
                    }
                },
            )
            VideoIconButton(Icons.Filled.Replay10, stringResource(R.string.video_back10), onClick = { pb.skip(-PlayerPool.SKIP_MS) })
            VideoIconButton(Icons.Filled.Forward10, stringResource(R.string.video_forward10), onClick = { pb.skip(PlayerPool.SKIP_MS) })
            SpeedButton(pb)
            VideoIconButton(Icons.Filled.PhotoCamera, stringResource(R.string.video_capture), onClick = { session.captureFrame(pb) })
            VideoIconButton(Icons.Filled.AddLink, stringResource(R.string.video_insert_link), onClick = { session.insertLink(pb) })
            when (host) {
                ControlsHost.INLINE -> session.itemOf(pb)?.let { item ->
                    VideoIconButton(Icons.Filled.PictureInPictureAlt, stringResource(R.string.video_pop_out), onClick = { session.popOut(item) })
                }
                ControlsHost.FLOATING ->
                    VideoIconButton(Icons.Filled.PushPin, stringResource(R.string.video_pin), onClick = { session.pinToCanvas() })
                ControlsHost.FULLSCREEN -> Unit
            }
            if (host == ControlsHost.FULLSCREEN) {
                VideoIconButton(Icons.Filled.FullscreenExit, stringResource(R.string.video_exit_fullscreen), onClick = { session.exitFullscreen() })
            } else {
                VideoIconButton(Icons.Filled.Fullscreen, stringResource(R.string.video_fullscreen), onClick = { session.enterFullscreen(pb) })
            }
            if (host == ControlsHost.INLINE) {
                session.itemOf(pb)?.let { item ->
                    VideoIconButton(Icons.Filled.Close, stringResource(R.string.video_stop), onClick = { session.deactivate(item) })
                }
            }
        }
    }
}

@Composable
private fun SeekRow(pb: Playback, compact: Boolean) {
    // While dragging, the thumb follows the finger rather than the (lagging) player position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = pb.durationMs.coerceAtLeast(0L)
    val fraction = if (duration > 0) (pb.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { if (duration > 0) pb.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = OnScrim, activeTrackColor = OnScrim, inactiveTrackColor = OnScrim.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).pointerHoverIcon(PointerIcon.Hand),
        )
        if (!compact) {
            val shown = dragging?.let { (it * duration).toLong() } ?: pb.positionMs
            Text(
                "${formatTimestamp(shown / 1000.0)} / ${formatTimestamp(duration / 1000.0)}",
                color = OnScrim,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    pb.error?.let { err ->
        Text(
            stringResource(R.string.video_error, err),
            color = Color(0xFFFFB4AB),
            fontSize = 12.sp,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun SpeedButton(pb: Playback) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.height(48.dp).width(64.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(speedLabel(pb.speed), color = OnScrim, fontSize = 14.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.video_speed),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            for (s in Playback.SPEEDS) {
                DropdownMenuItem(
                    text = { Text(speedLabel(s) + if (s == pb.speed) "  ✓" else "") },
                    onClick = { pb.setSpeed(s); open = false },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
    Spacer(Modifier.width(2.dp))
}

private fun speedLabel(s: Float): String =
    (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

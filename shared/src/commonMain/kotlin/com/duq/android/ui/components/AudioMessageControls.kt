package com.duq.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.ui.theme.DuqColors

/**
 * Playback state for audio messages
 */
enum class AudioPlaybackState {
    IDLE,       // Not playing
    LOADING,    // Downloading/buffering
    PLAYING,    // Currently playing
    PAUSED      // Paused mid-playback
}

/**
 * Audio playback controls for voice messages in chat.
 *
 * @param state Current playback state
 * @param durationMs Audio duration in milliseconds (null if unknown)
 * @param progress Playback progress (0.0-1.0)
 * @param onPlayPauseClick Callback when play/pause button is clicked
 */
@Composable
fun AudioMessageControls(
    state: AudioPlaybackState,
    durationMs: Int?,
    progress: Float = 0f,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100),
        label = "audio_progress"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Play/Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DuqColors.primary.copy(alpha = 0.2f))
                .clickable(enabled = state != AudioPlaybackState.LOADING) { onPlayPauseClick() },
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                AudioPlaybackState.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DuqColors.primary,
                        strokeWidth = 2.dp
                    )
                }
                AudioPlaybackState.PLAYING -> {
                    // Pause icon (two vertical bars)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(14.dp)
                                .background(DuqColors.primary, RoundedCornerShape(1.dp))
                        )
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(14.dp)
                                .background(DuqColors.primary, RoundedCornerShape(1.dp))
                        )
                    }
                }
                else -> {
                    // Play icon (triangle)
                    Text(
                        text = "▶",
                        fontSize = 14.sp,
                        color = DuqColors.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DuqColors.primary.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(4.dp)
                    .background(DuqColors.primary)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Duration text
        Text(
            text = formatDuration(durationMs),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = DuqColors.textSecondary
        )
    }
}

/**
 * Format duration from milliseconds to MM:SS or M:SS
 */
private fun formatDuration(durationMs: Int?): String {
    if (durationMs == null || durationMs <= 0) return "0:00"

    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

package com.duq.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.audio.PlaybackInfo
import com.duq.android.audio.PlaybackState
import com.duq.android.data.model.Message
import com.duq.android.ui.theme.DuqColors

/**
 * List of chat messages with audio playback support.
 * No loading spinners - uses optimistic updates.
 *
 * @param messages List of messages to display
 * @param audioPlaybackInfo Current audio playback info (which message is playing, progress, etc.)
 * @param onAudioPlayPauseClick Callback when play/pause is clicked for a message
 * @param modifier Modifier
 */
@Composable
fun MessagesList(
    messages: List<Message>,
    audioPlaybackInfo: PlaybackInfo = PlaybackInfo(),
    onAudioPlayPauseClick: (String) -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier
) {
    // Reverse the messages so newest is first (for reverseLayout)
    val reversedMessages = remember(messages) { messages.asReversed() }
    val listState = rememberLazyListState()

    // Track message count to detect new messages
    var lastMessageCount by remember { mutableIntStateOf(0) }

    // Animate scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.size > lastMessageCount && lastMessageCount > 0) {
            // New message - animate to bottom (index 0 in reversed list)
            listState.animateScrollToItem(0)
        }
        lastMessageCount = messages.size
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Text(
                text = "Пока пусто\nЗажми микрофон и говори — или напиши сообщение",
                color = DuqColors.textTertiary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        } else {
            // reverseLayout = true means item[0] is at BOTTOM
            // So we reverse messages: newest first, displayed at bottom
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(reversedMessages, key = { it.id }) { message ->
                    // Determine audio state for this specific message
                    val isCurrentlyPlaying = audioPlaybackInfo.messageId == message.id
                    val audioState = if (message.isAudioLoading) {
                        // Идёт ре-синтез озвучки → спиннер + клик заблокирован (см. AudioMessageControls).
                        AudioPlaybackState.LOADING
                    } else if (isCurrentlyPlaying) {
                        when (audioPlaybackInfo.state) {
                            PlaybackState.LOADING -> AudioPlaybackState.LOADING
                            PlaybackState.PLAYING -> AudioPlaybackState.PLAYING
                            PlaybackState.PAUSED -> AudioPlaybackState.PAUSED
                            PlaybackState.IDLE -> AudioPlaybackState.IDLE
                        }
                    } else {
                        AudioPlaybackState.IDLE
                    }
                    val progress = if (isCurrentlyPlaying) audioPlaybackInfo.progress else 0f
                    // Длительность — живая из PlaybackInfo (Message.audioDurationMs не заполняется).
                    val durationMs = if (isCurrentlyPlaying && audioPlaybackInfo.durationMs > 0) {
                        audioPlaybackInfo.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        null
                    }

                    MessageBubble(
                        message = message,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = message.isStreaming,
                        audioPlaybackState = audioState,
                        audioProgress = progress,
                        audioDurationMs = durationMs,
                        onAudioPlayPauseClick = { onAudioPlayPauseClick(message.id) },
                        hazeState = hazeState
                    )
                }
            }
        }
    }
}

package com.duq.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duq.android.ui.theme.DuqColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Voice waveform visualization component.
 *
 * 2026 Voice-First UI trend: Real-time audio visualization
 * shows users that the app is actively listening/processing.
 *
 * @param amplitudes List of amplitude values (0.0-1.0)
 * @param isActive Whether recording/playback is active
 * @param barCount Number of bars to display
 * @param barWidth Width of each bar
 * @param barSpacing Spacing between bars
 * @param minBarHeight Minimum bar height
 * @param maxBarHeight Maximum bar height
 * @param color Primary bar color (defaults to AI cyan)
 */
@Composable
@Suppress("LongParameterList")
fun VoiceWaveform(
    amplitudes: List<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 32,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
    minBarHeight: Float = 0.1f,
    maxBarHeight: Float = 1.0f,
    color: Color = DuqColors.primary
) {
    // Animated phase for idle wave effect
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val barWidthPx = barWidth.toPx()
        val barSpacingPx = barSpacing.toPx()
        val totalBarWidth = barWidthPx + barSpacingPx
        val centerY = canvasHeight / 2

        val effectiveBarCount = minOf(barCount, (canvasWidth / totalBarWidth).toInt())

        for (i in 0 until effectiveBarCount) {
            val x = (canvasWidth - effectiveBarCount * totalBarWidth) / 2 + i * totalBarWidth + barWidthPx / 2

            // Get amplitude from list or generate smooth idle wave
            val amplitude = if (isActive && amplitudes.isNotEmpty()) {
                val index = (i.toFloat() / effectiveBarCount * amplitudes.size).toInt()
                    .coerceIn(0, amplitudes.lastIndex)
                amplitudes[index].coerceIn(minBarHeight, maxBarHeight)
            } else {
                // Idle sine wave animation
                val wave = sin(wavePhase + i * 0.3f)
                (0.15f + wave * 0.1f).coerceIn(minBarHeight, 0.3f)
            }

            val barHeight = canvasHeight * amplitude
            val startY = centerY - barHeight / 2
            val endY = centerY + barHeight / 2

            // Draw bar with rounded caps
            drawLine(
                color = color.copy(alpha = if (isActive) 1f else 0.6f),
                start = Offset(x, startY),
                end = Offset(x, endY),
                strokeWidth = barWidthPx,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Circular voice waveform for recording indicator.
 * Used around the Arc Reactor for voice state visualization.
 */
@Composable
fun CircularVoiceWaveform(
    amplitudes: List<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    color: Color = DuqColors.primary,
    radius: Dp = 60.dp,
    barCount: Int = 24
) {
    val infiniteTransition = rememberInfiniteTransition(label = "circular_wave")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radiusPx = radius.toPx()

        for (i in 0 until barCount) {
            val angle = (rotation + i * (360f / barCount)) * (PI / 180f).toFloat()

            // Get amplitude
            val amplitude = if (isActive && amplitudes.isNotEmpty()) {
                val index = (i.toFloat() / barCount * amplitudes.size).toInt()
                    .coerceIn(0, amplitudes.lastIndex)
                amplitudes[index].coerceIn(0.1f, 1f)
            } else {
                0.3f + sin(rotation * 0.05f + i * 0.2f).toFloat() * 0.1f
            }

            val innerRadius = radiusPx * 0.8f
            val outerRadius = radiusPx * (0.8f + amplitude * 0.4f)

            val startX = centerX + innerRadius * cos(angle)
            val startY = centerY + innerRadius * sin(angle)
            val endX = centerX + outerRadius * cos(angle)
            val endY = centerY + outerRadius * sin(angle)

            drawLine(
                color = color.copy(alpha = if (isActive) amplitude else 0.4f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Generates fake amplitude data for demo/idle states.
 * Returns a flow of amplitude lists.
 */
@Composable
fun rememberFakeAmplitudes(
    barCount: Int = 32,
    isActive: Boolean = false
): List<Float> {
    val amplitudes = remember { mutableStateListOf<Float>() }

    LaunchedEffect(isActive) {
        while (isActive) {
            amplitudes.clear()
            repeat(barCount) {
                amplitudes.add(Random.nextFloat() * 0.6f + 0.2f)
            }
            delay(50) // ~20fps
        }
    }

    return if (isActive) amplitudes.toList() else emptyList()
}

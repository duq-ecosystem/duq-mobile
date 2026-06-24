package com.duq.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.duq.android.ui.DuqState
import com.duq.android.ui.theme.DuqColors

/**
 * DuqOrb - A clean, modern visualization of Duq's state.
 * Replaces the Iron Man Arc Reactor with an elegant pulsing orb.
 */
@Composable
fun ArcReactor(
    state: DuqState?,
    modifier: Modifier = Modifier
) {
    // Get color based on state
    val targetColor = when (state) {
        DuqState.IDLE -> DuqColors.idle
        DuqState.LISTENING, DuqState.RECORDING -> DuqColors.listening
        DuqState.PROCESSING -> DuqColors.processing
        DuqState.PLAYING -> DuqColors.speaking
        DuqState.ERROR -> DuqColors.errorState
        null -> DuqColors.textTertiary
    }

    // Animate color transitions smoothly
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "orbColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbAnimation")

    // Pulse speed based on state
    val pulseSpeed = when (state) {
        DuqState.LISTENING, DuqState.RECORDING -> 800
        DuqState.PROCESSING -> 400
        DuqState.PLAYING -> 1000
        DuqState.ERROR -> 300
        else -> 2500
    }

    // Breathing/pulse animation
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseSpeed, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Rotation for processing state
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == DuqState.PROCESSING) 1500 else 8000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Secondary ring pulse
    val ringPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseSpeed * 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )

    val isActive = state != null && state != DuqState.ERROR

    Canvas(modifier = modifier.size(180.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        // Outer glow - soft ambient
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedColor.copy(alpha = 0.15f * breathe),
                    animatedColor.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.4f
            ),
            radius = radius * 1.4f,
            center = center
        )

        // Outer ring - thin elegant line
        rotate(rotation * 0.3f, center) {
            drawCircle(
                color = animatedColor.copy(alpha = 0.3f * ringPulse),
                radius = radius * 0.92f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Middle ring with dash pattern (for processing)
        if (state == DuqState.PROCESSING) {
            rotate(rotation, center) {
                val dashCount = 12
                val dashAngle = 360f / dashCount
                val dashSweep = dashAngle * 0.4f

                for (i in 0 until dashCount) {
                    drawArc(
                        color = animatedColor.copy(alpha = 0.8f),
                        startAngle = i * dashAngle - 90f,
                        sweepAngle = dashSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 0.75f, center.y - radius * 0.75f),
                        size = Size(radius * 1.5f, radius * 1.5f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        } else {
            // Static ring when not processing
            drawCircle(
                color = animatedColor.copy(alpha = 0.2f),
                radius = radius * 0.75f,
                center = center,
                style = Stroke(width = 1.5f.dp.toPx())
            )
        }

        // Inner core - the main orb
        val coreRadius = radius * 0.45f * breathe
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (isActive) {
                    listOf(
                        animatedColor.copy(alpha = 0.9f),
                        animatedColor.copy(alpha = 0.6f),
                        animatedColor.copy(alpha = 0.2f)
                    )
                } else {
                    listOf(
                        animatedColor.copy(alpha = 0.4f),
                        animatedColor.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                },
                center = center,
                radius = coreRadius * 1.2f
            ),
            radius = coreRadius,
            center = center
        )

        // Core highlight - adds depth
        if (isActive) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f * breathe),
                        Color.White.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    center = Offset(center.x - coreRadius * 0.2f, center.y - coreRadius * 0.2f),
                    radius = coreRadius * 0.6f
                ),
                radius = coreRadius * 0.5f,
                center = Offset(center.x - coreRadius * 0.15f, center.y - coreRadius * 0.15f)
            )
        }

        // Innermost dot - the "eye"
        drawCircle(
            color = if (isActive) Color.White.copy(alpha = 0.9f * breathe) else animatedColor.copy(alpha = 0.3f),
            radius = radius * 0.08f,
            center = center
        )
    }
}

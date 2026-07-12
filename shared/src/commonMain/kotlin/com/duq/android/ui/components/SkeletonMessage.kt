package com.duq.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.duq.android.ui.theme.DuqColors

/** Skeleton-загрузка пузыря сообщения (shimmer, glass-morphism). */
@Composable
fun SkeletonMessage(modifier: Modifier = Modifier, isUserMessage: Boolean = false) {
    val shimmerBrush = rememberShimmerBrush()
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUserMessage) 16.dp else 4.dp,
        bottomEnd = if (isUserMessage) 4.dp else 16.dp
    )
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 12.dp),
        contentAlignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShape)
                .background(DuqColors.glassSurface).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!isUserMessage) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(shimmerBrush))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(40.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                }
            }
            Box(Modifier.fillMaxWidth(0.85f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.65f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.45f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
        }
    }
}

/** Skeleton элемента списка диалогов. */
@Composable
fun SkeletonConversationItem(modifier: Modifier = Modifier) {
    val shimmerBrush = rememberShimmerBrush()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(shimmerBrush))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
        }
    }
}

/** Анимированный shimmer-brush (glass-morphism). */
@Composable
fun rememberShimmerBrush(): Brush {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translation"
    )
    return Brush.linearGradient(
        colors = listOf(DuqColors.glassSurface, DuqColors.glassHighlight, DuqColors.glassSurface),
        start = Offset(shimmerTranslation - 200f, 0f),
        end = Offset(shimmerTranslation, 0f)
    )
}

/** Loading-состояние списка сообщений. */
@Composable
fun SkeletonMessageList(count: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        repeat(count) { index -> SkeletonMessage(isUserMessage = index % 2 == 0) }
    }
}

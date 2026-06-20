package com.duq.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.duq.android.DuqState
import com.duq.android.R

/**
 * DuqDuck — каноническая резиновая утка + цветной статус-halo.
 *
 * Силуэт — векторный asset [R.drawable.ic_rubber_duck]. Поверх — живые Compose-анимации:
 * покачивание (bob), наклон головы (tilt), дыхание-пульс (scale) И ГЛАВНОЕ — пульсирующий
 * радиальный glow-halo за уткой, ЦВЕТ которого зависит от [state]: запись — красный,
 * обработка/распознавание — фиолетовый, проигрывание — зелёный, слушает — жёлтый,
 * ошибка — алый. Цвет плавно перетекает между состояниями (animateColor), halo дышит
 * (радиус+alpha), на активных фазах — заметно ярче и быстрее. В IDLE halo почти гаснет.
 */
@Composable
fun DuqDuck(
    state: DuqState?,
    modifier: Modifier = Modifier,
    silhouetteAlpha: Float = 1f   // прозрачность ТОЛЬКО силуэта; halo всегда полной яркости
) {
    val transition = rememberInfiniteTransition(label = "duck")

    // ── Цвет halo по статусу (плавный переход между состояниями) ──
    val targetHalo = when (state) {
        DuqState.RECORDING -> Color(0xFFFF5252)            // запись — красный/коралл
        DuqState.LISTENING -> Color(0xFFFFD54F)            // слушает — тёплый жёлтый
        DuqState.PROCESSING -> Color(0xFF7C4DFF)           // распознаю/думаю — фиолетовый
        DuqState.PLAYING -> Color(0xFF00E676)              // озвучка — зелёный
        DuqState.ERROR -> Color(0xFFFF1744)                // ошибка — алый
        else -> Color(0xFF5B8DEF)                          // idle — спокойный синий (еле виден)
    }
    val haloColor by animateColorAsState(
        targetValue = targetHalo,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "haloColor"
    )

    // Насколько halo яркий в этом состоянии (idle — почти погашен).
    val haloPeak = when (state) {
        DuqState.RECORDING, DuqState.ERROR -> 0.85f
        DuqState.PROCESSING -> 0.75f
        DuqState.PLAYING, DuqState.LISTENING -> 0.65f
        else -> 0.18f
    }
    val haloMs = when (state) {
        DuqState.RECORDING, DuqState.ERROR -> 520
        DuqState.PROCESSING -> 680
        DuqState.PLAYING, DuqState.LISTENING -> 820
        else -> 2600
    }
    // Пульс halo: alpha и радиус дышат синхронно — «вспышка».
    val haloPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(haloMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloPulse"
    )

    // Покачивание на воде — быстрее при активности.
    val bobMs = when (state) {
        DuqState.LISTENING, DuqState.RECORDING -> 480
        DuqState.PROCESSING -> 300
        DuqState.PLAYING -> 600
        else -> 1500
    }
    val bob by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(bobMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob"
    )

    // Наклон головы — сильнее/быстрее когда слушает/пишет.
    val tiltDeg = when (state) {
        DuqState.LISTENING, DuqState.RECORDING -> 9f
        DuqState.PROCESSING -> 6f
        else -> 3f
    }
    val tiltMs = if (state == DuqState.LISTENING || state == DuqState.RECORDING) 420 else 2000
    val tilt by transition.animateFloat(
        initialValue = -tiltDeg, targetValue = tiltDeg,
        animationSpec = infiniteRepeatable(tween(tiltMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tilt"
    )

    // Дыхание/пульс силуэта — ЗАМЕТНО усилен (раньше 0.02–0.06 — «еле видно»).
    val pulseAmp = when (state) {
        DuqState.RECORDING -> 0.12f
        DuqState.PROCESSING -> 0.10f
        DuqState.PLAYING -> 0.09f
        DuqState.LISTENING -> 0.08f
        else -> 0.03f
    }
    val pulseMs = when (state) {
        DuqState.PROCESSING -> 380
        DuqState.PLAYING -> 520
        DuqState.LISTENING, DuqState.RECORDING -> 440
        else -> 2200
    }
    val pulse by transition.animateFloat(
        initialValue = 1f - pulseAmp, targetValue = 1f + pulseAmp,
        animationSpec = infiniteRepeatable(tween(pulseMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    // ── Орбитальные частицы вокруг утки — «живость», не плоское свечение ──
    // Бегут по кругу при активности; направление/скорость зависят от фазы:
    // RECORDING — частые быстрые точки (ловлю звук), PROCESSING — вращение по орбите
    // (распознаю/думаю), PLAYING — мягкий выдох. В IDLE орбита скрыта.
    val orbitActive = state == DuqState.RECORDING || state == DuqState.PROCESSING ||
        state == DuqState.PLAYING || state == DuqState.LISTENING
    val orbitMs = when (state) {
        DuqState.PROCESSING -> 1400
        DuqState.RECORDING -> 2200
        else -> 3200
    }
    val orbitAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(orbitMs, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit"
    )
    val orbitCount = when (state) {
        DuqState.PROCESSING -> 8
        DuqState.RECORDING -> 6
        else -> 5
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Цветной статус-halo + орбитальные частицы за уткой.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.46f
                    val center = Offset(cx, cy)
                    val maxR = size.minDimension * 0.62f
                    val r = maxR * (0.72f + 0.28f * haloPulse)
                    val a = haloPeak * haloPulse
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to haloColor.copy(alpha = a),
                                0.45f to haloColor.copy(alpha = a * 0.45f),
                                1f to Color.Transparent
                            ),
                            center = center, radius = r
                        ),
                        radius = r, center = center
                    )
                    // Орбитальные точки: по окружности, пульсируют по размеру, «хвост» по alpha.
                    if (orbitActive) {
                        val orbR = maxR * 0.82f
                        for (i in 0 until orbitCount) {
                            val ang = Math.toRadians((orbitAngle + i * (360f / orbitCount)).toDouble())
                            val px = cx + (orbR * kotlin.math.cos(ang)).toFloat()
                            val py = cy + (orbR * kotlin.math.sin(ang)).toFloat()
                            // голова кометы ярче хвоста
                            val lead = (orbitCount - i).toFloat() / orbitCount
                            val dotR = size.minDimension * (0.012f + 0.018f * lead)
                            drawCircle(
                                color = haloColor.copy(alpha = (0.25f + 0.6f * lead) * (0.6f + 0.4f * haloPulse)),
                                radius = dotR,
                                center = Offset(px, py)
                            )
                        }
                    }
                }
        )
        Image(
            painter = painterResource(R.drawable.ic_rubber_duck),
            contentDescription = "DUQ",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(pulse)
                .graphicsLayer {
                    translationY = bob * size.height * 0.035f
                    rotationZ = tilt
                    alpha = silhouetteAlpha
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.62f, 0.38f)
                }
        )
    }
}

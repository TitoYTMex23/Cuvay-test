package com.cuvay.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra de carga. frac = 0..1 -> progreso real (se anima suave).
 * frac = null -> "no se cuanto falta": una franja que corre de lado a lado.
 */
@Composable
fun BarLoader(
    frac: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = Red,
    track: Color = Color(0x33FFFFFF)
) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        if (frac != null) {
            val f by animateFloatAsState(frac.coerceIn(0f, 1f), tween(250))
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(color))
        } else {
            val t = rememberInfiniteTransition()
            val x by t.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = maxWidth * x).width(maxWidth * 0.4f).fillMaxHeight().background(color))
            }
        }
    }
}

/** Circulito que gira, para botones y esquinas. */
@Composable
fun Spinner(d: Dp = 18.dp, color: Color = Color.White) {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    Canvas(Modifier.size(d)) {
        val w = this.size.minDimension * 0.16f
        drawArc(
            color = color, startAngle = a, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round)
        )
    }
}

/** Etiqueta a la izquierda, porcentaje a la derecha y la barra debajo. dark = fondo oscuro (texto claro). */
@Composable
fun ProgressLine(label: String, frac: Float?, modifier: Modifier = Modifier, dark: Boolean = false) {
    val fg = if (dark) Soft else Muted
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (frac != null) Text("${(frac * 100).toInt()}%", color = fg, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        BarLoader(frac, track = if (dark) Color(0x33FFFFFF) else Chip)
    }
}

/** Reemplazo de "Cargando..." para pantallas enteras (tarjeta blanca con barra que corre). */
@Composable
fun LoadingBlock(text: String = "Cargando…", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().card(Color.White, 16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        BarLoader(null, track = Chip)
    }
}

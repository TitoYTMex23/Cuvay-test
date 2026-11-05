package com.cuvay.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val Blue = Color(0xFF002A8F)
val Red = Color(0xFFCF142B)
val Ink = Color(0xFF0B1330)
val Muted = Color(0xFF55607A)
val Panel = Color.White
val Navy0 = Color(0xFF050F2B)
val Navy1 = Color(0xFF0B2A73)
val Soft = Color(0xFFB8C7F5)      // texto claro sobre fondo oscuro
val Mine = Color(0xFF1D4ED8)      // burbuja propia del chat
val Chip = Color(0xFFEFF2FA)      // fondo suave dentro de tarjetas blancas
val Glass = Color(0x1AFFFFFF)     // vidrio translucido sobre fondo oscuro
val Display = FontFamily.SansSerif // cambia por FontFamily(Font(R.font.archivo_black)) si bajas la fuente
val Heavy = FontWeight.Black

/** Recorta con esquinas redondeadas. */
fun Modifier.rounded(r: Dp = 12.dp): Modifier = this.clip(RoundedCornerShape(r))

/** Tarjeta: color solido con esquinas redondeadas. */
fun Modifier.card(color: Color = Panel, r: Dp = 16.dp): Modifier = this.clip(RoundedCornerShape(r)).background(color)

private fun starPath(cx: Float, cy: Float, r: Float): Path {
    val star = Path()
    for (i in 0 until 5) {
        val a = -PI / 2 + i * 4 * PI / 5
        val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    return star
}

/** Fondo: azul noche en degradado con el triangulo y la estrella de Cuba muy sutiles. */
@Composable
fun FlagBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy0, Navy1)))) {
        Canvas(Modifier.fillMaxSize()) {
            val tri = Path().apply {
                moveTo(0f, size.height * 0.10f)
                lineTo(size.width * 0.66f, size.height * 0.50f)
                lineTo(0f, size.height * 0.90f)
                close()
            }
            drawPath(tri, Red.copy(alpha = 0.20f))
            drawPath(starPath(size.width * 0.20f, size.height * 0.50f, size.width * 0.17f), Color.White.copy(alpha = 0.07f))
        }
        content()
    }
}

/** Mini bandera dibujada (logo del encabezado). */
@Composable
fun FlagMark(modifier: Modifier = Modifier) {
    Canvas(modifier.rounded(5.dp)) {
        val h = size.height / 5f
        drawRect(Color.White, Offset(0f, 0f), size)
        for (i in 0 until 5) if (i % 2 == 0) drawRect(Blue, Offset(0f, i * h), Size(size.width, h))
        val tri = Path().apply { moveTo(0f, 0f); lineTo(size.width * 0.52f, size.height / 2f); lineTo(0f, size.height); close() }
        drawPath(tri, Red)
        drawPath(starPath(size.width * 0.17f, size.height / 2f, size.height * 0.20f), Color.White)
    }
}

private val palette = listOf(
    Color(0xFFE11D48), Color(0xFF2563EB), Color(0xFF059669), Color(0xFFD97706),
    Color(0xFF7C3AED), Color(0xFF0891B2), Color(0xFFDB2777), Color(0xFF4F46E5)
)

/** Color estable por nombre (para avatares y nombres en el chat). */
fun userColor(name: String): Color = palette[abs(name.trim().lowercase().hashCode()) % palette.size]

/** Circulo con la inicial del nombre. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val ch = name.trim().trimStart('@').firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(Modifier.size(size).rounded(size / 2).background(userColor(name)), contentAlignment = Alignment.Center) {
        Text(ch, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black)
    }
}

/** Selector segmentado (pestanas) sobre fondo oscuro. */
@Composable
fun Seg(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().card(Glass, 14.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, t ->
            Text(t, Modifier.weight(1f).rounded(10.dp).background(if (i == sel) Color.White else Color.Transparent)
                .clickable { onSel(i) }.padding(vertical = 10.dp),
                color = if (i == sel) Blue else Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

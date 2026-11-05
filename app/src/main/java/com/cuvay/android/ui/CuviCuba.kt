package com.cuvay.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =====================================================================
//  CuviCuba.kt
//  Piezas de diseño cubanas para Cuvi: el Malecón con palmas, banderines,
//  bandera, tarjetas, burbujas con markdown y la "palabra cubana del día".
//  Todo lleva prefijo Cb / cb para no chocar con otros archivos del paquete.
// =====================================================================

// ---------- Paleta ----------
internal val CbAzul = Color(0xFF002A8F)       // azul de la bandera
internal val CbAzulVivo = Color(0xFF3A6BFF)   // azul que se ve bien sobre fondo oscuro
internal val CbRojo = Color(0xFFCF142B)       // rojo de la bandera
internal val CbOro = Color(0xFFFFD166)        // dorado del sol
internal val CbCrema = Color(0xFFFFF4DC)      // crema de postal antigua
internal val CbNoche = Color(0xFF081230)      // azul noche del panel
internal val CbNoche2 = Color(0xFF0F1D47)
internal val CbMar = Color(0xFF25D9C8)        // turquesa del Caribe
internal val CbPalma = Color(0xFF2EE6A6)      // verde palma
internal val CbTexto2 = Color(0xFFB4C2E6)     // texto secundario

// ---------- Estrella solitaria ----------
internal fun DrawScope.cbStar(center: Offset, r: Float, color: Color, rot: Float = -PI.toFloat() / 2f) {
    val p = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.382f
        val a = rot + i * (PI.toFloat() / 5f)
        val x = center.x + cos(a) * rad
        val y = center.y + sin(a) * rad
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

/** Rayita tricolor (azul / blanco / rojo) para separar secciones. */
@Composable
internal fun CbFlagLine(modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))
            .background(
                Brush.horizontalGradient(
                    0.00f to CbAzulVivo, 0.34f to CbAzulVivo,
                    0.34f to Color.White, 0.66f to Color.White,
                    0.66f to CbRojo, 1.00f to CbRojo
                )
            )
    )
}

/** Banderita cubana dibujada: 5 franjas, triángulo rojo y la estrella. */
@Composable
internal fun CbMiniFlag(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sh = h / 5f
        for (i in 0 until 5) {
            drawRect(if (i % 2 == 0) CbAzul else Color.White, Offset(0f, sh * i), Size(w, sh + 0.5f))
        }
        val tri = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.46f, h / 2f)
            lineTo(0f, h)
            close()
        }
        drawPath(tri, CbRojo)
        cbStar(Offset(w * 0.15f, h / 2f), h * 0.17f, Color.White)
    }
}

/** Medallón de Cuvi: círculo azul con aro dorado y la estrella solitaria. */
@Composable
internal fun CbStarBadge(diameter: Dp = 26.dp) {
    Canvas(Modifier.size(diameter)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Brush.verticalGradient(listOf(CbAzulVivo, CbAzul)), r)
        drawCircle(CbOro, r - 0.75.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
        cbStar(Offset(c.x, c.y + 0.5.dp.toPx()), r * 0.52f, Color.White)
    }
}

// ---------- El Malecón de Cuvi ----------
private class CbSky(val top: Color, val mid: Color, val bottom: Color, val night: Boolean, val sunY: Float)

private fun cbSkyFor(hour: Int): CbSky = when {
    hour < 5 || hour >= 20 -> CbSky(Color(0xFF050B24), Color(0xFF0E1B55), Color(0xFF243A8A), true, 0f)     // noche
    hour < 8 -> CbSky(Color(0xFF2C3E9E), Color(0xFFE5688A), Color(0xFFFFB27A), false, 0.66f)              // amanecer
    hour < 17 -> CbSky(Color(0xFF0B5FD6), Color(0xFF1FA6E8), Color(0xFF7FE3F2), false, 0.50f)             // día
    else -> CbSky(Color(0xFF2A1F6B), Color(0xFFD9467A), Color(0xFFFF9F43), false, 0.66f)                  // atardecer
}

private fun cbHash(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) / 2147483647f
}

/** Palma real: tronco curvo y hojas en media luna que se mecen con `sway` (-1..1). */
internal fun DrawScope.cbPalm(base: Offset, h: Float, lean: Float, sway: Float, color: Color) {
    val top = Offset(base.x + lean * h + sway * h * 0.03f, base.y - h)
    val ctrl = Offset(base.x - lean * h * 0.25f, base.y - h * 0.55f)
    val trunk = Path().apply {
        moveTo(base.x, base.y)
        quadraticBezierTo(ctrl.x, ctrl.y, top.x, top.y)
    }
    drawPath(trunk, color, style = Stroke(width = (h * 0.07f).coerceAtLeast(2f), cap = StrokeCap.Round))
    val fronds = floatArrayOf(10f, 45f, 80f, 115f, 150f, 185f, -20f, 200f)
    val len = h * 0.45f
    for ((i, deg) in fronds.withIndex()) {
        val a = (deg + sway * 5f * (if (i % 2 == 0) 1f else -1f)) * (PI.toFloat() / 180f)
        val dx = cos(a)
        val dy = -sin(a)
        val tip = Offset(top.x + dx * len, top.y + dy * len + len * 0.34f)
        val nx = -dy
        val ny = dx
        val mid = Offset(top.x + dx * len * 0.5f, top.y + dy * len * 0.5f - len * 0.08f)
        val c1 = Offset(mid.x + nx * len * 0.16f, mid.y + ny * len * 0.16f)
        val c2 = Offset(mid.x - nx * len * 0.16f, mid.y - ny * len * 0.16f + len * 0.10f)
        val leaf = Path().apply {
            moveTo(top.x, top.y)
            quadraticBezierTo(c1.x, c1.y, tip.x, tip.y)
            quadraticBezierTo(c2.x, c2.y, top.x, top.y)
            close()
        }
        drawPath(leaf, color)
    }
}

/** Banderines de fiesta (azul / blanco / rojo) colgados de una cuerda. */
private fun DrawScope.cbBunting(t: Float) {
    val w = size.width
    val y0 = 2.dp.toPx()
    val sag = 6.dp.toPx()
    val fw = 10.dp.toPx()
    val fh = 11.dp.toPx()
    val n = (w / (fw + 6.dp.toPx())).toInt().coerceAtLeast(4)
    val rope = Path().apply {
        moveTo(0f, y0)
        quadraticBezierTo(w / 2f, y0 + sag * 2f, w, y0)
    }
    drawPath(rope, Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.dp.toPx()))
    val cols = arrayOf(CbAzulVivo, Color.White, CbRojo)
    for (i in 0 until n) {
        val f = (i + 0.5f) / n
        val x = f * w
        val y = y0 + 4f * sag * f * (1f - f)
        val swing = sin(t * 2f + i) * 0.14f * fw
        val tri = Path().apply {
            moveTo(x - fw / 2f, y)
            lineTo(x + fw / 2f, y)
            lineTo(x + swing, y + fh)
            close()
        }
        drawPath(tri, cols[i % 3].copy(alpha = 0.95f))
    }
}

/**
 * Escena del Malecón: cielo que cambia con la hora (amanecer, día, atardecer, noche con la
 * estrella solitaria), mar con olitas, palmas que se mecen, muro del malecón y banderines.
 */
@Composable
internal fun CbMalecon(modifier: Modifier = Modifier, hour: Int) {
    val inf = rememberInfiniteTransition()
    val t by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart)
    )
    val sky = remember(hour) { cbSkyFor(hour) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.68f

        // Cielo
        drawRect(
            Brush.verticalGradient(listOf(sky.top, sky.mid, sky.bottom), startY = 0f, endY = horizon),
            size = Size(w, horizon)
        )
        if (sky.night) {
            for (i in 0 until 18) {
                val sx = cbHash(i * 3 + 1) * w
                val sy = cbHash(i * 7 + 2) * horizon * 0.85f
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2f + i))
                drawCircle(Color.White.copy(alpha = tw), radius = (0.7f + cbHash(i + 40)) * 1.1.dp.toPx(), center = Offset(sx, sy))
            }
            val moon = Offset(w * 0.56f, h * 0.30f)
            drawCircle(CbCrema.copy(alpha = 0.16f), 17.dp.toPx(), moon)
            drawCircle(CbCrema, 8.5.dp.toPx(), moon)
            // La estrella solitaria
            val lone = Offset(w * 0.80f, h * 0.30f)
            drawCircle(CbOro.copy(alpha = 0.14f + 0.06f * sin(t * 2f)), 15.dp.toPx(), lone)
            cbStar(lone, 6.5.dp.toPx(), CbOro)
        } else {
            val sun = Offset(w * 0.56f, horizon * sky.sunY)
            val glow = 40.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFFE9A8).copy(alpha = 0.85f), Color.Transparent), center = sun, radius = glow),
                glow, sun
            )
            drawCircle(Color(0xFFFFF3C9), 10.dp.toPx(), sun)
        }

        // Mar
        val seaTop = if (sky.night) Color(0xFF14398A) else Color(0xFF17B3C6)
        val seaMid = if (sky.night) Color(0xFF0A1F60) else Color(0xFF0A4A96)
        val seaBot = if (sky.night) Color(0xFF050F36) else Color(0xFF06205C)
        drawRect(
            Brush.verticalGradient(listOf(seaTop, seaMid, seaBot), startY = horizon, endY = h),
            topLeft = Offset(0f, horizon), size = Size(w, h - horizon)
        )
        // Reflejo del sol / la luna
        val refCol = if (sky.night) CbCrema else Color(0xFFFFE9A8)
        for (i in 0 until 6) {
            val y = horizon + (h - horizon) * (0.10f + i * 0.14f)
            val half = (10.dp.toPx() + i * 3.dp.toPx()) * (0.75f + 0.25f * sin(t * 3f + i))
            drawRoundRect(
                refCol.copy(alpha = 0.34f - i * 0.045f),
                topLeft = Offset(w * 0.56f - half, y), size = Size(half * 2f, 1.6.dp.toPx()),
                cornerRadius = CornerRadius(1f, 1f)
            )
        }
        // Olitas
        for (k in 0 until 3) {
            val y0 = horizon + (h - horizon) * (0.30f + 0.26f * k)
            val amp = (1.2f + k * 0.5f).dp.toPx()
            val step = 6.dp.toPx()
            val p = Path()
            p.moveTo(0f, y0)
            var x = 0f
            while (x < w) {
                x += step
                p.lineTo(x, y0 + sin(x / w * 6.2832f * (2 + k) + t * (k + 1)) * amp)
            }
            drawPath(p, Color.White.copy(alpha = 0.18f - k * 0.04f), style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round))
        }

        // Palmas
        val wallH = 9.dp.toPx()
        val palmCol = if (sky.night) Color(0xFF030A1F) else Color(0xFF05132E)
        cbPalm(Offset(w * 0.93f, h - wallH * 0.5f), h * 0.92f, -0.10f, sin(t), palmCol)
        cbPalm(Offset(w * 0.84f, h - wallH * 0.5f), h * 0.62f, -0.14f, sin(t + 2f), palmCol)
        cbPalm(Offset(w * 0.045f, h - wallH * 0.5f), h * 0.80f, 0.10f, sin(t + 4f), palmCol)

        // Muro del Malecón
        drawRect(Color(0xFF0A1535), topLeft = Offset(0f, h - wallH), size = Size(w, wallH))
        drawRect(Color(0xFF2A3C6E), topLeft = Offset(0f, h - wallH), size = Size(w, 1.5.dp.toPx()))
        var jx = 14.dp.toPx()
        while (jx < w) {
            drawRect(Color(0xFF1B2A55), topLeft = Offset(jx, h - wallH + 1.5.dp.toPx()), size = Size(1.dp.toPx(), wallH))
            jx += 30.dp.toPx()
        }

        // Banderines
        cbBunting(t)
    }
}

// ---------- Piezas de interfaz ----------

/** Tarjeta de menú: barra de color a la izquierda, icono, título y subtítulo. */
@Composable
internal fun CbMenuCard(
    emoji: String, title: String, subtitle: String, accent: Color,
    highlighted: Boolean = false, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val fill = if (highlighted) Brush.horizontalGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f)))
    else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f)))
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(fill)
            .border(1.dp, if (highlighted) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = CbTexto2, fontSize = 11.sp)
        }
        Text("›", color = accent, fontSize = 22.sp, modifier = Modifier.padding(end = 14.dp))
    }
}

/** Mosaico chiquito: icono arriba y título abajo. Para filas de 3. */
@Composable
internal fun CbTile(emoji: String, title: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.height(3.dp))
        Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Pastilla de texto (sugerencias, estilos, trucos). */
@Composable
internal fun CbPill(
    text: String, selected: Boolean, accent: Color,
    modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(if (small) 10.dp else 12.dp)
    Text(
        text,
        modifier.clip(shape)
            .background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 7.dp else 10.dp, vertical = if (small) 5.dp else 7.dp),
        color = if (selected) Color.White else CbTexto2,
        fontSize = if (small) 10.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 2
    )
}

/** Botón redondo con un emoji (música, sonido, volver, limpiar). */
@Composable
internal fun CbIconToggle(
    label: String, active: Boolean, accent: Color,
    size: Dp = 34.dp, fontSize: TextUnit = 15.sp, onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (active) accent else Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = fontSize, color = Color.White)
    }
}

/** Botón de enviar: círculo dorado con avioncito de papel. */
@Composable
internal fun CbSendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .background(
                if (enabled) Brush.linearGradient(listOf(CbOro, Color(0xFFFFB347)))
                else Brush.linearGradient(listOf(Color(0xFF2A3764), Color(0xFF2A3764)))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(16.dp)) {
            val p = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                lineTo(size.width * 0.28f, size.height / 2f)
                close()
            }
            drawPath(p, if (enabled) CbNoche else Color(0xFF6F82AD))
        }
    }
}

/** Tres puntitos que brincan mientras Cuvi piensa. */
@Composable
internal fun CbTypingDots(color: Color = CbRojo) {
    val tr = rememberInfiniteTransition()
    Row(
        Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val y by tr.animateFloat(
                initialValue = 0f,
                targetValue = -5f,
                animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse, StartOffset(i * 140))
            )
            Box(Modifier.offset(y = y.dp).size(6.dp).clip(CircleShape).background(color))
        }
    }
}

// ---------- Texto: markdown liviano y voz ----------
private val CbInlineRx = Regex("""\*\*(.+?)\*\*|`([^`\n]+)`|\*(?!\s)([^*\n]+?)\*""")
private val CbBulletRx = Regex("""^\s*[*\-•]\s+""")
private val CbHeadRx = Regex("""^#{1,6}\s+""")

private fun AnnotatedString.Builder.cbInline(s: String, codeBg: Color) {
    var last = 0
    for (m in CbInlineRx.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val b = m.groups[1]?.value
        val c = m.groups[2]?.value
        val i = m.groups[3]?.value
        when {
            b != null -> withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(b) }
            c != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(c) }
            i != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(i) }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}

/** Convierte lo que responde la IA (**negrita**, *cursiva*, `código`, viñetas, # títulos) en texto lindo. */
internal fun cbMarkdown(src: String, accent: Color = CbOro, codeBg: Color = Color.White.copy(alpha = 0.14f)): AnnotatedString =
    buildAnnotatedString {
        val lines = src.replace("\r", "").split("\n")
        lines.forEachIndexed { idx, raw ->
            var line = raw.trimEnd()
            val head = CbHeadRx.containsMatchIn(line)
            if (head) {
                line = line.replace(CbHeadRx, "")
            } else if (CbBulletRx.containsMatchIn(line)) {
                line = line.replace(CbBulletRx, "•  ")
            }
            if (head) {
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = accent)) { cbInline(line, codeBg) }
            } else {
                cbInline(line, codeBg)
            }
            if (idx < lines.lastIndex) append("\n")
        }
    }

/** Texto listo para que lo lea la voz: sin enlaces ni símbolos, cortado donde termina una frase. */
internal fun cbSpeakable(src: String, max: Int = 230): String {
    val s = src
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_`#>~]+"), "")
        .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (s.length <= max) return s
    val cut = s.substring(0, max)
    val end = maxOf(maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!')), maxOf(cut.lastIndexOf('?'), cut.lastIndexOf(':')))
    return if (end > max / 3) cut.substring(0, end + 1) else cut.substringBeforeLast(' ', cut)
}

// ---------- Palabra cubana del día ----------
internal class CbCubanismo(val palabra: String, val significado: String, val ejemplo: String, val emoji: String)

internal val cbCubanismos = listOf(
    CbCubanismo("Asere", "Amigo, compadre, colega.", "¿Qué bolá, asere? ¿Cómo andas?", "🤝"),
    CbCubanismo("¿Qué bolá?", "Saludo cubano: ¿qué tal?, ¿cómo estás?", "¡Qué bolá! Hace rato que no te veía.", "👋"),
    CbCubanismo("Guagua", "Autobús.", "Perdí la guagua y llegué tarde.", "🚌"),
    CbCubanismo("Jama", "Comida.", "Ya está lista la jama, ven a comer.", "🍽️"),
    CbCubanismo("Yuma", "El extranjero (sobre todo Estados Unidos) o una persona de allá.", "Mi primo se fue pa' la yuma.", "✈️"),
    CbCubanismo("Chopin", "Tienda donde se compra (viene de «shopping»).", "Voy al chopin a buscar aceite.", "🛍️"),
    CbCubanismo("Botella", "Aventón: un viaje gratis que te da alguien en su carro.", "Me tiré una botella hasta el Vedado.", "🚗"),
    CbCubanismo("Almendrón", "Carro americano antiguo que trabaja como taxi colectivo.", "Cogimos un almendrón por el Malecón.", "🚕"),
    CbCubanismo("Jaba", "Bolsa.", "Échame eso en la jaba.", "👜"),
    CbCubanismo("Pincho", "Trabajo, empleo.", "Salgo del pincho a las cinco.", "💼"),
    CbCubanismo("Resolver", "Conseguir algo como sea, buscándole la vuelta.", "Hay que resolver el transporte para mañana.", "🔧"),
    CbCubanismo("Inventar", "Ingeniárselas con lo que hay para salir adelante.", "Tuvimos que inventar un arreglo con lo que había.", "💡"),
    CbCubanismo("Una pila", "Mucho, un montón.", "Había una pila de gente en la cola.", "📚"),
    CbCubanismo("Monada", "Algo muy bonito o tierno.", "¡Qué monada de niño!", "😍"),
    CbCubanismo("Descarga", "Reunión de músicos que tocan improvisando, por puro gusto.", "Esta noche hay descarga en casa de Pedro.", "🎷"),
    CbCubanismo("Guateque", "Fiesta campesina con música, comida y baile.", "El domingo hay guateque en la finca.", "🎻"),
    CbCubanismo("Congrí", "Arroz y frijoles cocinados juntos.", "Hoy hay congrí con yuca y puerco.", "🍚"),
    CbCubanismo("Moros y cristianos", "Arroz blanco con frijoles negros.", "Mamá hizo moros y cristianos para el almuerzo.", "🍛"),
    CbCubanismo("Socio", "Amigo cercano.", "Ese es mi socio desde la escuela.", "👊"),
    CbCubanismo("Candela", "Fuego; también algo intenso o un problema.", "¡Qué candela hace hoy!", "🔥"),
    CbCubanismo("Cafecito", "El café cubano: fuerte, dulce y para compartir con las visitas.", "Pasa, que te cuelo un cafecito.", "☕"),
    CbCubanismo("Malecón", "El muro y avenida frente al mar de La Habana, punto de encuentro de la gente.", "Nos vemos en el Malecón al atardecer.", "🌊"),
    CbCubanismo("Mi'jo", "Forma cariñosa de llamar a alguien más joven (de «mi hijo»).", "Ven acá, mi'jo, que te explico.", "💛"),
    CbCubanismo("Fajarse", "Esforzarse mucho trabajando (también, pelearse).", "Me fajé toda la tarde con esa tarea.", "💪"),
)

/** La palabra cambia cada día y es la misma para todos. */
internal fun cbPalabraDelDia(): CbCubanismo {
    val cal = java.util.Calendar.getInstance()
    val d = cal.get(java.util.Calendar.DAY_OF_YEAR) + cal.get(java.util.Calendar.YEAR) * 366
    return cbCubanismos[d % cbCubanismos.size]
}

@Composable
private fun CbDark(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(10.dp)).background(CbNoche2).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = CbCrema, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
    )
}

/** Postal crema con la palabra del día, su significado y un ejemplo. */
@Composable
internal fun CbPalabraCard(word: CbCubanismo, onSpeak: () -> Unit, onNext: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(CbCrema, Color(0xFFFFE3A6))))
            .border(1.5.dp, CbOro, shape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(word.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Palabra cubana del día", color = CbRojo, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(word.palabra, color = CbNoche, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = Display, maxLines = 1)
            }
            CbMiniFlag(Modifier.size(30.dp, 19.dp).clip(RoundedCornerShape(2.dp)))
        }
        Text(word.significado, color = Color(0xFF2B3558), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
        Text("«" + word.ejemplo + "»", color = CbRojo, fontSize = 12.sp, fontStyle = FontStyle.Italic, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CbDark("🔊 Escuchar", onSpeak)
            CbDark("🔄 Otra palabra", onNext)
        }
    }
}

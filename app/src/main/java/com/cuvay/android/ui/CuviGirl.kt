package com.cuvay.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// =====================================================================
//  CuviGirl.kt  -  CUVI, la chica cubana (reemplaza al robot).
//  Es una marioneta 2D de piezas recortadas de la ilustracion original
//  (cabeza, torso, 2 brazos x 2 huesos, 2 piernas x 2 huesos) que se
//  anima con el MISMO vector de pose / IK que usaba el robot.
//  El rostro se dibuja con los pixeles originales; solo los parpadeos,
//  ojos felices y bocas son parches pequenos encima.
//  Los PNG van en: app/src/main/assets/cuvi/
// =====================================================================

/** Escala: unidades-u por pixel de la imagen fuente. */
internal const val G_S = 0.00126f
internal const val G_GROUND = 1.29f
internal const val G_SRC_BOTTOM = 1003f
internal const val G_CX = 294f
internal const val G_HIP_SRC_Y = 548f
internal const val G_HIPY = 0.7167f   // = G_GROUND - (G_SRC_BOTTOM - G_HIP_SRC_Y) * G_S

internal fun girlX(sx: Float): Float = 0.5f + (sx - G_CX) * G_S
internal fun girlY(sy: Float): Float = G_GROUND - (G_SRC_BOTTOM - sy) * G_S

internal class GirlSprite(val img: ImageBitmap, val bx: Int, val by: Int, val k: Int)

internal class GirlArt(
    val head: GirlSprite, val torso: GirlSprite,
    val armL: GirlLimb, val armR: GirlLimb, val legL: GirlLimb, val legR: GirlLimb,
    val eyesClosed: GirlSprite, val eyesHappy: GirlSprite, val mouthSmile: GirlSprite, val mouthO: GirlSprite,
    val avatar: ImageBitmap
)

/** Extremidad de una sola pieza deformada con malla: 3 huesos (padre fijo, hueso 1, hueso 2) y pesos suaves. */
internal class GirlLimb(
    val bmp: Bitmap, val cols: Int, val rows: Int,
    val j0x: Float, val j0y: Float, val j1x: Float, val j1y: Float,
    val rx: FloatArray, val ry: FloatArray, val w1: FloatArray, val w2: FloatArray,
    val verts: FloatArray
)

private fun smooth01(x: Float): Float { val t = if (x < 0f) 0f else if (x > 1f) 1f else x; return t * t * (3f - 2f * t) }

private val girlPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

internal object GirlArtCache {
    @Volatile var art: GirlArt? = null

    private fun raw(context: Context, name: String): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.assets.open("cuvi/$name").use { s ->
            return BitmapFactory.decodeStream(s, null, opts)!!
        }
    }

    private fun bmp(context: Context, name: String): ImageBitmap = raw(context, name).asImageBitmap()

    /** Prepara la malla de una extremidad: (j0,j1) = articulaciones en px del arte, (ax,ay) = eje en reposo, z = zonas de mezcla. */
    fun limb(
        context: Context, file: String, bx: Int, by: Int,
        j0x: Float, j0y: Float, j1x: Float, j1y: Float, ax: Float, ay: Float,
        z1a: Float, z1b: Float, z2a: Float, z2b: Float, cols: Int, rows: Int
    ): GirlLimb {
        val bm = raw(context, file)
        val n = (cols + 1) * (rows + 1)
        val rx = FloatArray(n); val ry = FloatArray(n); val w1 = FloatArray(n); val w2 = FloatArray(n)
        for (r in 0..rows) for (c in 0..cols) {
            val i = r * (cols + 1) + c
            val x = bx + bm.width * c / cols.toFloat()
            val y = by + bm.height * r / rows.toFloat()
            rx[i] = x; ry[i] = y
            val t0 = (x - j0x) * ax + (y - j0y) * ay
            val t1 = (x - j1x) * ax + (y - j1y) * ay
            w1[i] = smooth01((t0 - z1a) / (z1b - z1a))
            w2[i] = smooth01((t1 - z2a) / (z2b - z2a))
        }
        return GirlLimb(bm, cols, rows, j0x, j0y, j1x, j1y, rx, ry, w1, w2, FloatArray(n * 2))
    }

    fun sprite(context: Context, file: String, bx: Int, by: Int, k: Int) = GirlSprite(bmp(context, file), bx, by, k)

    fun load(context: Context): GirlArt? {
        art?.let { return it }
        return try {
            val a = GirlArt(
        head = GirlArtCache.sprite(context, "cuvi_head.png", 224, 30, 3),
        torso = GirlArtCache.sprite(context, "cuvi_torso.png", 175, 169, 1),
        eyesClosed = GirlArtCache.sprite(context, "cuvi_eyes_closed.png", 250, 110, 3),
        eyesHappy = GirlArtCache.sprite(context, "cuvi_eyes_happy.png", 250, 110, 3),
        mouthSmile = GirlArtCache.sprite(context, "cuvi_mouth_smile.png", 276, 148, 3),
        mouthO = GirlArtCache.sprite(context, "cuvi_mouth_o.png", 276, 148, 3),
                armL = GirlArtCache.limb(context, "cuvi_arml.png", 137, 218, 190.00f, 244.00f, 175.15f, 377.20f, -0.11080f, 0.99384f, 0f, 45f, -18f, 18f, 4, 18),
                armR = GirlArtCache.limb(context, "cuvi_armr.png", 362, 202, 400.00f, 244.00f, 411.70f, 376.30f, 0.08809f, 0.99611f, 0f, 45f, -18f, 18f, 4, 18),
                legL = GirlArtCache.limb(context, "cuvi_legl.png", 173, 537, 240.00f, 548.00f, 247.49f, 672.00f, 0.06029f, 0.99818f, 0f, 75f, -32f, 32f, 6, 24),
                legR = GirlArtCache.limb(context, "cuvi_legr.png", 287, 537, 362.00f, 548.00f, 390.11f, 672.00f, 0.22106f, 0.97526f, 0f, 75f, -32f, 32f, 6, 24),
                avatar = bmp(context, "cuvi_avatar.png")
            )
            art = a
            a
        } catch (e: Exception) {
            android.util.Log.e("CuviGirl", "No pude cargar los PNG de assets/cuvi/", e)
            null
        }
    }
}

/** Carga (una sola vez) las piezas de CUVI desde assets. Devuelve null mientras carga. */
@Composable
internal fun rememberGirlArt(): GirlArt? {
    val ctx = LocalContext.current.applicationContext
    var art by remember { mutableStateOf(GirlArtCache.art) }
    LaunchedEffect(Unit) {
        if (art == null) art = withContext(Dispatchers.IO) { GirlArtCache.load(ctx) }
    }
    return art
}

/** Dibuja una pieza; (px,py) = pivote en pixeles de la imagen fuente. Se usa dentro de [inSrcSpace]. */
internal fun DrawScope.drawGirlSprite(s: GirlSprite, px: Float, py: Float) {
    if (s.k == 1) {
        drawImage(
            image = s.img,
            dstOffset = IntOffset((s.bx - px).roundToInt(), (s.by - py).roundToInt()),
            filterQuality = FilterQuality.Medium
        )
    } else {
        withTransform({ scale(1f / s.k, 1f / s.k, Offset.Zero) }) {
            drawImage(
                image = s.img,
                dstOffset = IntOffset(((s.bx - px) * s.k).roundToInt(), ((s.by - py) * s.k).roundToInt()),
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

/**
 * Dibuja una extremidad deformada con malla (piel suave en hombro/cadera y codo/rodilla).
 * c0 = articulacion 0 (hombro/cadera) en canvas, c1 = articulacion 1 (codo/rodilla) en canvas,
 * a1/a2 = giro de cada hueso respecto al reposo (grados), s = px de canvas por px del arte.
 */
internal fun DrawScope.drawGirlLimb(l: GirlLimb, c0: Offset, c1: Offset, a1: Float, a2: Float, s: Float) {
    val r1 = a1 * 0.017453292f; val r2 = a2 * 0.017453292f
    val co1 = kotlin.math.cos(r1); val si1 = kotlin.math.sin(r1)
    val co2 = kotlin.math.cos(r2); val si2 = kotlin.math.sin(r2)
    val n = l.rx.size
    for (i in 0 until n) {
        val px = l.rx[i]; val py = l.ry[i]
        val d0x = (px - l.j0x) * s; val d0y = (py - l.j0y) * s
        val d1x = (px - l.j1x) * s; val d1y = (py - l.j1y) * s
        val p0x = c0.x + d0x; val p0y = c0.y + d0y
        val b1x = c0.x + co1 * d0x - si1 * d0y; val b1y = c0.y + si1 * d0x + co1 * d0y
        val b2x = c1.x + co2 * d1x - si2 * d1y; val b2y = c1.y + si2 * d1x + co2 * d1y
        val w1 = l.w1[i]; val w2 = l.w2[i]
        val mx = b1x * (1f - w2) + b2x * w2; val my = b1y * (1f - w2) + b2y * w2
        l.verts[2 * i] = p0x * (1f - w1) + mx * w1
        l.verts[2 * i + 1] = p0y * (1f - w1) + my * w1
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(l.bmp, l.cols, l.rows, l.verts, 0, null, 0, girlPaint) }
}

/** Espacio de la imagen fuente: 1 unidad = 1 px del arte, origen en la articulacion [joint], girado [deg]. */
internal fun DrawScope.inSrcSpace(joint: Offset, deg: Float, u: Float, block: DrawScope.() -> Unit) {
    val sc = G_S * u
    withTransform({
        translate(joint.x, joint.y)
        rotate(deg, Offset.Zero)
        scale(sc, sc, Offset.Zero)
    }, block)
}

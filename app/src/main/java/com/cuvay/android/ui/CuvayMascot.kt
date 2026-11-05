package com.cuvay.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

// =====================================================================
//  CUVI 3D IK, la mascota cubana de Cuvay (Súper Edición 3D + 15 Actos).
//  AHORA ES LA CHICA CUVI (marioneta de piezas, ver CuviGirl.kt). Props, efectos
//  y los 15 actos son los de siempre: sombreado 3D volumétrico,
//  rotación de perspectiva 3D en tiempo real, esqueleto con cinemática
//  inversa (brazos y piernas), squash & stretch y 15 actos autónomos:
//  - Clásicos: saludar (Wave), caminar (Walk), saltar (Jump),
//    manos en la cabeza "ay ay ay" (Head), patear pelota + cabezazo + GOOOL (Kick),
//    bailar salsa (Dance), ponerse gafas con estilo (Cool), celebrar (Cheer).
//  - Nuevos 3D: pintar/decorar con varita mágica 3D (Decorate),
//    tocar el Tres Cubano (Guitar), jugar Dominó ¡Capicúa! (Domino),
//    tomar Cafecito Cubano turbo (Cafecito), DJ Fiesta Holográfica (DjParty),
//    Breakdance giro 3D (Spin3D) y Boxeo Olímpico Cubano (Boxing).
// =====================================================================

internal val MASCOT_W = 200.dp
internal val MASCOT_H = 230.dp

private val McBlue = Color(0xFF002A8F)
private val McRed = Color(0xFFCF142B)
private val McCyan = Color(0xFF3DE0FF)
private val McGreen = Color(0xFF2EE6A6)
private val McGold = Color(0xFFFFC83D)
private val McMagenta = Color(0xFFFF499E)
private val McPurple = Color(0xFF8C52FF)

private const val PIF = 3.1415927f

// ---- Índices del "pose vector" (todo float para poder interpolar suavemente) ----
private const val BX = 0; private const val BY = 1; private const val SQ = 2; private const val LEAN = 3
private const val HEAD = 4; private const val LHX = 5; private const val LHY = 6; private const val RHX = 7
private const val RHY = 8; private const val LFX = 9; private const val LFY = 10; private const val RFX = 11
private const val RFY = 12; private const val EYE = 13; private const val MOUTH = 14; private const val HAPPY = 15
private const val SHADE = 16; private const val LOOKX = 17; private const val LOOKY = 18; private const val FLAGAMP = 19
private const val BALL = 20; private const val BALLX = 21; private const val BALLY = 22; private const val BALLS = 23
private const val BALLR = 24; private const val SWEAT = 25; private const val CONF = 26; private const val NOTES = 27
private const val SPARK = 28; private const val AIR = 29; private const val SCROLL = 30
// Nuevos canales 3D para utilería y rotación tridimensional
private const val ROTY3D = 31; private const val ROTZ3D = 32; private const val WAND = 33
private const val GUITAR = 34; private const val DOMINO = 35; private const val COFFEE = 36
private const val DJDECK = 37; private const val BOXGLOVE = 38; private const val AURA3D = 39
private const val NP = 40

enum class MascotAct(val dur: Float) {
    Wave(2.8f),
    Walk(3.6f),
    Jump(2.6f),
    Head(3.0f),
    Kick(4.4f),
    Dance(3.8f),
    Cool(3.0f),
    Cheer(2.8f),
    Decorate(3.4f),
    Guitar(3.8f),
    Domino(3.4f),
    Cafecito(3.2f),
    DjParty(3.8f),
    Spin3D(2.8f),
    Boxing(3.2f)
}

private typealias Act = MascotAct

private fun ss(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
private fun seg(t: Float, a: Float, b: Float): Float = ss((t - a) / (b - a))
private fun mix(a: Float, b: Float, k: Float): Float = a + (b - a) * k
private fun fr(x: Float): Float = x - floor(x)

private fun basePose(): FloatArray {
    val p = FloatArray(NP)
    p[SQ] = 1f; p[LHX] = -0.045f; p[LHY] = 0.29f; p[RHX] = 0.12f; p[RHY] = 0.25f
    p[LFX] = -0.085f; p[RFX] = 0.085f; p[EYE] = 1f; p[FLAGAMP] = 1f; p[BALLS] = 1f
    return p
}

/** Pose objetivo de cada acto en el segundo [t] del acto (T = reloj global). */
private fun poseFor(act: Act, t: Float, T: Float): FloatArray {
    val p = basePose()
    p[BY] = 0.004f * sin(T * 2.2f)
    val fade = seg(t, 0f, 0.3f) * (1f - seg(t, act.dur - 0.3f, act.dur))
    when (act) {
        Act.Wave -> {
            val up = seg(t, 0.1f, 0.45f) * (1f - seg(t, act.dur - 0.5f, act.dur - 0.1f))
            p[LHX] = mix(-0.045f, -0.16f + 0.05f * sin(T * 10f), up)
            p[LHY] = mix(0.29f, -0.22f, up)
            p[MOUTH] = up; p[HAPPY] = 0.8f * up
            p[HEAD] = 4f * sin(T * 3f) * up; p[LEAN] = 2f * sin(T * 2f)
            p[RHY] = 0.25f + 0.02f * sin(T * 5f)
            p[FLAGAMP] = 1f + up
            p[ROTY3D] = 10f * sin(T * 2.2f) * fade
        }
        Act.Walk -> {
            val ph = T * 9.5f; val s = sin(ph); val c = cos(ph)
            p[LFY] = max(0f, s) * 0.075f * fade
            p[RFY] = max(0f, -s) * 0.075f * fade
            p[LFX] = -0.085f + 0.04f * c * fade
            p[RFX] = 0.085f - 0.04f * c * fade
            p[BY] = -0.012f * abs(s) * fade
            p[LHY] = 0.29f - 0.06f * s * fade
            p[RHY] = 0.25f + 0.04f * s * fade
            p[LEAN] = 3f * sin(ph * 0.5f) * fade
            p[HEAD] = 3f * c * fade
            p[LOOKX] = 0.9f * fade
            p[FLAGAMP] = 1.6f
            p[SCROLL] = fade
            p[ROTY3D] = 16f * sin(ph * 0.5f) * fade
        }
        Act.Jump -> {
            val per = 1.3f
            val u = (t % per) / per
            var h = 0f; var squat = 0f; var sq = 1f
            if (u < 0.22f) {
                val k = seg(u, 0f, 0.22f); squat = 0.07f * k; sq = 1f - 0.10f * k
            } else if (u < 0.72f) {
                val k = (u - 0.22f) / 0.5f
                h = 0.22f * sin(PIF * k); squat = 0.07f * (1f - k); sq = 1f + 0.07f * sin(PIF * k)
            } else if (u < 0.84f) {
                val k = (u - 0.72f) / 0.12f
                squat = 0.07f * sin(PIF * k); sq = 1f - 0.12f * sin(PIF * k)
            }
            val tuck = if (h > 0f) 0.04f * sin(PIF * ((u - 0.22f) / 0.5f)) else 0f
            val up = seg(u, 0.1f, 0.3f) * (1f - seg(u, 0.78f, 0.9f))
            p[BY] = squat - h; p[SQ] = sq; p[AIR] = h
            p[LFY] = h + tuck; p[RFY] = h + tuck
            p[LHX] = mix(-0.045f, -0.12f, up); p[LHY] = mix(0.29f, -0.26f, up)
            p[RHX] = mix(0.12f, 0.12f, up); p[RHY] = mix(0.25f, -0.26f, up)
            p[MOUTH] = up; p[HAPPY] = up; p[FLAGAMP] = 1f + 1.5f * up
            p[ROTY3D] = 28f * sin(u * 2f * PIF) * up
        }
        Act.Head -> {
            val a = seg(t, 0f, 0.3f) * (1f - seg(t, 2.6f, 3.0f))
            val b = seg(t, 1.7f, 2.2f)
            val both = a * (1f - b)
            p[LHX] = mix(-0.045f, -0.09f + 0.015f * sin(T * 13f) * b, a)
            p[LHY] = mix(0.29f, -0.28f + 0.015f * cos(T * 13f) * b, a)
            p[RHX] = mix(0.12f, 0.09f, both); p[RHY] = mix(0.25f, -0.28f, both)
            p[HEAD] = 9f * sin(T * 15f) * both + 5f * b * a
            p[LEAN] = 2f * sin(T * 7f) * both
            p[EYE] = mix(1f, 0.15f, both)
            p[MOUTH] = -both
            p[SWEAT] = both
            p[FLAGAMP] = 1f + 1.5f * both
            p[LOOKY] = -0.6f * b * a
            p[ROTY3D] = -12f * a + 6f * sin(T * 5f) * a
        }
        Act.Kick -> {
            val w = seg(t, 0.9f, 1.35f); val k = seg(t, 1.35f, 1.5f); val r = seg(t, 1.8f, 2.3f)
            var lfx = mix(-0.085f, 0.03f, w); lfx = mix(lfx, -0.30f, k); lfx = mix(lfx, -0.085f, r)
            var lfy = mix(0f, 0.10f, w); lfy = mix(lfy, 0.02f, k); lfy = mix(lfy, 0f, r)
            val gy = 1.23f
            when {
                t < 1.5f -> {
                    p[BALLX] = mix(-0.10f, 0.18f, seg(t, 0f, 0.9f)); p[BALLY] = gy
                    p[BALLR] = p[BALLX] / 0.07f * 57.3f
                }
                t < 2.55f -> {
                    val s = (t - 1.5f) / 1.05f
                    p[BALLX] = mix(0.18f, 0.42f, s)
                    p[BALLY] = mix(gy, 0.03f, s) - 0.5f * sin(PIF * s)
                    p[BALLR] = 500f * t; p[BALLS] = 1f + 0.35f * sin(PIF * s)
                }
                else -> {
                    val s = (t - 2.55f) / 0.9f; val s1 = min(s, 1f)
                    p[BALLX] = mix(0.42f, 1.12f, s)
                    p[BALLY] = mix(0.03f, gy, s1) - 0.45f * sin(PIF * s1)
                    p[BALLR] = 500f * t; p[BALLS] = 1f + 0.35f * sin(PIF * s1)
                }
            }
            p[BALL] = seg(t, 0f, 0.2f) * (1f - seg(t, 3.4f, 3.6f))
            val kr = k * (1f - r)
            p[LFX] = lfx; p[LFY] = lfy
            p[LEAN] = 4f * w * (1f - k) - 3f * kr
            p[LHX] = -0.045f - 0.07f * kr; p[LHY] = 0.29f - 0.08f * kr
            p[RHY] = 0.25f - 0.10f * kr
            p[LOOKX] = -0.9f * (1f - seg(t, 1.3f, 1.6f))
            p[LOOKY] = -0.8f * seg(t, 1.5f, 1.8f) * (1f - seg(t, 2.6f, 2.9f))
            p[MOUTH] = -1f * seg(t, 1.4f, 1.6f) * (1f - seg(t, 2.7f, 2.9f))
            val hh = seg(t, 2.45f, 2.58f) * (1f - seg(t, 2.58f, 2.95f))
            p[HEAD] = 14f * hh; p[BY] += 0.02f * hh; p[SQ] = 1f - 0.05f * hh
            // GOOOL!
            val c = seg(t, 3.35f, 3.65f)
            val hj = if (t > 3.5f) max(0f, sin((t - 3.5f) * 8f)) * 0.12f else 0f
            p[LHX] = mix(p[LHX], -0.10f, c); p[LHY] = mix(p[LHY], -0.28f, c)
            p[RHX] = mix(p[RHX], 0.10f, c); p[RHY] = mix(p[RHY], -0.28f, c)
            p[BY] -= hj; p[LFY] += hj; p[RFY] += hj; p[AIR] = hj
            p[MOUTH] = mix(p[MOUTH], 1f, c); p[HAPPY] = c
            p[CONF] = c * (1f - seg(t, act.dur - 0.4f, act.dur))
            p[FLAGAMP] = 1f + 1.5f * c
            p[ROTY3D] = 18f * sin(T * 3f) * fade
        }
        Act.Dance -> {
            val w = 2f * PIF * 1.5f
            val s = sin(w * T); val c = cos(w * T)
            val e = fade; val q = 0.5f + 0.5f * s
            p[BX] = 0.035f * s * e; p[LEAN] = -9f * s * e; p[HEAD] = 8f * s * e
            p[BY] = -0.015f * abs(c) * e
            p[LFY] = max(0f, c) * 0.055f * e; p[RFY] = max(0f, -c) * 0.055f * e
            p[LHX] = -0.045f - 0.10f * q * e; p[LHY] = 0.29f - 0.55f * q * e
            p[RHX] = 0.12f + 0.02f * e; p[RHY] = 0.25f - 0.50f * (1f - q) * e
            p[MOUTH] = e; p[HAPPY] = e; p[NOTES] = e; p[FLAGAMP] = 1f + 1.2f * e
            p[ROTY3D] = 24f * s * e
        }
        Act.Cool -> {
            val hand = seg(t, 0.2f, 0.6f) * (1f - seg(t, 1.8f, 2.2f))
            val end = 1f - seg(t, 2.4f, 2.9f)
            p[LHX] = mix(-0.045f, 0.06f, hand); p[LHY] = mix(0.29f, -0.27f, hand)
            p[SHADE] = seg(t, 0.7f, 1.0f) * (1f - seg(t, 2.6f, 2.9f))
            p[SPARK] = max(0f, 1f - abs(t - 1.25f) / 0.3f)
            p[LEAN] = -3f * seg(t, 0.5f, 1.0f) * end + 1.5f * sin(T * 2f)
            p[HEAD] = -6f * seg(t, 0.8f, 1.2f) * end
            p[FLAGAMP] = 1.3f
            p[ROTY3D] = -15f * seg(t, 0.6f, 1.1f) * end
        }
        Act.Cheer -> {
            val e = fade
            val h = 0.10f * abs(sin(T * 5f)) * e
            p[BY] = -h; p[LFY] = h; p[RFY] = h; p[AIR] = h
            p[LHX] = mix(-0.045f, -0.10f, e); p[LHY] = mix(0.29f, -0.28f, e)
            p[RHX] = mix(0.12f, 0.10f, e); p[RHY] = mix(0.25f, -0.28f, e)
            p[MOUTH] = e; p[HAPPY] = e; p[CONF] = e
            p[FLAGAMP] = 1f + 1.5f * e; p[SQ] = 1f + 0.03f * sin(T * 10f) * e
            p[ROTY3D] = 20f * sin(T * 4f) * e
        }
        Act.Decorate -> {
            val e = fade
            val sw = sin(T * 6f)
            val cw = cos(T * 6f)
            p[LHX] = mix(-0.045f, -0.18f + 0.06f * sw, e)
            p[LHY] = mix(0.29f, -0.20f + 0.07f * cw, e)
            p[RHX] = mix(0.12f, 0.16f, e)
            p[RHY] = mix(0.25f, 0.05f + 0.04f * sw, e)
            p[BY] = -0.04f * e + 0.02f * sin(T * 4f) * e
            p[AIR] = 0.05f * e
            p[HEAD] = 6f * sw * e
            p[LEAN] = 4f * cw * e
            p[MOUTH] = e; p[HAPPY] = e
            p[WAND] = e; p[AURA3D] = e
            p[ROTY3D] = 25f * sin(T * 3.2f) * e
        }
        Act.Guitar -> {
            val e = fade
            val strum = sin(T * 18f)
            val groove = sin(T * 5f)
            p[LHX] = mix(-0.045f, -0.02f + 0.02f * groove, e)
            p[LHY] = mix(0.29f, 0.10f + 0.045f * strum, e)
            p[RHX] = mix(0.12f, 0.18f, e)
            p[RHY] = mix(0.25f, 0.04f, e)
            p[LEAN] = -6f * groove * e
            p[HEAD] = 8f * groove * e
            p[LFY] = max(0f, groove) * 0.04f * e
            p[MOUTH] = e; p[HAPPY] = e; p[NOTES] = e
            p[GUITAR] = e
            p[ROTY3D] = 18f * groove * e
        }
        Act.Domino -> {
            val e = fade
            val slam = seg(t, 1.1f, 1.35f) * (1f - seg(t, 2.7f, 3.1f))
            val windup = seg(t, 0.3f, 1.0f) * (1f - seg(t, 1.1f, 1.3f))
            p[LHX] = mix(-0.045f, -0.14f * windup + 0.02f * slam, e)
            p[LHY] = mix(0.29f, -0.32f * windup + 0.08f * slam, e)
            p[LEAN] = -8f * windup + 10f * slam
            p[HEAD] = 10f * slam
            p[SHADE] = slam
            p[MOUTH] = e; p[HAPPY] = slam
            p[DOMINO] = e
            p[CONF] = slam * 0.7f
            p[ROTY3D] = 22f * sin(T * 3f) * e
        }
        Act.Cafecito -> {
            val e = fade
            val sip = seg(t, 0.6f, 1.1f) * (1f - seg(t, 1.9f, 2.3f))
            val buzz = seg(t, 1.5f, 2.0f)
            p[LHX] = mix(-0.045f, -0.02f, e)
            p[LHY] = mix(0.29f, mix(0.06f, -0.11f, sip), e)
            p[HEAD] = -8f * sip + 7f * sin(T * 22f) * buzz * e
            p[BY] = -0.06f * abs(sin(T * 14f)) * buzz * e
            p[COFFEE] = e
            p[AURA3D] = buzz * e
            p[HAPPY] = buzz * e; p[MOUTH] = buzz * e
            p[ROTY3D] = 16f * sin(T * 6f) * e
        }
        Act.DjParty -> {
            val e = fade
            val beat = abs(sin(T * 8f))
            val scratch = sin(T * 16f)
            p[LHX] = mix(-0.045f, -0.09f + 0.04f * scratch, e)
            p[LHY] = mix(0.29f, 0.12f, e)
            p[RHX] = mix(0.12f, 0.14f, e)
            p[RHY] = mix(0.25f, -0.26f + 0.05f * beat, e)
            p[BY] = -0.03f * beat * e
            p[HEAD] = 10f * sin(T * 8f) * e
            p[SHADE] = e
            p[DJDECK] = e; p[NOTES] = e; p[AURA3D] = e
            p[MOUTH] = e; p[HAPPY] = e
            p[ROTY3D] = 20f * sin(T * 3.5f) * e
        }
        Act.Spin3D -> {
            val e = fade
            val spinProg = seg(t, 0.2f, 2.5f)
            val h = 0.16f * sin(PIF * spinProg)
            p[BY] = -h; p[AIR] = h
            p[LFY] = h * 0.9f; p[RFY] = h * 0.9f
            p[LHX] = -0.18f * e; p[LHY] = -0.16f * e
            p[RHX] = 0.18f * e; p[RHY] = -0.16f * e
            p[ROTY3D] = 360f * spinProg
            p[ROTZ3D] = 18f * sin(spinProg * 2f * PIF)
            p[AURA3D] = e; p[CONF] = e * 0.6f
            p[HAPPY] = e; p[MOUTH] = e
        }
        Act.Boxing -> {
            val e = fade
            val jabL = max(0f, sin(T * 14f))
            val jabR = max(0f, -sin(T * 14f))
            p[LHX] = mix(-0.045f, -0.18f - 0.10f * jabL, e)
            p[LHY] = mix(0.29f, -0.06f - 0.04f * jabL, e)
            p[RHX] = mix(0.12f, 0.16f + 0.10f * jabR, e)
            p[RHY] = mix(0.25f, -0.06f - 0.04f * jabR, e)
            p[LFX] = -0.10f + 0.03f * sin(T * 7f) * e
            p[RFX] = 0.10f + 0.03f * sin(T * 7f) * e
            p[BY] = -0.025f * abs(sin(T * 14f)) * e
            p[LEAN] = 6f * (jabL - jabR) * e
            p[BOXGLOVE] = e
            p[MOUTH] = e
            p[ROTY3D] = 22f * (jabL - jabR) * e
        }
    }
    return p
}

private class MascotRt {
    var act = Act.Wave
    var t = 0f
    @JvmField var T = 0f
    @JvmField var loop = false
    var from: FloatArray = basePose()
    var cur: FloatArray = basePose()

    fun step(dt: Float) {
        T += dt; t += dt
        if (t >= act.dur) startAct(if (loop) act else pickNext())
        val target = poseFor(act, t, T)
        val k = ss(t / 0.35f)
        cur = FloatArray(NP) { from[it] + (target[it] - from[it]) * k }
    }

    fun startAct(a: Act) { from = cur; act = a; t = 0f }

    private fun pickNext(): Act {
        val all = Act.values().filter { it != act }
        return all[Random.nextInt(all.size)]
    }
}

/** Mascota completa, con animación 3D e IK. [tick] sube cada vez que la tocan; [forcedAct] permite elegir acto. */

/** Mascota completa, con animación 3D e IK. [tick] sube cada vez que la tocan; [forcedAct] permite elegir acto; [lockAct] repite ese acto. */
@Composable
fun CuvayMascot(
    modifier: Modifier = Modifier,
    tick: Int = 0,
    forcedAct: MascotAct? = null,
    enable3DRotation: Boolean = true,
    lockAct: Boolean = false
) {
    val rt = remember { MascotRt() }
    val art = rememberGirlArt()
    var frame by remember { mutableStateOf(0L) }
    val density = LocalDensity.current.density
    rt.loop = lockAct && forcedAct != null

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last == 0L) last = n
                val dt = ((n - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = n
                rt.step(dt)
                frame = n
            }
        }
    }
    LaunchedEffect(tick) {
        if (tick > 0) {
            val choices = listOf(Act.Jump, Act.Cheer, Act.Spin3D, Act.Dance, Act.Domino, Act.Boxing)
            rt.startAct(choices.random())
        }
    }
    LaunchedEffect(forcedAct) {
        if (forcedAct != null) rt.startAct(forcedAct)
    }

    val rotY = if (enable3DRotation) rt.cur[ROTY3D] else 0f
    val rotZ = if (enable3DRotation) rt.cur[ROTZ3D] else 0f

    Canvas(
        modifier.graphicsLayer {
            rotationY = rotY
            rotationZ = rotZ
            cameraDistance = 14f * density
        }
    ) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        drawMascot(rt.cur, rt.T, art)
    }
}

/** Solo el rostro de CUVI (avatares redondos). Con [animated] parpadea. */
@Composable
fun CuvayMascotHead(modifier: Modifier = Modifier, animated: Boolean = false) {
    val art = rememberGirlArt()
    val t = rememberInfiniteTransition(label = "cuviFace")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 3400
            1f at 0; 1f at 3100; 0.08f at 3220; 1f at 3350
        }),
        label = "blink"
    )
    val closed = animated && blink < 0.4f
    Canvas(modifier.clip(CircleShape)) {
        val a = art ?: return@Canvas
        val side = min(size.width, size.height)
        val padX = (size.width - side) / 2f
        val padY = (size.height - side) / 2f
        val sc = side / a.avatar.width
        withTransform({ translate(padX, padY); scale(sc, sc, Offset.Zero) }) {
            drawImage(image = a.avatar, filterQuality = FilterQuality.Medium)
        }
        if (closed) {
            val s = 0.0049504950495049506f * side
            withTransform({ translate(padX + (-0.9554455445544554f) * side, padY + (-0.1188118811881188f) * side); scale(s, s, Offset.Zero) }) {
                drawGirlSprite(a.eyesClosed, 0f, 0f)
            }
        }
    }
}

/** Cinemática inversa de 2 huesos. Devuelve [codoX, codoY, puntaX, puntaY]. */
private fun ik(sx: Float, sy: Float, tx: Float, ty: Float, l1: Float, l2: Float, outward: Float): FloatArray {
    var dx = tx - sx; var dy = ty - sy
    var d = sqrt(dx * dx + dy * dy)
    val maxD = (l1 + l2) * 0.995f
    if (d > maxD) { dx *= maxD / d; dy *= maxD / d; d = maxD }
    if (d < 0.02f) d = 0.02f
    val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
    val h = sqrt(max(0f, l1 * l1 - a * a))
    val mx = sx + dx * a / d; val my = sy + dy * a / d
    val px = -dy / d; val py = dx / d
    val j1x = mx + px * h; val j1y = my + py * h
    val j2x = mx - px * h; val j2y = my - py * h
    val s1 = (j1x - mx) * outward + (j1y - my) * 0.6f
    val s2 = (j2x - mx) * outward + (j2y - my) * 0.6f
    return if (s1 >= s2) floatArrayOf(j1x, j1y, sx + dx, sy + dy) else floatArrayOf(j2x, j2y, sx + dx, sy + dy)
}

private fun polyPath(cx: Float, cy: Float, r: Float, n: Int, rot: Float): Path {
    val path = Path()
    for (i in 0 until n) {
        val a = rot + i * 2f * PIF / n - PIF / 2f
        val x = cx + r * cos(a); val y = cy + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun starPath(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) r else r * 0.4f
        val a = -PIF / 2f + i * PIF / 5f
        val x = cx + rr * cos(a); val y = cy + rr * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Tubo con volumen 3D: sombra, color base y brillo especular. */
private fun DrawScope.tube(a: Offset, b: Offset, w: Float, base: Color, dark: Color, light: Color) {
    drawLine(dark, a, b, strokeWidth = w, cap = StrokeCap.Round)
    val s = Offset(-w * 0.05f, -w * 0.05f)
    drawLine(base, a + s, b + s, strokeWidth = w * 0.82f, cap = StrokeCap.Round)
    val h = Offset(-w * 0.20f, -w * 0.20f)
    drawLine(light.copy(alpha = 0.55f), a + h, b + h, strokeWidth = w * 0.16f, cap = StrokeCap.Round)
}

/** Bandera cubana ondeando en un asta dorada. (hx,hy) = mano que la sostiene, en unidades. */
private fun DrawScope.flagWithPole(hx: Float, hy: Float, amp: Float, T: Float, u: Float, ox: Float) {
    fun O(x: Float, y: Float) = Offset(ox + x * u, y * u)
    tube(O(hx, hy + 0.07f), O(hx, hy - 0.42f), 0.014f * u, McGold, Color(0xFF9A6B00), Color(0xFFFFE9A0))
    drawCircle(McGold, 0.016f * u, O(hx, hy - 0.425f))
    val x0 = hx + 0.007f; val y0 = hy - 0.41f
    val fw = 0.26f; val fh = 0.13f
    val n = 10
    fun off(f: Float) = amp * 0.02f * sin(T * 8f - f * 6f) * f
    val path = Path()
    for (i in 0 until n) {
        val f0 = i / n.toFloat(); val f1 = (i + 1) / n.toFloat()
        val xa = x0 + f0 * fw; val xb = x0 + f1 * fw
        val oa = off(f0); val ob = off(f1)
        for (k in 0 until 5) {
            path.reset()
            path.moveTo(O(xa, 0f).x, (y0 + fh * k / 5f + oa) * u)
            path.lineTo(O(xb, 0f).x, (y0 + fh * k / 5f + ob) * u)
            path.lineTo(O(xb, 0f).x, (y0 + fh * (k + 1) / 5f + ob) * u)
            path.lineTo(O(xa, 0f).x, (y0 + fh * (k + 1) / 5f + oa) * u)
            path.close()
            drawPath(path, if (k % 2 == 0) McBlue else Color.White)
        }
        // pliegues de tela
        val sh = (0.10f + 0.10f * sin(T * 8f - f0 * 6f + 1.5f)).coerceAtLeast(0f)
        path.reset()
        path.moveTo(O(xa, 0f).x, (y0 + oa) * u)
        path.lineTo(O(xb, 0f).x, (y0 + ob) * u)
        path.lineTo(O(xb, 0f).x, (y0 + fh + ob) * u)
        path.lineTo(O(xa, 0f).x, (y0 + fh + oa) * u)
        path.close()
        drawPath(path, Color.Black.copy(alpha = sh))
    }
    // triángulo rojo + estrella
    val ax = fh * 0.866f
    val tri = Path()
    tri.moveTo(O(x0, 0f).x, (y0 + off(0f)) * u)
    tri.lineTo(O(x0, 0f).x, (y0 + fh + off(0f)) * u)
    tri.lineTo(O(x0 + ax, 0f).x, (y0 + fh / 2f + off(ax / fw)) * u)
    tri.close()
    drawPath(tri, McRed)
    val sc = O(x0 + ax / 3f, 0f).x
    drawPath(starPath(sc, (y0 + fh / 2f + off(0.14f)) * u, 0.024f * u), Color.White)
}
private val G_SL = floatArrayOf(190.00f, 244.00f)
private val G_WL = floatArrayOf(157.00f, 540.00f)
private val G_EL = floatArrayOf(175.15f, 377.20f)
private val G_SR = floatArrayOf(400.00f, 244.00f)
private val G_WR = floatArrayOf(426.00f, 538.00f)
private val G_ER = floatArrayOf(411.70f, 376.30f)
private val G_HL = floatArrayOf(240.00f, 548.00f)
private val G_HR = floatArrayOf(362.00f, 548.00f)
private val G_KL = floatArrayOf(247.49f, 672.00f)
private val G_KR = floatArrayOf(390.11f, 672.00f)
private val G_AL = floatArrayOf(258.00f, 846.00f)
private val G_AR = floatArrayOf(430.00f, 848.00f)
private const val G_NECK_X = 292.00f
private const val G_NECK_Y = 172.00f

private val G_ARM_L1 = floatArrayOf(0.16887f, 0.16735f)
private val G_ARM_L2 = floatArrayOf(0.20640f, 0.20454f)
private val G_ARM_REST_UP = floatArrayOf(96.361f, 84.946f)
private val G_ARM_REST_LOW = floatArrayOf(96.361f, 84.946f)
private val G_LEG_L1 = floatArrayOf(0.15652f, 0.16020f)
private val G_LEG_L2 = floatArrayOf(0.21964f, 0.22739f)
private val G_LEG_REST_UP = floatArrayOf(86.543f, 77.229f)
private val G_LEG_REST_LOW = floatArrayOf(86.543f, 77.229f)

private const val K_ARM = 1.2f    // alcance del brazo de CUVI / alcance del brazo del robot
private const val K_LEG = 1.25f   // idem piernas
private val G_PANTS = Color(0xFF141C3C)
private val G_JACKET = Color(0xFF1B3A93)
private val G_SLEEVE_L = Color(0xFF162F6E)
private val G_SLEEVE_R = Color(0xFF0E2256)

private fun angDeg(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2(y1 - y0, x1 - x0) * 180f / PIF
private fun dst(a: FloatArray, b: FloatArray): Float = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]))

/** IK de 2 huesos (como [ik]) pero sin recorte visible: en reposo la extremidad queda recta como en la imagen. */
private fun ikG(sx: Float, sy: Float, tx: Float, ty: Float, l1: Float, l2: Float, outward: Float): FloatArray {
    var dx = tx - sx; var dy = ty - sy
    var d = sqrt(dx * dx + dy * dy)
    val maxD = (l1 + l2) * 0.9999f
    if (d > maxD) { dx *= maxD / d; dy *= maxD / d; d = maxD }
    if (d < 0.02f) d = 0.02f
    val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
    val h = sqrt(max(0f, l1 * l1 - a * a))
    val mx = sx + dx * a / d; val my = sy + dy * a / d
    val px = -dy / d; val py = dx / d
    val j1x = mx + px * h; val j1y = my + py * h
    val j2x = mx - px * h; val j2y = my - py * h
    val s1 = (j1x - mx) * outward + (j1y - my) * 0.6f
    val s2 = (j2x - mx) * outward + (j2y - my) * 0.6f
    return if (s1 >= s2) floatArrayOf(j1x, j1y, sx + dx, sy + dy) else floatArrayOf(j2x, j2y, sx + dx, sy + dy)
}

/** Cabeza de CUVI en espacio-imagen, origen en el cuello. Rostro original + parches de expresion. */
private fun DrawScope.drawGirlHead(art: GirlArt, p: FloatArray, T: Float, eyeOpen: Float) {
    val nx = G_NECK_X; val ny = G_NECK_Y
    drawGirlSprite(art.head, nx, ny)
    if (p[HAPPY] > 0.5f) drawGirlSprite(art.eyesHappy, nx, ny)
    else if (eyeOpen < 0.4f) drawGirlSprite(art.eyesClosed, nx, ny)
    val m = p[MOUTH]
    if (m > 0.5f) drawGirlSprite(art.mouthSmile, nx, ny) else if (m < -0.5f) drawGirlSprite(art.mouthO, nx, ny)
    if (p[HAPPY] > 0.05f) {
        val al = 0.18f * p[HAPPY].coerceIn(0f, 1f)
        drawCircle(Color(0xFFFF6B8A).copy(alpha = al), 8.5f, Offset(271f - nx, 143f - ny))
        drawCircle(Color(0xFFFF6B8A).copy(alpha = al), 8.5f, Offset(314f - nx, 143f - ny))
    }
    // audífonos DJ
    if (p[DJDECK] > 0.1f) {
        val a = p[DJDECK].coerceIn(0f, 1f)
        drawArc(McMagenta.copy(alpha = a), 180f, 180f, false, Offset(238f - nx, 36f - ny), Size(108f, 120f), style = Stroke(6f))
        for (s in 0..1) {
            val x = if (s == 0) 232f else 336f
            drawRoundRect(McMagenta.copy(alpha = a), Offset(x - nx, 116f - ny), Size(17f, 36f), CornerRadius(7f))
            drawRoundRect(McCyan.copy(alpha = a), Offset(x + 4f - nx, 121f - ny), Size(9f, 26f), CornerRadius(4f))
        }
    }
    // gafas de sol doradas
    if (p[SHADE] > 0.02f) {
        val sh = p[SHADE]; val sy = (1f - sh) * -60f
        for (s in 0..1) {
            val x0 = if (s == 0) 253f else 293f
            drawRoundRect(Color(0xFF0A0A12).copy(alpha = 0.95f * sh), Offset(x0 - nx, 110f + sy - ny), Size(38f, 25f), CornerRadius(10f))
            drawRoundRect(McGold.copy(alpha = sh), Offset(x0 - nx, 110f + sy - ny), Size(38f, 25f), CornerRadius(10f), style = Stroke(2.2f))
            drawLine(Color.White.copy(alpha = 0.5f * sh), Offset(x0 + 8f - nx, 118f + sy - ny), Offset(x0 + 17f - nx, 114f + sy - ny), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
        drawLine(McGold.copy(alpha = sh), Offset(291f - nx, 119f + sy - ny), Offset(295f - nx, 119f + sy - ny), strokeWidth = 2.2f)
        drawLine(McGold.copy(alpha = sh), Offset(253f - nx, 118f + sy - ny), Offset(246f - nx, 126f + sy - ny), strokeWidth = 2.2f)
        drawLine(McGold.copy(alpha = sh), Offset(331f - nx, 118f + sy - ny), Offset(340f - nx, 126f + sy - ny), strokeWidth = 2.2f)
        if (p[SPARK] > 0.02f) {
            val c = Offset(268f - nx, 118f - ny); val l = 20f * p[SPARK]
            drawLine(Color.White, Offset(c.x - l, c.y), Offset(c.x + l, c.y), strokeWidth = 2.4f, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(c.x, c.y - l), Offset(c.x, c.y + l), strokeWidth = 2.4f, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(c.x - l * 0.5f, c.y - l * 0.5f), Offset(c.x + l * 0.5f, c.y + l * 0.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(c.x - l * 0.5f, c.y + l * 0.5f), Offset(c.x + l * 0.5f, c.y - l * 0.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
        }
    }
    // gotita de sudor
    if (p[SWEAT] > 0.05f) {
        val c = Offset(345f - nx, 92f + 50f * fr(T * 1.5f) - ny)
        val sw = Color(0xFF9FE8FF).copy(alpha = p[SWEAT].coerceIn(0f, 1f))
        drawCircle(sw, 6f, c)
        val dp = Path()
        dp.moveTo(c.x - 5f, c.y - 2f); dp.lineTo(c.x, c.y - 15f); dp.lineTo(c.x + 5f, c.y - 2f); dp.close()
        drawPath(dp, sw)
    }
}

private fun DrawScope.drawMascot(p: FloatArray, T: Float, art: GirlArt?) {
    val u = min(size.width / 1.3f, size.height / 1.4f)
    val ox = (size.width - u) / 2f
    fun O(x: Float, y: Float) = Offset(ox + x * u, y * u)
    fun S(w: Float, h: Float) = Size(w * u, h * u)
    val blink = if (T % 3.6f in 3.3f..3.45f) 0.08f else 1f
    val hipX = 0.5f + p[BX]
    val hipY = G_HIPY + p[BY]
    fun JX(sx: Float) = hipX + (sx - G_CX) * G_S
    fun JY(sy: Float) = hipY + (sy - G_HIP_SRC_Y) * G_S

    // ---- Aura Holográfica 3D de Fondo ----
    if (p[AURA3D] > 0.02f) {
        val a = p[AURA3D]
        drawCircle(
            Brush.radialGradient(
                listOf(McCyan.copy(alpha = 0.35f * a), McPurple.copy(alpha = 0.18f * a), Color.Transparent),
                center = O(0.5f, 0.72f),
                radius = 0.65f * u
            ),
            radius = 0.65f * u,
            center = O(0.5f, 0.72f)
        )
        withTransform({
            scale(1f, 0.32f, O(0.5f, 1.28f))
            rotate(T * 90f, O(0.5f, 1.28f))
        }) {
            drawCircle(McCyan.copy(alpha = 0.7f * a), radius = 0.42f * u, center = O(0.5f, 1.28f), style = Stroke(0.016f * u))
            drawCircle(McGold.copy(alpha = 0.5f * a), radius = 0.30f * u, center = O(0.5f, 1.28f), style = Stroke(0.010f * u))
        }
    }

    // ---- Rayitas del suelo (al caminar) ----
    val sc = p[SCROLL]
    if (sc > 0.02f) {
        for (i in 0 until 7) {
            val gx = ((i * 0.2f - T * 0.6f) % 1.4f + 1.4f) % 1.4f
            drawLine(Color.White.copy(alpha = 0.3f * sc), Offset(gx * u - 0.05f * u, 1.335f * u),
                Offset(gx * u + 0.03f * u, 1.335f * u), strokeWidth = 0.012f * u, cap = StrokeCap.Round)
        }
    }
    // ---- Sombra ----
    val air = p[AIR].coerceIn(0f, 0.4f)
    val shw = 0.46f * (1f - air * 1.3f)
    drawOval(Color.Black.copy(alpha = 0.30f * (1f - air * 1.6f)), O(0.5f + p[BX] * 0.5f - shw / 2f, 1.292f), S(shw, 0.065f))


    if (art != null) scale(2f - p[SQ], p[SQ], O(hipX, 1.30f)) {
        // ================= PIERNAS (2 huesos, IK) =================
        for (side in 0..1) {
            val sg = if (side == 0) -1f else 1f
            val hS = if (side == 0) G_HL else G_HR
            val kS = if (side == 0) G_KL else G_KR
            val aS = if (side == 0) G_AL else G_AR
            val hx = JX(hS[0]); val hy = JY(hS[1])
            val footPose = if (side == 0) p[LFX] else p[RFX]
            val lift = if (side == 0) p[LFY] else p[RFY]
            val tx = girlX(aS[0]) + K_LEG * 0.85f * (footPose - sg * 0.085f)
            val ty = girlY(aS[1]) - K_LEG * lift
            val j = ikG(hx, hy, tx, ty, G_LEG_L1[side], G_LEG_L2[side], sg)
            val upAng = angDeg(hx, hy, j[0], j[1]) - G_LEG_REST_UP[side]
            val lowAng = angDeg(j[0], j[1], j[2], j[3]) - G_LEG_REST_LOW[side]
            drawGirlLimb(if (side == 0) art.legL else art.legR, O(hx, hy), O(j[0], j[1]), upAng, lowAng, G_S * u)
        }

        // ================= CUERPO (se inclina desde la cadera) =================
        rotate(p[LEAN], pivot = O(hipX, hipY)) {
            fun H(dx: Float, dy: Float) = O(hipX + dx, hipY + dy)

            inSrcSpace(O(hipX, hipY), 0f, u) { drawGirlSprite(art.torso, G_CX, G_HIP_SRC_Y) }

            // ================= CABEZA (rostro original) =================
            val headJ = Offset(O(JX(G_NECK_X), JY(G_NECK_Y)).x + p[LOOKX] * 0.004f * u, O(0f, JY(G_NECK_Y)).y + p[LOOKY] * 0.003f * u)
            val eyeOpen = (p[EYE] * blink).coerceIn(0f, 1f)
            inSrcSpace(headJ, p[HEAD], u) { drawGirlHead(art, p, T, eyeOpen) }

            // ================= BRAZOS (y bandera + utilería 3D) =================
            for (side in 0..1) {
                val sg = if (side == 0) -1f else 1f
                val sS = if (side == 0) G_SL else G_SR
                val eS = if (side == 0) G_EL else G_ER
                val wS = if (side == 0) G_WL else G_WR
                val shx = JX(sS[0]); val shy = JY(sS[1])
                val hxr = if (side == 0) p[LHX] else p[RHX]
                val hyr = if (side == 0) p[LHY] else p[RHY]
                val restX = if (side == 0) -0.045f else 0.12f
                val restY = if (side == 0) 0.29f else 0.25f
                val tx = shx + (wS[0] - sS[0]) * G_S + K_ARM * (hxr - restX)
                val ty = shy + (wS[1] - sS[1]) * G_S + K_ARM * (hyr - restY)
                val j = ikG(shx, shy, tx, ty, G_ARM_L1[side], G_ARM_L2[side], sg)
                if (side == 1 && p[GUITAR] < 0.2f && p[BOXGLOVE] < 0.2f) {
                    flagWithPole(j[2], j[3], p[FLAGAMP], T, u, ox)
                }
                val upAng = angDeg(shx, shy, j[0], j[1]) - G_ARM_REST_UP[side]
                val lowAng = angDeg(j[0], j[1], j[2], j[3]) - G_ARM_REST_LOW[side]
                drawGirlLimb(if (side == 0) art.armL else art.armR, O(shx, shy), O(j[0], j[1]), upAng, lowAng, G_S * u)
                val hand = O(j[2], j[3])

                if (p[BOXGLOVE] > 0.05f) {
                    val gR = 0.078f * u
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF4D5E), McRed, Color(0xFF7A0817)), center = hand - Offset(gR * 0.25f, gR * 0.25f), radius = gR * 1.3f), gR, hand)
                    drawCircle(Color.White.copy(alpha = 0.55f), gR * 0.28f, hand - Offset(gR * 0.3f, gR * 0.3f))
                }

                // Varita mágica proyectora 3D en mano izquierda
                if (side == 0 && p[WAND] > 0.05f) {
                    val tip = O(j[2] - 0.14f, j[3] - 0.20f)
                    tube(hand, tip, 0.020f * u, McGold, Color(0xFF9A6B00), Color.White)
                    drawCircle(Brush.radialGradient(listOf(Color.White, McCyan.copy(alpha = 0.6f), Color.Transparent), center = tip, radius = 0.14f * u), 0.14f * u, tip)
                    drawPath(starPath(tip.x, tip.y, 0.058f * u), McGold)
                }

                // Tacita de Cafecito Cubano humeante
                if (side == 0 && p[COFFEE] > 0.05f) {
                    val cup = hand + Offset(-0.02f * u, -0.03f * u)
                    drawRoundRect(Color.White, cup - Offset(0.045f * u, 0.04f * u), S(0.09f, 0.08f), CornerRadius(0.02f * u))
                    drawRoundRect(McBlue, cup - Offset(0.045f * u, 0.02f * u), S(0.09f, 0.02f), CornerRadius(0.005f * u))
                    drawOval(Color(0xFF4A2511), cup - Offset(0.038f * u, 0.038f * u), S(0.076f, 0.022f))
                    for (st in 0..1) {
                        val sy = cup.y - (0.06f + 0.07f * fr(T * 1.4f + st * 0.5f)) * u
                        val sx = cup.x + sin(T * 5f + st) * 0.018f * u
                        drawCircle(Color.White.copy(alpha = 0.45f * p[COFFEE]), 0.016f * u, Offset(sx, sy))
                    }
                }
            }


            // Tres Cubano / Guitarra 3D
            if (p[GUITAR] > 0.05f) {
                val gAlpha = p[GUITAR]
                val gc = H(0.02f, -0.25f)
                withTransform({ rotate(-18f, gc) }) {
                    drawOval(Color(0xFFD9822B).copy(alpha = gAlpha), gc - Offset(0.14f * u, 0.08f * u), S(0.22f, 0.16f))
                    drawOval(Color(0xFF8B4513).copy(alpha = gAlpha), gc - Offset(0.14f * u, 0.08f * u), S(0.22f, 0.16f), style = Stroke(0.012f * u))
                    drawCircle(Color(0xFF2B1405).copy(alpha = gAlpha), 0.036f * u, gc - Offset(0.02f * u, 0f))
                    drawRect(Color(0xFF5C2E0E).copy(alpha = gAlpha), gc + Offset(0.06f * u, -0.022f * u), S(0.22f, 0.044f))
                    for (s in -1..1) {
                        val sy = gc.y + s * 0.010f * u
                        drawLine(McGold.copy(alpha = gAlpha), Offset(gc.x - 0.08f * u, sy), Offset(gc.x + 0.27f * u, sy), strokeWidth = 0.004f * u)
                    }
                }
            }

            // Mesa Holográfica de Dominó Cubano (¡Capicúa!)
            if (p[DOMINO] > 0.05f) {
                val da = p[DOMINO]
                val dc = H(-0.20f, -0.05f)
                withTransform({ rotate(sin(T * 4f) * 8f, dc) }) {
                    drawRoundRect(Color.White.copy(alpha = da), dc - Offset(0.06f * u, 0.10f * u), S(0.12f, 0.20f), CornerRadius(0.022f * u))
                    drawRoundRect(McGold.copy(alpha = da), dc - Offset(0.06f * u, 0.10f * u), S(0.12f, 0.20f), CornerRadius(0.022f * u), style = Stroke(0.008f * u))
                    drawLine(Color.Black.copy(alpha = da), dc - Offset(0.05f * u, 0f), dc + Offset(0.05f * u, 0f), strokeWidth = 0.007f * u)
                    for (dxDot in intArrayOf(-1, 0, 1)) {
                        for (dyDot in intArrayOf(-1, 1)) {
                            drawCircle(McBlue.copy(alpha = da), 0.009f * u, dc + Offset(dxDot * 0.028f * u, dyDot * 0.045f * u))
                            drawCircle(McRed.copy(alpha = da), 0.009f * u, dc + Offset(dxDot * 0.028f * u, dyDot * 0.022f * u))
                        }
                    }
                }
            }

            // Consola DJ Holográfica 3D
            if (p[DJDECK] > 0.05f) {
                val dja = p[DJDECK]
                val deckTop = H(-0.24f, -0.10f)
                drawRoundRect(Color(0xFF0A1435).copy(alpha = 0.9f * dja), deckTop, S(0.48f, 0.09f), CornerRadius(0.025f * u))
                drawRoundRect(McCyan.copy(alpha = dja), deckTop, S(0.48f, 0.09f), CornerRadius(0.025f * u), style = Stroke(0.008f * u))
                for (d in 0..1) {
                    val discCenter = deckTop + Offset((0.12f + d * 0.24f) * u, 0.045f * u)
                    drawCircle(Color(0xFF182238).copy(alpha = dja), 0.036f * u, discCenter)
                    drawCircle(if (d == 0) McMagenta else McCyan, 0.014f * u, discCenter)
                }
            }
        }
    }

    // ================= PELOTA DE FÚTBOL =================
    if (p[BALL] > 0.02f) {
        val r = 0.07f * u * p[BALLS]
        val c = O(p[BALLX], p[BALLY])
        val hgt = (1.23f - p[BALLY]).coerceAtLeast(0f)
        drawOval(Color.Black.copy(alpha = 0.25f * p[BALL] * (1f - hgt / 1.2f).coerceIn(0.1f, 1f)),
            O(p[BALLX] - 0.06f, 1.30f), S(0.12f, 0.035f))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFBFC8DA)), center = c + Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.5f),
            r, c, alpha = p[BALL])
        val circ = Path().apply { addOval(Rect(c, r)) }
        clipPath(circ) {
            rotate(p[BALLR], pivot = c) {
                val ink = Color(0xFF1B1B22)
                drawPath(polyPath(c.x, c.y, r * 0.38f, 5, 0f), ink, alpha = p[BALL])
                for (i in 0 until 5) {
                    val a = i * 2f * PIF / 5f - PIF / 2f
                    drawLine(Color(0xFF8B93A5), Offset(c.x + cos(a) * r * 0.38f, c.y + sin(a) * r * 0.38f),
                        Offset(c.x + cos(a) * r * 0.78f, c.y + sin(a) * r * 0.78f), strokeWidth = r * 0.07f, alpha = p[BALL])
                    drawCircle(ink, r * 0.30f, Offset(c.x + cos(a) * r * 0.98f, c.y + sin(a) * r * 0.98f), alpha = p[BALL])
                }
            }
        }
        drawCircle(Color(0xFF6C7488), r, c, alpha = p[BALL], style = Stroke(r * 0.05f))
        drawCircle(Color.White, r * 0.16f, c + Offset(-r * 0.4f, -r * 0.4f), alpha = 0.55f * p[BALL])
    }

    // ================= NOTAS MUSICALES =================
    if (p[NOTES] > 0.02f) {
        val xs = floatArrayOf(0.02f, 1.02f, 0.10f)
        val cols = arrayOf(McGold, McCyan, McRed)
        for (i in 0 until 3) {
            val ph = fr(T * 0.5f + i / 3f)
            val x = xs[i]; val y = 0.95f - ph * 0.8f
            val al = sin(PIF * ph) * p[NOTES]
            drawOval(cols[i], O(x - 0.02f, y), S(0.045f, 0.032f), alpha = al)
            drawLine(cols[i], O(x + 0.02f, y + 0.015f), O(x + 0.02f, y - 0.075f), strokeWidth = 0.01f * u, alpha = al)
            drawLine(cols[i], O(x + 0.02f, y - 0.075f), O(x + 0.055f, y - 0.05f), strokeWidth = 0.01f * u, alpha = al, cap = StrokeCap.Round)
        }
    }

    // ================= CONFETI =================
    if (p[CONF] > 0.02f) {
        for (i in 0 until 28) {
            val x = fr(i * 0.618f) * 1.3f - 0.15f + 0.04f * sin(T * 3f + i)
            val y = -0.15f + fr(T * 0.5f + i * 0.37f) * 1.55f
            val col = when (i % 3) { 0 -> McRed; 1 -> Color(0xFF3A7BFF); else -> Color.White }
            rotate(T * 220f + i * 47f, pivot = O(x, y)) {
                drawRect(col, O(x - 0.014f, y - 0.007f), S(0.028f, 0.014f), alpha = p[CONF])
            }
        }
    }
}
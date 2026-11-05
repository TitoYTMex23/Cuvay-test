package com.cuvay.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ---- Datos (editalos aqui; no hace falta JSON ni Gson) ----
private data class DvService(val emoji: String, val title: String, val desc: String)
private data class DvProject(val category: String, val name: String, val desc: String, val tags: List<String>, val stage: String)

private const val DV_NAME = "Ernesto Aldama Zaldívar"
private const val DV_BRAND = "TitoMX"
private const val DV_HEADLINE = "Tu idea merece una buena solución."
private const val DV_ROLE = "Desarrollador de software · Android & Python"
private const val DV_LOCATION = "Isla de la Juventud, Cuba"
private const val DV_INTRO = "Creo aplicaciones, bots y herramientas que ayudan a personas y negocios a trabajar mejor. " +
    "Combino desarrollo Android, Python y automatización para convertir una necesidad concreta en una solución útil y fácil de utilizar."
private const val DV_TG = "https://t.me/TitoYTVIP"
private const val DV_TG_LABEL = "@TitoYTVIP"
private const val DV_WA = "https://wa.me/5354718735"
private const val DV_PHONE = "tel:+5354718735"
private const val DV_PHONE_LABEL = "+53 5471 8735"
private const val DV_CHANNEL = "https://t.me/titoYTMex"
private const val DV_CHANNEL_LABEL = "@titoYTMex"

private val dvSkills = listOf("Android", "Python", "Telegram API", "APIs & integraciones", "OpenRouter", "VPS & Linux", "WireGuard", "Automatización", "Soporte IT")
private val dvServices = listOf(
    DvService("📱", "Aplicaciones Android", "Apps a medida para tus servicios, herramientas, tiendas y comunidades. Integración con APIs, cuentas y paneles de administración."),
    DvService("🤖", "Bots de Telegram", "Atención al cliente, comunidades VIP, archivos, multimedia y procesos automatizados con Python."),
    DvService("✨", "Automatización e IA", "Conecta tus herramientas, organiza tareas repetitivas y utiliza APIs de inteligencia artificial donde aporten valor a tu negocio."),
    DvService("🧩", "Web y paneles de gestión", "Páginas adaptables, catálogos y paneles para gestionar usuarios, servicios y operaciones desde un mismo lugar."),
    DvService("🖥️", "VPS e infraestructura", "Despliegue y mantenimiento de proyectos web y bots en servidores Linux. Configuración de redes y soluciones con WireGuard."),
    DvService("🔧", "Soporte técnico", "Instalación y optimización de Windows, configuración de dispositivos, controladores y resolución de problemas técnicos.")
)
private val dvProjects = listOf(
    DvProject("APLICACIÓN ANDROID", "TitoCloud", "Un ecosistema de herramientas de nube y red: transferencia de archivos entre nube privada y Telegram, descargas, comunicación y módulos multimedia.",
        listOf("Android", "APIs", "Nube & Telegram"), "Proyecto del portafolio"),
    DvProject("TELEGRAM & MULTIMEDIA", "Compressor Bot Profesional", "Compresión y conversión de vídeo, gestión de archivos, miniaturas, descargas y automatización de suscripciones VIP. Integración con OpenRouter para tareas asistidas por IA.",
        listOf("Python", "Telegram API", "OpenRouter"), "Proyecto del portafolio"),
    DvProject("ANDROID & REDES", "TitoMX Tunnel Profesional", "Aplicación multiprotocolo con perfiles manuales, servidores administrados, licencias y autorización de dispositivos desde un panel de administración.",
        listOf("Android", "VPN", "Panel"), "Proyecto del portafolio")
)
private val dvSteps = listOf(
    "Escuchamos tu idea" to "Me cuentas qué necesitas y qué problema quieres resolver.",
    "Definimos el alcance" to "Acordamos funciones, prioridades y una propuesta ajustada a tu proyecto.",
    "Construimos y revisamos" to "Desarrollo, validación y entrega con instrucciones para utilizar la solución."
)

// ---- Paleta propia de esta pantalla ----
private val DvInk = Color(0xFF102C35)
private val DvMint = Color(0xFF58F1C5)
private val DvMuted = Color(0xFFB8D1D0)
private val DvBg = Color(0xFFF5F8F6)
private val DvText = Color(0xFF111A1C)
private val DvSub = Color(0xFF4A5B5D)
private val DvAccent = Color(0xFF00796B)
private val DvLine = Color(0xFFCAD5D3)
private val DvFirst = Color(0xFFBDEBD7)
private val DvTag = Color(0xFFE4EBE8)

/** Tarjeta de entrada (ponla en Ajustes). */
@Composable
fun DeveloperCard(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(DvInk, 20.dp).clickable(onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).rounded(15.dp).background(DvMint), contentAlignment = Alignment.Center) {
            Text("EA", color = DvInk, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CREADO POR TITOMX", color = DvMint, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text(DV_NAME, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Android · Bots · Automatización", color = DvMuted, fontSize = 12.sp)
            Text("Conoce mis servicios", color = DvMint, fontSize = 12.sp)
        }
        Text("→", color = DvMint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DeveloperProfileDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(ctx, "No se pudo abrir. Usa $DV_TG_LABEL en Telegram o llama al $DV_PHONE_LABEL.", Toast.LENGTH_LONG).show() }
    }
    fun share() {
        val txt = "$DV_NAME · $DV_BRAND\n$DV_ROLE\n\n$DV_INTRO\n\nServicios:\n" + dvServices.joinToString("\n") { "• ${it.title}" } +
            "\n\nProyectos: " + dvProjects.joinToString { it.name } +
            "\n\nHablemos de tu proyecto: $DV_TG\nWhatsApp: $DV_WA\nCanal: $DV_CHANNEL"
        runCatching {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Desarrollo a medida · TitoMX"); putExtra(Intent.EXTRA_TEXT, txt)
            }, "Compartir presentación"))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(DvBg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", Modifier.clip(CircleShape).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = DvText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Detrás de TitoMX", color = DvText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("DESARROLLO A TU MEDIDA", color = DvSub, fontSize = 8.sp, letterSpacing = 1.5.sp)
                }
                Text("COMPARTIR", Modifier.clip(RoundedCornerShape(10.dp)).clickable { share() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = DvAccent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
            DeveloperContent(::open)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeveloperContent(onContact: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
        // Cabecera
        Column(Modifier.fillMaxWidth().card(DvInk, 30.dp).background(Brush.linearGradient(listOf(DvInk, Color(0xFF0D4E4C)))).padding(25.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp).rounded(20.dp).background(DvMint), contentAlignment = Alignment.Center) {
                    Text("EA", color = DvInk, fontWeight = FontWeight.Black, fontSize = 25.sp, letterSpacing = (-1).sp)
                }
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TITOMX STUDIO", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = DvMint)
                    Text("Software con propósito.", fontSize = 12.sp, color = DvMuted)
                }
            }
            Text(DV_HEADLINE, color = Color.White, fontSize = 31.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(DV_NAME, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(DV_ROLE, color = DvMuted, fontSize = 12.sp)
                Text("📍 $DV_LOCATION", color = DvMuted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).rounded(15.dp).background(DvMint).clickable { onContact(DV_TG) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Hablemos de tu proyecto  →", color = DvInk, fontWeight = FontWeight.Bold)
            }
        }
        // Intro + skills
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            DvLabel("UNA PERSONA. MUCHAS SOLUCIONES.")
            Text("Tecnología que trabaja contigo.", color = DvText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(DV_INTRO, color = DvSub, fontSize = 15.sp, lineHeight = 22.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { dvSkills.forEach { DvTagChip(it) } }
        }
        // Servicios
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            DvLabel("QUÉ PUEDO CREAR PARA TI")
            Text("De la necesidad a la solución.", color = DvText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            dvServices.forEach { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).border(1.dp, DvLine, RoundedCornerShape(21.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.emoji, fontSize = 24.sp)
                    Text(s.title, color = DvText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(s.desc, color = DvSub, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
        // Proyectos
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DvLabel("ALGUNAS DE MIS IDEAS EN CÓDIGO")
            Text("Proyectos con identidad.", color = DvText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            dvProjects.forEachIndexed { i, p ->
                val shape = RoundedCornerShape(24.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(if (i == 0) DvFirst else Color.White).border(1.dp, DvLine, shape).padding(21.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("0${i + 1}", color = DvAccent, fontSize = 27.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.width(12.dp))
                        Text(p.category, color = DvText, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                    Text(p.name, color = DvText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(p.desc, color = DvSub, fontSize = 14.sp, lineHeight = 21.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { p.tags.forEach { DvTagChip(it) } }
                    Text(p.stage, color = DvAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Proceso
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DvLabel("ASÍ PODEMOS TRABAJAR")
            dvSteps.forEachIndexed { i, (t, d) ->
                Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(DvFirst), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = DvInk, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(t, color = DvText, fontWeight = FontWeight.Bold)
                        Text(d, color = DvSub, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
        // Contacto
        Column(Modifier.fillMaxWidth().card(DvInk, 26.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            DvLabel("TU PRÓXIMO PROYECTO EMPIEZA AQUÍ", DvMint)
            Text("¿Tienes un proyecto en mente?", color = Color.White, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text("Escríbeme qué necesitas y conversemos sobre cómo llevarlo a cabo.", color = DvMuted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).rounded(24.dp).background(DvMint).clickable { onContact(DV_TG) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Telegram · $DV_TG_LABEL", color = DvInk, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, DvMuted.copy(alpha = .5f), RoundedCornerShape(24.dp))
                .clickable { onContact(DV_WA) }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Consultar por WhatsApp", color = Color.White, fontWeight = FontWeight.Bold)
            }
            DvContact("Llamar", DV_PHONE_LABEL) { onContact(DV_PHONE) }
            DvContact("Mi canal de Telegram", DV_CHANNEL_LABEL) { onContact(DV_CHANNEL) }
        }
        Text("Hecho con identidad. Hecho por $DV_BRAND.", color = DvSub, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 22.dp))
    }
}

@Composable private fun DvLabel(t: String, color: Color = DvAccent) =
    Text(t, color = color, fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)

@Composable private fun DvTagChip(t: String) =
    Text(t, Modifier.clip(RoundedCornerShape(8.dp)).background(DvTag).padding(horizontal = 10.dp, vertical = 7.dp),
        color = DvSub, fontSize = 12.sp, fontWeight = FontWeight.Medium)

@Composable private fun DvContact(title: String, value: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = DvMuted, fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 13.sp)
        }
        Text("↗", color = DvMint, fontSize = 16.sp)
    }

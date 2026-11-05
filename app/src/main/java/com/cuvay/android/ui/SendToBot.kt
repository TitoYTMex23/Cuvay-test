package com.cuvay.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// =============================================================================
//  ACCIONES DE BOT: reenviar la publicación original a @Cuv_ai con tu sesión de Telegram (TDLib).
//  El archivo no pasa por el teléfono: Telegram lo reenvía de servidor a servidor.
// =============================================================================
private const val BOT = "Cuv_ai"

/** Corre fuera de la tarjeta: si haces scroll y la tarjeta sale de pantalla, el reenvío no se cancela. */
private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Estado real de cada reenvío, por publicación (sobrevive al scroll). */
private val fwdStates = mutableStateMapOf<String, FwdState>()

@Composable
fun SendToBotSection(ch: Api.Channel, msgId: Long, file: Api.Media) {
    if (ch.num == 1) return                       // el canal 1 es el destino del bot: no se reenvía a sí mismo
    val ctx = LocalContext.current
    val key = "${ch.num}_$msgId"
    val st = fwdStates[key]
    val working = st != null && st.step != FwdStep.ERROR && st.step != FwdStep.PUBLISHED
    var dlg by remember(key) { mutableStateOf(0) }            // 0 nada · 1 iniciar sesión · 2 confirmar

    // Al terminar el inicio de sesión se pasa solo a la confirmación.
    LaunchedEffect(Tg.auth, dlg) { if (dlg == 1 && Tg.auth is TgAuth.Ready) dlg = 2 }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD5DBEA)))
        Text("🤖 ACCIONES DE BOT", Modifier.padding(top = 10.dp, bottom = 8.dp),
            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text("🚀 Enviar a @$BOT para procesar y publicar",
            Modifier.fillMaxWidth().rounded(12.dp).background(if (working) Muted else Blue)
                .clickable(enabled = !working) {
                    Tg.start(ctx)
                    dlg = if (Tg.auth is TgAuth.Ready) 2 else 1
                }.padding(horizontal = 14.dp, vertical = 13.dp),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        if (st != null) Text(
            (when (st.step) { FwdStep.PUBLISHED -> "✓ "; FwdStep.ERROR -> "✕ "; else -> "… " }) + st.text,
            Modifier.padding(top = 6.dp),
            color = when (st.step) { FwdStep.PUBLISHED -> Color(0xFF059669); FwdStep.ERROR -> Red; else -> Muted },
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }

    if (dlg == 1) TgLoginDialog { dlg = 0 }
    if (dlg == 2) Dialog(onDismissRequest = { dlg = 0 }) {
        Column(Modifier.fillMaxWidth().card(Color.White, 20.dp).padding(18.dp)) {
            Text("¿Reenviar a @$BOT?", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            InfoRow(label = "Archivo", value = (if (file.name.isNotEmpty()) file.name + "  ·  " else "") + fmtSize(file.size))
            InfoRow(label = "Canal de origen", value = "${ch.title}  (@${ch.name})")
            InfoRow(label = "Destino", value = "@$BOT")
            Text("Telegram reenvía la publicación original; no se descarga nada en tu teléfono.",
                Modifier.padding(top = 4.dp, bottom = 14.dp), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Btn("CONFIRMAR REENVÍO", Blue) {
                dlg = 0
                botScope.launch { Tg.forward(ch.name, msgId, BOT) { fwdStates[key] = it } }
            }
            Spacer(Modifier.height(8.dp))
            Btn("CANCELAR", Ink) { dlg = 0 }
            Text("Cerrar sesión de Telegram", Modifier.padding(top = 12.dp).clickable { Tg.logOut(); dlg = 0 },
                color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Chip, 12.dp).padding(10.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 3)
    }
}

// =============================================================================
//  Inicio de sesión de Telegram (una sola vez; la sesión queda guardada cifrada)
// =============================================================================
@Composable
private fun TgField(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, secret: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).card(Chip, 12.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 15.sp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
private fun TgLoginDialog(onClose: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().card(Color.White, 20.dp).padding(18.dp)) {
            Text("Conecta tu Telegram", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("Cuvi usa tu cuenta solo para reenviar publicaciones a @$BOT. No descarga archivos ni lee tus chats.",
                Modifier.padding(top = 4.dp, bottom = 12.dp), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (Tg.net.isNotEmpty()) Text(Tg.net, Modifier.padding(bottom = 10.dp), color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            when (val a = Tg.auth) {
                is TgAuth.Idle, is TgAuth.Starting ->
                    Text("Iniciando Telegram…", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                is TgAuth.NeedPhone -> {
                    TgField(phone, { phone = it }, "+53 5XXXXXXX", KeyboardType.Phone)
                    Btn("ENVIAR CÓDIGO", Blue) { Tg.sendPhone(phone) }
                }
                is TgAuth.NeedCode -> {
                    Text("Te llegó un código a tu Telegram (o por SMS).", Modifier.padding(bottom = 8.dp),
                        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TgField(code, { code = it }, "Código", KeyboardType.Number)
                    Btn("CONFIRMAR", Blue) { Tg.sendCode(code) }
                }
                is TgAuth.NeedPassword -> {
                    Text("Tu cuenta tiene verificación en dos pasos." + if (Tg.pwHint.isNotEmpty()) " Pista: ${Tg.pwHint}" else "",
                        Modifier.padding(bottom = 8.dp), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TgField(pw, { pw = it }, "Contraseña", KeyboardType.Password, secret = true)
                    Btn("ENTRAR", Blue) { Tg.sendPassword(pw) }
                }
                is TgAuth.Ready -> Text("Sesión lista.", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                is TgAuth.Failed -> Text(a.why, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (Tg.busy) Text("Un momento…", Modifier.padding(top = 8.dp), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (Tg.hint.isNotEmpty()) Text(Tg.hint, Modifier.padding(top = 8.dp), color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Btn("CANCELAR", Ink) { onClose() }
        }
    }
}

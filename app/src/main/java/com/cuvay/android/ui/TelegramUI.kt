package com.cuvay.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun TelegramTab() {
    var selectedChatId by remember { mutableStateOf<Long?>(null) }
    val authState = Tg.auth

    if (authState !is TgAuth.Ready) {
        TelegramAuthScreen()
    } else {
        if (selectedChatId == null) {
            TelegramChatList(onChatSelected = { selectedChatId = it })
        } else {
            TelegramChatDetail(chatId = selectedChatId!!, onBack = { selectedChatId = null })
        }
    }
}

@Composable
fun TelegramAuthScreen() {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Conecta tu cuenta de Telegram", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            var phone by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            var pw by remember { mutableStateOf("") }

            when (val a = Tg.auth) {
                is TgAuth.Idle, is TgAuth.Starting -> {
                    Text("Iniciando Telegram…", color = Muted, fontSize = 14.sp)
                    LaunchedEffect(Unit) { Tg.start(ctx) }
                }
                is TgAuth.NeedPhone -> {
                    Field(phone, { phone = it }, "+53 5XXXXXXX", Modifier.width(200.dp))
                    Spacer(Modifier.height(8.dp))
                    Btn("ENVIAR CÓDIGO", Blue) { Tg.sendPhone(phone) }
                }
                is TgAuth.NeedCode -> {
                    Text("Te llegó un código a tu Telegram (o por SMS).", color = Muted, fontSize = 14.sp)
                    Field(code, { code = it }, "Código", Modifier.width(200.dp))
                    Spacer(Modifier.height(8.dp))
                    Btn("CONFIRMAR", Blue) { Tg.sendCode(code) }
                }
                is TgAuth.NeedPassword -> {
                    Text("Tu cuenta tiene verificación en dos pasos.", color = Muted, fontSize = 14.sp)
                    if (Tg.pwHint.isNotEmpty()) Text("Pista: ${Tg.pwHint}", color = Muted, fontSize = 12.sp)
                    Field(pw, { pw = it }, "Contraseña", Modifier.width(200.dp))
                    Spacer(Modifier.height(8.dp))
                    Btn("ENTRAR", Blue) { Tg.sendPassword(pw) }
                }
                is TgAuth.Failed -> {
                    Text(a.why, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Btn("REINTENTAR", Blue) { Tg.start(ctx) }
                }
                else -> {}
            }
            if (Tg.busy) Text("Un momento…", Modifier.padding(top = 8.dp), color = Muted, fontSize = 12.sp)
            if (Tg.hint.isNotEmpty()) Text(Tg.hint, Modifier.padding(top = 8.dp), color = Red, fontSize = 13.sp)
        }
    }
}

@Composable
fun TelegramChatList(onChatSelected: (Long) -> Unit) {
    val chats by Tg.chats.collectAsState()

    LaunchedEffect(Unit) {
        Tg.loadChats()
    }

    Column(Modifier.fillMaxSize()) {
        Text("Tus Chats", modifier = Modifier.padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)

        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Cargando chats...", color = Muted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(chat = chat, onClick = { onChatSelected(chat.id) })
                }
            }
        }
    }
}

@Composable
fun ChatListItem(chat: TdApi.Chat, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Placeholder avatar
        Box(Modifier.size(48.dp).background(Chip, shape = androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Text(chat.title.take(1).uppercase(), color = Blue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val lastMsgText = when (val content = chat.lastMessage?.content) {
                is TdApi.MessageText -> content.text.text
                is TdApi.MessagePhoto -> "Foto"
                is TdApi.MessageVideo -> "Video"
                is TdApi.MessageDocument -> "Documento"
                else -> "Mensaje"
            }
            Text(lastMsgText, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TelegramChatDetail(chatId: Long, onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<TdApi.Message>>(emptyList()) }
    var chat by remember { mutableStateOf<TdApi.Chat?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(chatId) {
        scope.launch {
            try {
                chat = Tg.getChat(chatId)
                val history = Tg.getChatHistory(chatId)
                messages = history.messages.toList()
            } catch (e: Exception) {
                // handle error
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // App Bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Blue)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹ Volver", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(16.dp))
            Text(chat?.title ?: "Chat", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Messages
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Cargando mensajes...", color = Muted)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItem(msg)
                }
            }
        }

        // Input
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF0F0F0))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                Field(inputText, { inputText = it }, "Escribe un mensaje...")
            }
            Spacer(Modifier.width(8.dp))
            Btn("ENVIAR", Blue) {
                if (inputText.isNotBlank()) {
                    val textToSend = inputText
                    inputText = ""
                    scope.launch {
                        try {
                            val newMsg = Tg.sendMessage(chatId, textToSend)
                            messages = listOf(newMsg) + messages
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(msg: TdApi.Message) {
    val isOut = msg.isOutgoing
    val bg = if (isOut) Color(0xFFDCF8C6) else Color(0xFFF0F0F0) // WhatsApp like colors
    val align = if (isOut) Alignment.CenterEnd else Alignment.CenterStart
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .background(bg, shape)
                .padding(8.dp)
                .widthIn(max = 280.dp)
        ) {
            when (val content = msg.content) {
                is TdApi.MessageText -> {
                    Text(content.text.text, color = Ink, fontSize = 16.sp)
                }
                is TdApi.MessagePhoto -> {
                    Text("📷 Foto", color = Muted, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    content.caption?.text?.let { if (it.isNotBlank()) Text(it, color = Ink, fontSize = 14.sp) }
                }
                is TdApi.MessageVideo -> {
                    Text("📹 Video", color = Muted, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    content.caption?.text?.let { if (it.isNotBlank()) Text(it, color = Ink, fontSize = 14.sp) }
                }
                is TdApi.MessageDocument -> {
                    Text("📄 Documento", color = Muted, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    content.caption?.text?.let { if (it.isNotBlank()) Text(it, color = Ink, fontSize = 14.sp) }
                }
                else -> {
                    Text("Mensaje no soportado", color = Muted, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }
            Text(Tg.formatDate(msg.date), color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
        }
    }
}

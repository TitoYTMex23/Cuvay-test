package com.cuvay.android.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cuvay.android.BuildConfig

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// =============================================================================
//  Sesión de Telegram dentro de Cuvi (TDLib) para reenviar publicaciones a @Cuv_ai.
//  - Solo se arranca cuando el usuario toca "Enviar a @Cuv_ai" (no corre en segundo plano antes).
//  - La base de TDLib (con la sesión) vive en filesDir/tdlib, cifrada con una clave aleatoria que
//    a su vez se guarda cifrada con el Android Keystore. Ninguna credencial viaja en el APK salvo api_id/api_hash.
//  - No descarga archivos: solo ordena a Telegram que reenvíe el mensaje original al chat del bot.
// =============================================================================

sealed class TgAuth {
    object Idle : TgAuth()
    object Starting : TgAuth()
    object NeedPhone : TgAuth()
    object NeedCode : TgAuth()
    object NeedPassword : TgAuth()
    object Ready : TgAuth()
    class Failed(val why: String) : TgAuth()
}

enum class FwdStep { PREPARING, SENDING, SENT, PROCESSING, PUBLISHED, ERROR }
class FwdState(val step: FwdStep, val text: String)

class TgError(val code: Int, message: String) : Exception(message)

fun tgFriendly(e: Throwable): String {
    val m = e.message ?: ""
    val s = m.uppercase()
    return when {
        e is TgError && e.code == 0 -> m                                   // mensaje propio de Cuvi
        "USERNAME_NOT_OCCUPIED" in s || "USERNAME_INVALID" in s -> "No encontré ese canal público"
        "FORWARDS_RESTRICTED" in s -> "El canal tiene el reenvío desactivado"
        "MESSAGE_NOT_FOUND" in s || "MESSAGE_ID_INVALID" in s -> "Esa publicación ya no existe en el canal"
        "PHONE_NUMBER_INVALID" in s -> "Número no válido. Escríbelo con el código de país (ej. +53…)"
        "PHONE_NUMBER_BANNED" in s -> "Telegram bloqueó ese número"
        "PHONE_CODE_EXPIRED" in s -> "El código venció. Pide otro."
        "PHONE_CODE_INVALID" in s || "CODE_INVALID" in s -> "Código incorrecto"
        "PASSWORD_HASH_INVALID" in s -> "Contraseña incorrecta"
        "FLOOD" in s || (e as? TgError)?.code == 420 -> "Telegram pide esperar un poco. Inténtalo más tarde."
        m.isNotBlank() -> m
        else -> "Error de Telegram"
    }
}

object Tg {
    /** Estado del inicio de sesión (Compose lo observa). */
    var auth by mutableStateOf<TgAuth>(TgAuth.Idle); private set
    /** Último error del paso de inicio de sesión (teléfono/código/contraseña). */
    var hint by mutableStateOf(""); private set
    /** Pista de la contraseña 2FA, si la cuenta la tiene. */
    var pwHint by mutableStateOf(""); private set
    /** Estado real de la conexión con Telegram ("" = conectado). */
    var net by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set


    // --- Estado para la UI ---
    private val _chats = MutableStateFlow<List<TdApi.Chat>>(emptyList())
    val chats: StateFlow<List<TdApi.Chat>> = _chats.asStateFlow()

    private val _files = MutableStateFlow<Map<Int, TdApi.File>>(emptyMap())
    val files: StateFlow<Map<Int, TdApi.File>> = _files.asStateFlow()

    private val chatPositions = ConcurrentHashMap<Long, TdApi.ChatPosition>()
    private val chatMap = ConcurrentHashMap<Long, TdApi.Chat>()

    private var client: Client? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- arranque
    fun start(ctx: Context) {
        if (client != null) return
        appCtx = ctx.applicationContext
        if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.isBlank()) {
            updateAuth(TgAuth.Failed("Falta configurar TG_API_ID y TG_API_HASH al compilar Cuvi"))
            return
        }
        updateAuth(TgAuth.Starting)
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        client = Client.create(Client.ResultHandler { onUpdate(it) }, null, null)
    }

    private fun updateAuth(a: TgAuth) { main.post { auth = a } }

    private fun params(ctx: Context) = TdApi.SetTdlibParameters().apply {
        useTestDc = false
        databaseDirectory = File(ctx.filesDir, "tdlib").absolutePath
        filesDirectory = File(ctx.cacheDir, "tdlib_files").absolutePath
        databaseEncryptionKey = dbKey(ctx)
        useFileDatabase = false
        useChatInfoDatabase = true
        useMessageDatabase = false
        useSecretChats = false
        apiId = BuildConfig.TG_API_ID
        apiHash = BuildConfig.TG_API_HASH
        systemLanguageCode = Locale.getDefault().language.ifEmpty { "es" }
        deviceModel = Build.MODEL
        systemVersion = Build.VERSION.RELEASE
        applicationVersion = BuildConfig.VERSION_NAME
    }

    // ---------------------------------------------------------------- clave de la base (Keystore)
    private const val KS_ALIAS = "cuvay_tg_db"
    private const val PREFS = "cuvay_tg"

    private fun ksKey(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(KS_ALIAS, null)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun wrap(b: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, ksKey())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(b), Base64.NO_WRAP)
    }

    private fun unwrap(s: String): ByteArray {
        val (iv, ct) = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
    }

    private fun dbKey(ctx: Context): ByteArray {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("k", null)?.let { s -> runCatching { return unwrap(s) } }
        // Primera vez, o el Keystore perdió la clave: clave nueva y base vieja fuera (hay que iniciar sesión otra vez).
        File(ctx.filesDir, "tdlib").deleteRecursively()
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString("k", wrap(k)).apply()
        return k
    }

    // ---------------------------------------------------------------- actualizaciones de TDLib
    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateConnectionState -> main.post {
                net = when (o.state) {
                    is TdApi.ConnectionStateWaitingForNetwork -> "Sin conexión a internet"
                    is TdApi.ConnectionStateConnectingToProxy -> "Conectando por el proxy…"
                    is TdApi.ConnectionStateConnecting -> "Conectando con Telegram…"
                    else -> ""
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                succeeded[o.oldMessageId] = o.message.id
                jobs.firstOrNull { it.tempId == o.oldMessageId }?.let { sentOk(it, o.message.id) }
            }
            is TdApi.UpdateMessageSendFailed -> jobs.firstOrNull { it.tempId == o.oldMessageId }?.let {
                jobs.remove(it); it.emit(FwdState(FwdStep.ERROR, tgFriendly(TgError(o.error.code, o.error.message))))
            }
            is TdApi.UpdateNewMessage -> botReply(o.message)
            is TdApi.UpdateMessageContent -> botEdit(o.chatId, o.messageId, o.newContent)

            is TdApi.UpdateChatPosition -> {
                updateChatPosition(o.chatId, o.position)
            }
            is TdApi.UpdateChatLastMessage -> {
                chatMap[o.chatId]?.let { c ->
                    c.lastMessage = o.lastMessage
                    refreshChatList()
                }
            }
            is TdApi.UpdateNewChat -> {
                chatMap[o.chat.id] = o.chat
            }
            is TdApi.UpdateChatTitle -> chatMap[o.chatId]?.title = o.title
            is TdApi.UpdateChatPhoto -> chatMap[o.chatId]?.photo = o.photo
            is TdApi.UpdateFile -> {
                _files.update { current ->
                    current.toMutableMap().apply { put(o.file.id, o.file) }
                }
            }

            else -> {}
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> appCtx?.let { client?.send(params(it), null) }
            is TdApi.AuthorizationStateWaitPhoneNumber -> updateAuth(TgAuth.NeedPhone)
            is TdApi.AuthorizationStateWaitCode -> updateAuth(TgAuth.NeedCode)
            is TdApi.AuthorizationStateWaitPassword -> { main.post { pwHint = s.passwordHint ?: "" }; updateAuth(TgAuth.NeedPassword) }
            is TdApi.AuthorizationStateReady -> updateAuth(TgAuth.Ready)
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> updateAuth(TgAuth.Starting)
            is TdApi.AuthorizationStateClosed -> { client = null; updateAuth(TgAuth.Idle) }
            else -> updateAuth(TgAuth.Failed("Esta cuenta pide un paso que Cuvi aún no soporta (correo, QR o registro). " +
                "Inicia sesión una vez en Telegram y vuelve a intentarlo."))
        }
    }

    // ---------------------------------------------------------------- inicio de sesión
    private fun authCall(f: TdApi.Function<*>) {
        main.post { hint = ""; busy = true }
        client?.send(f) { r ->
            main.post {
                busy = false
                if (r is TdApi.Error) hint = tgFriendly(TgError(r.code, r.message))
            }
        }
    }

    fun sendPhone(phone: String) = authCall(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone.trim() })
    fun sendCode(code: String) = authCall(TdApi.CheckAuthenticationCode(code.trim()))
    fun sendPassword(pw: String) = authCall(TdApi.CheckAuthenticationPassword(pw))
    fun logOut() { client?.send(TdApi.LogOut(), null) }

    private fun updateChatPosition(chatId: Long, pos: TdApi.ChatPosition) {
        if (pos.list is TdApi.ChatListMain) {
            chatPositions[chatId] = pos
            refreshChatList()
        }
    }

    private fun refreshChatList() {
        val sorted = chatMap.values.filter { chatPositions.containsKey(it.id) }
            .sortedByDescending { chatPositions[it.id]?.order ?: 0L }
        _chats.update { sorted }
    }

    suspend fun getChatHistory(chatId: Long, fromMessageId: Long = 0, limit: Int = 50): TdApi.Messages {
        return call(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
    }

    suspend fun getChat(chatId: Long): TdApi.Chat {
        return call(TdApi.GetChat(chatId))
    }

    suspend fun searchChats(query: String): TdApi.Chats {
        return call(TdApi.SearchChats(query, 50))
    }

    suspend fun downloadFile(fileId: Int, priority: Int = 1, offset: Long = 0, limit: Long = 0, synchronous: Boolean = false): TdApi.File {
        return call(TdApi.DownloadFile(fileId, priority, offset, limit, synchronous))
    }

    fun downloadFileAsync(fileId: Int) {
        client?.send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { }
    }

    suspend fun sendMessage(chatId: Long, text: String): TdApi.Message {
        val input = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), false, true)
        return call(TdApi.SendMessage(chatId, 0, 0, null, null, input))
    }

    fun formatDate(timestamp: Int): String {
        val format = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(timestamp * 1000L))
    }

    fun loadChats() {
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { }
    }


    // ---------------------------------------------------------------- reenvío y seguimiento del bot
    private class Job(val botChatId: Long, val emit: (FwdState) -> Unit) {
        @Volatile var tempId = 0L      // id provisional del mensaje reenviado
        @Volatile var sentId = 0L      // id definitivo, cuando Telegram lo confirma
        @Volatile var botMsgId = 0L    // mensaje con el que responde el bot
    }
    private val jobs = CopyOnWriteArrayList<Job>()
    private val succeeded = ConcurrentHashMap<Long, Long>()      // provisional -> definitivo (por si el aviso llega antes)

    private fun sentOk(j: Job, id: Long) {
        j.sentId = id
        j.emit(FwdState(FwdStep.SENT, "Enviado. Esperando respuesta del bot…"))
    }

    private fun textOf(c: TdApi.MessageContent?) = (c as? TdApi.MessageText)?.text?.text.orEmpty()

    private fun botReply(m: TdApi.Message) {
        if (m.isOutgoing) return
        val to = (m.replyTo as? TdApi.MessageReplyToMessage)?.messageId ?: return
        val j = jobs.firstOrNull { it.botChatId == m.chatId && it.sentId != 0L && it.sentId == to } ?: return
        j.botMsgId = m.id
        classify(j, textOf(m.content))
    }

    private fun botEdit(chatId: Long, msgId: Long, c: TdApi.MessageContent) {
        val j = jobs.firstOrNull { it.botChatId == chatId && it.botMsgId == msgId } ?: return
        classify(j, textOf(c))
    }

    /** Lee las respuestas reales del bot (⏳ procesando / ✅ publicado / ❌ error). No se inventa ningún estado. */
    private fun classify(j: Job, t: String) {
        when {
            t.startsWith("✅") -> { jobs.remove(j); j.emit(FwdState(FwdStep.PUBLISHED, "Publicado correctamente. Ya está en tu feed.")) }
            t.startsWith("❌") -> { jobs.remove(j); j.emit(FwdState(FwdStep.ERROR, t.removePrefix("❌").trim().ifEmpty { "El bot no pudo procesarlo" })) }
            t.startsWith("⏳") -> j.emit(FwdState(FwdStep.PROCESSING, "Archivo recibido. El bot lo está procesando…"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T = suspendCancellableCoroutine { c ->
        val cl = client
        if (cl == null) { c.resumeWithException(TgError(0, "Telegram no está iniciado")); return@suspendCancellableCoroutine }
        cl.send(f) { r ->
            if (r is TdApi.Error) c.resumeWithException(TgError(r.code, r.message)) else c.resume(r as T)
        }
    }

    /**
     * Reenvía la publicación [tgMsgId] del canal público [channel] al chat de [bot].
     * Los ids vienen de la propia app (usuario del canal e ID de mensaje del feed); no se reconstruye nada.
     */
    suspend fun forward(channel: String, tgMsgId: Long, bot: String, emit: (FwdState) -> Unit) {
        val ui: (FwdState) -> Unit = { s -> main.post { emit(s) } }
        var job: Job? = null
        try {
            ui(FwdState(FwdStep.PREPARING, "Preparando reenvío…"))
            val src = call(TdApi.SearchPublicChat(channel.trimStart('@')))
            val dst = call(TdApi.SearchPublicChat(bot.trimStart('@')))
            val id = tgMsgId shl 20                    // TDLib numera los mensajes de canal como id de servidor << 20
            val m = call(TdApi.GetMessage(src.id, id))
            if (m.content is TdApi.MessageText) throw TgError(0, "Esa publicación no tiene archivo")
            val p = call(TdApi.GetMessageProperties(src.id, id))
            if (!p.canBeForwarded) throw TgError(0, "El canal tiene el reenvío desactivado")

            val j = Job(dst.id, ui); job = j; jobs.add(j)
            ui(FwdState(FwdStep.SENDING, "Enviando a @${bot.trimStart('@')}…"))
            val res = call(TdApi.ForwardMessages().apply {
                chatId = dst.id; fromChatId = src.id
                messageIds = longArrayOf(id)
                sendCopy = false; removeCaption = false
            })
            val sent = res.messages?.firstOrNull() ?: throw TgError(0, "Telegram no permitió reenviar esa publicación")
            j.tempId = sent.id
            succeeded[sent.id]?.let { sentOk(j, it) }
        } catch (e: Exception) {
            job?.let { jobs.remove(it) }
            ui(FwdState(FwdStep.ERROR, tgFriendly(e)))
        }
    }
}

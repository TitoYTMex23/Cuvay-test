package com.cuvay.android.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Motor DNS para el chat bonito. Emula la API del servidor de chat (me, threads, messages, send y las
 * rutas de rooms) sobre el mensajero DNS del servidor Go local (las rutas api/chat de ese servidor).
 * La UI de Chat.kt no sabe nada: ChatApi.call() le pasa la peticion a DnsBackend.handle() cuando esta activo.
 *
 * Todo (GLOBAL, PRIVADOS, GRUPOS) viaja por DNS cifrado. GLOBAL y grupos = el mensaje se reparte como
 * mensaje privado a cada miembro (sobre "§c|sala|id|nick|texto"). Lento por naturaleza (minutos).
 */
class DnsErr(val code: String, msg: String) : Exception(msg)

interface DnsIo {
    fun http(method: String, path: String, body: JSONObject? = null, readMs: Int = 15000): String
    fun load(): String
    fun save(s: String)
}

class AndroidDnsIo(private val base: String, ctx: Context) : DnsIo {
    private val p = ctx.applicationContext.getSharedPreferences("cuvay_dnschat", Context.MODE_PRIVATE)
    override fun http(method: String, path: String, body: JSONObject?, readMs: Int): String {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = readMs; c.requestMethod = method
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP $code" + if (txt.isNotBlank()) ": " + txt.trim().take(120) else "")
        return txt
    }
    override fun load() = p.getString("db", "") ?: ""
    override fun save(s: String) { p.edit().putString("db", s).apply() }
}

object DnsBackend {
    const val ROOT = "http://dns.local"
    @Volatile var active = false
    private var io: DnsIo? = null
    private val lock = Object()

    private class M(var id: Long, val room: String, val from: String, var text: String, val ts: Long,
                    val mine: Boolean, var kind: String = "text", var deleted: Boolean = false, var rev: Long = 0)
    private class G(val id: Long, var name: String, var owner: String, val members: MutableList<String>, var descr: String = "")
    private class Job(val addr: String, val text: String, val msgId: Long, var tries: Int = 0)

    private val msgs = ArrayList<M>()
    private val contacts = LinkedHashMap<String, String>()      // addr -> nombre
    private val groups = LinkedHashMap<Long, G>()
    private val seen = LinkedHashSet<String>()
    private val peerTs = HashMap<String, Long>()
    private val imported = HashSet<String>()
    private val readId = HashMap<String, Long>()
    private val seenMid = LinkedHashSet<String>()
    private val queue = java.util.concurrent.LinkedBlockingQueue<Job>()
    private var nextId = 1L
    private var rev = 1L
    private var nick = ""
    var myAddr = ""; private set
    private var threadsOn = false

    // ------------------------------------------------------------------ arranque
    /** El nucleo Go solo envia por un servidor de chat HABILITADO. En un telefono nuevo ninguno lo esta: lo habilita (y se registra). */
    private fun ensureServerEnabled(i: DnsIo) {
        runCatching {
            val o = JSONObject(i.http("GET", "/api/chat/info"))
            if (!o.optBoolean("exists") || o.optBoolean("anyEnabled")) return
            val a = o.optJSONArray("servers") ?: return
            if (a.length() == 0) return
            val dom = a.getJSONObject(0).optString("domain")
            if (dom.isNotEmpty())
                i.http("POST", "/api/chat/enable", JSONObject().put("action", "server").put("server", dom).put("on", true), 90_000)
        }
    }

    fun start(io0: DnsIo) {
        ensureServerEnabled(io0)      // fuera del lock: puede tardar unos segundos
        synchronized(lock) {
            io = io0
            if (!threadsOn) loadDb()
            runCatching {
                val o = JSONObject(io0.http("GET", "/api/chat/info"))
                myAddr = o.optString("address")
            }
            if (nick.isEmpty()) nick = if (myAddr.length >= 5) "u" + myAddr.lowercase().take(5) else "yo"
            active = true
            if (!threadsOn) { threadsOn = true; startThreads() }
        }
    }
    fun stop() { active = false }
    fun setNick(n: String) { synchronized(lock) { nick = n.trim().take(16).ifEmpty { nick }; saveDb() } }
    fun nick() = nick

    private fun startThreads() {
        // 1) cola de envio: uno a uno (el mensajero DNS tarda minutos por mensaje)
        Thread {
            while (true) {
                val j = try { queue.take() } catch (e: InterruptedException) { return@Thread }
                if (!active) { queue.put(j); Thread.sleep(3000); continue }
                runJob(j)
            }
        }.also { it.isDaemon = true; it.start() }
        // 2) revisar el buzon por DNS (lento, en su propio hilo)
        Thread {
            while (true) {
                if (active) runCatching { io?.http("POST", "/api/chat/poll", JSONObject(), 11 * 60 * 1000) }
                Thread.sleep(8000)
            }
        }.also { it.isDaemon = true; it.start() }
        // 3) meter en la UI lo que ya llego (barato: lee solo el servidor local)
        Thread {
            while (true) {
                if (active) runCatching { ingestAll() }
                Thread.sleep(3000)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** El servidor Go local responde 200 aun cuando el envio por DNS falla ({"ok":false,"error":"chat_err_..."}). */
    private fun failText(key: String) = when (key) {
        "chat_err_unreachable" -> "sin respuesta del servidor DNS"
        "chat_err_rate_limited" -> "límite de envíos por hora"
        "chat_err_inbox_full" -> "buzón del destinatario lleno"
        "chat_err_pair_quota" -> "demasiados mensajes sin leer para esa persona"
        "chat_err_unknown_recipient", "chat_err_peer_not_on_server" -> "esa dirección no existe en este servidor"
        "chat_err_no_key", "chat_err_unverified" -> "falta la clave del servidor (sk=) en tu config"
        "chat_err_disabled", "chat_err_server_disabled", "chat_err_server_off" -> "este teléfono no tiene config activa (Fuentes) con la clave del servidor"
        "chat_err_version" -> "versión del servidor incompatible"
        "chat_err_busy" -> "servidor ocupado"
        else -> key.removePrefix("chat_err_").ifEmpty { "error" }
    }
    private fun isPermanent(key: String) = key in setOf("chat_err_no_key", "chat_err_unverified", "chat_err_disabled",
        "chat_err_server_disabled", "chat_err_server_off", "chat_err_version", "chat_err_unknown_recipient", "chat_err_peer_not_on_server")

    private fun runJob(j: Job) {
        val i = io ?: return
        var key = ""
        try {
            val raw = i.http("POST", "/api/chat/send", JSONObject().put("peer", j.addr).put("text", j.text), 16 * 60 * 1000)
            val o = runCatching { JSONObject(raw) }.getOrNull()
            if (o == null || o.optBoolean("ok", true)) return          // enviado de verdad
            key = o.optString("error").ifEmpty { "chat_err_generic" }
        } catch (e: Exception) { key = "chat_err_unreachable" }
        if (key == "chat_err_disabled" && j.tries == 0) {          // servidor sin habilitar: habilitarlo y reintentar
            ensureServerEnabled(i); j.tries++; queue.put(j); return
        }
        j.tries++
        if (!isPermanent(key) && j.tries < 3) {
            Thread.sleep(if (key == "chat_err_rate_limited" || key == "chat_err_inbox_full" || key == "chat_err_pair_quota") 60000 else 15000)
            queue.put(j)
        } else synchronized(lock) {
            msgs.firstOrNull { it.id == j.msgId }?.let {
                if (!it.text.startsWith("⚠ ")) { it.text = "⚠ no enviado (" + failText(key) + "): " + it.text; it.rev = ++rev; saveDb() }
            }
        }
    }

    // ------------------------------------------------------------------ persistencia
    private fun loadDb() {
        val s = io?.load().orEmpty(); if (s.isEmpty()) return
        runCatching {
            val o = JSONObject(s)
            nick = o.optString("nick"); nextId = o.optLong("nextId", 1); rev = o.optLong("rev", 1)
            msgs.clear(); contacts.clear(); groups.clear(); seen.clear(); peerTs.clear(); imported.clear(); readId.clear(); seenMid.clear()
            o.optJSONArray("msgs")?.let { a -> for (i in 0 until a.length()) { val m = a.getJSONObject(i)
                msgs.add(M(m.getLong("id"), m.getString("room"), m.getString("from"), m.getString("text"), m.getLong("ts"),
                    m.getBoolean("mine"), m.optString("kind", "text"), m.optBoolean("del"), m.optLong("rev"))) } }
            o.optJSONObject("contacts")?.let { c -> c.keys().forEach { contacts[it] = c.getString(it) } }
            o.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) { val g = a.getJSONObject(i)
                val ms = g.getJSONArray("members"); val l = ArrayList<String>(); for (k in 0 until ms.length()) l.add(ms.getString(k))
                groups[g.getLong("id")] = G(g.getLong("id"), g.getString("name"), g.getString("owner"), l, g.optString("descr")) } }
            o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) }
            o.optJSONArray("mids")?.let { a -> for (i in 0 until a.length()) seenMid.add(a.getString(i)) }
            o.optJSONObject("peerTs")?.let { c -> c.keys().forEach { peerTs[it] = c.getLong(it) } }
            o.optJSONArray("imported")?.let { a -> for (i in 0 until a.length()) imported.add(a.getString(i)) }
            o.optJSONObject("read")?.let { c -> c.keys().forEach { readId[it] = c.getLong(it) } }
        }
    }
    private fun saveDb() {
        while (msgs.size > 3000) msgs.removeAt(0)
        while (seen.size > 6000) seen.remove(seen.first())
        while (seenMid.size > 3000) seenMid.remove(seenMid.first())
        val o = JSONObject().put("nick", nick).put("nextId", nextId).put("rev", rev)
        o.put("msgs", JSONArray().also { a -> msgs.forEach { m -> a.put(JSONObject().put("id", m.id).put("room", m.room)
            .put("from", m.from).put("text", m.text).put("ts", m.ts).put("mine", m.mine).put("kind", m.kind)
            .put("del", m.deleted).put("rev", m.rev)) } })
        o.put("contacts", JSONObject().also { c -> contacts.forEach { (k, v) -> c.put(k, v) } })
        o.put("groups", JSONArray().also { a -> groups.values.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name)
            .put("owner", g.owner).put("descr", g.descr).put("members", JSONArray(g.members))) } })
        o.put("seen", JSONArray(seen.toList())); o.put("mids", JSONArray(seenMid.toList()))
        o.put("peerTs", JSONObject().also { c -> peerTs.forEach { (k, v) -> c.put(k, v) } })
        o.put("imported", JSONArray(imported.toList()))
        o.put("read", JSONObject().also { c -> readId.forEach { (k, v) -> c.put(k, v) } })
        io?.save(o.toString())
    }

    // ------------------------------------------------------------------ utilidades
    private fun now() = System.currentTimeMillis() / 1000
    private fun isAddr(s: String) = s.length == 20 && s.all { it in 'a'..'z' || it in '2'..'7' }   // base32 en minúsculas, trae dígitos 2-7
    private fun nameOf(addr: String) = contacts[addr]?.takeIf { it.isNotBlank() } ?: addr.lowercase().take(8)
    private fun addrOf(name: String): String? {
        if (isAddr(name)) return name
        return contacts.entries.firstOrNull { it.value.equals(name, true) }?.key
            ?: contacts.keys.firstOrNull { it.lowercase().take(8) == name.lowercase() }
    }
    private fun add(room: String, from: String, text: String, ts: Long, mine: Boolean, kind: String = "text"): M {
        val m = M(nextId++, room, from, text, ts, mine, kind, false, ++rev)
        msgs.add(m); return m
    }
    private fun toMsg(m: M) = JSONObject().put("id", m.id).put("from", m.from).put("text", if (m.deleted) "" else m.text)
        .put("ts", m.ts).put("deleted", m.deleted).put("kind", m.kind).put("edited", false).put("pinned", false)
    private fun roomMembers(room: String): List<String> = when {
        room == "global" -> contacts.keys.toList()
        room.startsWith("g:") -> groups[room.removePrefix("g:").toLongOrNull()]?.members?.filter { it != myAddr } ?: emptyList()
        else -> emptyList()
    }
    private fun rid() = (1..6).map { "abcdefghijkmnpqrstuvwxyz23456789"[(Math.random() * 32).toInt()] }.joinToString("")
    private fun ensureContact(addr: String) {
        if (contacts.containsKey(addr)) return
        val n = addr.lowercase().take(8)
        contacts[addr] = n
        runCatching { io?.http("POST", "/api/chat/contacts", JSONObject().put("addr", addr).put("name", n)) }
    }
    private fun enqueue(addr: String, text: String, msgId: Long) {
        // 500 bytes maximo por mensaje del mensajero
        var t = text
        while (t.toByteArray().size > 500) t = t.dropLast(1)
        queue.put(Job(addr, t, msgId))
    }

    // ------------------------------------------------------------------ entrada (lo que llega por DNS)
    private fun ingestAll() {
        val i = io ?: return
        val th = JSONArray(i.http("GET", "/api/chat/threads"))
        val todo = ArrayList<String>()
        synchronized(lock) {
            for (k in 0 until th.length()) {
                val t = th.getJSONObject(k); val a = t.optString("addr"); if (a.isEmpty()) continue
                val nm = t.optString("name")
                if (nm.isNotBlank() && contacts[a] != nm) contacts[a] = nm
                else if (!contacts.containsKey(a)) contacts[a] = a.lowercase().take(8)
                var ts = t.optLong("lastTs"); if (ts > 1_000_000_000_000L) ts /= 1000
                if (peerTs[a] != ts || t.optInt("unread") > 0) todo.add(a)
                peerTs[a] = ts
            }
        }
        var changed = false
        for (a in todo) {
            val o = runCatching { JSONObject(i.http("GET", "/api/chat/messages?peer=" + java.net.URLEncoder.encode(a, "UTF-8"))) }.getOrNull() ?: continue
            val arr = o.optJSONArray("msgs") ?: continue
            synchronized(lock) {
                val first = imported.add(a)
                for (k in 0 until arr.length()) {
                    val m = arr.getJSONObject(k)
                    val dir = m.optString("dir"); val key = "$a|$dir|${m.optLong("seq")}"
                    if (!seen.add(key)) continue
                    if (dir != "in" && !(first && dir == "out")) continue
                    var ts = m.optLong("ts"); if (ts > 1_000_000_000_000L) ts /= 1000; if (ts <= 0) ts = now()
                    if (ingestOne(a, dir == "out", m.optString("text"), ts)) changed = true
                }
                saveDb()
            }
        }
    }

    private fun ingestOne(addr: String, out: Boolean, text: String, ts: Long): Boolean {
        val nm = nameOf(addr)
        if (text.startsWith("§")) {
            if (out) return false
            val p = text.split("|", limit = 5)
            when (p[0]) {
                "§c" -> if (p.size == 5) {
                    val room = p[1].let { if (it == "global") it else "g:" + it.removePrefix("g") }
                    if (!seenMid.add(p[2])) return false
                    if (room != "global") { val g = groups[room.removePrefix("g:").toLongOrNull()]; if (g == null || addr !in g.members) return false }
                    add(room, p[3].ifBlank { nm }, p[4], ts, false); return true
                }
                "§g" -> if (p.size >= 5) {
                    val id = p[1].toLongOrNull() ?: return false
                    val ms = p[4].split(",").filter { isAddr(it) }.toMutableList()
                    val ex = groups[id]
                    if (ex != null && ex.owner != addr) return false
                    if (myAddr.isNotEmpty() && myAddr !in ms) { groups.remove(id); return true }
                    if (ex == null) groups[id] = G(id, p[2], p[3].ifBlank { addr }, ms) else { ex.name = p[2]; ex.members.clear(); ex.members.addAll(ms) }
                    ms.forEach { ensureContactLocal(it) }
                    return true
                }
                "§j" -> if (p.size >= 3) {
                    val id = p[1].toLongOrNull() ?: return false
                    val g = groups[id] ?: return false
                    if (g.owner == myAddr && addr !in g.members) { g.members.add(addr); contacts[addr] = p[2].ifBlank { nm }; broadcastGroup(g) }
                    return true
                }
            }
            return false
        }
        add("@$nm", if (out) nick else nm, text, ts, out)
        return true
    }
    private fun ensureContactLocal(a: String) { if (a != myAddr && !contacts.containsKey(a)) { contacts[a] = a.lowercase().take(8); val i = io; Thread { runCatching { i?.http("POST", "/api/chat/contacts", JSONObject().put("addr", a).put("name", a.lowercase().take(8))) } }.start() } }
    private fun broadcastGroup(g: G) {
        val env = "§g|${g.id}|${g.name}|${g.owner}|${g.members.joinToString(",")}"
        g.members.filter { it != myAddr }.forEach { enqueue(it, env, -1) }
    }

    // ------------------------------------------------------------------ API emulada
    fun handle(method: String, path: String, body: JSONObject?): JSONObject {
        val i = path.indexOf('?')
        val route = if (i >= 0) path.substring(0, i) else path
        val q = HashMap<String, String>()
        if (i >= 0) path.substring(i + 1).split("&").forEach { kv ->
            val e = kv.indexOf('='); if (e > 0) q[kv.substring(0, e)] = URLDecoder.decode(kv.substring(e + 1), "UTF-8") }
        val b = body ?: JSONObject()
        synchronized(lock) {
            when (route) {
                "/api/me" -> return JSONObject().put("name", nick).put("admin", false).put("hasPassword", true).put("token", "dns")
                "/api/login", "/api/register" -> return JSONObject().put("token", "dns").put("name", nick)
                "/api/logout", "/api/password" -> return JSONObject().put("ok", true)
                "/api/users" -> return users(q["q"].orEmpty())
                "/api/threads" -> return threads()
                "/api/pins" -> return JSONObject().put("messages", JSONArray())
                "/api/stickers" -> return JSONObject()
                "/api/send" -> return send(b)
                "/api/delete" -> {
                    val m = msgs.firstOrNull { it.id == b.optLong("id") && it.mine } ?: throw DnsErr("forbidden", "Solo puedes borrar tus mensajes")
                    m.deleted = true; m.rev = ++rev; saveDb()
                    return JSONObject().put("message", toMsg(m))
                }
                "/api/rooms/mine" -> return roomsMine()
                "/api/rooms/search" -> return JSONObject().put("rooms", JSONArray())
                "/api/rooms/create" -> return createRoom(b)
                "/api/rooms/join" -> return joinRoom(b)
                "/api/rooms/members" -> {
                    val g = groups[q["id"]?.toLongOrNull()] ?: throw DnsErr("not_found", "Grupo no encontrado")
                    val a = JSONArray()
                    g.members.forEach { a.put(JSONObject().put("name", if (it == myAddr) nick else nameOf(it)).put("role", if (it == g.owner) "owner" else "member")) }
                    return JSONObject().put("members", a)
                }
                "/api/rooms/leave", "/api/rooms/delete" -> {
                    val id = b.optLong("id"); val g = groups.remove(id)
                    if (g != null && g.owner == myAddr && route.endsWith("delete")) g.members.filter { it != myAddr }.forEach { enqueue(it, "§g|$id|${g.name}|${g.owner}|x", -1) }
                    else if (g != null) { g.members.remove(myAddr); if (g.owner != myAddr) enqueue(g.owner, "§j|$id|", -1) }
                    saveDb(); return JSONObject().put("ok", true)
                }
            }
        }
        if (route == "/api/messages") return messages(q)
        throw DnsErr("dns_unavailable", "No disponible en modo DNS")
    }

    private fun users(qs: String): JSONObject {
        val a = JSONArray(); val s = qs.trim().lowercase()
        if (isAddr(qs.trim())) a.put(JSONObject().put("name", qs.trim().lowercase().take(8).let { nameOf(qs.trim()).takeIf { contacts.containsKey(qs.trim()) } ?: qs.trim() }).put("online", false))
        else contacts.entries.filter { s.isEmpty() || it.value.lowercase().contains(s) }.take(30)
            .forEach { a.put(JSONObject().put("name", it.value).put("online", false)) }
        return JSONObject().put("users", a)
    }

    private fun threads(): JSONObject {
        val a = JSONArray()
        val last = HashMap<String, M>()
        msgs.forEach { if (it.room.startsWith("@")) last[it.room] = it }
        last.values.sortedByDescending { it.ts }.forEach { m ->
            val un = msgs.count { it.room == m.room && !it.mine && it.id > (readId[m.room] ?: 0) }
            a.put(JSONObject().put("peer", m.room.removePrefix("@")).put("text", if (m.deleted) "" else m.text).put("ts", m.ts)
                .put("mine", m.mine).put("unread", un).put("deleted", m.deleted).put("kind", m.kind))
        }
        return JSONObject().put("threads", a)
    }

    private fun roomsMine(): JSONObject {
        val a = JSONArray()
        groups.values.forEach { g ->
            val room = "g:${g.id}"
            val last = msgs.lastOrNull { it.room == room && !it.deleted }
            val o = JSONObject().put("id", g.id).put("room", room).put("name", g.name).put("kind", "group")
                .put("role", if (g.owner == myAddr) "owner" else "member").put("members", g.members.size).put("descr", g.descr)
                .put("unread", msgs.count { it.room == room && !it.mine && it.id > (readId[room] ?: 0) })
            if (last != null) o.put("last", JSONObject().put("from", last.from).put("text", last.text))
            if (g.owner == myAddr) o.put("code", "${g.id}@${g.owner}")
            a.put(o)
        }
        return JSONObject().put("rooms", a)
    }

    private fun createRoom(b: JSONObject): JSONObject {
        val name = b.optString("name").trim()
        if (name.isEmpty()) throw DnsErr("invalid", "Escribe un nombre")
        if (myAddr.isEmpty()) throw DnsErr("dns_unavailable", "Activa primero tu dirección DNS")
        val id = 100000L + (Math.random() * 900000000L).toLong()
        groups[id] = G(id, name.take(40), myAddr, mutableListOf(myAddr), b.optString("descr"))
        saveDb()
        return JSONObject().put("ok", true).put("id", id).put("room", "g:$id").put("code", "$id@$myAddr")
    }

    private fun joinRoom(b: JSONObject): JSONObject {
        val code = b.optString("code").trim()
        val p = code.split("@")
        val id = p.getOrNull(0)?.toLongOrNull(); val owner = p.getOrNull(1).orEmpty().trim().lowercase()
        if (id == null || !isAddr(owner)) throw DnsErr("invalid", "Código inválido (formato: número@dirección)")
        ensureContact(owner)
        if (groups[id] == null) groups[id] = G(id, "Grupo $id (esperando al dueño)", owner, mutableListOf(owner))
        enqueue(owner, "§j|$id|$nick", -1)
        saveDb()
        return JSONObject().put("ok", true)
    }

    private fun send(b: JSONObject): JSONObject {
        val text = b.optString("text").trim()
        if (text.isEmpty()) throw DnsErr("invalid", "Mensaje vacío")
        val kind = b.optString("kind", "text").let { if (it == "sticker") it else "text" }
        val room = if (b.has("room")) b.optString("room") else "@" + b.optString("to")
        val m: M
        if (room.startsWith("@")) {
            val name = room.removePrefix("@")
            val addr = addrOf(name) ?: throw DnsErr("not_found", "No conozco a @$name. Pega su dirección de 20 letras en Buscar")
            ensureContact(addr)
            m = add("@" + nameOf(addr), nick, text, now(), true, kind)
            if (text.toByteArray().size > 500) { msgs.remove(m); throw DnsErr("too_long", "Máximo 500 bytes por mensaje") }
            enqueue(addr, text, m.id)
        } else {
            if (room != "global" && groups[room.removePrefix("g:").toLongOrNull()] == null) throw DnsErr("not_found", "Grupo no encontrado")
            val mid = rid(); seenMid.add(mid)
            val code = if (room == "global") "global" else "g" + room.removePrefix("g:")
            val env = "§c|$code|$mid|$nick|"
            if ((env + text).toByteArray().size > 500) throw DnsErr("too_long", "Mensaje demasiado largo para DNS")
            m = add(room, nick, text, now(), true, kind)
            roomMembers(room).forEach { enqueue(it, env + text, m.id) }
        }
        readId[m.room] = m.id
        saveDb()
        return JSONObject().put("message", toMsg(m))
    }

    /** "@direccion-de-20-letras" y "@nombre" son la misma conversacion. */
    private fun roomKey(r: String) = if (r.startsWith("@") && isAddr(r.substring(1))) "@" + nameOf(r.substring(1)) else r

    private fun messages(q: Map<String, String>): JSONObject {
        val room0 = q["room"] ?: ("@" + (q["peer"] ?: ""))
        val after = q["after"]?.toLongOrNull() ?: 0L
        val since = q["since"]?.toLongOrNull() ?: 0L
        val wait = (q["wait"]?.toIntOrNull() ?: 0).coerceIn(0, 25)
        val limit = q["limit"]?.toIntOrNull() ?: 60
        val end = System.currentTimeMillis() + wait * 1000L
        while (true) {
            synchronized(lock) {
                val room = roomKey(room0)
                val mine = msgs.filter { it.room == room }
                val fresh = mine.filter { it.id > after }
                val upd = if (after > 0) mine.filter { it.id <= after && it.rev > since } else emptyList()
                if (fresh.isNotEmpty() || upd.isNotEmpty() || wait == 0 || System.currentTimeMillis() >= end) {
                    val page = if (after == 0L) fresh.takeLast(limit) else fresh.take(limit)
                    mine.maxOfOrNull { it.id }?.let { readId[room] = it }
                    return JSONObject().put("messages", JSONArray(page.map { toMsg(it) }))
                        .put("updated", JSONArray(upd.map { toMsg(it) })).put("rev", rev)
                }
            }
            Thread.sleep(500)
        }
    }
}

import os, re, json, time, hmac, hashlib, sqlite3, asyncio, glob, threading, shutil, secrets
from urllib.parse import unquote
from contextlib import closing
from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.responses import FileResponse, JSONResponse

DB = os.environ.get("DB", "/data/panel.db")
CHAT_DB = os.environ.get("CHAT_DB", "/chat/chat.db")   # base del chat (carpeta montada en docker-compose.yml)
PASSWORD = os.environ["ADMIN_PASSWORD"]
# Palabra que va en el codigo: cuvay-<CODE_LABEL>-<codigo>. Sin punto = alias (la app lo manda a este panel por defecto);
# con punto (p. ej. "pepe.example.com") la app busca el panel en https://panel.<CODE_LABEL>.
CODE_LABEL = re.sub(r"[^a-z0-9.]", "", os.environ.get("CODE_LABEL", "titomx").lower()) or "titomx"
KEY = os.environ.get("SECRET", PASSWORD + "-thefeed").encode()
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

DEFAULT = {"min_version": 0, "latest_version": 0, "apk_url": "", "notes": "",
           "maintenance": {"on": False, "title": "Volvemos pronto", "body": "Estamos haciendo mejoras."}, "flags": {}}

def db():
    c = sqlite3.connect(DB); c.row_factory = sqlite3.Row; return c

with db() as c:
    c.executescript("""
    create table if not exists kv(k text primary key, v text);
    create table if not exists msgs(id integer primary key autoincrement, data text, active int default 1, created int);
    create table if not exists devices(id text primary key, version int, model text, first int, last int, opens int default 0);
    create table if not exists feeds(id integer primary key autoincrement, name text default '', uri text not null,
        activate int default 1, active int default 1, created int);
    """)
    if "approved" not in [r[1] for r in c.execute("pragma table_info(devices)")]:
        c.execute("alter table devices add column approved int default 0")
    if "code" not in [r[1] for r in c.execute("pragma table_info(feeds)")]:
        c.execute("alter table feeds add column code text default ''")

FEED_RE = re.compile(r"(?:cuvay|thefeed)://[^/\s]+/[^?\s]+(\?\S*)?")   # acepta el prefijo nuevo y el viejo

# ---------- Codigos cortos: cuvay-<CODE_LABEL>-<CODIGO> ----------
# Sin 0/O/1/I para que no se confundan al dictarlos. El codigo NO es la clave: es solo la llave para pedirla al panel.
CODE_ABC = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
CODE_LEN = 8

def new_code(d):
    while True:
        c = "".join(secrets.choice(CODE_ABC) for _ in range(CODE_LEN))
        if not d.execute("select 1 from feeds where code=?", (c,)).fetchone(): return c

def full_code(c): return f"cuvay-{CODE_LABEL}-{c}" if c else ""

def ensure_codes():
    with db() as d:
        for r in d.execute("select id from feeds where coalesce(code,'')=''").fetchall():
            d.execute("update feeds set code=? where id=?", (new_code(d), r["id"]))
ensure_codes()

def parse_uri(uri):
    """Lo mismo que hace la app: cuvay://DOMINIO/CLAVE?n=NICK&sk=..&d=extra,extra&r=ip,ip"""
    m = re.match(r"(?:cuvay|thefeed)://([^/?]+)/([^?\s]+)(?:\?(\S*))?", uri or "")
    if not m: return None
    q = {}
    for kv in (m.group(3) or "").split("&"):
        if "=" in kv:
            k, v = kv.split("=", 1); q[k] = unquote(v)
    lst = lambda x: [i.strip() for i in x.split(",") if i.strip()]
    return {"domain": unquote(m.group(1)).lower(), "key": unquote(m.group(2)), "sk": q.get("sk", "").strip(),
            "extra": lst(q.get("d", "")), "resolvers": lst(q.get("r", "")), "nick": q.get("n", "")}

_tries = {}
def too_many(ip, ok=False):
    """Maximo 15 intentos fallidos cada 10 minutos por IP (los codigos no se pueden adivinar a fuerza bruta)."""
    now = time.time(); l = [t for t in _tries.get(ip, []) if t > now - 600]
    if not ok: l.append(now)
    _tries[ip] = l
    return len(l) > 15

def kv_get(k, default=None):
    with db() as c:
        r = c.execute("select v from kv where k=?", (k,)).fetchone()
    return r["v"] if r else default

def kv_set(k, v):
    with db() as c:
        c.execute("insert into kv(k,v) values(?,?) on conflict(k) do update set v=excluded.v", (k, v))

def feeds_mode():
    return "all" if kv_get("feeds_mode") == "all" else "approved"     # por defecto: solo dispositivos aprobados

def clean_uri(text):
    m = FEED_RE.search(str(text or ""))          # acepta un mensaje completo: toma solo el enlace
    if not m or len(m.group(0)) > 2000: raise HTTPException(400, "Eso no es un enlace cuvay://dominio/clave válido")
    return m.group(0)

def get_cfg():
    with db() as c:
        r = c.execute("select v from kv where k='config'").fetchone()
    return {**DEFAULT, **(json.loads(r["v"]) if r else {})}

def sign(exp): return f"{exp}.{hmac.new(KEY, str(exp).encode(), hashlib.sha256).hexdigest()}"
def valid(tok):
    try:
        exp, _ = tok.split(".", 1)
        return int(exp) > time.time() and hmac.compare_digest(tok, sign(int(exp)))
    except Exception:
        return False
def auth(request: Request):
    if not valid(request.cookies.get("tok", "")): raise HTTPException(401, "Sesión expirada")

# ---------- API pública (la app) ----------
@app.get("/api/app/config")
def app_config(v: int = 0, id: str = "", model: str = ""):
    now = int(time.time()); c = get_cfg(); id = id[:64]
    with db() as d:
        if id:
            d.execute("""insert into devices(id,version,model,first,last,opens) values(?,?,?,?,?,1)
                on conflict(id) do update set version=excluded.version, model=excluded.model, last=excluded.last, opens=opens+1""",
                (id[:64], v, model[:40], now, now))
        rows = d.execute("select id,data from msgs where active=1 order by id desc").fetchall()
        dev = d.execute("select approved from devices where id=?", (id,)).fetchone() if id else None
        feed_rows = d.execute("select id,uri,activate from feeds where active=1 order by id").fetchall()
    # "feeds" solo viaja a dispositivos aprobados (o a todos si lo activas en el panel). Si la clave
    # no viaja, la app no toca sus feeds; si viaja vacía, la app quita los que puso el panel.
    deliver = feeds_mode() == "all" or bool(dev and dev["approved"])
    msgs = []
    for r in rows:
        m = json.loads(r["data"]); m["id"] = r["id"]
        if m.get("start") and now < m["start"]: continue
        if m.get("end") and now > m["end"]: continue
        if v < m.get("min_v", 0): continue
        if m.get("max_v") and v > m["max_v"]: continue
        msgs.append(m)
    out = {"force": v < c["min_version"], "update": v < c["latest_version"], "latest_version": c["latest_version"],
           "apk_url": c["apk_url"], "notes": c["notes"], "maintenance": c["maintenance"], "flags": c["flags"], "messages": msgs}
    if deliver:
        out["feeds"] = [{"id": f"f{r['id']}", "uri": r["uri"], "activate": bool(r["activate"])} for r in feed_rows]
    return JSONResponse(out, headers={"Cache-Control": "no-store"})

@app.get("/api/app/code/{code}")
def app_code(code: str, request: Request):
    ip = (request.headers.get("x-forwarded-for", "") or (request.client.host if request.client else "")).split(",")[0].strip()
    if too_many(ip, ok=True): raise HTTPException(429, "Demasiados intentos. Espera unos minutos.")
    code = re.sub(r"[^A-Za-z0-9]", "", code).upper()
    with db() as d:
        r = d.execute("select uri from feeds where code=? and active=1", (code,)).fetchone() if len(code) == CODE_LEN else None
    a = parse_uri(r["uri"]) if r else None
    if not a:
        too_many(ip)
        raise HTTPException(404, "Código no válido o ya no está activo")
    return JSONResponse(a, headers={"Cache-Control": "no-store"})

# ---------- Admin ----------
@app.post("/admin/api/login")
async def login(request: Request):
    b = await request.json()
    if not hmac.compare_digest(str(b.get("password", "")), PASSWORD):
        await asyncio.sleep(1.2); raise HTTPException(401, "Contraseña incorrecta")
    r = JSONResponse({"ok": True})
    r.set_cookie("tok", sign(int(time.time()) + 7 * 86400), httponly=True, secure=os.environ.get("SECURE")=="1", samesite="strict", max_age=7 * 86400)
    return r

@app.get("/admin/api/config", dependencies=[Depends(auth)])
def cfg_get(): return get_cfg()

@app.put("/admin/api/config", dependencies=[Depends(auth)])
async def cfg_put(request: Request):
    b = await request.json(); c = get_cfg()
    for k in DEFAULT:
        if k in b: c[k] = b[k]
    with db() as d:
        d.execute("insert into kv(k,v) values('config',?) on conflict(k) do update set v=excluded.v", (json.dumps(c),))
    return c

@app.get("/admin/api/msgs", dependencies=[Depends(auth)])
def msgs_list():
    with db() as d: rows = d.execute("select id,data,active,created from msgs order by id desc").fetchall()
    return [{**json.loads(r["data"]), "id": r["id"], "active": bool(r["active"]), "created": r["created"], "code": full_code(r["code"])} for r in rows]

@app.post("/admin/api/msgs", dependencies=[Depends(auth)])
async def msgs_new(request: Request):
    b = await request.json(); act = 1 if b.pop("active", True) else 0; b.pop("id", None)
    with db() as d: cur = d.execute("insert into msgs(data,active,created) values(?,?,?)", (json.dumps(b), act, int(time.time())))
    return {"id": cur.lastrowid}

@app.put("/admin/api/msgs/{mid}", dependencies=[Depends(auth)])
async def msgs_upd(mid: int, request: Request):
    b = await request.json(); act = 1 if b.pop("active", True) else 0; b.pop("id", None); b.pop("created", None)
    with db() as d: d.execute("update msgs set data=?, active=? where id=?", (json.dumps(b), act, mid))
    return {"ok": True}

@app.delete("/admin/api/msgs/{mid}", dependencies=[Depends(auth)])
def msgs_del(mid: int):
    with db() as d: d.execute("delete from msgs where id=?", (mid,))
    return {"ok": True}

@app.get("/admin/api/devices", dependencies=[Depends(auth)])
def devices():
    now = int(time.time())
    with db() as d:
        rows = [dict(r) for r in d.execute("select * from devices order by last desc").fetchall()]
    vers = {}
    for r in rows: vers[str(r["version"])] = vers.get(str(r["version"]), 0) + 1
    return {"total": len(rows), "d1": sum(r["last"] > now - 86400 for r in rows), "d7": sum(r["last"] > now - 7 * 86400 for r in rows),
            "versions": vers, "list": rows[:200]}

# ---------- Feeds que se envian a la app ----------
def feed_out(r):
    return {"id": r["id"], "name": r["name"], "uri": r["uri"], "activate": bool(r["activate"]),
            "active": bool(r["active"]), "created": r["created"], "code": full_code(r["code"])}

@app.get("/admin/api/feeds", dependencies=[Depends(auth)])
def feeds_list():
    with db() as d:
        rows = d.execute("select * from feeds order by id").fetchall()
        n = d.execute("select count(*) c from devices where approved=1").fetchone()["c"]
    return {"mode": feeds_mode(), "approved": n, "feeds": [feed_out(r) for r in rows]}

@app.post("/admin/api/feeds", dependencies=[Depends(auth)])
async def feeds_new(request: Request):
    b = await request.json(); uri = clean_uri(b.get("uri"))
    with db() as d:
        cur = d.execute("insert into feeds(name,uri,activate,active,created,code) values(?,?,?,?,?,?)",
            (str(b.get("name", ""))[:60], uri, 1 if b.get("activate", True) else 0, 1 if b.get("active", True) else 0, int(time.time()), new_code(d)))
    return {"id": cur.lastrowid}

@app.put("/admin/api/feeds/{fid}", dependencies=[Depends(auth)])
async def feeds_upd(fid: int, request: Request):
    b = await request.json(); uri = clean_uri(b.get("uri"))
    with db() as d:
        d.execute("update feeds set name=?, uri=?, activate=?, active=? where id=?",
            (str(b.get("name", ""))[:60], uri, 1 if b.get("activate", True) else 0, 1 if b.get("active", True) else 0, fid))
    return {"ok": True}

@app.post("/admin/api/feeds/{fid}/code", dependencies=[Depends(auth)])
def feeds_newcode(fid: int):
    """Genera un codigo nuevo (el anterior deja de funcionar)."""
    with db() as d:
        c = new_code(d); d.execute("update feeds set code=? where id=?", (c, fid))
    return {"code": full_code(c)}

@app.delete("/admin/api/feeds/{fid}", dependencies=[Depends(auth)])
def feeds_del(fid: int):
    with db() as d: d.execute("delete from feeds where id=?", (fid,))
    return {"ok": True}

@app.put("/admin/api/feeds_mode", dependencies=[Depends(auth)])
async def feeds_mode_put(request: Request):
    b = await request.json()
    kv_set("feeds_mode", "all" if b.get("mode") == "all" else "approved")
    return {"mode": feeds_mode()}

@app.post("/admin/api/devices/approve_all", dependencies=[Depends(auth)])
def dev_approve_all():
    with db() as d: n = d.execute("update devices set approved=1 where approved=0").rowcount
    return {"ok": True, "approved": n}

@app.post("/admin/api/devices/{did}/approve", dependencies=[Depends(auth)])
async def dev_approve(did: str, request: Request):
    b = await request.json()
    with db() as d: d.execute("update devices set approved=? where id=?", (1 if b.get("approved", True) else 0, did[:64]))
    return {"ok": True}

# ---------- Chat (moderacion del chat global) ----------
def chat():
    if not os.path.exists(CHAT_DB): raise HTTPException(503, "No encuentro la base del chat (revisa el volumen en docker-compose.yml)")
    c = sqlite3.connect(CHAT_DB, timeout=10); c.row_factory = sqlite3.Row; return c

def has_soft(d):
    """El chat nuevo marca los borrados (deleted/rev) para que las apps los quiten sin recargar."""
    cols = {r[1] for r in d.execute("pragma table_info(msgs)")}
    return {"deleted", "rev", "pinned"} <= cols

@app.get("/admin/api/chat", dependencies=[Depends(auth)])
def chat_info():
    with closing(chat()) as d:
        soft = has_soft(d)
        users = d.execute("select count(*) c from users").fetchone()["c"]
        g = d.execute("select count(*) c from msgs where room='global'" + (" and deleted=0" if soft else "")).fetchone()["c"]
        p = d.execute("select count(*) c from msgs where room!='global'").fetchone()["c"]
        last = [dict(r) for r in d.execute("select id,sender,text,ts from msgs where room='global'" + (" and deleted=0" if soft else "") + " order by id desc limit 30")]
    return {"users": users, "global": g, "private": p, "last": last}

@app.delete("/admin/api/chat/msg/{mid}", dependencies=[Depends(auth)])
def chat_del(mid: int):
    with closing(chat()) as d:
        if has_soft(d):
            rev = d.execute("select coalesce(max(rev),0)+1 from msgs").fetchone()[0]
            d.execute("update msgs set text='', deleted=1, pinned=0, rev=? where id=? and room='global'", (rev, mid))
        else:
            d.execute("delete from msgs where id=? and room='global'", (mid,))
        d.commit()
    return {"ok": True}

@app.post("/admin/api/chat/clear", dependencies=[Depends(auth)])
def chat_clear():
    folder = os.path.dirname(CHAT_DB)
    bak = os.path.join(folder, "chat-respaldo-" + time.strftime("%Y%m%d_%H%M%S") + ".db")
    with closing(chat()) as src:
        with closing(sqlite3.connect(bak)) as dst: src.backup(dst)      # respaldo antes de borrar
        n = src.execute("delete from msgs where room='global'").rowcount
        src.commit()
    for f in sorted(glob.glob(os.path.join(folder, "chat-respaldo-*.db")))[:-5]:   # deja solo los 5 respaldos mas nuevos
        try: os.remove(f)
        except OSError: pass
    return {"ok": True, "deleted": n}

# ---------- Canales de Telegram que lee el servidor TheFeed ----------
# La carpeta /root/thefeed del VPS esta montada en /chat (ver docker-compose.yml del panel).
# La fuente real es THEFEED_CHANNELS en .env (deploy-server.sh regenera data/channels.txt desde ahi),
# asi que se escriben las dos cosas. Para reiniciar thefeed-server se deja un archivo bandera que
# vigila una unidad systemd del VPS (el panel no toca Docker).
ROOT = os.environ.get("THEFEED_DIR", "/chat")
ENV_FILE = os.path.join(ROOT, ".env")
CH_FILE = os.path.join(ROOT, "data", "channels.txt")
RESTART_FLAG = os.path.join(ROOT, ".restart-thefeed")
CH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{4,31}$")
MAX_CH = 50
ch_lock = threading.Lock()

def norm_channel(text):
    t = str(text or "").strip()
    m = re.search(r"(?:t\.me|telegram\.me)/(?:s/)?@?([A-Za-z0-9_]+)", t)   # acepta https://t.me/usuario
    if m: t = m.group(1)
    t = t.lstrip("@").strip()
    if not CH_RE.match(t):
        raise HTTPException(400, "Usa el @usuario de un canal público (5 a 32 letras, números o _)")
    return "@" + t

def read_channels():
    try:
        with open(ENV_FILE, encoding="utf-8") as f:
            for line in f:
                if line.startswith("THEFEED_CHANNELS="):
                    v = line.split("=", 1)[1].strip().strip('"').strip("'")
                    return ["@" + c.strip().lstrip("@") for c in v.split(",") if c.strip()]
    except OSError:
        pass
    out = []
    try:
        with open(CH_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("@"): out.append(line)
    except OSError:
        pass
    return out

def atomic_write(path, text, mode=None, owner=None):
    tmp = path + ".tmp-panel"
    old = os.stat(path) if os.path.exists(path) else None
    with open(tmp, "w", encoding="utf-8") as f: f.write(text)
    try:
        if old:
            os.chmod(tmp, old.st_mode & 0o777); os.chown(tmp, old.st_uid, old.st_gid)
        if mode is not None: os.chmod(tmp, mode)
        if owner: os.chown(tmp, *owner)
    except OSError:
        pass
    os.replace(tmp, path)

def write_channels(chans):
    try:
        with open(ENV_FILE, encoding="utf-8") as f: lines = f.read().splitlines()
    except OSError:
        raise HTTPException(503, "No puedo leer el .env del servidor (revisa el volumen en docker-compose.yml)")
    new = "THEFEED_CHANNELS=" + ",".join(chans)
    for i, l in enumerate(lines):
        if l.startswith("THEFEED_CHANNELS="): lines[i] = new; break
    else:
        lines.append(new)
    try:
        shutil.copy2(ENV_FILE, ENV_FILE + ".bak-panel")                      # respaldo del .env antes de tocarlo
        atomic_write(ENV_FILE, "\n".join(lines) + "\n")
        os.makedirs(os.path.dirname(CH_FILE), exist_ok=True)
        atomic_write(CH_FILE, "# generado por deploy-server.sh\n" + "\n".join(chans) + "\n", mode=0o644, owner=(1000, 1000))
        with open(RESTART_FLAG, "w") as f: f.write(str(int(time.time())))   # pide reiniciar thefeed-server
    except OSError as e:
        raise HTTPException(500, "No pude guardar: " + str(e))

def ch_out():
    chans = read_channels()
    try: age = max(1, int(time.time() - os.path.getmtime(RESTART_FLAG)))
    except OSError: age = 0
    return {"channels": [{"name": c, "n": i + 1, "locked": i == 0} for i, c in enumerate(chans)],
            "private": [{"id": inv_id(h), "mask": inv_mask(h), "n": len(chans) + i + 1} for i, h in enumerate(read_private())],
            "pending": age, "max": MAX_CH}

@app.get("/admin/api/channels", dependencies=[Depends(auth)])
def channels_list(): return ch_out()

@app.post("/admin/api/channels", dependencies=[Depends(auth)])
async def channels_add(request: Request):
    b = await request.json(); name = norm_channel(b.get("name"))
    with ch_lock:
        chans = read_channels()
        if any(c.lower() == name.lower() for c in chans): raise HTTPException(400, "Ese canal ya está en la lista")
        if len(chans) >= MAX_CH: raise HTTPException(400, "Máximo %d canales" % MAX_CH)
        chans.append(name)                    # siempre al final: el orden define el numero del canal en la app
        write_channels(chans)
    return {"ok": True, "name": name}

@app.delete("/admin/api/channels/{name}", dependencies=[Depends(auth)])
def channels_del(name: str):
    name = norm_channel(name)
    with ch_lock:
        chans = read_channels()
        i = next((k for k, c in enumerate(chans) if c.lower() == name.lower()), -1)
        if i < 0: raise HTTPException(404, "Ese canal no está en la lista")
        if i == 0: raise HTTPException(400, "El primer canal es el destino del servidor; no se puede quitar desde el panel")
        chans.pop(i); write_channels(chans)
    return {"ok": True}

# ---------- Canales privados (enlaces de invitacion) ----------
# El servidor lee data/private_channels.txt (un enlace por linea), se une al canal al arrancar con la
# sesion de Telegram y los numera despues de los publicos. Un enlace de invitacion es una llave de
# acceso: el panel nunca lo devuelve completo, solo una version tapada.
PRIV_FILE = os.path.join(ROOT, "data", "private_channels.txt")
INV_RE = re.compile(r"^[A-Za-z0-9_-]{10,40}$")
MAX_PRIV = 30

def invite_hash(text, strict=True):
    t = str(text or "").strip()
    m = (re.search(r"(?:t\.me|telegram\.me)/(?:\+|joinchat/)([A-Za-z0-9_-]+)", t)
         or re.search(r"invite=([A-Za-z0-9_-]+)", t))
    if m: h = m.group(1)
    elif t.startswith("+") or not strict: h = t.lstrip("+")
    else: h = ""
    return h if INV_RE.match(h) else None

def norm_invite(text):
    h = invite_hash(text)
    if not h: raise HTTPException(400, "Eso no parece un enlace de invitación privado (https://t.me/+...)")
    return h

def inv_id(h): return hashlib.sha256(h.encode()).hexdigest()[:10]
def inv_mask(h): return "t.me/+••••" + h[-3:]

def read_private():
    out = []
    try:
        with open(PRIV_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"): continue
                h = invite_hash(line, strict=False)
                if h and h not in out: out.append(h)
    except OSError:
        pass
    return out

def write_private(hashes):
    try:
        os.makedirs(os.path.dirname(PRIV_FILE), exist_ok=True)
        atomic_write(PRIV_FILE, "# gestionado desde el panel (un enlace de invitación por línea)\n"
                     + "".join("https://t.me/+" + h + "\n" for h in hashes), mode=0o600, owner=(1000, 1000))
        with open(RESTART_FLAG, "w") as f: f.write(str(int(time.time())))
    except OSError as e:
        raise HTTPException(500, "No pude guardar: " + str(e))

@app.post("/admin/api/private", dependencies=[Depends(auth)])
async def private_add(request: Request):
    b = await request.json(); h = norm_invite(b.get("link"))
    with ch_lock:
        hs = read_private()
        if h in hs: raise HTTPException(400, "Ese canal privado ya está en la lista")
        if len(hs) >= MAX_PRIV: raise HTTPException(400, "Máximo %d canales privados" % MAX_PRIV)
        hs.append(h); write_private(hs)
    return {"ok": True, "mask": inv_mask(h)}

@app.delete("/admin/api/private/{pid}", dependencies=[Depends(auth)])
def private_del(pid: str):
    with ch_lock:
        hs = read_private()
        keep = [h for h in hs if inv_id(h) != pid]
        if len(keep) == len(hs): raise HTTPException(404, "Ese canal privado no está en la lista")
        write_private(keep)
    return {"ok": True}

@app.get("/")
def index(): return FileResponse(os.path.join(os.path.dirname(__file__), "static", "index.html"))

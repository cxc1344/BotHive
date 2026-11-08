#!/usr/bin/env python3
"""Python mirror of the algorithm in TarExtract.kt, exercised against archives produced by Python's tarfile
(ustar / GNU / pax formats). Validates header offsets, number parsing, long names, link handling and the
guest-side symlink resolution that fixes the 'rootfs incomplete' bug. It validates the ALGORITHM, not the Kotlin."""
import io, os, sys, tarfile, tempfile, shutil, gzip

BLOCK = 512

class TarException(Exception): pass

def c_string(b, off, ln):
    e = off
    while e < off + ln and b[e] != 0: e += 1
    return b[off:e].decode("utf-8")

def parse_number(b, off, ln):
    if b[off] & 0x80:
        v = b[off] & 0x7F
        for i in range(1, ln): v = (v << 8) | b[off + i]
        return v
    v = 0; seen = False
    for i in range(off, off + ln):
        c = b[i]
        if c == 0 or c == 32:
            if seen: break
            continue
        if c < 48 or c > 55: raise TarException("bad number")
        v = (v << 3) | (c - 48); seen = True
    return v

def read_block(f):
    d = b""
    while len(d) < BLOCK:
        r = f.read(BLOCK - len(d))
        if not r: break
        d += r
    return d

def padding(size): return (BLOCK - size % BLOCK) % BLOCK

def skip(f, n):
    while n > 0:
        r = f.read(min(8192, n))
        if not r: raise TarException("truncated")
        n -= len(r)

def read_text(f, size):
    d = b""
    while len(d) < size:
        r = f.read(size - len(d))
        if not r: raise TarException("meta truncated")
        d += r
    skip(f, padding(size))
    return d.decode("utf-8").rstrip("\0")

def parse_pax(t):
    m = {}
    for line in t.split("\n"):
        sp = line.find(" ")
        if sp < 0: continue
        kv = line[sp + 1:]; eq = kv.find("=")
        if eq < 0: continue
        m[kv[:eq]] = kv[eq + 1:]
    return m

def clean_name(n):
    while n.startswith("./"): n = n[2:]
    n = n.rstrip("/")
    if n == "" or n == ".": return None
    if n.startswith("/"): raise TarException("absolute")
    if ".." in n.split("/"): raise TarException("dotdot")
    return n

def inside(root, p): return p == root or p.startswith(root + os.sep)

def prepare(root, rel):
    t = os.path.join(root, rel); parent = os.path.dirname(t)
    if not os.path.isdir(parent):
        try: os.makedirs(parent)
        except OSError: return None
    return t if inside(root, os.path.realpath(parent)) else None

def extract(f, dest):
    os.makedirs(dest, exist_ok=True)
    root = os.path.realpath(dest)
    long_name = long_link = pax_path = pax_link = None
    res = dict(files=0, dirs=0, syms=0, hards=0, skipped=[])
    index = 0
    while True:
        h = read_block(f)
        if len(h) == 0: break
        if len(h) < BLOCK: raise TarException("header truncated")
        if h == b"\0" * BLOCK: break
        index += 1
        typ = chr(h[156]); size = parse_number(h, 124, 12)
        raw_name = c_string(h, 0, 100)
        prefix = c_string(h, 345, 155) if (h[257:262] == b"ustar" and h[262] == 0) else ""
        raw_link = c_string(h, 157, 100); mode = parse_number(h, 100, 8)
        if typ == "L": long_name = read_text(f, size); continue
        if typ == "K": long_link = read_text(f, size); continue
        if typ == "x":
            m = parse_pax(read_text(f, size)); pax_path = m.get("path", pax_path); pax_link = m.get("linkpath", pax_link); continue
        if typ == "g": skip(f, size + padding(size)); continue
        name0 = pax_path or long_name or (prefix + "/" + raw_name if prefix else raw_name)
        link0 = pax_link or long_link or raw_link
        pax_path = pax_link = long_name = long_link = None
        rel = clean_name(name0)
        if typ == "5":
            if rel is not None:
                t = prepare(root, rel)
                if t is None: res["skipped"].append(rel)
                else: os.makedirs(t, exist_ok=True); res["dirs"] += 1
        elif typ in ("0", "\0", "7"):
            if rel is None: raise TarException("empty file name")
            t = prepare(root, rel)
            if t is None: res["skipped"].append(rel); skip(f, size + padding(size))
            else:
                if os.path.lexists(t): os.remove(t)
                left = size
                with open(t, "wb") as o:
                    while left > 0:
                        r = f.read(min(16384, left))
                        if not r: raise TarException("file data truncated")
                        o.write(r); left -= len(r)
                skip(f, padding(size))
                if mode & 0o111: os.chmod(t, 0o755)
                res["files"] += 1
        elif typ == "2":
            t = prepare(root, rel)
            if t is None: res["skipped"].append(rel)
            else:
                if os.path.lexists(t): os.remove(t)
                os.symlink(link0, t); res["syms"] += 1
        elif typ == "1":
            src_rel = clean_name(link0); t = prepare(root, rel); src = os.path.join(root, src_rel)
            if t is None or not os.path.isfile(src) or not inside(root, os.path.realpath(src)): res["skipped"].append(rel)
            else:
                if os.path.lexists(t): os.remove(t)
                shutil.copyfile(src, t); res["hards"] += 1
        elif typ in ("3", "4", "6"): res["skipped"].append(name0)
        else: skip(f, size + padding(size)); res["skipped"].append(name0)
    if index == 0: raise TarException("empty archive")
    return res

def resolve_in_guest(root, rel):
    root = os.path.realpath(root)
    parts = [p for p in rel.split("/") if p]; cur = root; hops = 0
    while parts:
        p = parts.pop(0)
        if p == ".": continue
        if p == "..":
            if cur != root: cur = os.path.dirname(cur)
            continue
        nxt = os.path.join(cur, p)
        if os.path.islink(nxt):
            hops += 1
            if hops > 16: return None
            target = os.readlink(nxt); tparts = [x for x in target.split("/") if x]
            if target.startswith("/"): cur = root
            parts = tparts + parts
        elif os.path.lexists(nxt): cur = nxt
        else: return None
    return cur

def build(fmt, entries, gz=False):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=fmt) as tf:
        for e in entries:
            ti = tarfile.TarInfo(e["name"]); ti.type = e.get("type", tarfile.REGTYPE); ti.mode = e.get("mode", 0o644)
            data = e.get("data")
            if "link" in e: ti.linkname = e["link"]
            if data is not None: ti.size = len(data)
            tf.addfile(ti, io.BytesIO(data) if data is not None else None)
    raw = buf.getvalue()
    return gzip.compress(raw) if gz else raw

fails = 0
def check(label, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + label)
    if not cond: fails += 1

long_dir = "d/" + "x" * 130
alpine_like = [
    dict(name="./", type=tarfile.DIRTYPE, mode=0o755),
    dict(name="./bin", type=tarfile.DIRTYPE, mode=0o755),
    dict(name="./bin/busybox", data=b"BUSYBOX" * 100, mode=0o755),
    dict(name="./bin/sh", type=tarfile.SYMTYPE, link="/bin/busybox"),          # absolute symlink: the bug
    dict(name="./bin/ls", type=tarfile.LNKTYPE, link="./bin/busybox", mode=0o755),
    dict(name="./etc", type=tarfile.DIRTYPE, mode=0o755),
    dict(name="./etc/alpine-release", data=b"3.24.4\n"),
    dict(name="./lib", type=tarfile.DIRTYPE, mode=0o755),
    dict(name="./lib/ld-musl-aarch64.so.1", data=b"LD" * 50, mode=0o755),
    dict(name="./dev/null", type=tarfile.CHRTYPE),
    dict(name="./" + long_dir + "/file.txt", data=b"long"),
    dict(name="./usr/bin/env", type=tarfile.SYMTYPE, link="../../bin/busybox"),
    dict(name="./usr", type=tarfile.DIRTYPE),
]
alpine_like.sort(key=lambda e: e["name"].count("/"))   # parents first is not required (prepare() mkdirs) but keep it tidy

for fmt_name, fmt in (("USTAR", tarfile.USTAR_FORMAT), ("GNU", tarfile.GNU_FORMAT), ("PAX", tarfile.PAX_FORMAT)):
    d = tempfile.mkdtemp()
    try:
        data = build(fmt, alpine_like, gz=True)
        res = extract(gzip.GzipFile(fileobj=io.BytesIO(data)), os.path.join(d, "root"))
    except TarException as e:
        if fmt_name == "USTAR" and "dotdot" not in str(e):
            # USTAR cannot hold >255-char paths; tarfile raises on build for that, extractor never reached
            pass
        check(f"{fmt_name}: extract raised {e}", False); shutil.rmtree(d); continue
    except ValueError as e:
        print(f"SKIP {fmt_name}: tarfile cannot build this format with long name ({e})"); shutil.rmtree(d); continue
    root = os.path.join(d, "root")
    sh = os.path.join(root, "bin/sh")
    check(f"{fmt_name}: bin/sh is a symlink", os.path.islink(sh))
    check(f"{fmt_name}: HOST exists('bin/sh') is False (this is why the old check failed)", not os.path.exists(sh))
    r = resolve_in_guest(root, "bin/sh")
    check(f"{fmt_name}: guest resolution reaches bin/busybox", r is not None and r == os.path.realpath(os.path.join(root, "bin/busybox")))
    check(f"{fmt_name}: busybox content+exec bit", open(os.path.join(root, "bin/busybox"), "rb").read() == b"BUSYBOX" * 100 and os.access(os.path.join(root, "bin/busybox"), os.X_OK))
    check(f"{fmt_name}: hardlink copied", os.path.isfile(os.path.join(root, "bin/ls")))
    check(f"{fmt_name}: relative symlink resolves in guest", resolve_in_guest(root, "usr/bin/env") == os.path.realpath(os.path.join(root, "bin/busybox")))
    check(f"{fmt_name}: long path file", open(os.path.join(root, long_dir, "file.txt"), "rb").read() == b"long")
    check(f"{fmt_name}: device node skipped, counted", any("dev/null" in s for s in res["skipped"]))
    check(f"{fmt_name}: alpine-release", open(os.path.join(root, "etc/alpine-release")).read() == "3.24.4\n")
    shutil.rmtree(d)

# security / robustness cases
d = tempfile.mkdtemp()
try:
    extract(io.BytesIO(build(tarfile.GNU_FORMAT, [dict(name="../evil", data=b"x")])), os.path.join(d, "r")); check("traversal rejected", False)
except TarException: check("traversal rejected", True)
try:
    extract(io.BytesIO(build(tarfile.GNU_FORMAT, [dict(name="/abs", data=b"x")])), os.path.join(d, "r2")); check("absolute path rejected", False)
except TarException: check("absolute path rejected", True)
escape_target = tempfile.mkdtemp()
data = build(tarfile.GNU_FORMAT, [dict(name="evil", type=tarfile.SYMTYPE, link=escape_target), dict(name="evil/pwned.txt", data=b"x")])
res = extract(io.BytesIO(data), os.path.join(d, "r3"))
check("symlink-escape write skipped", not os.path.exists(os.path.join(escape_target, "pwned.txt")) and len(res["skipped"]) == 1)
full = build(tarfile.GNU_FORMAT, [dict(name="a.bin", data=b"y" * 5000)])
try:
    extract(io.BytesIO(full[:BLOCK + 700]), os.path.join(d, "r4")); check("truncated data detected", False)
except TarException: check("truncated data detected", True)
try:
    extract(io.BytesIO(b""), os.path.join(d, "r5")); check("empty archive detected", False)
except TarException: check("empty archive detected", True)
# dangling / looping symlink resolution
os.makedirs(os.path.join(d, "g/bin")); os.symlink("/bin/nothing", os.path.join(d, "g/bin/sh"))
check("dangling link -> None", resolve_in_guest(os.path.join(d, "g"), "bin/sh") is None)
os.symlink("b", os.path.join(d, "g/bin/a")); os.symlink("a", os.path.join(d, "g/bin/b"))
check("symlink loop -> None", resolve_in_guest(os.path.join(d, "g"), "bin/a") is None)
shutil.rmtree(d); shutil.rmtree(escape_target)
print("TOTAL FAILS:", fails)
sys.exit(1 if fails else 0)

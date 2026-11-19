#!/usr/bin/env python3
"""Black-box probe of Sestacon's HTTP API (default http://localhost:18080). Python 3.6 compatible.

Each case states the DESIRED behaviour; a miss is a GAP. Run: python3 e2e/sestacon_probe.py [--only SA02,SA06]
"""
import argparse
import json
import sys
import time
import uuid
import urllib.error
import urllib.request

BASE = "http://localhost:18080"
CASES = []


def http(method, path, body=None, headers=None, timeout=30):
    req = urllib.request.Request(BASE + path, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, "%s: %s" % (type(e).__name__, e)


def ingest(name, content, ctype="text/markdown", acl=None, source=None):
    b = uuid.uuid4().hex
    parts = []
    fields = [("aclTags", t) for t in (acl or [])] + ([("sourceSystem", source)] if source else [])
    for k, v in fields:
        parts.append(('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (b, k, v)).encode())
    data = content if isinstance(content, bytes) else content.encode("utf-8")
    parts.append(('--%s\r\nContent-Disposition: form-data; name="files"; filename="%s"\r\n'
                  "Content-Type: %s\r\n\r\n" % (b, name, ctype)).encode() + data + b"\r\n")
    parts.append(("--%s--\r\n" % b).encode())
    return http("POST", "/api/ingest", b"".join(parts),
                {"Content-Type": "multipart/form-data; boundary=" + b})


def retrieve(query, roles=None, top_k=5, metadata=None):
    h = {"Content-Type": "application/json"}
    if roles:
        h["X-Roles"] = roles
    payload = {"query": query, "topK": top_k}
    if metadata:
        payload["metadata"] = metadata
    code, body = http("POST", "/api/retrieve", json.dumps(payload).encode(), h)
    try:
        return code, json.loads(body)
    except ValueError:
        return code, body


def case(cid, sev, title):
    def deco(fn):
        CASES.append((cid, sev, title, fn))
        return fn
    return deco


def uniq(prefix):
    return "%s%s" % (prefix, uuid.uuid4().hex[:8])


def has(hits, word):
    return isinstance(hits, list) and any(word in json.dumps(h, ensure_ascii=False) for h in hits)


@case("SA01", "H", "service is healthy")
def sa01():
    code, body = http("GET", "/actuator/health")
    return code == 200, "health=%s %s" % (code, body[:80])


@case("SA02", "H", "ingested doc is retrievable with its source")
def sa02():
    word = uniq("zephyrquartz")
    c, _ = ingest("probe-%s.md" % word, "# Notes\nThe %s protocol rotates keys nightly.\n" % word)
    code, hits = retrieve("%s protocol keys" % word)
    return c == 200 and code == 200 and has(hits, word), "ingest=%s retrieve=%s" % (c, code)


@case("SA03", "M", "query matching nothing returns a list, not an error")
def sa03():
    code, hits = retrieve(uniq("qqzzxx"))
    return code == 200 and isinstance(hits, list), "code=%s type=%s" % (code, type(hits).__name__)


@case("SA04", "M", "empty file is rejected with 4xx")
def sa04():
    code, body = ingest("empty.md", "")
    return 400 <= code < 500, "code=%s %s" % (code, body[:80])


@case("SA05", "M", "unsupported content type is rejected with 415")
def sa05():
    code, body = ingest("x.exe", b"MZ\x90\x00", "application/x-msdownload")
    return code == 415, "code=%s %s" % (code, body[:80])


@case("SA06", "H", "ACL-tagged doc is hidden without the role and visible with it")
def sa06():
    word = uniq("acltoken")
    ingest("acl-%s.md" % word, "Restricted: the %s vault code is rotated weekly.\n" % word, acl=["probe-role"])
    _, anon = retrieve("%s vault" % word)
    _, withrole = retrieve("%s vault" % word, roles="probe-role")
    leaked, seen = has(anon, word), has(withrole, word)
    return (not leaked) and seen, "leakedToAnon=%s visibleToRole=%s" % (leaked, seen)


@case("SA07", "M", "blank query is a 4xx, not a 5xx")
def sa07():
    code, body = http("POST", "/api/retrieve", json.dumps({"query": "  "}).encode(),
                      {"Content-Type": "application/json"})
    return 400 <= code < 500, "code=%s %s" % (code, str(body)[:80])


@case("SA08", "M", "re-ingesting the same file does not duplicate hits")
def sa08():
    word = uniq("dupcheck")
    name, text = "dup-%s.md" % word, "The %s subsystem handles retries.\n" % word
    ingest(name, text)
    ingest(name, text)
    _, hits = retrieve("%s subsystem retries" % word, top_k=10)
    n = sum(1 for h in hits if word in json.dumps(h)) if isinstance(hits, list) else -1
    return n == 1, "matching hits=%s (want 1)" % n


@case("SA09", "L", "sourceSystem metadata filter narrows results")
def sa09():
    word = uniq("srcfilter")
    ingest("a-%s.md" % word, "Shared topic %s from jira.\n" % word, source="jira")
    ingest("b-%s.md" % word, "Shared topic %s from wiki.\n" % word, source="wiki")
    _, hits = retrieve("shared topic %s" % word, top_k=10, metadata={"sourceSystem": "jira"})
    ok = isinstance(hits, list) and bool(hits) and all("wiki" not in json.dumps(h) for h in hits)
    return ok, "hits=%s" % (len(hits) if isinstance(hits, list) else hits)


@case("SA10", "M", "topK is honoured and bad topK is a 4xx or clamped, never 5xx")
def sa10():
    code1, hits = retrieve("notes", top_k=1)
    code0, _ = retrieve("notes", top_k=0)
    codeneg, _ = retrieve("notes", top_k=-5)
    ok = code1 == 200 and isinstance(hits, list) and len(hits) <= 1 and code0 < 500 and codeneg < 500
    return ok, "k=1->%s k=0->%s k=-5->%s" % (code1, code0, codeneg)


@case("SA11", "M", "unicode content is retrievable")
def sa11():
    word = uniq("unicodeword")
    ingest("uni-%s.md" % word, "Документация: сервис %s обрабатывает платежи 日本語。\n" % word)
    _, hits = retrieve("сервис %s платежи" % word)
    return has(hits, word), "hits=%s" % (len(hits) if isinstance(hits, list) else hits)


@case("SA12", "M", "chat without an LLM key fails fast with a clear message")
def sa12():
    t = time.time()
    code, body = http("POST", "/api/chat", json.dumps({"question": "what is in my notes?"}).encode(),
                      {"Content-Type": "application/json"}, timeout=25)
    took = time.time() - t
    return code != -1 and took < 20 and code < 600, "code=%s took=%.1fs %s" % (code, took, str(body)[:80])


@case("SA13", "L", "delete removes a document from retrieval")
def sa13():
    word = uniq("deltoken")
    name = "del-%s.md" % word
    ingest(name, "Temporary note about %s.\n" % word)
    code, _ = http("DELETE", "/api/documents/%s" % name)
    _, hits = retrieve("temporary note %s" % word)
    gone = isinstance(hits, list) and not has(hits, word)
    return code in (200, 204) and gone, "delete=%s gone=%s" % (code, gone)


PNG_1x1 = __import__("base64").b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def ingest_json(name, content, ctype):
    code, body = ingest(name, content, ctype=ctype)
    try:
        return code, json.loads(body)
    except ValueError:
        return code, body


def change_status(resp):
    try:
        return resp["results"][0]["change"]["status"]
    except (KeyError, IndexError, TypeError):
        return None


@case("SA14", "M", "a widened format (HTML) is accepted and retrievable")
def sa14():
    word = uniq("htmlword")
    c, _ = ingest("page-%s.html" % word, "<html><body><h1>%s</h1><p>pipelines run nightly</p></body></html>" % word,
                  ctype="text/html")
    code, hits = retrieve("%s pipelines nightly" % word)
    return c == 200 and code == 200 and has(hits, word), "ingest=%s retrieve=%s" % (c, code)


@case("SA15", "M", "image ingestion is disabled (415) — OCR code retained, not allow-listed")
def sa15():
    word = uniq("imgword")
    c, body = ingest_json("diagram-%s.png" % word, PNG_1x1, "image/png")
    return c == 415, "code=%s %s" % (c, str(body)[:80])


@case("SA16", "H", "ingest reports version change: NEW then UNCHANGED on identical re-ingest")
def sa16():
    word = uniq("verword")
    name, text = "ver-%s.md" % word, "# Spec\nThe %s module caps retries at three.\n" % word
    c1, r1 = ingest_json(name, text, "text/markdown")
    c2, r2 = ingest_json(name, text, "text/markdown")
    s1, s2 = change_status(r1), change_status(r2)
    return c1 == 200 and c2 == 200 and s1 == "NEW" and s2 == "UNCHANGED", "first=%s second=%s" % (s1, s2)


@case("SA17", "H", "changed content reports a non-UNCHANGED change and a new version hash")
def sa17():
    word = uniq("chgword")
    name = "chg-%s.md" % word
    ingest_json(name, "The %s limit is 100.\n" % word, "text/markdown")
    vcode1, v1 = http("GET", "/api/documents/%s/version" % name)
    _, r2 = ingest_json(name, "The %s limit is now 250 after tuning.\n" % word, "text/markdown")
    vcode2, v2 = http("GET", "/api/documents/%s/version" % name)
    st = change_status(r2)
    try:
        h1 = json.loads(v1)["docHash"]
        h2 = json.loads(v2)["docHash"]
    except (ValueError, KeyError):
        h1 = h2 = None
    ok = vcode1 == 200 and vcode2 == 200 and st in ("PARTIAL", "FULL") and h1 and h2 and h1 != h2
    return ok, "status=%s hashChanged=%s" % (st, h1 != h2)


@case("SA18", "M", "version of an unknown document is 404")
def sa18():
    code, _ = http("GET", "/api/documents/%s/version" % uniq("nosuchdoc"))
    return code == 404, "code=%s" % code


@case("SA19", "H", "chunk-ingest (/api/documents) reports a version change like the file path")
def sa19():
    word = uniq("chunkver")
    doc = "chunkdoc-%s" % word
    body = json.dumps([{"documentId": doc, "sectionPath": "s1",
                        "content": "The %s rule caps size at ten." % word}]).encode()
    h = {"Content-Type": "application/json"}
    c1, b1 = http("POST", "/api/documents", body, h)
    c2, b2 = http("POST", "/api/documents", body, h)

    def status(b):
        try:
            return json.loads(b)["changes"][0]["status"]
        except (ValueError, KeyError, IndexError):
            return None

    s1, s2 = status(b1), status(b2)
    return c1 == 200 and c2 == 200 and s1 == "NEW" and s2 == "UNCHANGED", "first=%s second=%s" % (s1, s2)


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()
    BASE = a.base
    only = [x for x in a.only.split(",") if x]
    run = [c for c in CASES if not only or any(c[0].startswith(o) for o in only)]
    bad = 0
    for cid, sev, title, fn in run:
        try:
            ok, ev = fn()
            st = "PASS " if ok else "GAP  "
        except Exception as e:  # noqa: BLE001
            ok, ev, st = False, "%s: %s" % (type(e).__name__, e), "ERROR"
        bad += 0 if ok else 1
        print("[%s] %s (%s) %s\n         %s" % (st, cid, sev, title, ev))
    print("\nSUMMARY: %d case(s), %d not passing" % (len(run), bad))
    return 1 if (a.strict and bad) else 0


if __name__ == "__main__":
    sys.exit(main())

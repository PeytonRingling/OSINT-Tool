#!/usr/bin/env python3
"""Site health check: for every site with a known-existing handle, confirm the detection rule still reports it as found, and that a random
handle is reported absent. Prints a report and exits 1 on any drift. Run from the repo root (weekly via .github/workflows/site-health.yml)."""
import json, sys, random, string, re, concurrent.futures as cf, urllib.request, urllib.error, urllib.parse
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
MARKERS = ["just a moment", "client challenge", "attention required", "checking your browser", "cf-browser-verification", "enable javascript and cookies"]
known_extra = json.load(open("tools/known_accounts.json"))
sites = []
for f in ("app/src/main/assets/sites.json", "app/src/main/assets/wmn_sites.json"):
    for s in json.load(open(f))["sites"]:
        k = s.get("known") or known_extra.get(s["name"])
        if k: sites.append((s, k))

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, r.read(200000).decode("utf-8", "replace"), r.geturl()
    except urllib.error.HTTPError as e: return e.code, "", ""
    except Exception: return None, "", ""

def state(site, name):
    code, body, final = fetch(site["url"].replace("{u}", urllib.parse.quote(name, safe="")))
    if code is None: return "err"
    low = body.lower()
    if body and any(m in low for m in MARKERS) and len(body) < 20000: return "err"
    if code in set([404, 410] + site.get("absentCodes", [])): return "absent"
    if code in (401, 403, 406, 429) or not 200 <= code < 300: return "err"
    if site.get("foundMsg"): return "found" if site["foundMsg"].replace("{u}", name).lower() in low else "absent"
    if site.get("method") == "message": return "absent" if site.get("errorMsg") and site["errorMsg"].lower() in low else "found"
    if site.get("method") == "redirect": return "absent" if site.get("errorUrl") and site["errorUrl"] in final else "found"
    return "found"

def check(item):
    site, known = item
    bogus = "zq" + "".join(random.choices(string.ascii_lowercase + string.digits, k=9))
    a, b = state(site, known), state(site, bogus)
    return site["name"], a, b

with cf.ThreadPoolExecutor(16) as ex: results = list(ex.map(check, sites))
drift = [(n, a, b) for n, a, b in results if "err" not in (a, b) and (a != "found" or b != "absent")]
blocked = [n for n, a, b in results if "err" in (a, b)]
print(f"{len(results)} sites checked · {len(drift)} drifted · {len(blocked)} inconclusive (blocked/unreachable)")
for n, a, b in drift: print(f"DRIFT {n}: known account -> {a}, random handle -> {b}")
sys.exit(1 if drift else 0)

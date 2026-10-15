#!/usr/bin/env python3
"""Converts WhatsMyName (CC BY-SA 4.0, github.com/WebBreacher/WhatsMyName) into Recon's site format and keeps only sites that
calibrate: the 'known' account must be detected and a random bogus handle must not. Usage: wmn_import.py wmn-data.json out.json"""
import json, sys, re, random, string, concurrent.futures as cf, urllib.request, urllib.error, urllib.parse, ssl
from urllib.parse import urlparse
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
MARKERS = ["just a moment", "client challenge", "attention required", "checking your browser", "cf-browser-verification", "enable javascript and cookies"]
src = json.load(open(sys.argv[1]))
own = {urlparse(s["url"]).hostname for s in json.load(open("app/src/main/assets/sites.json"))["sites"]}

def convert(s):
    if "NSFW" in s["cat"] or "{account}" not in s["uri_check"] or s.get("uri_check", "").startswith("http://"): return None
    if urlparse(s["uri_check"]).hostname in own: return None
    if s.get("e_code") != 200 or not s.get("e_string"): return None
    site = {"name": s["name"], "url": s["uri_check"].replace("{account}", "{u}"), "method": "message", "confidence": "MEDIUM",
            "foundMsg": s["e_string"], "category": s["cat"]}
    if s.get("uri_pretty"): site["profileUrl"] = s["uri_pretty"].replace("{account}", "{u}")
    if s.get("m_code") in (400, 410, 500): site["absentCodes"] = [s["m_code"]]
    return site, s.get("known") or []

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return r.status, r.read(200000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, ""
    except Exception: return None, ""

def state(site, name):
    code, body = fetch(site["url"].replace("{u}", urllib.parse.quote(name, safe="")))
    if code is None: return "err"
    low = body.lower()
    if body and any(m in low for m in MARKERS) and len(body) < 20000: return "err"
    if code in (404, 410) or code in site.get("absentCodes", []): return "absent"
    if not 200 <= code < 300: return "err"
    return "found" if site["foundMsg"].replace("{u}", name).lower() in low else "absent"

def calibrate(item):
    site, known = item
    bogus = "zq" + "".join(random.choices(string.ascii_lowercase + string.digits, k=9))
    for k in known[:2]:
        if " " in k: continue
        if state(site, k) == "found" and state(site, bogus) == "absent": return site
    return None

conv = [c for c in map(convert, src["sites"]) if c]
print(len(conv), "convertible", file=sys.stderr)
with cf.ThreadPoolExecutor(24) as ex: ok = [s for s in ex.map(calibrate, conv) if s]
print(len(ok), "calibrated", file=sys.stderr)
json.dump({"version": 1, "note": "Derived from WhatsMyName (c) Micah Hoffman et al., CC BY-SA 4.0, https://github.com/WebBreacher/WhatsMyName. Kept only sites whose known account detected and a bogus handle did not, on " + __import__("datetime").date.today().isoformat() + ".",
           "sites": sorted(ok, key=lambda s: s["name"].lower())}, open(sys.argv[2], "w"), indent=1, ensure_ascii=False)

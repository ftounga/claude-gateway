import os, sys, json, time, subprocess, base64, urllib.request, statistics
key = base64.b64decode(subprocess.check_output(["kubectl","-n","claude-gateway-staging","get","secret","backend-secrets","-o","jsonpath={.data.APP_ELEVENLABS_API_KEY}"])).decode().strip()
VOICE="onwK4e9ZLuTAKqWW03F9"; MODEL="eleven_multilingual_v2"; BASE="https://api.elevenlabs.io"
def req(path, body=None, method="GET"):
    r = urllib.request.Request(BASE+path, data=(json.dumps(body).encode() if body else None), method=method,
        headers={"xi-api-key":key,"Content-Type":"application/json"})
    return urllib.request.urlopen(r, timeout=60)
def usage():
    now=int(time.time()*1000)
    d=json.load(req(f"/v1/usage/character-stats?start_unix={now-86400000*2}&end_unix={now}"))
    return sum(d["usage"]["All"])
phrases = [l.strip() for l in open(sys.argv[1]) if l.strip()]
out = {"stream":[], "ts":[]}
for mode in ["stream","ts"]:
    u0 = usage()
    for i,p in enumerate(phrases):
        path = f"/v1/text-to-speech/{VOICE}/stream" + ("/with-timestamps" if mode=="ts" else "") + "?output_format=mp3_44100_128"
        t0=time.perf_counter()
        resp = req(path, {"text":p,"model_id":MODEL,"language_code":"fr"}, "POST")
        first = resp.read(1); tfb=(time.perf_counter()-t0)*1000
        rest = resp.read(); data = first+rest
        hdr = {k.lower():v for k,v in resp.headers.items()}
        cost = hdr.get("character-cost") or hdr.get("x-character-count")
        out[mode].append({"i":i,"len":len(p),"ttfb_ms":tfb,"bytes":len(data),"hdr_cost":cost})
        if mode=="ts" and i<3:
            open(f"raw_ts_{i}.bin","wb").write(data)
        if mode=="stream" and i==0: print("headers:", {k:v for k,v in hdr.items() if 'char' in k or 'cost' in k})
    time.sleep(60)
    u1 = usage()
    out[mode+"_usage_delta"] = u1-u0
json.dump(out, open("p0_result.json","w"), indent=1)
for mode in ["stream","ts"]:
    t=[r["ttfb_ms"] for r in out[mode]]; t.sort()
    print(mode, "usage_delta", out[mode+"_usage_delta"], "sum_len", sum(r["len"] for r in out[mode]),
          "hdr_cost_sum", sum(int(r["hdr_cost"] or 0) for r in out[mode]),
          "ttfb p50 %.0f p95 %.0f"%(statistics.median(t), t[int(len(t)*0.95)-1]), "bytes", sum(r["bytes"] for r in out[mode]))

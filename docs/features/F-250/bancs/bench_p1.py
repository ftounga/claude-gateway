import json,subprocess,base64,urllib.request,time,statistics,sys
key = base64.b64decode(subprocess.check_output(["kubectl","-n","claude-gateway-staging","get","secret","backend-secrets","-o","jsonpath={.data.APP_ELEVENLABS_API_KEY}"])).decode().strip()
model=sys.argv[2]
ph=[l.strip() for l in open(sys.argv[1]) if l.strip()]
res={"mp3":[],"ts":[]}
for i,p in enumerate(ph):
    for mode in (["mp3","ts"] if i%2==0 else ["ts","mp3"]):
        path="/stream"+("/with-timestamps" if mode=="ts" else "")
        t0=time.perf_counter()
        r=urllib.request.urlopen(urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/onwK4e9ZLuTAKqWW03F9{path}?output_format=mp3_44100_128",data=json.dumps({"text":p,"model_id":model,"language_code":"fr"}).encode(),headers={"xi-api-key":key,"Content-Type":"application/json"},method="POST"))
        if mode=="mp3":
            r.read(1)
        else:
            buf=b""
            while b"\n" not in buf:
                c=r.read1(4096)
                if not c: break
                buf+=c
        res[mode].append((time.perf_counter()-t0)*1000); r.read()
for m,v in res.items():
    s=sorted(v); print(model,m,"p50 %.0f p90 %.0f p95 %.0f"%(statistics.median(s),s[int(.9*len(s))-1],s[int(.95*len(s))-1]))
d=sorted(b-a for a,b in zip(res["mp3"],res["ts"])); print("diff paired p50 %.0f p95 %.0f"%(statistics.median(d), d[int(.95*len(d))-1]))
json.dump(res,open(f"p1_{model}.json","w"))

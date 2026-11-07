# Banc P2 (F-250 / SF-250-06) — à lancer dans le dossier qui contient ph/p<k>.{mp3,json,csv,events.json} produits par sim.mjs.
import json, numpy as np
lag_close=[]; lag_open=[]
for k in range(6):
    d=np.genfromtxt(f"ph/p{k}.csv",delimiter=",",names=True)
    ev=json.load(open(f"ph/p{k}.events.json"))
    for n,e in enumerate(ev):
        cc=(e["t0"]+e["t1"])/2
        if e["v"]=="PBM":
            sl=(d["t"]>cc-0.1)&(d["t"]<cc+0.1)
            lag_close.append((d["t"][sl][np.argmax(d["press"][sl])]-cc)*1000)
        if e["v"]=="A" and (e["t1"]-e["t0"])>0.06:
            sl=(d["t"]>cc-0.1)&(d["t"]<cc+0.1)
            lag_open.append((d["t"][sl][np.argmax(d["open"][sl])]-cc)*1000)
for name,l in [("fermetures M B P",lag_close),("ouvertures A",lag_open)]:
    l=np.array(l); print("%s : n=%d médiane %+.1f ms, p90 retard %.1f ms, p90 |écart| %.1f ms"%(name,len(l),np.median(l),np.percentile(np.maximum(l,0),90),np.percentile(np.abs(l),90)))

# Banc P2 (F-250 / SF-250-06) — à lancer dans le dossier qui contient ph/p<k>.{mp3,json,csv,events.json} produits par sim.mjs.
import json, numpy as np
on=[]; off=[]
for k in range(6):
    d=np.genfromtxt(f"ph/p{k}.csv",delimiter=",",names=True)
    ev=json.load(open(f"ph/p{k}.events.json"))
    for n,e in enumerate(ev):
        if e["v"]!="A": continue
        cc=(e["t0"]+e["t1"])/2
        sl=(d["t"]>e["t0"]-0.12)&(d["t"]<e["t1"]+0.12)
        tt=d["t"][sl]; o=d["open"][sl]
        lo=o.min(); hi=o.max(); half=lo+(hi-lo)/2
        if hi-lo<0.15: continue
        i=np.argmax(o>=half); on.append((tt[i]-e["t0"])*1000)
for name,l in [("ouverture A (mi-hauteur vs début de la voyelle)",on)]:
    l=np.array(l); print("%s : n=%d médiane %+.1f ms, p90 retard %.1f ms, p90 |écart| %.1f ms"%(name,len(l),np.median(l),np.percentile(np.maximum(l,0),90),np.percentile(np.abs(l),90)))

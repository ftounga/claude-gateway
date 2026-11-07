# Banc P2 (F-250 / SF-250-06) — à lancer dans le dossier qui contient ph/p<k>.{mp3,json,csv,events.json} produits par sim.mjs.
import subprocess, json, numpy as np
SR=16000
V=set("aeiouyàâéèêëîïôöûùüœ")
def env(mp3):
    pcm=subprocess.run(["ffmpeg","-v","quiet","-i",mp3,"-f","s16le","-ac","1","-ar",str(SR),"-"],capture_output=True).stdout
    x=np.frombuffer(pcm,dtype=np.int16).astype(float)/32768
    hop=int(0.005*SR); win=int(0.02*SR)
    e=np.array([np.sqrt(np.mean(x[i:i+win]**2)) for i in range(0,len(x)-win,hop)])
    return np.arange(len(e))*0.005+0.01, e
chain=[]; acoustic=[]
for k in range(6):
    t,e=env(f"ph/p{k}.mp3")
    d=np.genfromtxt(f"ph/p{k}.csv",delimiter=",",names=True)
    al=json.load(open(f"ph/p{k}.json")); ch=[c.lower() for c in al["chars"]]
    for i,c in enumerate(ch):
        if c not in "mbp": continue
        j=i
        while j+1<len(ch) and ch[j+1]==c: j+=1
        if (i>0 and ch[i-1]==c): continue
        c0=al["starts"][i]; c1=al["ends"][j]; cc=(c0+c1)/2
        # (A) chaîne du produit : fermeture affichée vs centre de la consonne dans la frise (horloge audio)
        sl=(d["t"]>cc-0.15)&(d["t"]<cc+0.15)
        tl=d["t"][sl][np.argmax(d["press"][sl])]
        chain.append((tl-cc)*1000)
        # (B) acoustique, cas nets seulement : consonne entre deux voyelles (dans le mot ou à travers une espace)
        prev=next((ch[q] for q in range(i-1,-1,-1) if ch[q]!=' '),None); nxt=next((ch[q] for q in range(j+1,len(ch)) if ch[q]!=' '),None)
        if prev in V and nxt in V:
            sel=(t>cc-0.06)&(t<cc+0.06)
            ts=t[sel][np.argmin(e[sel])]
            acoustic.append((ts-cc)*1000)
chain=np.array(chain); acoustic=np.array(acoustic)
print("(A) chaîne : n=%d médiane %+.1f ms, p90 retard %.1f ms, max retard %.1f ms"%(len(chain),np.median(chain),np.percentile(np.maximum(chain,0),90),chain.max()))
print("(B) alignement fournisseur vs creux acoustique (intervocaliques) : n=%d médiane %+.1f ms, p90 |écart| %.1f ms"%(len(acoustic),np.median(acoustic),np.percentile(np.abs(acoustic),90)))
print("(A) valeurs:",np.round(chain).astype(int).tolist())
print("(B) valeurs:",np.round(acoustic).astype(int).tolist())

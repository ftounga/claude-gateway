import re,sys
def pts(d): return [tuple(map(float,p.split(','))) for p in re.findall(r'-?[\d.]+,-?[\d.]+',d)]
for f in sys.argv[1:]:
    s=open(f).read(); bad=tot=0
    if 'class="lien"' in s:
        for m in re.finditer(r'class="lien" points="([^"]+)"',s):
            P=pts(m.group(1))
            for a,b in zip(P,P[1:]):
                tot+=1; bad+= abs(a[0]-b[0])>0.5 and abs(a[1]-b[1])>0.5
    else:
        for g in re.findall(r'<g id="edge\d+" class="edge">(.*?)</g>',s,re.S):
            d=re.search(r'<path[^>]* d="([^"]+)"',g)
            if not d: continue
            P=pts(d.group(1).replace('M','').replace('C',' '))
            # segments cubiques : P0, (c1,c2,p3)...
            i=0
            while i+3<len(P):
                seg=P[i:i+4]; tot+=1
                xs={round(p[0],1) for p in seg}; ys={round(p[1],1) for p in seg}
                bad+= not (len(xs)==1 or len(ys)==1)
                i+=3
    print(f, "segments",tot,"non orthogonaux",bad)

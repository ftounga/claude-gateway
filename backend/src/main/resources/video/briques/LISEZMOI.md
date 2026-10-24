# Briques des cours vidéo

Point de départ, pas un gabarit : copie, adapte, compose librement. Méthode complète : `.claude/guides/cours-video.md`.

- `charte.css` — jetons de charte (navy / orange / crème, thème sombre ; `class="scene clair"` pour le clair) et
  composants. Autres couleurs : un `theme.css` chargé **après** `charte.css` qui redéfinit les `--*`.
- `briques.js` — `Briques.timeline("root")` (timeline GSAP en pause) et ses gestes ; `Briques.motA()`. Écris aussi dans la page `window.__timelines = window.__timelines || {}; window.__timelines["root"] = B.tl;` (le lint ne lit pas `briques.js`).
- `gsap.min.js` — copié par la chaîne du poste au premier `video_render_check` (ne pas télécharger).
- `../icons/` — icônes officielles demandées à `video_briques(icones: [...])` (`chercher` pour trouver un nom).

Coordonnées en pixels sur une scène 1920×1080 ; zones réservées : en-tête 0–110 px, sous-titres 940–1040 px.

## Composants (HTML) et gestes (JS)

| Brique | HTML | Geste |
|---|---|---|
| En-tête + progression | `<div class="hdr"><div class="tag">Cours · Sujet</div><div class="sep"></div><div class="chap"><span id="c1">1 · Titre</span>…</div></div><div class="prog"><i id="pg"></i></div>` | `B.chapters(["c1","c2"], [0, 12.0])` ; `B.progress("pg", total)` |
| Titre d'ouverture | `<div class="title" id="t"><h1>Du poste au <em>pod</em></h1><p>Sous-titre</p></div>` | `B.show("t", 0, .8)` |
| Zone / sous-réseau | `<div class="zone ok" id="z1" style="left:880px;top:250px;width:470px;height:560px"><div class="zt">VPC · 10.0.0.0/16</div></div>` (`ok` vert, `ko` rouge) | `B.show("z1", t)` ; `B.color("z1", t, {borderColor:"#3ecf8e"})` |
| Icône + libellé | `<div class="ico" id="i1" style="left:1050px;top:400px"><img src="icons/elb-application-load-balancer.png"><div class="lb">ALB interne</div><div class="sub mono">10.0.1.x</div></div>` | `B.pop("i1", t)` ; `B.pulse("i1", t)` |
| Flèche qui se dessine | `<svg class="wires"><path id="w1" class="w or" d="M330 400 L560 400" pathLength="1"/></svg>` (`or`, `gr`, `rd`, `bl`, `dash`) | `B.draw("w1", t, .7)` |
| Paquet qui circule | `<div class="pkt" id="p1">HTTPS → :443</div>` (`green`, `red`) | `B.move("p1", [[290,450],[640,450],[1120,450]], t, .8)` |
| Table de routage / règles | `<div class="rt" id="rt"><div class="h"><img src="icons/route-table.png">Table de routage</div><div class="r" id="r1"><span class="d">10.0.0.0/16</span><span>local</span></div><div class="r miss" id="r2"><span class="d">100.64.0.0/16</span><span>— aucune route —</span></div></div>` | `B.show("rt", t)` ; `B.color("r1", t, {backgroundColor:"rgba(62,207,142,.18)"})` ; `B.show("r2", t)` |
| Requête DNS tapée | `<div class="dnsq" id="q"><span class="k">Requête DNS</span><span class="typed" id="qt">api.exemple.interne ?</span></div>` | `B.show("q", t, .3)` ; `B.typeIn("qt", t + 1, 4)` |
| Badge OK / KO / info | `<div class="badge ok" id="b1" style="left:900px;top:740px">✓ Routable</div>` | `B.pop("b1", t)` |
| Étiquette (AZ, port) | `<div class="chip mono" id="az" style="left:910px;top:290px">AZ eu-west-3a</div>` | `B.pop("az", t)` |
| Sous-titres calés | `<div id="cap" class="captions"></div>` | `B.captions("cap", [[0.4, "Texte avec <b>gras</b>"], …], fin)` |
| Carte de résumé | `<div class="card" id="k1"><div class="n">01</div><div class="t">Idée</div><div class="d">détail</div></div>` | `B.show("k1", t)` |
| Fin | `<div class="end" id="end"><h2>Titre</h2><p>Sources : …</p></div>` | `B.show("end", t, .6, {y:0})` |

## Caler au mot

```js
// mots du chapitre 2 (copiés de audio/s2.mots.json), chapitre commençant à 12.0 s :
const m2 = [{"mot":"plage","debut":1.42,"fin":1.80}, /* … */];
B.pop("r1", Briques.motA(m2, "plage", 1, 0, 12.0));   // apparaît quand « plage » est dit
```

## Rappels

- Chaque `<audio>` a un `id`, un `data-start`, un `data-duration` (sinon vidéo muette).
- Texte ≥ 20 px ; une idée par plan ; toute notion technique affichée ; rien d'inventé (`x.x.x.x` si inconnu).
- Relecture : `video_render_check` puis `read_file` sur chaque image, avant `video_render` et `video_publier`.

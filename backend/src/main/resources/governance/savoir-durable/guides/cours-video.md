# Guide — fabriquer un cours vidéo

> Savoir-faire lu **à la demande**, quand une vidéo (cours, explication animée, tutoriel) est demandée.
> C'est une **méthode**, jamais un contenu : le sujet, le découpage, la narration et chaque animation
> viennent de la demande et des sources (carte du poste, pages, dépôt, comptes rendus). Elle vaut pour
> tout cloud (AWS, Azure, GCP, Kubernetes, on-prem) et tout domaine.
>
> Issue d'un essai réel : un cours de 2 min 34 en 1080p, voix off, sous-titres, flux animés — jugé
> excellent. Suis l'ordre, ne saute pas la relecture.

## 0. Outillage (une fois par dossier)

1. `video_briques(dossier: "<sujet>/video/<nom-court>")` : dépose ce guide, les **briques** (`briques/charte.css`,
   `briques/briques.js`, `briques/LISEZMOI.md`) et, à la demande, les **icônes officielles**
   (`icones: ["aws/network/elb-application-load-balancer", "k8s/compute/pod", …]` → `icons/<nom>.png`).
   Nom inconnu ? `video_briques(chercher: "load balancer")` rend les noms exacts. Lis `briques/LISEZMOI.md`.
2. GSAP n'est pas à télécharger : la chaîne du poste le copie dans `briques/gsap.min.js` au premier contrôle.
3. Charte : celle de l'application par défaut (navy / orange / crème, thème sombre). Si l'utilisateur demande
   d'autres couleurs (« aux couleurs du client »), écris un `theme.css` chargé **après** `charte.css` qui redéfinit
   les jetons `--*` ; ne modifie jamais `briques/` (re-déposé à chaque appel).

## 1. Faits d'abord

- Extrais des sources la liste des **faits** que la vidéo affirmera : ressources, CIDR, ports, noms DNS,
  protocoles, flux, décisions et leur date. Note d'où vient chacun.
- **Jamais d'adresse, de port ou de nom inventé.** Une valeur inconnue s'affiche **générique** (`10.180.171.x`,
  `<nom-du-service>`), ou se dit comme hypothèse « à confirmer ». Ce qui n'est pas sourcé est retiré.
- Une vidéo unique dure 1 à 3 min. Au-delà (ou au-delà de 4 notions majeures), propose plutôt une série
  d'épisodes et fais valider le plan avant de fabriquer.

## 2. Narration par chapitre

- 3 à 7 chapitres ; **une idée par plan**. Phrases courtes, à l'oral, dans l'ordre où l'image les montre.
- Écris les nombres comme ils se disent (« dix, cent quatre-vingts, cent soixante et onze, slash vingt-cinq »)
  pour la voix ; l'écran, lui, affiche la forme exacte (`10.180.171.0/25`).
- Range la narration dans `narration.json` (`{"s1": "…", "s2": "…"}`) : c'est la source des sous-titres.

## 3. Voix off et mots horodatés

- `voix_off(texte: <chapitre>, fichier: "<dossier>/audio/s1.mp3")`, **un appel par chapitre**. Sans voix
  explicite, la voix par défaut de l'utilisateur s'applique ; s'il demande « une voix féminine », « la voix 3 »,
  « plus dynamique », passe `voix`/`consignes`. « Fais-moi écouter des voix » → `voix_off(auditions: true)`.
- Chaque appel rend la durée et `s1.mots.json` (`{mot, debut, fin}` en secondes). **Toute animation se cale
  sur ces instants** : l'objet apparaît quand le mot est prononcé, pas « à peu près ».

## 4. Composition HyperFrames (une page HTML animée)

Squelette minimal (1920×1080, 30 i/s) :

```html
<!doctype html><html lang="fr"><head><meta charset="utf-8">
<link rel="stylesheet" href="briques/charte.css"><!-- puis theme.css si charte remplacée -->
</head><body>
<div id="root" class="scene" data-composition-id="root" data-width="1920" data-height="1080" data-fps="30">
  <audio id="as1" src="audio/s1.mp3" data-start="0.4" data-duration="11.2" data-volume="1"></audio>
  <div class="clip" id="s1" data-start="0" data-duration="12.0"> … plans du chapitre 1 … </div>
  <div id="cap" class="captions"></div>
</div>
<script src="briques/gsap.min.js"></script><script src="briques/briques.js"></script>
<script>
  const B = Briques.timeline("root");            // timeline GSAP EN PAUSE
  window.__timelines = window.__timelines || {}; window.__timelines["root"] = B.tl;   // visible du lint
  B.pop("s1a", 0.3); B.draw("s1w", 8.9, 1.0); B.move("s1p", [[310,720],[1580,720]], 9.3, 0.7);
  B.captions("cap", [[0.0, "Du poste au <b>pod</b> :"], [4.0, "pourquoi un <b>load balancer</b>."]], 12.0);
  B.end(154.4);                                  // durée totale
</script></body></html>
```

Règles HyperFrames :
- `data-composition-id` sur la racine ; chaque chapitre (`.clip`) et chaque `<audio>` porte `data-start` et
  `data-duration` (secondes) ; chapitre N+1 = début de N + durée de sa voix + ~0,6 s.
- **Tout `<audio>` a un `id`** — sans `id`, la vidéo sort **muette** (piège vécu).
- La timeline GSAP est **en pause** et enregistrée **dans la page elle-même** (`window.__timelines["root"] = B.tl`) :
  le lint ne lit pas `briques.js` et signale sinon `missing_timeline_registry`. Jamais d'animation CSS libre, de
  `setTimeout` ni de `Date.now()` : seul le temps de la timeline compte.
- Tout en local (briques, icônes, audio) : aucun CDN au rendu. Polices : `Inter` et `JetBrains Mono` (fournies par
  le rendu) ; toute autre police exige un `@font-face`, sinon le lint échoue (`font_family_without_font_face`).

Règles de lisibilité :
- Texte **≥ 20 px** en 1080p (libellés 22–26 px, titres 48–96 px, sous-titres ~30 px).
- **Une idée par plan** ; on retire (`hide`) ce qui ne sert plus avant d'ajouter.
- **Toute notion technique est AFFICHÉE**, avec l'animation qui va bien : CIDR sur la zone, port sur le paquet
  (`HTTPS → :443`), nom DNS tapé (`typeIn`), ressource avec son icône officielle, ligne de table de routage qui
  s'allume, badge OK/KO sur le résultat.
- Un conteneur `display:flex` **mange les espaces** autour d'un `<b>` : enveloppe le texte dans un `<span>`
  (`<div><span>du poste au <b>pod</b> :</span></div>`) — `captions` le fait déjà.
- Ni chevauchement ni texte qui sort du cadre : marge de 56 px, bandeau du haut et sous-titres réservés.

## 5. Relecture — obligatoire avant tout rendu et toute publication

1. `video_render_check(dossier, instants: "3,10.5,18,…")` : un instant par plan, **au moment où tout est
   affiché** (pas pendant une transition), plus la fin.
2. Le lint doit être **sans erreur** ; corrige chaque avertissement qui touche le son ou le temps.
3. **Ouvre chaque image avec `read_file` et regarde-la** : lisibilité, chevauchements, textes coupés, icône
   manquante, notion technique absente de l'écran, sous-titre qui déborde. Corrige, puis relance le contrôle.
   Une image non regardée n'est pas une relecture (« vérifier avant de conclure »).

## 6. Rendu et publication

1. `video_render(dossier, qualite: "looks")` (rend la main tout de suite), puis `video_render_status(job_id)`
   jusqu'à « Terminé ». Un rendu long continue même si le tour se termine.
2. Vérifie le résultat : `video_images` sur 3 à 5 instants du mp4 (son compris : durée attendue).
3. `video_publier(path, titre, description, chapitres: [{t, titre}…])` — les chapitres reprennent les débuts des
   `.clip`. Une nouvelle voix ou une correction = nouvelle version (`remplace: <video_id>`).
4. Retours à la seconde de l'utilisateur : `video_retours(video)` les liste (instant, image, texte) ; regarde
   chaque instant (`video_images`), corrige, puis republie avec `remplace` et
   `retours_traites: [{id, statut: traite|rejete, motif}]` — un retour écarté se motive.

## Pièges connus

| Symptôme | Cause | Remède |
|---|---|---|
| vidéo muette | `<audio>` sans `id` | un `id` unique par piste |
| mots collés autour du gras | conteneur flex | envelopper le texte d'un `<span>` |
| objet visible avant sa phrase | instant estimé | reprendre l'instant dans `.mots.json` |
| texte coupé, chevauchement | vu seulement sur image | `video_render_check` + `read_file` |
| adresse « plausible » à l'écran | valeur inventée | générique `x.x.x.x` ou « à confirmer » |

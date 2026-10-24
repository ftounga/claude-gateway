# Mini-spec — F-212 / SF-212-04 Front : le bloc preuve et les captures jointes au message

## Identifiant
`F-212 / SF-212-04` — Feature parente : F-212 « Les captures de preuve » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-212-04-bloc-preuve`

## Objectif
Afficher les captures de preuve dans le fil (vignettes, zoom, « Copier l'image », « Télécharger », relevés sous les vignettes) et les **joindre à l'encart message** qu'elles prouvent, avec « Copier le message avec les captures » pour un collage d'un coup dans Outlook ou Teams.

## Comportement attendu
### Cas nominal
- Composant `app-proof-captures` (standalone) : titre (« Captures de preuve · titre · étape N »), vignettes servies par `GET /api/workspaces/{id}/proofs/{captureId}`, clic = agrandir/réduire, « Copier l'image » (`navigator.clipboard.write([new ClipboardItem({'image/png': …})])`, promesse passée telle quelle pour garder le geste), « Télécharger » (`preuve-<titre>-<n>.png`).
- Relevés **sous** les vignettes (jamais dans l'image, amendement PO) : `$ commande` puis « code 0 · 1,2 s · 24/10 17:42 », « interrompue », « non exécutée », « sortie tronquée » ; ligne d'origine « poste · shell — Capture générée à partir de la sortie réelle du poste. »
- Dans le fil : bloc `proof` du compte rendu (relu au rechargement) et en direct (SSE `proof`, rangé comme une carte).
- **Usage cible (précision PO)** : sous un encart ```` ```message preuves=<id1>,<id2> ```` (attribut lu par `message-segments`, à côté de `attente=`), les captures désignées (cherchées dans tout le fil) ; à défaut de référence dans la réponse, les captures du tour sous son **dernier** encart. Bouton « Copier le message avec les captures » : `text/html` (corps mis en forme comme « Copier » + `<img src="data:image/png;base64,…">`) et `text/plain` (corps). Le bouton « Copier » (texte seul) et « Marquer envoyé » (F-211) restent intacts. Les captures restent aussi dans leur bloc du fil.
### Cas d'erreur
| Situation | Comportement |
|---|---|
| presse-papiers image indisponible | message + repli Télécharger |
| copie refusée par le navigateur | message + repli Télécharger |
| image introuvable | « Image indisponible. » |
| « Copier le message avec les captures » impossible | message : copier le message puis chaque image |
| identifiant `preuves=` invalide | ignoré (32 hexadécimaux seulement) |

## Critères d'acceptation
- [x] Vignettes, zoom, Copier l'image (PNG), Télécharger, relevés et origine affichés.
- [x] Repli Télécharger sans presse-papiers image.
- [x] `preuves=` joint les captures à leur encart ; repli sous le dernier encart ; jamais affiché.
- [x] « Copier le message avec les captures » : text/html avec images data:, text/plain = corps.
- [x] « Marquer envoyé » et le bloc du fil intacts.
- [x] Événement SSE `proof` relayé ; bloc sans image ignoré.
- [x] Charte : tokens existants seulement (`--cg-orange-2`, `--cg-divider`, `--cg-space-*`, `--cg-font-*`, `--cg-text-secondary`).

## Plan de test
`proof-captures.component.spec.ts` (6), `terminal-proof-attached.spec.ts` (2), `message-segments.spec.ts` (+1), `atelier.service.spec.ts` (+1) ; suite complète Karma (ChromeHeadlessNoSandbox) ; `npm run build` (gardes). Isolation : l'image n'est servie qu'au propriétaire (SF-212-03, testé côté serveur).

## Préoccupations transversales
Aucune (Auth, tenant, plans, navigation : non — pas de nouvelle route front).

## Impacts
`proof-captures.component.ts` (nouveau), `atelier-terminal.component.{ts,html}`, `atelier.component.ts` (SSE en direct), `atelier.service.ts`, `atelier.models.ts`, `copy-block.model.ts`, `message-segments.ts`.

## Hors périmètre
Overlay F-208 « Voir la preuve » (le texte de preuve de l'étape cite l'identifiant) ; invite personnalisée par poste.

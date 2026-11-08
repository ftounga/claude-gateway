# Mini-spec — F-253 / SF-253-01 L'audit global de l'affichage

## Identifiant
`F-253 / SF-253-01`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3, §4)

## Statut
`in-progress` · **Estimation :** 0,5 j · **Branche :** `feat/SF-253-01-audit-affichage` · **Dépend de :** —

## Objectif
Recenser tout ce qui a été cadré et livré pour l'affichage de `/jarvis`, dire pour chaque capacité si elle marche
vraiment (preuve code, prod, rendu réel), et outiller la vérification par un banc de rendu de la vraie page.

## Comportement attendu
1. `docs/audits/AUDIT-2026-11-08-affichage-jarvis.md` : tableau récapitulatif PO en tête ; 48 capacités (F-231, F-234,
   F-236, F-237, F-238, F-243, F-245, F-247, F-248, F-250, cerveau rapide) avec promesse, déclencheur, état réel, verdict
   (OK / CASSÉ / JAMAIS BRANCHÉ / DÉCLENCHEUR IMPOSSIBLE) et preuve ; propositions nouvelles non construites en fin.
2. `docs/features/F-253/CADRAGE-F-253-jarvis-montre-vraiment.md` et la ligne F-253 de `docs/PRODUCT_SPEC.md`.
3. Banc `frontend/scripts/banc-jarvis-montre/` (`mock.mjs`, `scenario.mjs`) : la vraie page `/jarvis` (build de
   développement), API simulée (univers inventé à la forme du PO, cerveau rapide scripté avec les ordres prouvés en
   prod), Chrome sans tête **sans** `--hide-scrollbars`, PC 1920×1080, PC avec sujet, téléphone 390×844 ; une ligne
   OK/BUG par vérification (rendu, noms visibles, défilement horizontal, « quelque chose apparaît » après chaque phrase
   de l'essai, erreurs de page) ; code 1 si un BUG.
4. Captures « avant » : `docs/features/F-253/captures/avant/`.

### Cas d'erreur
- Build absent ou Chrome absent : le banc échoue au lancement (message de Node), aucune donnée écrite hors `<sortie>`.
- Requête `/api` inconnue : 404 `{"error":"banc"}`, journalisée dans `<tag>-requetes.txt` (la page doit tenir).

## Critères d'acceptation
- [ ] L'audit couvre chaque feature d'affichage Jarvis citée au PRODUCT_SPEC, avec verdict et preuve par ligne.
- [ ] Le tableau récapitulatif PO est en tête et renvoie aux SF de correction.
- [ ] Le banc rejoue l'essai sur `main` et rend les BUG prouvés (« mes clients », « les vidéos de CAGIP » sans sujet).
- [ ] F-253 figure au PRODUCT_SPEC (« À faire ») avec le lien vers le cadrage et l'audit.

## Plan de test
Banc exécuté sur le build de `main` : 8 BUG / 18 attendus (constat). Aucun code applicatif modifié ⇒ ni unitaires ni
intégration ; isolation utilisateur : sans objet (données simulées, aucune lecture en base).

## Impact
Docs (`docs/audits/`, `docs/features/F-253/`, `docs/PRODUCT_SPEC.md`), `frontend/scripts/banc-jarvis-montre/`.
Préoccupations transversales : aucune.

## Hors périmètre
Toute correction (SF-253-02→08).

# Mini-spec — [F-236 / SF-236-27] Polissage final, mesure et planche de l'itération 4

## Identifiant
SF-236-27

## Feature parente
F-236 — Jarvis en 3D (cadrage §15, §18 ; itération 4 « Au-delà », SF de polissage finale)

## Statut
done — **visa du PO délégué** pour cette vague (non bloquant) ; lecture J+7 après déploiement

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-27-polissage-final`

---

## Objectif

Polir les vues de l'itération 4, produire la planche finale, poser le rapport de mesure (i/s, CPU, vues) et tracer
les décisions de suite.

## Comportement attendu

### Cas nominal

1. **Polissage** (constaté sur les captures) : anneau de la carte d'activité ramené de 0,62 à 0,42 (il débordait du
   halo du client), plancher d'éclat 0,08 → 0,12 (l'anneau se lit en entier) ; échos du radar 8-22 px → 16-40 px
   (ils se perdaient sur l'anneau).
2. **Banc** : les scènes de l'itération 4 (`cockpit-replay`, `presentation`, `veilleur`, `ambiance-radar`,
   `cockpit-activite`) enregistrées dans `BANC_SCENES` (elles retombaient silencieusement sur le cœur seul) ; test.
   Activité simulée `BANC_ACTIVITY`, ambiance `radar` (trois alertes).
3. **Planche** `docs/features/F-236/captures/iteration-4/index.html` (générée de `planche.json` par
   `scripts/jarvis-planche.mjs`) : 6 vues × téléphone + PC = 12 images (`captures/au-dela/`), toutes non vides et
   sans erreur console ; `npm run verify:planche` OK.
4. **Rapport de mesure** (ci-dessous) et décisions de suite.

### Rapport de mesure (état au 2026-11-02)

| Mesure | État | Lecture |
|---|---|---|
| i/s p50 PC (1920 × 1080, 2560 × 1440) | **non mesurable au banc** (rendu logiciel swiftshader) | À relever sur le PC du PO ; remonté par `RENDER` (`fps_*`). |
| i/s p50 et CPU p90 téléphone | **idem** | Téléphone du PO ; `RENDER`. |
| Bundle initial | 485,40 ko — **+0** sur toute l'itération 4 | Les vues nouvelles sont dans le morceau paresseux `jarvis-engine` ou le composant `/jarvis`. |
| Allocation par image | **0** dans `frameRadar` / `frameActivity` (tampons préalloués, boucles indexées) | Revu en code. |
| Mesure J+7 / J+14 (§18) | **non lisible** : itérations 3 et 4 non déployées (déploiement unique de fin de vague) | Lire `GET /api/admin/jarvis/measure` à J+7 du déploiement : `replays`, `presentations`, `cockpits`, `nights`, `activity`, `r_*`, `fps_*`. |

### Décisions de suite (tracées)

| # | Décision | Signal qui la fera revoir |
|---|---|---|
| S1 | Aucune vue de l'itération 4 n'est enrichie avant la lecture J+14. | Vue jamais ouverte à J+14 ⇒ ne plus l'enrichir (« proposer depuis l'usage observé »). |
| S2 | Si `low_perf` + `context_lost` ≥ 10 % : revoir le niveau Bas avant tout enrichissement. | §18. |
| S3 | Si bascule manuelle en 2D ou mode sobre durable : revoir densité et caméra. | §18 (signal négatif). |
| S4 | Les remarques de goût du PO sur les planches 2, 3, 4 seront traitées ensemble (visas délégués). | Retour du PO. |

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Capture prise pendant une recompilation | Refaite (garde d'overlay SF-236-14). |
| Scène de banc inconnue | Retombe sur le cœur — désormais couverte par un test pour l'itération 4. |

---

## Critères d'acceptation

- [x] CA1 — Planche produite (12 images, chemins vérifiés ; `npm run verify:planche` OK).
- [x] CA2 — Polissage appliqué et visible sur les captures refaites.
- [ ] CA3 — **Visa du PO** : délégué pour cette vague (non bloquant).
- [ ] CA4 — Rapport i/s et CPU téléphone + PC : sur les appareils du PO, après déploiement (`RENDER`).
- [x] CA5 — Décisions de suite tracées (S1-S4).

## Plan de test

- Unitaires : `jarvis-banc.spec.ts` (scènes de l'itération 4 lues). Suite complète `ng test` verte.
- Visuel : captures headless des 6 vues (téléphone + PC), planche.
- Isolation utilisateur : sans objet (aucun appel nouveau).

## Périmètre

### Impacté
`engine/jarvis-effects-3d.ts` (rayon, plancher, taille des échos), `jarvis-banc.ts`, `jarvis-banc.spec.ts`,
`jarvis-banc-scenes.ts`, `jarvis-banc.component.ts`, `scenes3d/jarvis-scene3d.fixture.ts`,
`docs/features/F-236/captures/{au-dela,iteration-4}/`.

### Hors scope (explicite)
- Mesure réelle (après déploiement). Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Non | Banc de développement seulement (`/jarvis-banc`, inopérant en production). |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Visa PO délégué ; planche fournie. | Consigne de la vague (« trancher sans solliciter le PO »). | Bloquer sur le visa. | Oui. |
| A2 | J+7 tracé comme à lire après déploiement. | Rien n'est déployé avant la fin de vague. | Retarder la SF de 7 jours. | Oui. |

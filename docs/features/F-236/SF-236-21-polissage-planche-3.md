# Mini-spec — [F-236 / SF-236-21] Polissage visuel 3 et planche de l'itération 3

## Identifiant
SF-236-21

## Feature parente
F-236 — Jarvis en 3D (cadrage §7.3, §15 ; itération 3 « Le cockpit PC », SF de polissage)

## Statut
done — **visa du PO délégué** pour cette vague (non bloquant)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-21-polissage-planche-3`

---

## Objectif

Équilibrer le cockpit PC (densité, hiérarchie, lisibilité des panneaux) et produire la planche de référence de
l'itération 3.

## Comportement attendu

### Cas nominal

1. **Hiérarchie** : un filet (orange 18 %) entre les vues d'un panneau ; le titre de section porte la lecture.
2. **Lisibilité** : barre de défilement aux couleurs du verre ; aberration chromatique de la finition ramenée de
   0,006 à 0,004 (franges visibles sur les étoiles floues du bord) ; « 0 » éteint en crème (SF-236-16) ; ciel des
   chantiers à 20 px (SF-236-16).
3. **Séquence T-P4** au banc (`cockpit-repli-anim`) : le panneau gauche posé à l'instant `t` sur la courbe `glisse`
   (6 images, 0 → 320 ms).
4. **Planche** `docs/features/F-236/captures/iteration-3/index.html`, générée de `planche.json` par
   `scripts/jarvis-planche.mjs` : 19 captures (cockpit, journée, météo, chronologies, personnes, inspecteur,
   recherche, téléphone ; PC + téléphone) et la séquence T-P4 — 40 images, toutes refaites après polissage, chemins
   vérifiés.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Capture prise pendant une recompilation | Refaite (garde d'overlay SF-236-14) ; serveur de dev relancé si cassé. |
| Remarques de goût du PO | Traitées dans la SF de polissage suivante (visa délégué). |

---

## Critères d'acceptation

- [x] CA1 — Planche produite (40 images, chemins vérifiés ; `npm run verify:planche` OK).
- [x] CA2 — Polissage appliqué et visible sur les captures refaites.
- [ ] CA3 — **Visa du PO** : délégué pour cette vague (non bloquant).
- [ ] CA4 — i/s sur le PC (1920 × 1080, 2560 × 1440) et le téléphone du PO : à relever en usage (`RENDER`).

## Contraintes de validation

| Élément | Règle |
|---|---|
| Aberration | 0,004 |
| Filet entre vues | `--cg-holo` à 18 % |

## Plan de test

- Suites Jarvis + suite frontend complète + build (bundle initial inchangé, morceau 3D sous la garde).
- `npm run verify:planche`. Visuel : 40 captures, critère automatique (non vide, sans erreur console, sans overlay).

## Périmètre

### Impacté
`hud/jarvis-cockpit.component.scss`, `jarvis-post-chain.ts`, banc (`cockpit-repli-anim`), captures `cockpit/`,
`telephone/`, `transitions/cockpit-repli-anim-*`, planche `iteration-3/`.

### Hors scope (explicite)
- Toute vue nouvelle (itération 4). Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Visa du PO délégué : fusion et suite sans attendre. | Consigne de la vague (goût réversible). | Bloquer sur le visa. | Oui. |
| A2 | Séquence T-P4 posée au banc par style (transition CSS non pilotable par l'horloge du banc). | Captures reproductibles sur la même courbe `glisse`. | Captures en temps réel (non déterministes). | Oui. |
| A3 | Planche non publiée en page hébergée (images locales au dépôt). | Même pratique qu'aux itérations 1 et 2. | Publication séparée. | Oui. |

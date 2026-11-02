# Mini-spec — [F-236 / SF-236-14] Polissage visuel 2 et planche de l'itération 2

## Identifiant
SF-236-14

## Feature parente
F-236 — Jarvis en 3D (cadrage §7.3, §15 ; itération 2 « Jarvis montre ce qu'il fait », SF de polissage)

## Statut
done — **visa du PO délégué** pour cette vague (non bloquant)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-14-polissage-planche-2`

---

## Objectif

Accorder les scènes de l'itération 2 entre elles (lisibilité, densité téléphone), traiter les trois réserves du PO sur
l'itération 1 et produire la planche de référence de l'itération 2.

## Comportement attendu

### Cas nominal

1. **Réserve 1 — centre du grand cerveau trop lumineux sur PC** : quand le champ est déployé, les particules du
   cœur chaud sont atténuées (jusqu'à −60 %, la périphérie intacte), le petit cœur au milieu baisse sa lumière d'un
   tiers et le surcroît de bloom des cerveaux est ramené à 60 % au niveau Haut : le centre redevient un noyau lisible,
   plus une tache blanche (capture `reserves/cerveau-t4000`).
2. **Réserve 2 — étiquettes du centre peu lisibles une fois déployé** : grand cerveau déployé (`spread > 0,4`), les
   étiquettes prennent une bande de verre navy (82 %) — texte net, contraste ≥ 5,8:1 quel que soit le fond.
3. **Réserve 3 — légendes « t = … ms » cassées** : générateur de planches `frontend/scripts/jarvis-planche.mjs`
   (légende lue par une expression complète `…-t<ms>-<appareil>.png`) + test `npm run verify:planche` ; la planche de
   l'itération 1 est **corrigée** (« t = elephone.png ms » → « t = 0 ms », …).
4. **Cohérence des scènes** : panneaux **au-dessus des étiquettes** (CSS2D leur donne un z-index par profondeur : le
   panneau « devant tout » passait dessous au téléphone) ; verre des panneaux à 84 % (lisible devant les cœurs
   lumineux et les étiquettes).
5. **Banc fiable** : le script de captures refuse une capture qui montre le **message d'erreur du serveur de
   développement** (recompilation en cours) — part de gris neutre > 8 % ⇒ refaite, sinon échec signalé.
6. **Planche** `docs/features/F-236/captures/iteration-2/index.html` (générée de `planche.json`) : 34 captures de scènes
   (téléphone + PC) — S-GEN, S1-S17, « Montrer » × 6, A1-A5, réserve du grand cerveau — et 4 séquences de 6 images
   (T-P1, T-P2, T-L4, T-P3) ; 116 images, toutes refaites après polissage.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Capture prise pendant une recompilation | Refaite (≤ 3) ; sinon « ÉCHEC … message d'erreur du serveur de développement ». |
| Nom de capture hors convention | Légende vide (jamais « elephone.png »). |
| Le PO demande des changements de goût | Traités dans la SF de polissage suivante (visa délégué pour cette vague). |

---

## Critères d'acceptation

- [x] CA1 — Planche produite (116 images, chemins vérifiés), légendes correctes (test du générateur).
- [x] CA2 — Les trois réserves de l'itération 1 traitées (captures `reserves/`, planche 1 corrigée).
- [x] CA3 — Règles §7.3 vérifiées (rappel en fin de planche, tests cités dans les mini-specs SF-08 → 13).
- [ ] CA4 — **Visa du PO** : délégué pour cette vague (non bloquant) ; remarques de goût à traiter ensuite.
- [ ] CA5 — i/s sur les appareils du PO : à relever (banc en rendu logiciel) ; l'événement `RENDER` les remonte.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Atténuation du centre | −60 % au plus sur les particules chaudes, petit cœur −35 %, bloom des cerveaux × 0,6 (niveau Haut). |
| Garde d'overlay | Part de gris neutre ≤ 8 %. |

## Plan de test

- `npm run verify:planche` (générateur, légendes, correction de la planche 1).
- Suites Jarvis + suite frontend complète + build (bundle initial inchangé).
- Visuel : 116 captures refaites, critère automatique (non vide, sans erreur console, sans overlay de dev).

## Périmètre

### Impacté
`engine/jarvis-brain-3d.ts`, `engine/jarvis-engine.ts` (centre adouci, étiquettes sur verre), `jarvis-labels.scss`,
`engine/jarvis-panels-style.ts` (verre 84 %, panneaux au-dessus des étiquettes) ; `scripts/jarvis-planche.mjs` (+
test), `scripts/png-coverage.mjs` (`--overlay`), `scripts/jarvis-3d-captures.sh`, `package.json`
(`verify:planche`) ; planches `iteration-1/index.html` (légendes) et `iteration-2/` ; captures refaites.

### Hors scope (explicite)
- Toute nouvelle scène ou vue (itération 3 : cockpit PC).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Visa du PO délégué : fusion et suite sans attendre. | Consigne de la vague (goût réversible). | Bloquer sur le visa. | Oui. |
| A2 | Verre des panneaux 72 % → 84 %. | Lisibilité devant étiquettes et cœurs lumineux (vu au banc) ; écart mineur à §7.2, à confirmer au visa. | Garder 72 %. | Oui. |
| A3 | Planche 1 corrigée en place (légendes seulement), sans refaire ses images. | La réserve portait sur les légendes ; ses images restent la référence visée. | Régénérer la planche 1. | Oui. |
| A4 | Captures de la réserve du grand cerveau dans `captures/reserves/`. | Ne pas écraser les images de référence de l'itération 1. | Écraser `cerveaux/`. | Oui. |

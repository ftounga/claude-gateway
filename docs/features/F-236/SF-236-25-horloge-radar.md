# Mini-spec — [F-236 / SF-236-25] Horloge des veilles + radar des alertes

## Identifiant
SF-236-25

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-14 / NF-15, §15 ; itération 4 « Au-delà »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-25-horloge-radar`

---

## Objectif

Voir quand chaque veille passe dans la semaine (la prochaine en surbrillance), et repérer d'un coup d'œil les alertes
réelles sur un anneau radar autour de l'univers.

## Comportement attendu

### Cas nominal

1. **Horloge des veilles (NF-14 / N10, pur `hud/jarvis-watch-clock.ts`)** : cinq pistes lun → ven (les veilles F-230
   ne passent que ces jours-là), axe 6 h – 22 h (élargi si besoin), un trait par heure de passage (`runTimes`) des
   veilles **actives** (ni en pause, ni proposées) ; la **prochaine** (premier passage après maintenant, lundi après
   le week-end) en surbrillance et écrite (« Prochaine : Tickets FORGE · aujourd'hui 12:00 »).
2. Affichée dans le panneau droit du cockpit PC et dans le volet « Aujourd'hui » du téléphone ; lue **une fois**, à
   la première ouverture du cockpit, **hors tour** (`GET /api/jarvis/watches`, filtrée `user_id`, injectable
   `JARVIS_WATCHES`).
3. **Radar des alertes (NF-15, pur `scenes3d/jarvis-radar.ts`)** : un écho par alerte **réelle** de l'ambiance — la
   veille qui sonne (A5, rouge), le poste muet (A1, rouge), le sujet qui appelle (A3, teinte de l'attente) — à
   l'azimut de **son client** ; client non posé ⇒ au cœur. Effet `radar` du plan d'ambiance : anneau horizontal
   au-delà de l'orbite des postes, 4 graduations, balayage en 4 s, échos qui pulsent (1,2 s) ; 0 allocation par
   image. Comme l'ambiance (SF-236-13), au repos seulement, sauf l'écho de la veille qui sonne.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| `watches` illisible (pas une liste, entrées incomplètes, heures invalides) | Rien, aucune erreur (test). |
| Aucune veille active | Pas de section. |
| Aucune alerte | Pas de radar. |

---

## Critères d'acceptation

- [x] CA1 — `watches` illisible ⇒ rien, aucune erreur (tests pur et composant).
- [x] CA2 — Échos = alertes réelles (tests : aucune alerte ⇒ aucun écho ; chaque alerte ⇒ son écho, son client).
- [x] CA3 — La prochaine veille en surbrillance, une seule (test).
- [x] CA4 — Règle de l'ambiance respectée (au repos sauf A5) ; rouge seulement pour les alertes (test N13 vert).
- [x] CA5 — Captures (cockpit, ambiance) dans la planche SF-236-27.

## Plan de test

- Unitaires : `hud/jarvis-watch-clock.spec.ts` (horloge : pistes, actives, prochaine, week-end, illisible, axe ;
  radar : échos, client, cœur, repos / tour).
- Composant : `jarvis-cockpit.component.spec.ts` (lue une fois hors tour, surbrillance ; illisible ⇒ rien).
- Isolation utilisateur : endpoint existant `GET /api/jarvis/watches` (filtré `user_id`), inchangé.

## Périmètre

### Impacté
`hud/jarvis-watch-clock.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}` (`JARVIS_WATCHES`),
`scenes3d/jarvis-radar.ts`, `scenes3d/jarvis-ambiance-3d.ts`, `scenes3d/jarvis-scene3d.model.ts` (effet `radar`),
`engine/jarvis-effects-3d.ts`, banc (veilles simulées).

### Hors scope (explicite)
- Horloge en 3D (cadran dans l'univers) : la matrice §12.2 la range en volet ; au PC, panneau.
- Fuseau de la veille : les heures sont affichées telles que posées (fuseau du compte).
- Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | Non | Lecture existante des veilles (filtrée `user_id`). |
| Navigation / routing | Non | Aucune route ni paramètre. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Horloge en panneau (PC) et volet (téléphone), pas en cadran 3D. | Lisibilité des heures ; matrice §12.2. | Cadran 3D. | Oui. |
| A2 | Radar au repos seulement, sauf l'écho A5. | Règle existante de l'ambiance (SF-236-13, test). | Radar permanent. | Oui. |
| A3 | Heures affichées dans le fuseau de la veille. | Le PO pose ses veilles dans son fuseau. | Conversion. | Oui. |

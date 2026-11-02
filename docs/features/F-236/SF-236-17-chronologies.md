# Mini-spec — [F-236 / SF-236-17] Chronologies (tour en cours, journée)

## Identifiant
SF-236-17

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-3 / NF-4 / N14, §3.1 H14, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-17-chronologies`

---

## Objectif

Voir l'agent travailler action par action (arc de perles du tour) et retrouver ce qui s'est fait aujourd'hui et quand
(chronologie de la journée), dans le panneau droit du cockpit.

## Comportement attendu

### Cas nominal

1. **Chronologie du tour (NF-4 / N14)** — `TurnTimeline` (pur) : une **perle par appel d'outil** (`pulses` du bus),
   colorée par la **famille** de la scène affichée à ce moment, d'après le registre F-234 (`SCENE_FAMILY`) :
   poste (orange — commande, fichiers, tickets, web, vidéo, point), attente (cyan — « oui », message, attente,
   points de route), réussite (vert — création, étape), cerveau (violet — passation, mémoire, essaim, veille),
   autre (gris — S-GEN). Perles posées sur un arc de 160° ; au-delà de 40 : les 40 dernières et « +n ». Remise à
   zéro au début d'un tour, gardée à la fin. **Aucun appel.**
2. **Chronologie de la journée (NF-3)** — bouton « Charger la journée » : lit l'historique du fil du **sujet
   courant** (`GET /api/workspaces/{id}/chat`, existant, isolé par `user_id`), **hors tour et à la demande**
   seulement. Un tour = un message de l'utilisateur du jour (heure locale) + la réponse qui suit : heure, demande
   (une ligne, 90 c.), première phrase de la réponse (160 c.), nombre d'actions (blocs + omis), une perle par outil
   (famille du registre), « dit à Jarvis » si `via = JARVIS`. Axe de la journée (8 h au plus tard → maintenant ou
   l'heure qui suit le dernier tour) avec un point par tour.
3. **Toucher un tour** ⇒ son **résumé** (demande, réponse, perles, actions) ; retouché ⇒ refermé. (Le replay
   arrive à l'itération 4, SF-236-22.)

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Tour en cours | Bouton désactivé, « Disponible à la fin du tour. » ; `loadDay()` refusé (aucun appel). |
| Aucun sujet choisi | Bouton désactivé. |
| Historique illisible (réseau, 403/404) | « La journée n'a pas pu être lue. », aucune erreur. |
| Aucun tour aujourd'hui | « Aucun tour aujourd'hui sur ce sujet. » |
| Message sans réponse / sans transcription | Réponse absente, 0 action. |

---

## Critères d'acceptation

- [x] CA1 — Aucun appel pendant un tour (test : bouton désactivé, `loadDay` sans appel, historique jamais lu au
  montage).
- [x] CA2 — Perles colorées par famille = registre : toute scène connue (`knownSceneIds`) a sa famille, jamais de
  rouge (test).
- [x] CA3 — Tours du jour seulement, réponse, actions, familles, voix ; axe de la journée (tests).
- [x] CA4 — Toucher un tour ⇒ résumé (test du composant).
- [x] CA5 — Capture `cockpit-chronologies` non vide, sans erreur console (historique simulé, aucun appel).

## Contraintes de validation

| Élément | Règle |
|---|---|
| Perles du tour | ≤ 40 dessinées, « +n » au-delà |
| Demande / réponse | 90 / 160 caractères, une ligne |
| Lecture de la journée | hors tour, à la demande, sujet courant seulement |

## Plan de test

- Unitaires : `hud/jarvis-timelines.spec.ts` (familles = registre, tour, borne, arc, journée, axe).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (arc, journée refusée pendant un tour, chargement hors tour,
  résumé, erreur).
- Isolation utilisateur : l'historique est lu par l'endpoint existant (`AtelierService.getHistory`), filtré
  `user_id` côté passerelle ; aucun endpoint nouveau.
- Visuel : banc `cockpit-chronologies` (historique simulé injecté, `JARVIS_DAY_HISTORY`).

## Périmètre

### Impacté
`hud/jarvis-timelines.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}` (`JARVIS_DAY_HISTORY`, arc, journée),
banc (`cockpit-chronologies`, historique simulé).

### Hors scope (explicite)
- Replay d'un tour (SF-236-22). Chronologie multi-sujets (l'endpoint est par sujet).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | Non (lecture existante) | `GET /api/workspaces/{id}/chat` inchangé, isolé `user_id`. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Familles dans la **grammaire des couleurs** (5 familles, jamais de rouge). | « Couleur par famille » sans casser §23 (une couleur = un sens). | Une couleur par scène. | Oui. |
| A2 | Chronologies dans le **panneau droit**, pas en bandeau bas. | Le bas porte sous-titres et commandes (non régressés). | Bandeau bas (chevauchement). | Oui. |
| A3 | Journée du **sujet courant**, au clic. | L'historique est par sujet ; « à la demande » (cadrage). | Lire tous les sujets à l'ouverture. | Oui. |
| A4 | Historique injecté par jeton (`JARVIS_DAY_HISTORY`), résolu au premier chargement. | Banc et tests sans réseau ; le cockpit ne dépend pas du client HTTP pour s'afficher. | Service injecté au montage. | Oui. |

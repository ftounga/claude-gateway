# Mini-spec — F-121 / SF-121-11-FE — L'écran dit la vérité sur les précisions (lecture entre outils, refus en volume)

## Identifiant

`F-121 / SF-121-11-FE`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Volet **frontend** de `SF-121-11`
(`docs/features/F-121/SF-121-11-pilotage-steers.md`, PR #943, mergée).

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-11-FE-libelles-precisions`

---

## Objectif

Aligner les deux phrases que l'écran dit des précisions sur ce que le serveur fait désormais : la
file est lue **dès la fin de l'outil en cours** (et non « à l'étape suivante »), et le refus vient du
**volume** de texte en attente (et non du nombre de précisions).

---

## Contexte

`SF-121-11` (backend, mergée) a changé deux faits observables :

1. une précision est prise **entre les appels d'outils** — le libellé d'attente « en attente de
   l'étape suivante » promet donc plus d'attente qu'il n'y en a ;
2. `409 too_many_steers` ne vient plus du **nombre** de précisions (au-delà de dix, elles se
   fondent dans la dernière) mais du **volume** — le message « Trop de précisions en attente » dit
   une cause qui n'existe plus, et pousse l'utilisateur à croire qu'il a trop parlé.

Aucun contrat d'API ne change : `steer_queued` / `steer_applied` / `steer_followup` /
`steers_dropped` et le code d'erreur `too_many_steers` sont inchangés. C'est un travail de
**libellés**, importé du contrat figé de la mini-spec backend.

---

## Comportement nominal

| État d'une précision | Avant | Après |
|---|---|---|
| `pending` | « en attente de l'étape suivante » | « en attente — lue dès la fin de l'outil en cours » |
| `applied` / `followup` / `dropped` | inchangés | inchangés |

| Erreur de flux | Avant | Après |
|---|---|---|
| `too_many_steers` | « Trop de **précisions** en attente pour ce message ; laissez-le avancer. » | « Trop de **texte** en attente pour ce message ; laissez-le avancer. » |

---

## Cas d'erreur

| # | Cas | Comportement attendu |
|---|-----|----------------------|
| E1 | `too_many_steers` reçu sur le flux | bandeau d'erreur au nouveau texte, le terminal reste utilisable |
| E2 | Statut de précision inconnu (flux d'une version antérieure) | repli sur le libellé d'attente, aucune exception |

---

## Critères d'acceptation

- **CA1** — Une précision `pending` affiche « en attente — lue dès la fin de l'outil en cours ».
- **CA2** — Les libellés `applied` (avec son étape), `followup` et `dropped` sont **inchangés**.
- **CA3** — L'erreur `too_many_steers` affiche le message de volume.
- **CA4** — Aucun changement de couleur, de police, d'espacement ni de composant : `DESIGN_SYSTEM.md`
  respecté par construction (seules des chaînes changent).

---

## Plan de test minimal

- T1 — `atelier-terminal.component.spec.ts` : les quatre états rendus, le premier au nouveau libellé
  (test existant étendu).
- T2 — `atelier.component.spec.ts` : `too_many_steers` rend le message de volume.
- T3 — `npm run build && npm test` verts.

*Isolation utilisateur : sans objet — aucun accès aux données, aucun appel réseau ajouté.*

---

## Tables / endpoints / composants impactés

| Type | Élément | Nature |
|------|---------|--------|
| Table | *aucune* | — |
| Endpoint | *aucun* | contrat importé de `SF-121-11` (backend), inchangé |
| Frontend | `atelier/terminal/atelier-terminal.component.ts` | libellé d'attente |
| Frontend | `atelier/atelier.component.ts` | message `too_many_steers` |
| Frontend | specs des deux composants | assertions alignées |

---

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---------------|----------|---------------------|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | non | — |
| Navigation / routing | non | aucune route, aucun guard, aucune redirection |

---

## Hors périmètre

- Afficher l'étiquette `[Interjection de l'utilisateur — …]` dans le fil : l'étiquette sert le
  modèle, le fil montre ce que l'utilisateur a écrit (arbitrage A3 de `SF-121-11`).
- Afficher le volume restant en attente : information de plomberie, sans usage pour l'utilisateur.

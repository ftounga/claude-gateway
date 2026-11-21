# Mini-spec — [F-273 / SF-273-03] L'agent lit l'état des sondes de son sujet (`veille_etat`)

## Identifiant

`F-273 / SF-273-03`

## Feature parente

`F-273` — Sondes de relevé : l'erreur réelle, lisible

## Statut

`in-progress`

## Date de création

2026-11-21

## Branche Git

`feat/SF-273-03-agent-lit-ses-sondes`

---

## Objectif

Que l'agent du terminal puisse **lire** l'état réel des veilles et sondes de relevé de son sujet (proposée,
testée, active, en panne, dernier passage, dernière erreur) au lieu de deviner — le 21/11, il a écrit au PO
« l'état d'erreur affiché par l'application n'est pas visible depuis le terminal » et a supposé les sondes
« activées après Tout valider », ce qui était faux.

## Comportement attendu

### Cas nominal

Outil `veille_etat` (sans paramètre), offert là où `veille_poser` l'est (terminal d'un sujet sur poste, à son
propriétaire). Il rend, pour chaque veille **du sujet du tour** (filtre `user_id + workspace_id`), une ligne :
`« <nom> » (relevé|veille) — <état> · commande <commande> · dernier passage <date> <statut> · dernière erreur
<date> : <message>` où `<état>` ∈ « proposée, jamais testée », « proposée, testée le … », « active », « en pause »,
« en panne (N échecs) ». Une ligne de conduite finale : une proposition jamais testée ne tourne pas — c'est le PO
qui la teste et la valide (« Tout valider » dans « Ce que je sais », ou la carte du fil) ; pour réparer, rappeler
`veille_poser` avec le même nom. Lecture seule, rien n'est lancé sur le poste.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal hors sujet sur poste / autre compte | refus « réponds sans cet outil » (outil non offert) | — |
| Aucune veille dans le sujet | « Aucune veille ni sonde de relevé dans ce sujet. » | — |

## Critères d'acceptation

- [ ] `veille_etat` déclaré à côté de `veille_poser`, mêmes conditions d'ouverture.
- [ ] Rend état, dernier passage et dernière erreur gardée (SF-273-01) des veilles du seul sujet du tour.
- [ ] Une veille d'un autre sujet ou d'un autre compte n'apparaît jamais.
- [ ] Déclaré dans `jarvis-scene-registry.json` (garde `JarvisSceneRegistryGuardTest`).

## Périmètre

### Hors scope (explicite)

- Tester ou activer une sonde par l'agent (la validation reste au PO, F-230 D5).
- Veilles des autres sujets du poste.

## Contraintes de validation

Aucun paramètre.

## Technique

### Endpoint(s)

Aucun (outil d'agent, boucle d'atelier).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| jarvis_watches | SELECT | `user_id + workspace_id` |

### Migration Liquibase

- [x] Non applicable

## Plan de test

### Tests unitaires

- [ ] `JarvisWatchToolCatalog` — deux outils offerts sur un sujet sur poste, aucun ailleurs ; `isWatchTool` couvre les deux.

### Tests d'intégration

- [ ] `JarvisWatchApiIntegrationTest` — `veille_etat` rend la proposition jamais testée puis l'erreur gardée ; la veille d'un autre sujet n'apparaît pas.

### Isolation workspace

- [x] Applicable — veille d'un autre sujet / compte absente du rendu.

## Dépendances

- SF-273-01 (champs `last_error_*`) — mergée avant.

## Notes et décisions

- Ajouter un outil touche le préfixe stable des outils (cache) une seule fois : définition littérale, sans donnée
  volatile (règle F-134).
- Préoccupations transversales : aucune.

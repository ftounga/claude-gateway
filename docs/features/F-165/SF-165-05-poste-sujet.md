# Mini-spec — F-165 / SF-165-05 — `/poste` + `/sujet` : le poste et le projet courant

## Identifiant

`F-165 / SF-165-05`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-05-poste-sujet`

---

## Objectif

> En une phrase : ajouter deux commandes slash **vues** — **`/poste`** (état du runner du poste :
> connecté / vu il y a X, OS, shell, racine) et **`/sujet`** (carte du projet courant : nom, client/poste,
> moteur, nb tours, mode, plan) — **sans aucun tour modèle**, en **réutilisant les services existants**
> (`runnerHosts()` isolé `user_id` ; `getResume()` isolé `user_id`), sans nouvel endpoint ni table.

---

## Comportement attendu

### Cas nominal — `/poste`

1. Taper `/poste` et valider : interception AVANT tout `send`, **aucun tour**.
2. Si le projet n'a **pas de poste** (`hostId` absent : terminal hébergé/orphelin) → panneau en état
   **« pas de poste »** (message neutre), aucun appel.
3. Sinon le dispatch appelle **`GET /api/runner-hosts`** (`AtelierService.runnerHosts()`, existant, isolé
   `user_id`), **retient la ligne du poste courant** (`hostId`) et rend un panneau `panelKind: 'poste'` :
   - **client/poste** (nom) ;
   - **connecté** ou **hors ligne**, + **« vu il y a X »** (`lastSeenAt` relatif) ;
   - **OS**, **shell**, **racine** (`rootName`), **élévation** si applicable.

### Cas nominal — `/sujet`

1. Taper `/sujet` et valider : interception AVANT tout `send`, **aucun tour**.
2. Le dispatch appelle **`GET /api/workspaces/{id}/chat/resume`** (`AtelierService.getResume()`, existant,
   isolé `user_id` + `requireOwned`) et rend un panneau `panelKind: 'sujet'` :
   - **nom du projet** et **client/poste** (déjà connus de l'écran) ;
   - **moteur** (poste / hébergé) ;
   - **nombre de tours** rejouables, **mode** du fil (Agir / Plan), **plan** (nombre d'étapes) ;
   - **nouveau départ** posé ou non (frontière du fil).

### Décisions de câblage (aucune nouvelle route, aucune table)

- `/poste` = **état du runner** via `runnerHosts()` (la liste des postes de l'utilisateur, isolée
  `user_id`, qui porte **OS** et **shell** — contrairement à `RunnerStatus`). On y retient la ligne du
  `hostId` courant. Les « sujets » du poste au sens F-141 = les **projets** ; la carte se concentre sur
  l'**état** (cadrage §4 : connecté / vu il y a X, OS, shell), la liste des projets restant du ressort de
  la Forge — voir §Notes.
- `/sujet` = **carte du projet courant** au sens **F-141** (un « sujet » = le dossier/projet courant),
  composée des infos déjà à l'écran (nom, client, moteur) et de `getResume()` (tours, mode, plan). Le
  **coût total** reste du ressort de `/cout` (pas de double lecture) — voir §Notes. Le « sujet » **Radar**
  (F-99, transverse, **Vigie**) est hors de la portée du terminal Forge.

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| `/poste` : aucun poste (`hostId` absent) | Panneau **« pas de poste »** neutre ; aucun appel, aucun tour |
| `/poste` : poste introuvable dans la liste | Même état neutre « pas de poste » |
| `/poste` : gateway muette / erreur réseau | Panneau en **échec** neutre ; aucun tour |
| `/sujet` : aucun `projectId` connu | Panneau en **échec** neutre ; aucun appel |
| `/sujet` : projet d'autrui | `requireOwned` → **404** ; panneau en **échec** neutre, jamais l'état d'autrui |
| `/sujet` : gateway muette / erreur | Panneau en **échec** neutre ; aucun tour |
| Terminal en **lecture seule** (F-83) | Pas de composer → commandes impossibles (inchangé SF-165-01) |

---

## Critères d'acceptation

- [ ] `/poste` et `/sujet` figurent au registre `SLASH_PANEL_COMMANDS` (famille **Vue**, `panelKind`
      `poste` / `sujet`) et apparaissent dans l'autocomplétion et dans `/aide`.
- [ ] Valider `/poste` ou `/sujet` **n'émet jamais `send`** (garantie « aucun tour ») — prouvé par un test.
- [ ] `/poste` retient la ligne du **`hostId` courant** depuis `runnerHosts()` et rend état / OS / shell /
      « vu il y a X » ; sans poste → état neutre, aucun appel — prouvé par un test.
- [ ] `/sujet` appelle `GET .../chat/resume` et rend nom / client / moteur / tours / mode / plan — prouvé
      par un test.
- [ ] **Aucun nouvel endpoint, aucune table, aucune migration** : réutilisation stricte de l'existant.
- [ ] **Isolation** : `/poste` (`runnerHosts()`) et `/sujet` (`getResume()`) isolés `user_id` (JWT) par les
      endpoints existants ; `/sujet` sur un projet d'autrui → **404** ; jamais l'état/poste d'autrui.
- [ ] Les panneaux se **ferment** (SF-165-01) et restent **hors** `displayedMessages`.
- [ ] **Design** : jetons `--cg-*` ; `tabular-nums` ; ≥ 44 px ; aucun débordement à 390 px (SF-158).
- [ ] **Non-régression** : `/aide`, `/cout`, `/contexte`, `/quota`, `/budget`, `/revue`, message ordinaire,
      autocomplétion `@`, dictée, steer, porte, `demander` restent intacts.
- [ ] Builds **verts** : `npm run build` + Karma ciblé front. (Backend : aucun changement.)

---

## Périmètre

### Hors scope (explicite)

- Les actions `/compacter`, `/nouveau`, `/rappel` (SF-165-06).
- La **liste détaillée des projets** du poste dans `/poste` (état seulement ; la Forge liste les projets).
- Le **coût total** dans `/sujet` (du ressort de `/cout`, pas de double lecture).
- Le **sujet Radar** (F-99, transverse, Vigie) — hors du terminal Forge.
- Toute **nouvelle table**, migration ou endpoint.
- Toute logique de **moteur IA**.

---

## Technique

### Endpoint(s)

**Aucun endpoint créé.** Réutilisation en lecture :

| Méthode | Route (existante) | Service front | Isolation |
|---------|-------------------|---------------|-----------|
| `GET` | `/api/runner-hosts` | `AtelierService.runnerHosts()` | `user_id` (JWT) |
| `GET` | `/api/workspaces/{id}/chat/resume` | `AtelierService.getResume()` | `user_id` + `requireOwned` |

### Tables impactées

Aucune. **Aucune migration Liquibase.**

### Composants / fichiers

**Frontend**
| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (modifié) | Entrées registre `/poste` + `/sujet` ; types d'état + `ThreadPosteSummary` / `ThreadSujetSummary` ; champs `SlashPanel` ; `buildPanel` |
| `atelier/terminal/atelier-slash-poste.component.ts` (+ html/scss, nouveau) | Corps `/poste` (état, OS, shell, « vu il y a X ») |
| `atelier/terminal/atelier-slash-sujet.component.ts` (+ html/scss, nouveau) | Corps `/sujet` (nom, client, moteur, tours, mode, plan) |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Dispatch `/poste` (runnerHosts + filtre hostId) et `/sujet` (getResume) ; imports |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | `@case ('poste')` + `@case ('sujet')` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Frontend (Karma ciblé)

- [ ] `slash-panel-commands.spec.ts` (ajouts) : `/poste` + `/sujet` au registre, suggestions, interception,
      `buildPanel`.
- [ ] `atelier-slash-poste.component.spec.ts` (nouveau) : chargement / « pas de poste » / échec / prêt ;
      connecté vs hors ligne ; OS/shell rendus ; « vu il y a X ».
- [ ] `atelier-slash-sujet.component.spec.ts` (nouveau) : chargement / échec / prêt ; nom/client/moteur ;
      tours/mode/plan.
- [ ] `atelier-terminal.component.spec.ts` (ajouts) : `/poste` **n'émet pas `send`** ; avec `hostId` → un
      `GET /api/runner-hosts`, panneau `ready` sur la bonne ligne ; sans `hostId` → **aucun appel**, état
      « pas de poste ». `/sujet` **n'émet pas `send`** ; un `GET .../resume`, panneau `ready` ; sans projet
      → `error`.

### Tests d'intégration / backend

- [x] **Aucun** — SF-165-05 n'ajoute aucun endpoint. Les routes réutilisées (`/api/runner-hosts`,
  `/api/workspaces/{id}/chat/resume`) sont déjà couvertes par leurs tests d'intégration existants,
  isolation comprise.

### Isolation utilisateur

- [x] Applicable — portée par les endpoints existants : `runnerHosts()` isolé `user_id` (ne renvoie que
  les postes de l'utilisateur) ; `getResume()` isolé `user_id` + `requireOwned` (404 sur un projet
  d'autrui). **Aucune nouvelle route.**

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés / listés |
|--------------|--------|------------------------------|
| Auth / Principal | Aucun changement. Lectures via le JWT existant | `AtelierService.runnerHosts` / `getResume` (inchangés) |
| Contexte tenant | **Aucun nouvel accès données** : endpoints existants isolés `user_id` (+ `host_id`/`workspace_id`) | `runnerHosts()` (filtre `user_id`), `getResume()` (`requireOwned`) |
| Plans / limites | Aucun gate touché ; commandes **gratuites** (aucun tour) | `submit()` (interception avant gate, inchangé) |
| Navigation / routing | Aucune route Angular ajoutée/modifiée (panneaux locaux) | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-165-01** (socle) — **livrée**. **SF-165-02/03/04** (patrons de panneaux) — **livrées**.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **`/poste` = état du runner via `runnerHosts()`** (et non `getRunnerStatus`) : c'est la lecture existante
  qui porte **OS** et **shell** (le DTO `RunnerStatus` ne les porte pas), isolée `user_id`. La carte se
  concentre sur l'**état** (cadrage §4 : connecté / vu il y a X, OS, shell), la liste des projets restant
  du ressort de la Forge (pas de doublon).
- **`/sujet` = le projet courant (sens F-141)** : le « sujet » est le dossier/projet courant. La carte
  compose ce que l'écran connaît déjà (nom, client, moteur) et `getResume()` (tours, mode, plan). Le
  **coût total** est délibérément laissé à `/cout` (éviter une double lecture). Le « sujet » **Radar**
  (F-99, transverse, **Vigie**) est hors de la portée du terminal Forge — pas de résolution Vigie ici.
- **Aucun changement backend** : SF-165-05 est **100 % frontend**, réutilisation stricte d'endpoints déjà
  testés (isolation comprise).
- **Garantie « aucun tour »** conservée : `dispatchPanelCommand` n'émet jamais `send.emit()` ; `/poste` et
  `/sujet` sont des GET de lecture rendus localement, hors `displayedMessages`.
- **Gateway-First / Provider-First** : des vues sur NOS données (statut runner, état du fil) ; aucune
  capacité de Claude réimplémentée.
- **Aucune incohérence `ARCHITECTURE_CANONIQUE.md`** : aucune table, aucun endpoint.

# Mini-spec — F-124 / SF-124-04 — CRA en langage naturel enrichi : plages de dates + « tous/chaque client(s) »

## Identifiant

`F-124 / SF-124-04`

## Feature parente

`F-124` — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-124-04-cra-plages-et-tous-clients`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre le CRA par message (SF-124-03) réellement naturel : comprendre **une plage de dates**
(« du 10 à la fin du mois », « tout le mois », « du 10 au 20 », « 2ᵉ quinzaine ») convertie en
**jours ouvrés côté serveur** (déterministe, `WorkdayCalendar`), et résoudre **« tous mes
clients »/« chaque client »/« partout »** vers **tous les postes possédés** — en fournissant au
modèle la liste des postes de l'utilisateur (isolation `user_id`).

---

## Comportement attendu

### Cas nominal

1. L'utilisateur écrit un message libre dans le dialogue « CRA » de la Forge, par ex.
   « En août, du 10 jusqu'à la fin du mois chez tous mes clients ».
2. `POST /activity/cra {message}` → la Gateway fournit au **modèle** (interface `AIProvider`,
   Provider-First) la **liste des postes possédés** dans la consigne système et lui demande, par
   ligne, SOIT un `days` (comme avant), SOIT une **plage** normalisée (`preset` / `fromDay` /
   `toDay`, ou dates ISO `from`/`to`). Le modèle **ne compte pas** les jours.
3. « Tous mes clients / chaque client / partout » → le modèle génère **une ligne par poste connu**
   (liste fournie), même période.
4. La Gateway **convertit chaque plage en jours ouvrés** de façon **déterministe** (`WorkdayCalendar`,
   lun-ven hors fériés France), puis rapproche, valide (jours ≤ jours ouvrés du mois, 0,5 admis),
   **persiste** (écrase pour un mois) et **récapitule** chaque ligne — avec la **plage comprise** et
   les **jours ouvrés déduits** pour transparence.
5. Rétrocompat : « Free 20 jours » (nombre direct) continue de marcher à l'identique.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Nom de client **non reconnu** | Ligne `unknown`, **demandée**, jamais devinée ; rien écrit | 200 (récap) |
| Jours (issus d'un nombre **ou** d'une plage) **> jours ouvrés** du mois | Ligne `rejected` (motif), rien écrit | 200 (récap) |
| Plage vide (que week-ends/fériés) ou `fromDay > toDay` | Ligne `rejected` (0 jour), rien écrit | 200 (récap) |
| Ni `days` ni `range` exploitable pour une ligne | Ligne `rejected` | 200 (récap) |
| Message vide | Refus | 400 |
| Le provider ne renvoie rien d'exploitable | Récap vide, rien écrit | 200 |
| Non authentifié / sans droit Forge | Refus | 401 / 403 |

---

## Critères d'acceptation

- [ ] « du 10 à la fin du mois chez tous mes clients » (août) → **une ligne par poste possédé**,
  `days` = jours ouvrés du 10 au 31 (fériés France déduits, ex. 15/08).
- [ ] « tout le mois chez Free » → jours ouvrés **complets** du mois visé.
- [ ] « du 10 au 20 chez KG » → jours ouvrés de l'intervalle (fériés déduits).
- [ ] Rétrocompat : « Free 20 jours » écrit toujours 20 j.
- [ ] La conversion plage → jours ouvrés est **serveur** (déterministe) — le modèle ne compte jamais.
- [ ] « tous mes clients » n'expose au modèle **QUE les postes de l'utilisateur** (isolation) : le
  poste d'un autre user n'apparaît jamais dans la consigne ni n'est écrit.
- [ ] Un nom non reconnu → ligne `unknown` (jamais devinée) ; jours > ouvrés → ligne `rejected` nommée.
- [ ] L'extraction passe par `AIProvider` (aucune dépendance directe à Anthropic).

---

## Périmètre

### Hors scope (explicite)

- Modifier le **calcul du cumul** (SF-124-02) — cette SF n'écrit que `cra_entries`.
- Toute facturation, historique/journal des CRA, édition ligne à ligne en tableau.
- Réimplémenter un NLP maison (Provider-First : le modèle extrait, la Gateway convertit/valide/persiste).
- Reconnaissance de plages **multi-mois** dans une seule ligne (une ligne = un mois ; le serveur
  résout la plage **dans le mois visé**).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `message` | Oui | non vide, borné (4000 car.) | trim |
| nom → poste | — | rapproché par nom/alias, insensible casse/accents ; **inconnu → demandé** | — |
| `month` | — | `YYYY-MM` ; défaut mois courant | — |
| `days` (nombre) | — | `0 < d ≤ ouvrés(mois)`, multiples de 0,5 | arrondi 1 décimale |
| `range.preset` | — | `FULL_MONTH` \| `FIRST_HALF` \| `SECOND_HALF` | — |
| `range.fromDay` / `toDay` | — | jour du mois `1..len`, clampé ; `fromDay ≤ toDay` | — |
| `range.from` / `to` | — | dates ISO `YYYY-MM-DD` (résolues dans le mois de `from`) | — |
| jours issus d'une plage | — | comptés **serveur** via `WorkdayCalendar` ; `0 < d ≤ ouvrés(mois)` | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Droit |
|---------|-----|------|-------|
| POST | `/activity/cra` | JWT | Forge (`requireRunnerAccess`) — **inchangé** |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `cra_entries` (SF-124-02) | INSERT / UPDATE (écrase) | `(user_id, host_id, year_month)` |
| `runner_hosts` | SELECT (liste des postes → prompt + rapprochement, filtré `user_id`) | — |

### Migration Liquibase

- [ ] **Non applicable** — aucun changement de schéma (`cra_entries` existe, migration `110`).

### Composants Java impactés

- `activity/cra/CraExtraction.java` — ajout d'une **plage** (`CraRange`) optionnelle par ligne.
- `activity/cra/CraRange.java` — **nouveau** : la plage extraite (`preset`/`fromDay`/`toDay`/`from`/`to`).
- `activity/cra/CraExtractionParser.java` — lecture tolérante de `range` (nested ou plat), rétrocompat `days`.
- `activity/cra/CraService.java` — postes fournis au prompt ; conversion plage → jours ouvrés
  (déterministe) ; `period` (libellé compris) dans le récap.
- `activity/WorkdayCalendar.java` — ajout `businessDaysBetween(from, to)` (inclusif).
- `activity/dto/CraRecapResponse.java` — champ `period` par ligne.

### Composants Angular impactés

- `core/services/poste-billing.service.ts` — type `CraLine` : champ `period`.
- `postes/cra-dialog/cra-dialog.component.ts` / `.html` — afficher la plage comprise + jours déduits.
  Charte, **aucune couleur nouvelle** (pastilles §5 réutilisées).

---

## Plan de test

### Tests unitaires (backend)

- [ ] `WorkdayCalendarTest` — `businessDaysBetween` : intervalle simple, week-ends/fériés déduits,
  `from > to` → 0, intervalle d'un seul jour.
- [ ] `CraExtractionParserTest` — parse `range` (preset, fromDay/toDay, dates ISO) ; rétrocompat `days`.
- [ ] `CraServiceTest` — plage « du 10 à fin de mois » (août) → jours ouvrés ; « tout le mois » ;
  « du 10 au 20 » ; rétrocompat `days` ; plage > ouvrés **refusée** ; la liste des postes est
  **injectée dans la consigne système** (capture de la requête provider) ; « tous mes clients »
  (une ligne par poste, provider mocké) écrit chaque poste.

### Tests d'intégration (backend)

- [ ] `CraApiIntegrationTest` — plage → jours ouvrés persistés ; **isolation** : la consigne système
  d'Alice ne contient **jamais** le poste de Bob (`ArgumentCaptor<ChatCompletionRequest>`), et un
  message n'écrit jamais sur le poste d'un autre.

### Isolation user_id

- [x] Applicable — la liste des postes fournie au modèle et le rapprochement/persistance sont filtrés
  `user_id` (+ `host_id`) ; testé (unitaire + intégration).

### Tests frontend

- [ ] `cra-dialog` — affichage d'une ligne avec plage comprise + jours déduits (build/tests verts).

---

## Préoccupations transversales — analyse d'impact

- **Auth / Principal** : endpoint `POST /activity/cra` **inchangé** (chaîne JWT + `requireRunnerAccess`).
  Composant : `CraController` (non modifié). Aucun autre endpoint touché.
- **Contexte tenant** : la **liste des postes fournie au modèle** devient un nouveau lieu de lecture
  du tenant — elle vient **exclusivement** de `RunnerHostService.list(userId)` (déjà filtré `user_id`).
  Rapprochement et persistance filtrés `(user_id, host_id)`. Composants vérifiés : `CraService`
  (liste + rapprochement + persistance), `RunnerHostService.list`, `CraEntryRepository`. Test
  d'isolation ajouté (consigne système + écriture).
- **Plans / limites** : le tour d'extraction consomme **un** appel fournisseur (quota existant,
  `assertWithinQuota` avant / `recordUsage` après) — **inchangé**. Pas de nouveau gate.
- **Provider-First / Provider Independence** : extraction via `AIProvider` ; la Gateway **ne compte
  pas** via le modèle — elle convertit les plages elle-même (`WorkdayCalendar`). Aucun NLP maison.
- **Navigation / routing** : **aucune** route/guard ajoutés (dialogue existant). Les 4 terminaux
  ne sont pas touchés.

---

## Notes et décisions

- **Conversion déterministe** : le modèle **décrit** la période (preset / jour de début / jour de
  fin, ou dates ISO) ; le **serveur** compte les jours ouvrés (`WorkdayCalendar`), source unique de
  vérité pour lun-ven hors fériés France. C'est la garantie anti-erreur voulue par le PO.
- **Une ligne = un mois** : une plage est résolue **dans le mois visé** de la ligne (mois de `from`
  si dates ISO, sinon `month`/mois courant). Les plages multi-mois d'une seule ligne sont hors scope.
- **« Tous mes clients »** : résolu **par le modèle** grâce à la liste fournie (une ligne par poste),
  puis chaque ligne suit le même chemin rapprochement/validation. La liste ne contient que les
  postes du user (isolation).
- F-124 était **Terminée** (SF-124-01→03) ; SF-124-04 est un **complément** d'enrichissement du CRA.
  À la clôture, F-124 reste **Terminée** (SF-124-01→04).

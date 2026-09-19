# Mini-spec — [F-132 / SF-132-03] Panneau « Journal du runner » (Vigie)

## Identifiant

`F-132 / SF-132-03`

## Feature parente

`F-132` — Observabilité du runner (journal de diagnostic)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-132-03-panneau-journal-runner`

---

## Objectif

> En une phrase : offrir dans la Vigie, par poste, un panneau **« Journal du runner »** qui lit `GET /runner-hosts/{hostId}/diag` (SF-132-02) et affiche les événements de diagnostic (niveau, horodatage, catégorie/code, message), avec **filtre par niveau**, **recherche simple** et **rafraîchissement**.

---

## Comportement attendu

### Cas nominal

1. Sous chaque poste de la Vigie, un composant autonome `app-runner-diag-journal [hostId]` se charge lui-même (comme `app-vigie-readiness`) : à l'affichage du poste, il lit les derniers événements du poste.
2. Chaque ligne montre : un **badge de niveau** (DEBUG/INFO/WARN/ERROR — classes de badge **existantes**, aucune couleur nouvelle), l'**horodatage** (relatif ou daté), la **catégorie** et le **code**, et le **message** court s'il existe.
3. Un **filtre par niveau** (Tous / INFO / WARN / ERROR / DEBUG) recharge la liste au **niveau minimum** choisi (côté serveur, `?level=`).
4. Une **recherche simple** filtre la liste affichée (client) sur le texte (code, catégorie, message, niveau).
5. Un bouton **Rafraîchir** relit la liste.
6. États : chargement (spinner), liste vide (« Aucun événement »), échec (message d'erreur, aucune donnée exposée).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| L'appel `GET …/diag` échoue (404/403/500) | État d'échec discret (message « Journal indisponible »), aucune donnée partielle affichée ; le reste de la Vigie n'est pas impacté. |
| Liste vide | Message « Aucun événement de diagnostic » (pas une erreur). |
| Recherche sans résultat | Liste filtrée vide + note « Aucun événement ne correspond ». |

---

## Critères d'acceptation

- [ ] Le composant `app-runner-diag-journal` se crée et charge les événements d'un poste via `VigieService.runnerDiag(hostId)`.
- [ ] Chaque ligne affiche niveau (badge), horodatage, catégorie/code et message.
- [ ] Le filtre par niveau recharge au niveau minimum choisi (paramètre `level`).
- [ ] La recherche filtre la liste affichée (client) et le vide est géré.
- [ ] Le bouton Rafraîchir relit la liste.
- [ ] Un échec API affiche un état discret sans casser la Vigie (test).
- [ ] **Charte** : uniquement des couleurs/polices/espacements du `DESIGN_SYSTEM.md` (badges `.badge--error/--warning/--info/--neutral` existants ; pas de `window.alert`).

---

## Périmètre

### Hors scope (explicite)

- L'émission (SF-132-01) et le stockage/exposition (SF-132-02) — livrés.
- Le réglage du niveau **par poste** (passage DEBUG) — SF-132-05 (ici, filtre de **lecture** seulement).
- Le snapshot à la demande (SF-132-04, option).
- Une page dédiée / export du journal.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `level` (filtre) | valeurs `''`(Tous)/`DEBUG`/`INFO`/`WARN`/`ERROR` |
| `search` | texte libre, comparaison insensible à la casse, `trim` |

---

## Technique

### Endpoint(s) consommé(s)

- `GET /api/runner-hosts/{hostId}/diag?level=&limit=` (SF-132-02) — JWT existant.

### Tables impactées

Aucune (frontend).

### Migration Liquibase

- [ ] Non applicable.

### Composants Angular

- **`RunnerDiagJournalComponent`** (`app-runner-diag-journal`, standalone, signals) — panneau auto-chargeant par poste : liste + filtre niveau + recherche + rafraîchir.
- **`VigieService.runnerDiag(hostId, opts?)`** — nouvelle méthode (`Observable<RunnerDiagEntry[]>`).
- **Modèle** `core/models/runner-diag.models.ts` — `RunnerDiagLevel`, `RunnerDiagEntry`.
- **`VigieComponent`** (template) — insertion de `<app-runner-diag-journal [hostId]>` sous `<app-vigie-readiness>`.

---

## Plan de test

### Tests unitaires (Karma/Jasmine, `HttpClientTesting`)

- [ ] `VigieService.runnerDiag` — émet `GET /api/runner-hosts/{id}/diag` ; avec `level` → paramètre `level` présent.
- [ ] `RunnerDiagJournalComponent` — `should create` ; charge et rend les lignes (badge/niveau/message).
- [ ] Filtre niveau → re-fetch au bon niveau ; recherche → filtre la liste ; vide géré.
- [ ] Échec API → état d'échec, aucune ligne, pas d'exception.

### Tests d'intégration

- N/A (frontend ; le contrat serveur est couvert par SF-132-02).

### Isolation utilisateur

- [x] Non applicable côté composant — l'isolation `user_id`+`host_id` est garantie par l'endpoint (SF-132-02). Le composant n'envoie que `hostId` (déjà possédé, affiché dans la Vigie de l'utilisateur).

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|--------------|-----------|-----------|
| Auth / Principal | Non (JWT existant, intercepteur inchangé) | — |
| Contexte tenant | Non (isolation côté serveur SF-132-02) | — |
| Plans / limites | Non | — |
| **Navigation / routing** | **Non** — pas de nouvelle route : le panneau s'insère dans l'écran Vigie existant (`VigieComponent`), sous le poste ouvert. Aucun guard ni redirection modifiés. Composants vérifiés : `VigieComponent` (insertion), `app.routes.ts` (inchangé). |

---

## Notes et décisions

- **Composant auto-chargeant** (input `hostId`, `refresh()` sur changement de poste), calqué sur `VigieReadinessComponent` — cohérent avec les voisins per-poste (`app-radar-schedule`, `app-host-mail-address`).
- **Filtre niveau côté serveur** (re-fetch), **recherche côté client** (sur la page déjà chargée) : simple, sans nouvel endpoint.
- **Aucune couleur nouvelle** : réutilise les badges `.badge--*` de `styles.scss` (charte).

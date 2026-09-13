# Mini-spec — [F-89 / SF-89-04] Un terminal Teams sans droit le dit

---

## Identifiant

`F-89 / SF-89-04`

## Feature parente

`F-89` — Le volet Teams : le terminal Teams (mini-specs SF-89-01→03)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-89-04-terminal-teams-sans-droit`

---

## Objectif

Dans un terminal Teams dont le compte n'a pas le droit Teams, l'agent **dit** que le volet n'est pas
actif (au lieu de chercher sur la machine) et l'écran affiche un bandeau « Option Teams non active »
avec les gestes pour l'ouvrir.

---

## Contexte

Constat de production du 2026-09-13 : un terminal Teams (`workspaces.teams_terminal = true`) survit à
la fin d'un essai. Sans droit, `TeamsToolCatalog.toolsFor` rend une liste vide **en silence** (règle de
SF-89-01 : « l'agent n'a pas la capacité ») ; l'agent, qui ne sait pas où il est, répond comme un
terminal ordinaire (`bash`, `find` sur la machine). L'utilisateur croit à un défaut de comportement.
La règle de SF-89-01 est **conservée** (aucun outil `teams_*` sans droit) : ce qui manque, c'est la
**parole**.

---

## Comportement attendu

### Cas nominal

1. **Consigne système** (`AtelierChatService.buildSystemPrompt`) : quand
   `TeamsToolCatalog.isClosedFor(userId, workspace)` est vrai — workspace terminal Teams **et** droit
   fermé —, la consigne porte, juste après l'énoncé du rôle, le paragraphe
   `TeamsToolCatalog.CLOSED_NOTICE` : le volet Teams n'est pas actif sur ce compte ; ne pas chercher
   la réponse sur la machine à la place ; le dire à l'utilisateur et indiquer l'essai par code
   d'accès ou l'option Teams (écran Facturation).
2. Terminal Teams **avec** droit, ou terminal de projet : consigne **inchangée** (aucun paragraphe).
3. **Écran** (`AtelierComponent`) : à l'ouverture d'un terminal Teams, `GET /api/teams/access`
   (existant) ; `entitled = false` → signal `teamsOptionInactive = true`, transmis au terminal.
4. **Bandeau** (`AtelierTerminalComponent`, entrée `teamsOptionInactive`) : sous l'en-tête, rôle
   `status`, titre « Option Teams non active », phrase « Ce terminal ne peut pas lire Teams sur ce
   compte. L'historique reste lisible. », deux gestes : « Saisir un code d'accès » (→ `/billing#code-acces`)
   et « Voir la facturation » (→ `/billing`). Le fil et l'historique restent affichés, l'envoi n'est
   pas bloqué. Registre visuel existant du refus dit (F-70, filet `--cg-accent`) — aucune couleur nouvelle.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /api/teams/access` en échec (réseau, 403 Forge) | Pas de bandeau (on n'affirme pas un refus qu'on ne connaît pas) | — |
| Réponse arrivée après changement de projet | Ignorée (le bandeau suit le projet ouvert) | — |
| Catalogue `none()` (appelants sans volet Teams) | Aucun paragraphe (comportement d'avant) | — |
| Terminal en lecture seule (mosaïque) | Pas de bandeau (pas de chrome en lecture seule) | — |

---

## Critères d'acceptation

- [ ] CA1 — Terminal Teams sans droit : la consigne système contient `CLOSED_NOTICE` (« n'est pas actif », « ne cherche pas » sur la machine, code d'accès).
- [ ] CA2 — Terminal Teams avec droit : pas de `CLOSED_NOTICE`, outils `teams_*` présents.
- [ ] CA3 — Terminal de projet : pas de `CLOSED_NOTICE`, et `hasAccess` n'est même pas consulté.
- [ ] CA4 — Écran : terminal Teams + `entitled=false` → bandeau « Option Teams non active » avec les deux gestes ; historique toujours rendu.
- [ ] CA5 — Écran : `entitled=true`, terminal de projet, ou erreur d'appel → pas de bandeau.

---

## Périmètre

### Hors scope (explicite)

- Tunnel de souscription de l'option Teams (inexistant ; SF-107-03 / option Vigie) : le geste mène à la Facturation.
- Blocage de l'envoi dans un terminal Teams sans droit.
- Changement de la règle de SF-89-01 (outils absents sans droit).

---

## Valeurs initiales

Aucune.

## Contraintes de validation

Aucun champ saisi.

---

## Technique

### Endpoint(s)

Aucun nouveau ; réemploi de `GET /api/teams/access`.

### Tables impactées

Aucune (lecture de `workspaces.teams_terminal` et du droit existant). **Aucune migration.**

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

`AtelierComponent` (effet + signal `teamsOptionInactive`, gestes), `AtelierTerminalComponent`
(entrée `teamsOptionInactive`, sortie `openAccessCode`, bandeau), feuille `atelier-terminal-teams.component.scss`.

### Préoccupations transversales

- [ ] Plans / limites — non : aucun gate nouveau, lecture du droit existant (`TeamsAccessService.hasAccess(userId)`).
- [ ] Navigation / routing — non : gestes vers la route existante `/billing` (et son ancre existante `code-acces`), aucune route ni garde modifiée.
- [ ] Auth / Principal — non. Contexte tenant — non.

---

## Plan de test

### Tests unitaires

- [ ] `TeamsToolCatalogTest` — `isClosedFor` : Teams sans droit → vrai ; avec droit → faux ; projet → faux sans consulter le droit ; `none()` → faux.
- [ ] `AtelierChatServiceTeamsToolsTest` — consigne : Teams sans droit → contient `CLOSED_NOTICE` ; avec droit / projet → non.
- [ ] `terminal-teams.spec.ts` — bandeau présent avec ses gestes et l'historique ; absent sans l'entrée ou hors terminal Teams ; les gestes émettent.
- [ ] `atelier.component.spec.ts` — terminal Teams `entitled=false` → `teamsOptionInactive` ; `entitled=true` / erreur → faux.

### Tests d'intégration

- [ ] `TeamsTerminalApiIntegrationTest` (existant, `GET /api/teams/access`) vert — contrat inchangé.

### Isolation workspace

- [x] Applicable — le droit est lu pour le `userId` du tour (consigne) et du contexte de sécurité (écran) ; aucune donnée d'un autre utilisateur.

---

## Dépendances

### Subfeatures bloquantes

SF-89-01→03 (Done). Cohérent avec SF-107-06 (un administrateur n'est jamais « sans droit »).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Parole plutôt qu'outil refusant** : SF-89-01 interdit un outil `teams_*` qui refuserait ; la consigne dit l'état sans donner d'outil.
- **Consigne stable** : le paragraphe ne dépend que du droit ; il ne varie pas d'un tour à l'autre tant que le droit ne change pas (préfixe cacheable conservé).

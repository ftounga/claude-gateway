# Mini-spec — F-89 / SF-89-01 — Le terminal Teams dans le modèle, et le droit qui l'ouvre

## Identifiant

`F-89 / SF-89-01`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams

## Statut

`done` — mergée le 2026-09-13 (PR #459)

## Date de création

2026-09-12

## Branche Git

`feat/SF-89-01-terminal-teams-et-droit`

---

## Objectif

Donner au volet Teams **son propre terminal** — un workspace comme les autres, marqué
`teams_terminal`, rattaché à un poste — et poser **le droit qui l'ouvre** : sans l'option Teams, ni
le terminal ni les outils `teams_*` n'existent pour cet utilisateur.

---

## Contexte

F-74 a créé le **terminal du poste** en marquant un workspace d'un booléen (`host_terminal`) plutôt
qu'en créant une table. Le cadrage du volet Teams (§5.1) demande la même forme pour le terminal
Teams : *« même mécanique — le fil, les tours, la reprise (F-84), la place au registre (F-70),
l'usage compté par client (F-61) — mais son propre historique, sa propre peau, ses propres
blocs. »*

**La voie de F-74 a été vérifiée, pas supposée.** Tout ce qui fait un terminal pend déjà à
`workspace_id` : `atelier_messages`, la frontière de rejeu (`chat_thread_started_at`), la session
d'agent, la porte de confirmation (`agent_ask_before_bash`), le journal du runner, le registre des
terminaux vivants (`live_terminals.workspace_id`, F-70), le relevé d'usage par tour
(`usage_turns.workspace_id` + `host_id`, F-61). Une table dédiée obligerait à un second chemin pour
chacun. **Un second booléen est donc la bonne voie**, et pour la même raison qu'en F-74 : chaque
ligne et chaque lecture existantes restent justes par construction.

**Pourquoi pas `host_terminal` réutilisé, ou une énumération `kind`.** Le terminal du poste et le
terminal Teams ne sont pas la même chose : le premier ouvre un shell à la racine de la machine, le
second ne parle jamais à un shell et affiche des blocs qu'un terminal de projet n'affichera jamais
(SF-89-02). Les confondre ferait apparaître des cartes dans le terminal du poste — exactement ce que
la règle non négociable du cadrage interdit. Une énumération `kind` obligerait à relire toutes les
requêtes existantes pour y ajouter `kind = 'PROJECT'` ; un booléen à `false` par défaut ne le
demande pas.

---

## Comportement attendu

### Cas nominal — ouvrir le terminal Teams d'un poste

1. L'utilisateur a un poste appairé **et** le droit Teams (voir ci-dessous). Il demande le terminal
   Teams de ce poste : `POST /api/runner-hosts/{hostId}/teams-terminal` (corps vide).
2. La gateway exige le droit Teams (`teamsAccess.requireAccess()`), puis vérifie l'**appartenance**
   du poste (`requireOwned` — 404 sinon, sans oracle d'existence).
3. Si un terminal Teams existe déjà pour ce `(user_id, host_id)`, elle le **rend tel quel**.
4. Sinon elle en crée un, dans la même transaction :

   | Champ | Valeur | Pourquoi |
   |---|---|---|
   | `name` | `Terminal Teams` | se lit sans explication dans le relevé F-61 et au registre F-70 |
   | `user_id` | l'appelant | racine d'isolation |
   | `host_id` | le poste | c'est la machine dont le navigateur est observé (F-87) |
   | `project_path` | `""` (la racine) | il n'y a pas de projet : on lit Teams, pas des fichiers |
   | `source` | `LOCAL` | rien n'est déposé dans un stockage de workspace |
   | `execution_target` | `RUNNER` | la liaison Teams n'existe que sur la machine |
   | `agent_ask_before_bash` | `true` | la porte de F-73 / SF-73-02 reste armée |
   | `teams_terminal` | `true` | ce n'est ni un projet, ni le terminal du poste |

5. Réponse **`200`** avec le `WorkspaceDetailResponse` habituel, enrichi du drapeau `teamsTerminal`.
   **`200` et non `201`** : l'appel est **idempotent**, comme celui de F-74 / SF-74-01.
6. L'écran n'a plus qu'à ouvrir `/atelier/{id}` (SF-89-03).

### Cas nominal — le droit

`TeamsEntitlementService.isEntitled(userId)` répond **vrai** dans exactement deux cas :

- l'**option Teams** est en cours (`ACTIVE` / `PAST_DUE`) **et** le plan qui la porte est
  lui-même en cours ;
- un **accès offert** (F-62) est en cours — c'est l'essai, et D5 le dit explicitement :
  *« un essai se donne par code d'accès (F-62), le mécanisme existe déjà »*.

**Aucun plan n'inclut Teams.** Contrairement à l'Atelier (Gold, BYOK), le volet Teams est une option
et rien d'autre (D5). En revanche **tout plan mensuel en cours peut la porter** — `SOLO`, `PRO`,
`GOLD`, `BYOK` — parce qu'aucun d'eux ne la comprend déjà. `DAILY` en est exclu : un pass journée ne
porte pas un abonnement mensuel (même règle qu'en F-40).

**Le bypass administrateur est celui de l'Atelier**, à l'identique : un `ADMIN` ne consulte aucun
abonnement.

**L'option ouvre l'accès, elle n'ajoute pas de jetons** (D5, doctrine F-40 / F-62) : aucun quota
n'est lu ni modifié par ce chemin, et la consommation d'un tour Teams tombe sur le quota existant.

**Le montant de l'option : À CONFIRMER PAR LE PO.** Cette subfeature livre l'**état** de l'option et
la **règle** de droit ; elle ne livre aucun prix, aucun parcours d'achat et aucun identifiant de
tarif. Tant que le montant n'est pas tranché, seule une écriture en base (ou un code d'accès) peut
ouvrir l'option — exactement la situation de F-40 / SF-40-01, et pour la même raison : la règle
change une fois, sous tests, avant que quoi que ce soit ne puisse l'exercer.

### Cas nominal — la garde, au niveau de l'outil

`buildTools(userId, workspace)` décide déjà quels outils l'agent reçoit : c'est là que `bash` est
donné ou non selon la cible. Les outils `teams_*` s'y ajoutent sous **deux** conditions cumulatives :

1. le workspace est un **terminal Teams** (`teams_terminal = true`) ;
2. l'utilisateur a le **droit Teams**.

Sans l'une ou l'autre, **les outils ne sont pas donnés**. La nuance compte, et elle est le motif de
cette conception : **l'agent ne refuse pas, il n'a pas la capacité**. Il ne dira jamais « je pourrais
mais vous n'avez pas payé » ; interrogé sur Teams, il dira qu'il ne sait pas le lire.

Le catalogue vit dans **une seule classe** (`TeamsToolCatalog`) : F-88 y ajoute ses outils de
lecture, SF-89-02 y ajoutera les outils de présentation, et **la garde reste écrite à un seul
endroit**. Au terme de cette subfeature le catalogue contient `teams_status` — l'outil que le runner
sait déjà exécuter depuis F-87 / SF-87-03, et que l'agent n'avait jamais reçu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Utilisateur **sans droit Teams** demandant le terminal | Refus — le terminal n'existe pas pour lui | 403 |
| Poste inconnu **ou appartenant à quelqu'un d'autre** | `RunnerHostNotFoundException` — indiscernables | 404 |
| Poste virtuel « Hébergé » (F-71) | Refus : ce n'est pas une machine, aucun navigateur n'y est observable | 404 (poste absent de la table) |
| Appelant sans accès Forge | refus d'accès Atelier existant | 402 / 403 |
| Non authentifié | refus | 401 |
| Second appel sur un poste qui a déjà son terminal Teams | **le même terminal**, aucune création | 200 |
| Droit retiré alors qu'un terminal Teams existe | Le terminal **reste**, son historique reste lisible, mais l'agent **n'a plus les outils** — il dit qu'il ne sait pas lire Teams | 200 |

**Le droit retiré ne détruit rien.** Un compte rendu déjà produit reste lisible : supprimer
l'historique à la résiliation ferait perdre à l'utilisateur ce qu'il a payé le mois d'avant.

---

## Critères d'acceptation

- [ ] `POST /runner-hosts/{hostId}/teams-terminal` crée un workspace `teams_terminal = true`,
      `host_id` = le poste, `project_path = ""`, `source = LOCAL`, `execution_target = RUNNER`,
      `agent_ask_before_bash = true`, nommé `Terminal Teams`, et répond `200`.
- [ ] Un **second** appel rend **le même** identifiant et ne crée aucune seconde ligne.
- [ ] Sans droit Teams, l'appel répond **403** et **ne crée rien**.
- [ ] Le poste d'un **autre utilisateur** répond `404` et ne crée rien.
- [ ] `listByHost` (donc la carte du poste, le contrôle de doublon de F-72 et la garde de
      suppression de F-69) **ne contient ni** le terminal du poste **ni** le terminal Teams.
- [ ] `DELETE /runner-hosts/{hostId}` sur un poste **sans projet mais avec ses deux terminaux**
      répond `204` et les deux terminaux disparaissent.
- [ ] `GET /runner-hosts/overview` porte `teamsTerminalId` et `teamsTerminalLive`, et
      `liveTerminals` compte le terminal Teams quand il vit.
- [ ] `GET /api/teams/access` répond `{"entitled": true}` avec l'option, `false` sans.
- [ ] `TeamsEntitlementService` : option en cours sur `SOLO`/`PRO`/`GOLD`/`BYOK` en cours ⇒ droit ;
      option sur `DAILY`, option seule sur un plan résilié, option absente ⇒ refus ; accès offert
      F-62 en cours ⇒ droit ; `ADMIN` ⇒ droit sans consulter d'abonnement.
- [ ] **Le quota est inchangé par la présence de l'option** : `tokensForPlan(SOLO)` est identique
      avec et sans option, et un test le prouve (D5).
- [ ] `buildTools` donne `teams_status` **si et seulement si** le workspace est un terminal Teams
      **et** le droit est ouvert. Sur un terminal de projet, **aucun** outil `teams_*`, même avec
      l'option.
- [ ] La migration ajoute deux colonnes additives ; toute ligne existante reste **un projet** et un
      abonnement existant garde **exactement** le droit qu'il avait.

---

## Périmètre

### Hors scope (explicite)

- **Le prix, le parcours d'achat et la résiliation de l'option** — montant **à confirmer par le PO**.
- Les **outils de lecture** `teams_find_conversations`, `teams_read_conversation`, `teams_mentions`,
  `teams_search`, `teams_find_meetings`, `teams_meeting_transcript` : ce sont ceux de **F-88**. Cette
  subfeature livre le **catalogue** et sa **garde**, pas leur contenu.
- Les **blocs riches** (carte, moment, liste) → SF-89-02.
- **Tout écran** → SF-89-03.
- Un terminal Teams **sans poste**, ou plusieurs terminaux Teams sur la même machine.
- Renommer, déplacer ou dupliquer un terminal Teams.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `workspaces.teams_terminal` | `false` | Défaut en base **et** dans l'entité : toute ligne existante reste un projet. |
| `subscriptions.teams_option_status` | `null` | Aucune option souscrite : aucun abonnement existant ne change de droit. |
| `name` | `Terminal Teams` | Écrit par la gateway, jamais demandé. |
| `project_path` | `""` | Il n'y a pas de projet. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `hostId` (chemin) | Oui | — | UUID | — | — |
| `workspaces.teams_terminal` | Oui | — | booléen, `false` par défaut | **un seul par `(user_id, host_id)`**, garanti par le « retrouver ou créer » | — |
| `subscriptions.teams_option_status` | Non | 16 | `SubscriptionStatus` (`ACTIVE`, `PAST_DUE`, `CANCELED`, `INCOMPLETE`) ou `null` | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/runner-hosts/{hostId}/teams-terminal` | Oui | accès Forge **et** droit Teams |
| GET | `/api/teams/access` | Oui | accès Forge |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (`teams_terminal`), INSERT, SELECT, DELETE | colonne **additive**, `not null default false` |
| `subscriptions` | ALTER (`teams_option_status`) | colonne **additive**, nullable |
| `runner_hosts` | SELECT | appartenance seulement |
| `live_terminals` | SELECT | déjà lu par la vue d'ensemble (F-70) |

### Migration Liquibase

- [x] Oui — `078-teams-terminal-et-option.xml` (**numéro libre suivant** : 077 est la dernière)

---

## Plan de test

### Tests unitaires

- [ ] `WorkspaceService.openTeamsTerminal` — valeurs initiales, et **idempotence**.
- [ ] `WorkspaceService.listByHost` — exclut les **deux** terminaux marqués.
- [ ] `WorkspaceService.deleteTeamsTerminal` — supprime, ne lève pas quand il n'y en a pas.
- [ ] `RunnerHostOverviewService` — `teamsTerminalId` / `teamsTerminalLive` et `liveTerminals`.
- [ ] `TeamsEntitlementService` — les cas du tableau de droit, dont `DAILY` et le plan résilié.
- [ ] `TeamsAccessService` — bypass `ADMIN`, refus fail-closed sans principal.
- [ ] `AtelierChatService.buildTools` — `teams_status` présent sur terminal Teams + droit ; absent
      sans droit ; absent sur un terminal de projet **même avec le droit** ; absent sur le terminal
      du poste.
- [ ] Quota inchangé par l'option.

### Tests d'intégration

- [ ] `POST /runner-hosts/{id}/teams-terminal` → `200` + `teamsTerminal` vrai ; deux fois → même id.
- [ ] Sans option → `403`, et aucune ligne créée.
- [ ] Poste d'un autre utilisateur → `404`.
- [ ] `DELETE /runner-hosts/{id}` avec les deux terminaux → `204`.
- [ ] `GET /runner-hosts/overview` → le terminal Teams **n'est pas** dans `projects`.
- [ ] `GET /api/teams/access` → `entitled` vrai/faux selon l'option.

### Isolation workspace

- [x] Applicable — `requireOwned` sur le poste, `findByUserId…` sur les workspaces, `userId` pris du
      contexte de sécurité et jamais d'un paramètre client.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. F-87 est livrée ; F-40, F-61, F-62, F-70, F-74 le sont aussi.
- **F-88 est livrée en parallèle** : elle remplira `TeamsToolCatalog`. Le point de couture est
  volontairement une classe à part, pour que les deux travaux ne se marchent pas dessus.

### Questions ouvertes impactées

- Aucune. Le montant de l'option n'est pas une question ouverte du registre : c'est une décision
  réservée au PO (D5).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| Auth / Principal | Non | aucun nouveau type d'auth ; `currentUser.requireId()` comme partout |
| Contexte tenant | **Oui** — une nouvelle façon de retrouver un workspace (`host_id` + `teams_terminal`) | `WorkspaceRepository` (lectures ajoutées, toutes préfixées `findByUserId…`), `WorkspaceService.listByHost` / `openTeamsTerminal` / `deleteTeamsTerminal`, `RunnerHostController` (`requireOwned` avant tout), `RunnerHostOverviewService` |
| Plans / limites | **Oui** — un nouveau droit, et le plafond de quatre terminaux vivants (F-70) | `TeamsEntitlementService` (nouveau), `TeamsAccessService` (nouveau), `AtelierEntitlementService` **inchangé** (vérifié par test : le droit Atelier ne bouge pas), `QuotaProperties` **inchangé**, `LiveTerminalService` **inchangé** (il compte des `workspace_id`) |
| Navigation / routing | Non (backend) | — |
| **Facturation** (F-65) | **Oui, et la réponse est « rien »** | `SeatLedgerService` n'est appelé qu'à la création/clôture/réouverture d'un **poste** ; aucun de ces chemins n'est touché |
| **Consommation** (F-61) | **Oui, et la réponse est « rien à changer »** | `UsageTurnRepository` agrège par `(host_id, workspace_id)` : un tour Teams tombe sous le bon client, sans une ligne de code de plus |
| **Registre des terminaux vivants** (F-70) | **Oui, et la réponse est « rien à changer »** | `live_terminals.workspace_id` : le terminal Teams y entre comme les autres et compte dans le plafond de quatre |

---

## Notes et décisions

- **D-89-1 — un booléen, pas une table.** Vérifié : tout ce qui fait un terminal pend à
  `workspace_id` (voir §Contexte). Même arbitrage qu'en F-74 / SF-74-01, pour les mêmes raisons.
- **D-89-2 — un second booléen, pas une énumération.** Un `kind` obligerait à relire toutes les
  requêtes existantes ; un booléen laisse l'existant juste par construction.
- **D-89-3 — aucun plan n'inclut Teams ; tout plan mensuel en cours peut porter l'option.** C'est la
  différence avec F-40, où Gold et BYOK comprenaient déjà l'Atelier.
- **D-89-4 — l'accès offert (F-62) ouvre aussi Teams.** D5 le demande littéralement (« un essai se
  donne par code d'accès »). Un code est déjà un droit **borné dans le temps**, accordé
  délibérément : c'est exactement la forme d'un essai. Aucun jeton n'est ajouté, là non plus.
- **D-89-5 — la garde est au niveau de l'outil, et à un seul endroit.** `TeamsToolCatalog` : F-88 et
  SF-89-02 y ajoutent des outils sans jamais réécrire la garde.
- **D-89-6 — le droit retiré ne détruit rien.** Le terminal et son historique restent ; seuls les
  outils disparaissent.

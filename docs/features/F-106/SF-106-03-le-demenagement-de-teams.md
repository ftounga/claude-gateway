# Mini-spec — F-106 / SF-106-03 — Le déménagement de Teams

## Identifiant

`F-106 / SF-106-03`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §5, §6)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-03-demenagement-teams`

---

## Objectif

Faire vivre le terminal Teams, l'état de la liaison Teams et les réunions **dans la Vigie** : la
Forge ne garde qu'un lien « Ouvrir dans la Vigie » (ou « Activer dans la Vigie »), et le terminal
Teams ramène à la Vigie.

---

## Comportement attendu

### Cas nominal

1. **Gateway** : `POST /runner-hosts/{id}/teams-terminal` exige, après le droit Teams et la
   possession, que le client soit **activé dans la Vigie** (409 `host_not_in_space` sinon, rien
   n'est créé).
2. **Vigie — onglet Conversations** : « Terminal Teams » avec son signe de vie (§11) quand il est
   ouvert, une phrase qui dit ce qu'on y fait (« résume la réunion d'hier », « qu'attend Paul de
   moi ? ») et le bouton **« Ouvrir la conversation »** qui ouvre (ou crée) le terminal Teams du
   client puis navigue vers `/atelier/<id>`. Onglet résumé : « vivant » si le terminal vit.
3. **Vigie — en-tête du client** : l'indicateur de liaison Teams (`app-teams-link-badge`, §14),
   relevé **une fois par client** sur le terminal Teams existant
   (`GET /workspaces/{id}/teams/link`) ; rien n'est affiché si le client n'a pas encore de terminal
   Teams, s'il est hors ligne, ou si le relevé échoue (§14 « rien n'est affiché quand il n'y a rien à
   dire »). La réparation reste l'infobulle du composant (§14 : aucune action).
4. **Vigie — onglet Réunions** : ce que la conversation sait faire des réunions (comptes rendus,
   captures F-90, enregistrements F-91) et le même bouton **« Ouvrir la conversation »**.
5. **Forge — en-tête du poste** : le bouton « Teams » disparaît. À la place, si le compte a le droit
   Teams :
   - client activé dans la Vigie : lien **« Ouvrir dans la Vigie »** vers
     `/vigie/<id>?onglet=conversations` (avec le signe de vie du terminal Teams s'il vit) ;
   - sinon : entrée de menu « ··· » **« Activer dans la Vigie »** (`PUT …/spaces/VIGIE`), puis
     ouverture de `/vigie/<id>?onglet=conversations`.
6. **Terminal Teams** : son fil d'Ariane dit **« Vigie › client »** et ramène à
   `/vigie/<id>?onglet=conversations` (au lieu de « Forge › client » → `/forge/<id>`) ; le lien
   « poste » de la barre suit la même règle. Les terminaux de projet et du poste gardent la Forge.
7. **Anciens chemins** : il n'existait pas d'adresse dédiée au terminal Teams — il s'ouvrait par un
   bouton de la carte du poste. Le bouton disparu est remplacé par le lien vers la Vigie (5), et
   l'adresse `/atelier/<id>` d'un terminal Teams déjà ouvert continue de répondre (son retour mène
   désormais à la Vigie).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Ouvrir le terminal Teams d'un client hors Vigie | refus, rien n'est créé | 409 |
| Sans droit Teams | refus inchangé | 403 |
| Poste d'autrui | introuvable inchangé | 404 |
| Ouverture échouée depuis la Vigie | message « La conversation n'a pas pu être ouverte. Rien n'a été créé. » ; vue relue | 4xx/5xx |
| Activation dans la Vigie échouée depuis la Forge | message de la gateway, rien ne change | 4xx |
| Relevé de liaison en échec | aucun indicateur | — |

---

## Critères d'acceptation

- [ ] La gateway refuse (409) le terminal Teams d'un client non activé dans la Vigie.
- [ ] L'onglet Conversations de la Vigie ouvre le terminal Teams du client ; l'onglet Réunions aussi.
- [ ] L'en-tête du client dans la Vigie porte l'état de la liaison Teams quand il existe.
- [ ] La Forge n'a plus de bouton « Teams » ; elle porte « Ouvrir dans la Vigie » (client activé) ou
      « Activer dans la Vigie » (menu), et rien sans le droit Teams.
- [ ] Le fil d'Ariane d'un terminal Teams ramène à la Vigie ; celui d'un projet reste la Forge.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Un catalogue ou une liste de réunions lue par la Vigie : les réunions se demandent dans la
  conversation (F-87 : Teams n'est pas un écran à boutons).
- Les passerelles sujet ↔ projet (SF-106-04), la page non souscrite (SF-106-05).
- Retirer les outils `teams_*` d'un terminal Teams **déjà ouvert** quand son client quitte la Vigie
  (le catalogue suit le droit Teams, inchangé).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| relevé de liaison | absent | lu une fois par client ayant un terminal Teams, en ligne |

---

## Contraintes de validation

Aucune saisie.

---

## Technique

### Endpoint(s)

| Méthode | URL | Changement |
|---------|-----|------------|
| `POST` | `/api/runner-hosts/{hostId}/teams-terminal` | + client activé dans la Vigie (409) |

Lus sans changement : `GET /api/workspaces/{id}/teams/link`, `PUT /api/runner-hosts/{id}/spaces/VIGIE`.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `RunnerHostController.openTeamsTerminal` (garde d'espace).
- `VigieComponent` — onglets Conversations et Réunions, liaison dans l'en-tête.
- `PostesComponent` — « Ouvrir dans la Vigie » / « Activer dans la Vigie », retrait du bouton Teams.
- `ForgeBreadcrumbComponent` — racine paramétrable (Forge / Vigie), `queryParams` d'un niveau.
- `AtelierTerminalComponent` — fil et lien du poste d'un terminal Teams vers la Vigie.

### Préoccupations transversales

- **Navigation / routing : oui.** Chemins vérifiés : `/atelier/<id>` d'un terminal Teams (fil →
  `/vigie/<id>?onglet=conversations`), d'un terminal de projet ou du poste (fil → `/forge/<id>`,
  inchangé) ; Forge → `/vigie/<id>?onglet=conversations` ; Vigie → `/atelier/<id>`. Aucune route
  ajoutée ni retirée.
- **Plans / limites : oui (garde).** `TeamsAccessService.requireAccess` inchangé sur l'ouverture ;
  s'y ajoute l'espace Vigie. `PostesComponent.teamsEntitled` (lu une fois) décide des gestes vers la
  Vigie ; `VigieComponent` est déjà derrière le droit.
- Auth / Principal : non. Contexte tenant : non (possession vérifiée avant l'espace).

---

## Plan de test

### Tests unitaires / intégration

- [ ] `TeamsTerminalApiIntegrationTest` — poste activé dans la Vigie : terminal ouvert ; poste hors
      Vigie : 409, aucun terminal créé ; tests existants adaptés.
- [ ] `VigieComponent` — Conversations ouvre le terminal et navigue ; échec ⇒ message ; Réunions
      ouvre la conversation ; liaison lue une fois pour un client ayant un terminal Teams, absente
      sinon, silencieuse en échec.
- [ ] `PostesComponent` — plus de bouton Teams ; « Ouvrir dans la Vigie » (href) pour un client
      activé ; « Activer dans la Vigie » ⇒ PUT puis navigation ; rien sans droit.
- [ ] `ForgeBreadcrumbComponent` — racine Vigie ; `AtelierTerminalComponent` — fil d'un terminal
      Teams vers la Vigie, d'un projet vers la Forge.

### Isolation workspace

- [ ] Poste d'autrui : 404 inchangé sur l'ouverture du terminal Teams (test existant conservé).

---

## Dépendances

### Subfeatures bloquantes

- SF-106-01 — `done` ; SF-106-02 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Le terminal Teams reste une page à part** (`/atelier/<id>`) : il porte le fil, les comptes rendus
  et les captures ; l'embarquer dans l'onglet recopierait l'écran du terminal. L'onglet est la porte.
- **La liaison est lue sur le terminal Teams existant** : c'est l'endpoint de F-87, indexé par
  terminal ; un client sans terminal Teams n'a encore rien à dire de sa liaison.

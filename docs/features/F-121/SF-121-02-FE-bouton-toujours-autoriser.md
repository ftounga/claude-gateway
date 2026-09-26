# Mini-spec — F-121 / SF-121-02-FE — Bouton « toujours autoriser cette commande » dans l'invite de confirmation du terminal

## Identifiant

`F-121 / SF-121-02-FE`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison) — Lot 1, volet frontend de **SF-121-02**
(« modèle de permission allow/ask/deny persisté »), explicitement planifié en hors-scope de
SF-121-02 (`docs/features/F-121/SF-121-02-permissions-allow-ask-deny.md` §Périmètre et §Analyse
transversale).

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-02-FE-toujours-autoriser`

---

## Objectif

Donner à l'invite de confirmation du terminal le **troisième geste** que le backend SF-121-02 sait
déjà encaisser : « **toujours autoriser cette commande** », qui écrit une règle de permission
**persistante** pour ce projet au lieu de redemander à chaque tour.

---

## Comportement attendu

### Cas nominal

1. Le tour s'arrête sur une demande d'autorisation ; le flux SSE `confirm_request` porte
   `allowAlwaysOffered: true` (la politique de permission est branchée et l'outil n'est pas une
   écriture Teams — décidé côté gateway, jamais deviné à l'écran).
2. L'invite affiche un bouton supplémentaire, **cerclé** (registre secondaire), entre « Tout
   autoriser pour ce message » et « Refuser » :
   - `bash` → « **Toujours autoriser `<premier mot>`** » (ex. `git`), parce que c'est **exactement**
     la portée de la règle écrite par la gateway (`AtelierPermissionService.alwaysAllowCommand` :
     premier mot de la commande, en minuscules) ;
   - tout autre outil → « **Toujours autoriser `<outil>`** ».
3. L'infobulle dit la portée sans l'adoucir : la règle vaut **pour ce projet**, **sans limite de
   durée**, et il n'existe pas encore d'écran pour la retirer.
4. Le clic envoie `POST /api/workspaces/{id}/chat/confirm` avec
   `{ toolUseId, decision: 'allow', alwaysAllowCommand: true }`. La commande en cours s'exécute et
   la règle est écrite : les commandes suivantes de même préfixe ne redemanderont plus.
5. L'invite disparaît à la **résolution** relayée par le flux, comme pour les autres décisions.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `allowAlwaysOffered` absent / faux (backend antérieur, bac à sable, écriture Teams, politique non branchée) | Aucun bouton affiché — on ne propose pas un geste qui n'écrirait rien | — |
| `confirm_state` (aparté de rejeu SF-84-03) sans le drapeau, pour la **même** demande | Le drapeau **déjà connu** est conservé ; il n'est ni inventé ni perdu | — |
| Demande déjà tranchée / expirée au moment du clic | Invite retirée + message « L'exécution n'attend plus de réponse. » (chemin `confirmErrorMessage` existant) | 409 |
| Projet introuvable | « Projet introuvable. » (chemin existant) | 404 |
| Réponse déjà en vol (`answering`) | Boutons inertes : aucune double décision | — |
| Terminal en **lecture seule** (tuile de mosaïque, F-83) | Aucun bouton de décision, donc aucun bouton « toujours autoriser » | — |

---

## Critères d'acceptation

- [x] Quand `confirm_request` porte `allowAlwaysOffered: true`, l'invite affiche un troisième
      bouton d'autorisation ; quand il est absent ou faux, elle n'en affiche **aucun**.
- [x] Pour `bash`, le libellé nomme le **premier mot** de la commande, en minuscules — la portée
      réelle de la règle écrite par la gateway.
- [x] Pour un outil non-`bash`, le libellé nomme l'**outil**.
- [x] Le clic poste `alwaysAllowCommand: true` avec `decision: 'allow'` sur l'endpoint de la
      **bonne** cible (machine connectée → `/chat/confirm`), et la clé est **absente** du corps pour
      les autres décisions (aucun champ inutile envoyé).
- [x] Un aparté `confirm_state` portant le même `toolUseId` **ne fait pas disparaître** le bouton.
- [x] Le bouton est inerte tant qu'une réponse est en vol (`answering`).
- [x] En lecture seule, rien n'est proposé.
- [x] Aucun accès données nouveau côté écran : l'isolation `user_id` reste celle de l'endpoint
      existant (`requireTerminalAccess` + `requireOwned`), inchangé.

---

## Périmètre

### Hors scope (explicite)

- **Une UI de gestion des règles** (lister / retirer une règle persistée) — déjà hors Lot 1 dans
  SF-121-02 ; reste à planifier.
- **Le portage du drapeau à l'aparté `confirm_state`** côté backend (`PendingApproval`) : traité à
  l'écran par conservation du drapeau déjà reçu (voir §Notes et décisions, arbitrage A2).
- Le bac à sable hébergé (`/agent/confirm`) : la politique de permission est propre au RUNNER
  (hors-scope hérité de SF-121-02).
- Tout changement de la politique elle-même (résolution, effets, purge) : livré par SF-121-02.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `AtelierPendingConfirmation.allowAlwaysOffered` | `false` | Faux par défaut : on ne propose jamais un geste persistant que la gateway n'a pas annoncé |
| `AtelierConfirmDecision.alwaysAllowCommand` | absent | La clé n'est posée que quand le geste est celui-là |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `allowAlwaysOffered` (SSE, entrant) | Non | — | booléen ; **tout ce qui n'est pas `true` vaut `false`** | — | `payload.allowAlwaysOffered === true` |
| `alwaysAllowCommand` (API, sortant) | Non | — | booléen ; posé **uniquement** avec `decision: 'allow'` | — | clé omise sinon |
| Libellé du préfixe affiché | — | 32 caractères affichés | premier mot de `detail` pour `bash`, sinon `tool` | — | `trim()` + minuscules + coupe à 32 (`…`) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|-----------|
| POST | `/api/workspaces/{id}/chat/confirm` | Oui | **Aucun** — champ `alwaysAllowCommand` déjà accepté (SF-121-02) |

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

| Composant / fichier | Changement |
|---------------------|-----------|
| `core/models/atelier.models.ts` | `AtelierConfirmRequest.allowAlwaysOffered?`, `AtelierConfirmDecision.alwaysAllowCommand?` |
| `core/services/atelier.service.ts` | Lecture additive du drapeau sur `confirm_request` du flux de la machine connectée |
| `atelier/atelier.types.ts` | `AtelierPendingConfirmation.allowAlwaysOffered?` |
| `atelier/atelier.component.ts` | `showConfirmation` (conservation du drapeau par `toolUseId`), `answerConfirmation(allow, allowAll, alwaysAllowCommand)` |
| `atelier/atelier.component.html` | Câblage `(confirmAlways)` |
| `atelier/terminal/atelier-terminal.component.ts` | `@Output() confirmAlways`, libellé et infobulle de portée |
| `atelier/terminal/atelier-terminal.component.html` | Le bouton dans le bloc `.terminal-ask-actions` |
| `atelier/terminal/atelier-terminal.component.scss` | `flex-wrap` sur la rangée d'actions (4 boutons) |

### Analyse transversale (obligatoire)

- **Auth / Principal** : aucun changement. Aucun nouvel endpoint, aucun nouveau type d'auth ; le
  seul appel est l'existant `POST …/chat/confirm`, déjà protégé (`requireTerminalAccess`).
  Endpoints impactés : **aucun**.
- **Contexte tenant** : aucun nouveau moyen de résoudre le tenant. L'écran n'envoie qu'un
  `toolUseId` ; la règle est écrite côté gateway sur `(user_id, workspace_id)` (SF-121-02).
  Composants résolvant le tenant : inchangés.
- **Plans / limites** : la **porte** de permission n'est pas modifiée — l'écran ne fait que
  proposer une décision de plus à la porte existante (`RunnerConfirmationGate` →
  `AtelierPermissionService`). Appels de gate : `AtelierChatService.executeToolOnRunner`
  (inchangé) ; « tout autoriser pour ce message » (SF-38-20) : **inchangé**, chemin distinct.
- **Navigation / routing** : aucune route, aucun guard, aucune redirection. Les chemins de
  navigation existants ne sont pas touchés.
- **Vues consommant `AtelierPendingConfirmation`** : `atelier.component` (terminal, décisions),
  `atelier-terminal.component` (rendu), `mosaique/live-turn-view` (tuile **en lecture seule**, sans
  bouton). Le champ ajouté est **optionnel** : la tuile de mosaïque compile et se comporte à
  l'identique.

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [x] `terminal-toujours-autoriser.spec.ts` — le bouton est **absent** sans `allowAlwaysOffered`.
- [x] … — le bouton est **présent** avec le drapeau, et son libellé nomme le **premier mot**
      (`bash` : `git commit -m "x"` → « git »).
- [x] … — pour un outil non-`bash`, le libellé nomme l'outil.
- [x] … — le clic émet `confirmAlways`.
- [x] … — bouton inerte quand `answering`.
- [x] … — rien en lecture seule (`readOnly`).
- [x] `atelier.service` — `confirm_request` porte `allowAlwaysOffered` vers l'appelant ; absent ⇒
      `false`.
- [x] `atelier.component` — `answerConfirmation(true, false, true)` poste
      `alwaysAllowCommand: true` ; une autorisation simple ne pose **pas** la clé ; un aparté
      `confirm_state` de même `toolUseId` conserve le drapeau.

### Tests d'intégration

- [x] Test de composant avec `HttpTestingController` sur `POST /api/workspaces/{id}/chat/confirm`
      (corps vérifié) — il n'y a pas d'endpoint neuf à intégrer.

### Isolation workspace

- [x] Non applicable au frontend : aucun accès données direct. L'isolation `(user_id,
      workspace_id)` est portée par l'endpoint existant et testée côté backend (SF-121-02,
      `AtelierPermissionServiceTest`, `AtelierChatServicePermissionTest`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-121-02` — statut : **done** (PR #648 : SSE `allowAlwaysOffered`, API `alwaysAllowCommand`).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **A1 — Le libellé nomme la portée réelle.** « Toujours autoriser cette commande » est un
  raccourci trompeur pour `bash` : la gateway écrit une règle sur le **premier mot**. Autoriser
  « toujours » `git commit -m "x"` autorise en fait **tout `git`**. L'écran le dit donc en toutes
  lettres (libellé + infobulle) plutôt que de laisser croire à une règle exacte.
- **A2 — Le drapeau perdu au rejeu est conservé, pas redemandé.** L'aparté `confirm_state`
  (SF-84-03) ne porte pas `allowAlwaysOffered` côté backend. Plutôt que d'élargir le protocole du
  tour (record `PendingApproval`, `RelayTurnSource`, outils MCP) pour un bouton, l'écran **conserve
  le drapeau déjà reçu** pour le **même** `toolUseId` — ce n'est pas une invention, c'est la même
  demande. Cas résiduel assumé : une invite dont le `confirm_request` a été **évincé du tampon** du
  tour (tour très long) et retrouvée via le seul aparté n'affichera pas le bouton — repli **sûr**
  (jamais de règle persistante posée par erreur).
- **A3 — Registre visuel.** Bouton **cerclé** (jamais plein) : le geste plein reste « Autoriser »,
  l'action de ce tour. Aucune couleur nouvelle ; `flex-wrap` ajouté à la rangée d'actions pour
  quatre boutons sur écran étroit (le mobile les empile déjà, SF-158-13).

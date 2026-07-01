# Mini-spec — [F-02 / SF-02] Frontend — Interface de chat

## Identifiant

`F-02 / SF-02`

## Feature parente

`F-02` — Chat proxy Claude (Hosted)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-02-02-frontend-chat-ui`

---

## Objectif

> En une phrase : offrir un écran de chat (liste de conversations + fil de messages + sélecteur de modèle) qui consomme l'API F-02, avec une expérience proche de Claude.

Contrat importé de `SF-02-01-backend-chat-proxy` (section « Contrat API (figé) »).

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié ouvre `/chat` (route protégée par `authGuard`).
2. La sidebar liste ses conversations (`GET /api/conversations`), triées par activité.
3. Il saisit un message, choisit éventuellement un modèle (`GET /api/chat/models`), et envoie (`POST /api/chat`).
4. Le message utilisateur s'affiche immédiatement ; la réponse de Claude s'ajoute au fil ; la conversation est sélectionnée/rafraîchie.
5. Cliquer une conversation charge son détail (`GET /api/conversations/{id}`).
6. Renommer (`PATCH`) et supprimer (`DELETE`) une conversation sont disponibles (suppression confirmée via `MatDialog`).

### Cas d'erreur

| Situation | Comportement attendu | Détail |
|-----------|---------------------|--------|
| Message vide | Bouton envoyer désactivé | validation formulaire |
| 401 | Redirection `/login` | via `authInterceptor` existant |
| Erreur API (5xx/502/503) | `MatSnackBar` message métier | pas d'exception brute |
| Envoi en cours | Indicateur de chargement + saisie verrouillée | `submitting` signal |

---

## Critères d'acceptation

- [ ] Route `/chat` protégée par `authGuard`, chargée en lazy.
- [ ] `ChatService` expose `sendMessage`, `listConversations`, `getConversation`, `renameConversation`, `deleteConversation`, `getModels` — appels sur `/api/...`.
- [ ] Le composant affiche le fil (rôles distincts USER/ASSISTANT), la sidebar, le sélecteur de modèle, l'input.
- [ ] Envoi d'un message affiche la réponse assistant sans rechargement complet.
- [ ] Erreur serveur → `MatSnackBar` (aucun `window.alert/confirm`).
- [ ] Suppression via `MatDialog` de confirmation.
- [ ] Couleurs/typos/espacements conformes `DESIGN_SYSTEM.md` (Angular Material + variables `--cg-*`).
- [ ] Tests : `ChatService` (mock `HttpTestingController`) + composant (rendu + envoi) au vert.

---

## Périmètre

### Hors scope (explicite)

- Streaming token par token (réponse affichée en un bloc — cohérent backend V1).
- Rendu Markdown riche (V1 : `white-space: pre-wrap`, échappement natif Angular ; Markdown = amélioration ultérieure, arbitrage tracé).
- Upload de fichiers (F-04), gestion BYOK (F-03), quotas (F-10).

---

## Technique

### Contrat consommé

Importé de `SF-02-01` : `POST /api/chat`, `GET /api/conversations`, `GET /api/conversations/{id}`, `PATCH /api/conversations/{id}`, `DELETE /api/conversations/{id}`, `GET /api/chat/models`.

### Composants Angular

- `core/models/chat.models.ts` — types alignés sur le contrat.
- `core/services/chat.service.ts` — `HttpClient`, signals d'état.
- `chat/chat.component.*` — écran principal (standalone), Material : `MatSidenav`/`MatList`/`MatCard`/`MatFormField`/`MatInput`/`MatSelect`/`MatButton`/`MatIcon`/`MatDialog`/`MatSnackBar`/`MatProgressBar`.
- `chat/confirm-dialog/confirm-dialog.component.ts` — dialog de confirmation réutilisable.
- Route `/chat` ajoutée à `app.routes.ts` (lazy + `authGuard`), lien dans la home/nav.

### Tests

- [ ] `chat.service.spec.ts` — chaque méthode : URL, verbe, payload, réponse mockée.
- [ ] `chat.component.spec.ts` — création, envoi d'un message (provider mocké), affichage réponse, gestion erreur.

---

## Dépendances

### Subfeatures bloquantes

- `SF-02-01` (contrat figé) — les endpoints doivent exister sur `main` avant merge du frontend (backend mergé en premier).

### Préoccupation transversale — Navigation / routing

Composants/chemins impactés : `app.routes.ts` (nouvelle route `/chat` + `authGuard`), `home.component` (lien d'accès au chat). Aucune route existante modifiée ; `authGuard` réutilisé sans changement. Vérifié : `/`, `/login`, `/profile` inchangés.

---

## Notes et décisions (arbitrages)

- **Écran chat = layout dédié** (sidebar conversations à gauche, fil au centre) conforme `DESIGN_SYSTEM.md §4 note`. Réversible.
- **Pas de Markdown en V1** : rendu texte `pre-wrap`, échappement Angular par défaut (sécurité XSS). Amélioration ultérieure. Réversible.
- **Réponse non-streamée** : cohérent avec le backend V1.

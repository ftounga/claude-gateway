# Mini-spec — [F-269 / SF-269-01] Le type COMMANDE au registre des besoins (backend + hook)

## Identifiant
`F-269 / SF-269-01`

## Feature parente
`F-269` — Commandes pour Claude Code dans le registre des besoins

## Statut
`done`

## Date de création
2026-11-18

## Branche Git
`feat/SF-269-01-commande-backend`

---

## Objectif
Le registre des besoins accepte un troisième type **COMMANDE** — une instruction de l'administrateur, gardée
mot pour mot, que Claude Code exécute telle quelle à son prochain démarrage.

## Comportement attendu

### Cas nominal
1. **Par l'agent** : l'admin dit en substance « enregistre une commande pour Claude Code : lance les bancs
   Jarvis ». L'agent appelle `signaler_besoin` avec `type = COMMANDE`, `description` = le texte de la commande
   **verbatim**, `reformulation` = sa compréhension (facultative). `titre` est facultatif pour une COMMANDE :
   absent, il est tiré de la première ligne de la description (80 caractères au plus, « … » si coupé). La carte
   [Enregistrer] [Non] est posée comme pour tout besoin ; au clic, statut `EN_ATTENTE`.
2. **Saisie directe** : `POST /api/admin/app-requests/commands` `{ "text": "...", "reformulation": null }` —
   admin seulement — crée une ligne `kind = COMMANDE`, `status = EN_ATTENTE`, `recorded_at = now`,
   `workspace_id = null` (pas de terminal), `description = text` (seuls les blancs de tête et de fin sont
   retirés). Réponse 201 + la vue du besoin.
3. **Lecture admin** : `GET /api/admin/app-requests?kind=COMMANDE` filtre par type (`BUG`, `EVOLUTION`,
   `COMMANDE`) ; combinable avec `status`.
4. **Hook** `.claude/hooks/besoins-au-demarrage.sh` : les COMMANDE en attente sont listées **en premier**, dans
   un bloc « COMMANDES DE L'ADMINISTRATEUR — à exécuter telles quelles », puis les BUG/EVOLUTION.
5. **Outil** : seule la *description* de `signaler_besoin` change (enum + phrase COMMANDE) ; la doctrine
   système `APP_REQUEST_DOCTRINE` est inchangée (préfixe stable).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Non-administrateur sur `POST /admin/app-requests/commands` (ou toute route admin) | refus | 403 |
| `text` vide ou absent | `app_request_invalid` | 400 |
| `text` > 8 000 caractères | `app_request_invalid` | 400 |
| `kind` inconnu en filtre | `app_request_invalid` | 400 |
| Outil : `type` hors BUG/EVOLUTION/COMMANDE | erreur rendue à l'agent | — |
| Non-administrateur dans un terminal | l'outil n'est pas offert (inchangé, F-205) | — |

## Critères d'acceptation
- [ ] CA1 — `propose(..., "COMMANDE", null, "lance les bancs Jarvis", ...)` range un besoin COMMANDE, description intacte, titre tiré de la description.
- [ ] CA2 — `POST /admin/app-requests/commands` par l'admin → 201, `kind = COMMANDE`, `status = EN_ATTENTE`, `description` identique au texte envoyé.
- [ ] CA3 — le même appel par un USER → 403, et aucune ligne créée.
- [ ] CA4 — `GET /admin/app-requests?kind=COMMANDE` ne rend que des COMMANDE de l'appelant ; un autre admin ne voit pas celles du premier (isolation `user_id`).
- [ ] CA5 — la description de l'outil liste COMMANDE ; `APP_REQUEST_DOCTRINE` inchangée.
- [ ] CA6 — le hook présente les COMMANDE avant les autres besoins avec la consigne « exécuter telle quelle ».
- [ ] CA7 — migration `269-01` : `app_requests.workspace_id` nullable ; rollback documenté.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `text` | obligatoire, ≤ 8 000 caractères |
| `reformulation` | facultative, tronquée à 8 000 |
| `kind` (filtre) | BUG \| EVOLUTION \| COMMANDE |
| titre dérivé | première ligne non vide, ≤ 80 caractères |

## Périmètre — hors scope
- Exécution par la passerelle elle-même : c'est Claude Code, sur le PC du PO, qui exécute (rien ne s'exécute côté serveur).
- Compléments (`complete`) spécifiques aux commandes : mécanique F-205 inchangée.
- Écran (SF-269-02).

## Technique
- Tables : `app_requests` (`workspace_id` nullable, `kind` accepte COMMANDE — pas de contrainte CHECK existante).
- Endpoints : `POST /api/admin/app-requests/commands` (nouveau), `GET /api/admin/app-requests` (+ `kind`).
- Code : `AppRequest`, `AppRequestService` (`recordCommand`, filtre `kind`, titre dérivé), `AppRequestAdminController`,
  `AtelierChatService` (description de l'outil), hook shell.

### Préoccupations transversales
- Auth / Principal : non modifié — la nouvelle route utilise la garde existante `AdminService.assertAdmin()`. Composants : `AppRequestAdminController` (5 routes, toutes gardées).
- Contexte tenant : `user_id` de `CurrentUser` sur création et lecture (inchangé).
- Plans / limites, navigation : non.

## Plan de test
### Unitaires (`AppRequestServiceTest`)
- COMMANDE proposée sans titre → titre dérivé, description intacte (multi-ligne, ponctuation conservée).
- `recordCommand` : EN_ATTENTE, workspace null, recordedAt posé ; texte vide → invalide ; > 8 000 → invalide.
- `list(user, null, "COMMANDE")` filtre ; kind inconnu → invalide.
- type inconnu → message citant les trois types.
### Intégration (`AppRequestApiIntegrationTest`)
- admin POST commands → 201 ; GET `?kind=COMMANDE` la rend.
- USER POST commands → 403, aucune ligne.
- Isolation : un second admin ne voit pas la commande du premier.
- `AtelierChatServiceAppRequestTest` : description de l'outil contient COMMANDE.

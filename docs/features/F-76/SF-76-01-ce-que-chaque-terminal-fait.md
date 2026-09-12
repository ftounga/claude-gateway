# Mini-spec — F-76 / SF-76-01 — Ce que chaque terminal vivant est en train de faire

---

## Identifiant

`F-76 / SF-76-01`

## Feature parente

`F-76` — Voir travailler ses terminaux

## Statut

`todo`

## Date de création

2026-09-12

## Branche Git

`feat/SF-76-01-apercu-terminaux-vivants`

---

## Objectif

Faire porter par le registre des terminaux vivants (F-70) **ce que chaque terminal fait à
l'instant** — une activité nommée, un détail, et ses dernières lignes bornées — puis le rendre aux
deux écrans qui l'afficheront, sans ouvrir un seul canal de plus.

---

## Comportement attendu

### Cas nominal

**(1) Le battement de cœur transporte l'aperçu.** `POST /api/workspaces/{id}/terminal/live` accepte
trois champs **facultatifs** en plus du `sessionId` :

```json
{ "sessionId": "…",
  "activity": "RUNNING",
  "activityDetail": "npm test",
  "previewLines": ["$ npm test", "PASS src/app.spec.ts", "…"] }
```

Ils sont écrits sur la fiche de **cet onglet**, avec l'instant du relevé (`activity_at`). Un appel
qui ne les porte pas — un front antérieur, ou un onglet qui n'a rien de neuf à dire — **laisse
l'aperçu en place** : il n'efface rien. Pour effacer, on envoie `activity: "IDLE"` et une liste
vide.

**(2) Le serveur borne, et nettoie.** Quoi que l'appelant envoie :

- **6 lignes** au maximum, les **dernières** conservées ;
- **160 caractères** par ligne, tronqués à droite ;
- `activityDetail` tronqué à **120 caractères** ;
- les **séquences d'échappement ANSI** et les caractères de contrôle sont retirés — un aperçu se lit
  dans une tuile HTML, pas dans un émulateur de terminal ;
- une `activity` inconnue (valeur d'un front plus récent) est lue comme `IDLE` plutôt que refusée.

Une borne tenue par l'appelant n'est pas une borne : la troncature est faite **au serveur**, et les
tests la vérifient depuis l'API.

**(3) Le registre le rend.** `GET /api/terminals/live` — et la réponse de la prise de place, qui est
la même — porte désormais sur chaque terminal : `activity`, `activityDetail`, `previewLines`,
`activityAt`. C'est **la seule lecture** dont la vue de supervision aura besoin.

**(4) La vue d'ensemble le rend aussi.** `GET /api/runner-hosts/overview` gagne, **sans migration
supplémentaire** : `terminalPreview` sur chaque projet (nul quand aucun terminal n'y vit) et
`hostTerminalPreview` sur le poste, pour le terminal du poste (F-74). Une **seule** lecture du
registre par appel, comme aujourd'hui pour `liveTerminal`.

**(5) Deux onglets sur le même projet.** Chacun tient sa fiche. La vue d'ensemble, qui parle du
**projet** et non de l'onglet, retient l'aperçu **le plus récent** (`activity_at` le plus grand) —
et, à égalité d'instant, celui qui attend une autorisation, parce que c'est celui qu'il faut voir.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `previewLines` de 50 entrées de 4 000 caractères | Accepté, **ramené** à 6 × 160 — jamais un 400 : c'est du décor, il ne doit rien faire échouer | 200 |
| `activity` inconnue (`"DANCING"`) | Lue comme `IDLE`, le reste de l'aperçu est conservé | 200 |
| `activity` de plus de 24 caractères ou hors `[A-Z_]` | Erreur de validation — au-delà, ce n'est plus une étiquette | 400 |
| 5ᵉ terminal alors que 4 vivent | Inchangé : `409 terminal_limit_reached`, **aucune** fiche ni aucun aperçu laissé | 409 |
| Projet d'un autre utilisateur | Inchangé : traité comme inexistant, **aucun** aperçu écrit | 404 |
| Utilisateur non authentifié | Refus standard | 401 |
| Fiche expirée (TTL F-70 dépassé) | Son aperçu n'est **ni compté ni rendu** nulle part, et la ligne est purgée comme avant | — |

---

## Critères d'acceptation

1. Un `POST` portant `activity`, `activityDetail` et `previewLines` les rend **immédiatement** dans
   la réponse, et `GET /api/terminals/live` les rend à l'identique.
2. Un `POST` **sans** ces champs ne modifie pas l'aperçu déjà enregistré (il renouvelle seulement la
   place).
3. 50 lignes envoyées ⇒ **6** rendues, et ce sont les **dernières** ; une ligne de 4 000 caractères
   ⇒ **160**.
4. Une ligne portant `[31mERREUR[0m` est rendue `ERREUR` — sans séquence
   d'échappement, sans caractère de contrôle.
5. `activity: "DANCING"` ⇒ la fiche porte `IDLE` et l'appel réussit ; `activity: "running!"` ⇒ 400.
6. `GET /api/runner-hosts/overview` porte `terminalPreview` sur le projet dont un terminal vit, et
   **`null`** sur les autres ; `hostTerminalPreview` suit la même règle pour le terminal du poste.
7. Deux fiches vivantes sur le même projet ⇒ la vue d'ensemble rend **la plus récente** ; à instant
   égal, celle qui porte `AWAITING_APPROVAL`.
8. **Isolation** : l'aperçu d'un utilisateur n'apparaît **jamais** dans le registre ni dans la vue
   d'ensemble d'un autre, même sur le même projet.
9. Un `POST` sur le projet d'autrui rend **404** et n'écrit **aucun** aperçu.
10. Une fiche expirée ne rend aucun aperçu, et la suppression de compte les emporte (déjà couvert
    par `deleteByUserId`, vérifié).
11. Le plafond de quatre et le 409 de F-70 restent **inchangés** : un test de non-régression le
    vérifie avec des aperçus présents.

---

## Plan de test minimal

### Unitaires (`LiveTerminalServiceTest`)

- écriture de l'aperçu à la prise **et** au renouvellement ;
- omission des champs ⇒ aperçu conservé ; `IDLE` + liste vide ⇒ aperçu effacé ;
- troncature : 6 lignes / 160 caractères / détail 120 ;
- nettoyage ANSI et caractères de contrôle ;
- activité inconnue ⇒ `IDLE` ;
- `previewsByWorkspace` : plus récent gagnant, `AWAITING_APPROVAL` gagnant à instant égal ;
- une fiche expirée n'apparaît dans aucun des deux.

### Intégration (`LiveTerminalApiIntegrationTest`)

- `POST` avec aperçu → `GET /api/terminals/live` le rend ;
- bornage vérifié **depuis l'API** (le client ne peut pas contourner) ;
- 400 sur `activity` malformée ; 409 inchangé ; 401 sans jeton.

### Isolation `user_id` (**obligatoire**)

- `POST` sur le projet d'un autre → 404, aucune ligne, aucun aperçu ;
- deux utilisateurs, un terminal chacun sur leur projet : `GET /api/terminals/live` ne rend que le
  sien, aperçu compris ;
- la vue d'ensemble de A ne porte aucun `terminalPreview` issu d'une fiche de B, même workspace.

### Vue d'ensemble (`RunnerHostOverviewServiceTest`)

- `terminalPreview` présent / absent ; `hostTerminalPreview` sur le terminal du poste ;
- `liveTerminal` et `liveTerminals` **inchangés**.

---

## Tables / endpoints / composants impactés

### Table (migration **`075-live-terminals-preview.xml`**, PostgreSQL + H2, `rollback` fourni)

Colonnes **ajoutées** à `live_terminals`, toutes **nullables** (une fiche sans aperçu est légitime :
un onglet vient de s'ouvrir) :

| Colonne | Type | Rôle |
|---|---|---|
| `activity` | `varchar(24)` | `IDLE`, `THINKING`, `RUNNING`, `AWAITING_APPROVAL` |
| `activity_detail` | `varchar(120)` | Ce qui est en cours (`npm test`), jamais une commande complète |
| `preview_lines` | `varchar(1024)` | Les dernières lignes, séparées par `\n` |
| `activity_at` | `timestamptz` | Instant du relevé — c'est lui qui départage deux onglets |

Aucun index nouveau : ces colonnes ne sont jamais un critère de lecture.

### Endpoints

| Méthode | Chemin | Changement |
|---|---|---|
| `POST` | `/api/workspaces/{id}/terminal/live` | Trois champs **facultatifs** de plus dans le corps |
| `GET` | `/api/terminals/live` | Quatre champs **additifs** par terminal |
| `GET` | `/api/runner-hosts/overview` | `terminalPreview` par projet, `hostTerminalPreview` par poste |

Aucun endpoint nouveau : F-76 n'ouvre pas un second canal pour ce que le premier peut porter.

### Classes

Nouvelles : `terminals/TerminalActivity`, `terminals/dto/TerminalPreview`.
Modifiées : `LiveTerminal`, `LiveTerminalService`, `dto/LiveTerminalClaimRequest`,
`dto/LiveTerminalsResponse`, `RunnerHostOverviewService`, `runner/host/dto/RunnerHostOverviewResponse`.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `activity` | facultatif, `[A-Z_]{1,24}` ; valeur hors vocabulaire ⇒ `IDLE` |
| `activityDetail` | facultatif, tronqué à 120 caractères **au serveur** |
| `previewLines` | facultatif, **6** dernières entrées, **160** caractères chacune, nettoyées |
| `sessionId` | inchangé (F-70) : obligatoire, 1–64, `[A-Za-z0-9_-]` |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Non** | Aucun nouveau type d'auth ; `CurrentUser.requireId()` comme avant, sur les mêmes routes. |
| Contexte tenant | **Non** | Aucun nouveau moyen de résoudre le tenant : `user_id` vient du jeton, jamais du corps ; `requireOwned` reste **avant** toute écriture. |
| Plans / limites | **Non** | Aucun plafond nouveau, aucun gate touché. Le plafond de quatre terminaux (F-70) et son 409 sont **inchangés** — vérifié par un test de non-régression avec aperçus. `UsageService`, `AtelierAccess`, `HostSeatService` : non modifiés, non appelés. |
| Navigation / routing | **Non** (backend) | Aucune route frontend. |

---

## Hors périmètre

- Tout affichage (SF-76-02 et SF-76-03).
- Écouter le flux côté gateway pour en déduire l'activité (arbitrage A1 du cadrage).
- Historiser les aperçus : la fiche porte **le dernier**, elle n'accumule pas — l'historique d'un
  tour, c'est `atelier_messages`.
- Notifier hors du navigateur qu'un terminal attend une autorisation.

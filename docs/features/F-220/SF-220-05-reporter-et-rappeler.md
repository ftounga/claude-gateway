# Mini-spec — [F-220 / SF-220-05] Reporter un chantier, rappeler les dormants (backend)

## Identifiant
SF-220-05

## Feature parente
F-220 — Le tableau de bord du client

## Statut
in-progress

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-05-reporter-rappeler`

---

## Objectif
Permettre de reporter un chantier (il quitte les groupes du tableau jusqu'à une date) et rappeler, au plus une fois par jour, qu'il reste des chantiers dormants — sans spam.

## Comportement attendu

### Cas nominal
- **Reporter** : `POST /api/workspaces/{ws}/journey/chantiers/{n}/snooze` `{ "days": 3 }` → `200 { "number": n, "until": "…" }` ; `until` = maintenant + `days` jours. Un nouveau report remplace l'ancien (une ligne par chantier).
- **Ne plus reporter** : `DELETE /api/workspaces/{ws}/journey/chantiers/{n}/snooze` → `204` (idempotent).
- **Tableau du client** (SF-220-02) : chaque chantier porte `snoozedUntil` (date ou `null`) ; un report échu vaut `null`. Les chantiers reportés **restent listés** (l'écran les range à part) mais **ne comptent pas** dans `counts` (couleurs, vivants, dormants) ; nouveau compteur `counts.snoozed`.
- **Rappel des dormants** : le récapitulatif quotidien existant (F-185 / SF-185-07, 9 h jours ouvrés, verrou `notification_digests` : **une notification par compte et par jour**) prévient aussi les comptes qui ont au moins un chantier ouvert **dormant** (aucun geste depuis `app.client-board.dormant-days`, défaut 2) et **non reporté**. Nouvel événement `CHANTIERS_DORMANT` — titre « Des chantiers dorment », corps « Ouvrez la Forge pour retrouver ce qui reste à faire. » (neutre, aucun nom de sujet). Priorité la plus basse : si le compte a aussi un point du matin ou une relance, c'est ce cas-là qui est notifié (une seule notification).
- Dormant (rappel) = chantier au focus dont le parcours **et** le dernier message du sujet datent d'avant le seuil, ou autre chantier ouvert dont la ligne date d'avant le seuil ; sujet guidé, chantier ouvert.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal d'autrui | `requireOwned` d'abord | 404 |
| Chantier non ouvert | « Le chantier N n'est pas ouvert. » | 400 |
| `days` absent, < 1 ou > 30 | « Reporter de 1 à 30 jours. » | 400 |
| Échec du rappel pour un compte | journalisé, les autres comptes continuent | — |

## Critères d'acceptation
1. Reporter de 3 jours → `until` ≈ maintenant + 3 j ; le tableau montre `snoozedUntil`, le chantier ne compte plus dans `counts.outil|moi|autrui|dormant`, `counts.snoozed = 1`.
2. Reporter à nouveau remplace la date ; `DELETE` retire le report (le chantier recompte).
3. `days = 0` ou `31` → 400 ; chantier 9 inexistant → 400 ; Bob → 404.
4. Un chantier dormant non reporté → le récapitulatif notifie `CHANTIERS_DORMANT` une fois ; relancé le même jour → rien.
5. Le même chantier reporté → aucun rappel.
6. Un compte avec une relance due et un chantier dormant → une seule notification, `ATTENTES_TO_FOLLOW_UP`.

## Périmètre
### Hors scope (explicite)
- L'écran (SF-220-06). Un rappel par chantier ou à une autre heure (le récapitulatif existant suffit, décision « sans spam »).

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `snoozed_until` | maintenant + `days` | calculé serveur, horloge applicative |
| `created_at` / `updated_at` | maintenant | à chaque report |

## Contraintes de validation
| Champ | Obligatoire | Format | Unicité |
|-------|-------------|--------|---------|
| `days` | oui | entier 1 à 30 | — |
| (user, workspace, number) | — | — | unique (un report par chantier) |

## Technique
### Endpoint(s)
| Méthode | URL | Auth |
|---------|-----|------|
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/snooze` | JWT |
| DELETE | `/api/workspaces/{ws}/journey/chantiers/{n}/snooze` | JWT |
| GET | `/api/runner-hosts/{hostId}/chantiers` (enrichi : `snoozedUntil`, `counts.snoozed`) | JWT |

### Tables impactées
| Table | Opération | Notes |
|-------|-----------|-------|
| `chantier_snoozes` | INSERT / UPDATE / DELETE / SELECT | **nouvelle** — migration `176` |
| `subject_journeys`, `subject_journey_chantiers` | SELECT | rappel : balayage des chantiers ouverts anciens |
| `notification_digests` | INSERT | verrou existant (un envoi par compte et par jour) |

### Migration Liquibase
- [x] `176-chantier-snoozes.xml` — `chantier_snoozes(id uuid pk, user_id uuid, workspace_id uuid fk workspaces cascade, chantier_number int, snoozed_until timestamptz, created_at, updated_at)`, unique `(user_id, workspace_id, chantier_number)`, index `(user_id, snoozed_until)`. Rollback : la table se retire.

## Préoccupations transversales
- Contexte tenant : le rappel lit **tous les comptes** (tâche de fond) mais chaque émission porte le `user_id` du chantier ; chaque report est lu/écrit sous `user_id` + `workspace_id` (`requireOwned`). Composants : `ChantierSnoozeService`, `DormantChantierScanner`, `AttentesDigestService`, `ClientBoardService`.
- Auth, plans/limites, navigation : non.

## Plan de test
### Tests unitaires
- `AttentesDigestServiceTest` (ou nouveau) : priorité des événements, dormant seul → `CHANTIERS_DORMANT`.
### Tests d'intégration
- `ChantierSnoozeIntegrationTest` : critères 1, 2, 3 ; rappel (critères 4, 5) via `AttentesDigestService.run()` et le centre de notifications.
### Isolation utilisateur
- [x] Applicable — Bob → 404 sur reporter / ne plus reporter ; le rappel n'émet que pour le propriétaire.

## Dépendances
- SF-220-02 — Done. F-185 (récapitulatif) — Done.

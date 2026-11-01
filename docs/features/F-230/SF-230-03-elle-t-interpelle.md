# Mini-spec — F-230 / SF-230-03 Elle t'interpelle

## Identifiant
`F-230 / SF-230-03` — Feature parente : F-230 « Les veilles Jarvis » (cadrage, décision **D4**, coûts).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-230-03-elle-t-interpelle`.

## Objectif
Sur changement seulement, un appel IA léger (par `AIProvider`) qui ne reçoit **que le diff** et le périmètre rédige une phrase ; une **alerte** est rangée, une notification **qui sonne** part vers Jarvis (repli : le fil du chantier), et la ligne est posée dans le fil du chantier — dans le budget de la veille, coût affiché en euros.

## Comportement attendu

### Cas nominal
1. **Écouteur de changement** `JarvisWatchAlerter` (branché sur SF-230-02).
2. **Budget** : plafond mensuel **par veille** (`monthly_budget_usd`, défaut 5 € ≈ 5,44 $) comparé à la somme des coûts de ses alertes du mois ; **garde de quota** du compte (`QuotaService.assertWithinQuota`). Budget ou quota épuisé ⇒ **pas d'appel IA** : phrase **déterministe** (« Agenor : 3 changements — 1 nouveau, 2 changements de statut ») — jamais le silence (D6).
3. **Appel IA** (`AIProvider.complete`, Provider Independence) : consigne système **stable** (aucune donnée volatile — règle du préfixe stable), message = plateforme + périmètre + diff JSON borné (12 000 caractères), plafond de sortie 300 jetons ; modèle `app.jarvis.watches.model` (défaut : modèle de la boucle). Clé : BYOK active sinon plateforme. Coût inscrit au journal d'usage (`UsageLedgerService.recordTurn`) et sur l'alerte. Échec du fournisseur ⇒ phrase déterministe. Aucun contenu journalisé.
4. **Alerte** rangée (`jarvis_watch_alerts`) : phrase, diff, coût, `opened_at`.
5. **Notification** `PushEvent.WATCH_CHANGED` (titre/corps **neutres** : « Une veille a vu du changement » / « Ouvrez Jarvis pour l'écouter. », toujours émise) ; lien `JarvisAlertLinks` : `/jarvis?alerte={id}` si la route Jarvis (F-231) est annoncée (`app.jarvis.route-enabled=true`), sinon **repli** `/atelier/{workspaceId}?alerte={id}` (le fil du sujet, chantier de la veille).
6. **Ligne dans le fil** du sujet (chantier de la veille) : « Veille « Agenor » — 2 tickets passés en bloqué… ».
7. **Contrat de lecture des alertes** (pour SF-231-05) :
   - `GET  /api/jarvis/alerts?limit=20` → `JarvisAlertView[]` (les plus récentes du compte) ;
   - `GET  /api/jarvis/alerts/{alertId}` → `JarvisAlertView` ;
   - `POST /api/jarvis/alerts/{alertId}/open` → `204` (marque lue — mesure D8, idempotent).
8. **Écran** : arrivée par `?alerte=` sur le sujet ⇒ la bande des veilles affiche l'alerte (phrase, changements, coût en €) et la marque ouverte.

### Contrat API
```json
// JarvisAlertView
{ "id": "uuid", "watchId": "uuid", "watchName": "Agenor", "platform": "Jira", "workspaceId": "uuid",
  "chantierNumber": 2, "sentence": "Agenor : 2 tickets passés en bloqué, 1 nouveau assigné à toi.",
  "changes": [{"kind": "STATUS", "id": "AG-2", "title": "Batch", "before": "En cours", "after": "Bloqué"}],
  "aiWritten": true, "costEur": 0.04, "createdAt": "…", "openedAt": null, "link": "/atelier/…?alerte=…" }
// JarvisWatchView + : { "monthlyBudgetEur": 5.00, "monthSpentEur": 0.12 }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Alerte d'un autre compte | `watch_not_found` | 404 |
| Budget de la veille / quota épuisé | phrase déterministe, notification quand même | — |
| Fournisseur en échec | phrase déterministe | — |
| Push non configuré | l'alerte va au centre de notifications ; jamais d'erreur | — |

## Contraintes de validation
Phrase ≤ 500 caractères ; diff transmis ≤ 12 000 caractères ; sortie ≤ 300 jetons ; budget par défaut 5 €/mois/veille.

## Critères d'acceptation
- [ ] Un changement produit **un** appel IA qui ne contient que le diff et le périmètre (jamais la liste complète) ; l'alerte porte la phrase et le coût.
- [ ] Budget épuisé ⇒ aucun appel IA, phrase déterministe, notification émise.
- [ ] La notification ouvre `/atelier/{ws}?alerte={id}` tant que Jarvis n'est pas annoncé, `/jarvis?alerte={id}` sinon.
- [ ] Une ligne apparaît dans le fil du chantier de la veille.
- [ ] Un autre compte reçoit 404 sur `GET /api/jarvis/alerts/{id}` et `open` ; la liste ne rend que ses alertes.

## Plan de test
- **Unitaires** : `JarvisWatchAlerterTest` (IA appelée avec le diff seul, budget épuisé, quota épuisé, échec fournisseur, lien), `JarvisAlertLinksTest`.
- **Intégration** : `JarvisAlertApiIntegrationTest` (liste, lecture, ouverture, **isolation 404**).
- **Frontend** : bande des veilles avec `?alerte=` (affiche, marque ouverte), service.

## Tables / endpoints / composants impactés
Table **`jarvis_watch_alerts`** (migration **187**). `PushNotificationService` (lien propre à l'événement), `PushEvent` (+ `WATCH_CHANGED`). Frontend : `atelier.component` (lecture du paramètre `alerte`), bande des veilles.

## Préoccupations transversales
- **Plans / limites** : consommation hors présence humaine. Composants : `QuotaService.assertWithinQuota` (garde), `UsageLedgerService.recordTurn` (journal, listeners dont alertes de coût `CostAlertService`/`CostBudget`), plafond mensuel par veille (`monthly_budget_usd`). `QuotaWindowService` n'est pas appelé directement : la garde de quota du compte passe par `QuotaService`.
- **Contexte tenant** : alertes portent `user_id` + `workspace_id`, lues par `findByIdAndUserId`.
- **Navigation** : lien profond `/jarvis?alerte=` (route F-231) avec repli `/atelier/{ws}?alerte=` ; la route `atelier/:id` existante lit un paramètre de plus, sans changer ses autres chemins.

## Hors périmètre
La lecture vocale de l'alerte (F-231) ; écrire sur la plateforme.

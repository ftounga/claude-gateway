# Mini-spec — [F-107 / SF-107-02] Les droits deviennent des droits d'espace

---

## Identifiant

`F-107 / SF-107-02`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage validé : `CADRAGE-F-107-l-offre-par-espace.md`, §3, §3 bis, §5)

## Statut

`done` — livrée le 2026-09-13 (PR #533)

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-02-droits-d-espace`

---

## Objectif

Un seul service, `SpaceEntitlementService`, répond à « ce compte a-t-il le droit à l'espace
`FORGE` / `VIGIE` ? » et absorbe `AtelierEntitlementService` et `TeamsEntitlementService` **sans
changer une seule de leurs réponses**.

---

## Contexte

Deux services de droit coexistent : `AtelierEntitlementService` (Forge : Gold inclus, option sur
Solo/Pro/BYOK, accès offert, administrateur) et `TeamsEntitlementService` (volet Teams, devenu le droit
de la Vigie en F-106 : option sur tout plan mensuel, accès offert, administrateur). Ils dupliquent la
même mécanique (statuts « en cours », plans porteurs, accès offert, rôle). SF-107-03 va ajouter Gold
Vigie, Gold complet et l'option Vigie : sans une source unique par espace, chaque règle serait écrite
deux fois. La règle « l'administrateur a tout » (SF-107-06) doit être **conservée**.

---

## Comportement attendu

### Cas nominal

1. **`EntitlementSpace`** (paquet `billing`) : `FORGE`, `VIGIE`. Le paquet `billing` ne dépend pas du
   paquet `runner` (même règle que `SeatSource`) : `ClientSpace` n'est pas réemployé.
2. **`SpaceEntitlementService`** porte, par espace, une **règle** : plans qui incluent l'espace, plans
   porteurs de l'option, colonne d'état de l'option. Valeurs **identiques à aujourd'hui** :

   | Espace | Inclus dans | Option portée par | État de l'option |
   |---|---|---|---|
   | `FORGE` | `GOLD` | `SOLO`, `PRO`, `BYOK` | `atelier_option_status` |
   | `VIGIE` | *(aucun)* | `SOLO`, `PRO`, `GOLD`, `BYOK` | `teams_option_status` |

   Méthodes : `isEntitled(UUID, space)`, `isEntitled(Subscription, space)`, `isIncludedInPlan(sub, space)`,
   `isGrantedByOption(sub, space)`, `isOptionCarrier(plan, space)`, `isGrantedByRole(userId)`,
   `isGrantedByAccessCode(userId)`.
3. **Ordre d'évaluation conservé** (il est observable par les tests) : rôle administrateur → (lecture de
   l'abonnement) → inclus au plan → option → accès offert. Un Gold ne consulte jamais l'accès offert
   pour la Forge ; un administrateur ne lit ni abonnement ni accès offert.
4. **Absorption** : `AtelierEntitlementService` et `TeamsEntitlementService` sont **supprimés** ; leurs
   appelants lisent `SpaceEntitlementService` avec l'espace :
   `AtelierAccessService` (FORGE), `AtelierOptionService` (FORGE), `TeamsAccessService` (VIGIE),
   `RunnerTeamsMomentController` (VIGIE). La garde au niveau des outils (`TeamsToolCatalog.toolsFor` →
   `TeamsAccessService.hasAccess(userId)`) est conservée telle quelle.
5. `AdministratorEntitlement` est lu par `SpaceEntitlementService` seul.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Espace `null` | `IllegalArgumentException` (erreur de programmation, jamais une entrée client) | — |
| Utilisateur sans droit Forge ouvre la Forge | `AtelierAccessDeniedException` inchangée | 403 |
| Utilisateur sans droit Vigie ouvre Teams / le Radar | `TeamsAccessDeniedException` inchangée | 403 |
| Capture runner sans droit Vigie | refus inchangé | 403 |
| `userId` nul ou inconnu | pas administrateur, règle d'abonnement (fail-closed) | — |

---

## Critères d'acceptation

- [ ] CA1 — Tous les cas de `AtelierEntitlementServiceTest` (Gold, sans option, avec option, BYOK,
      accès offert, administrateur) sont rejoués sur `SpaceEntitlementService` avec `FORGE` et donnent
      la **même** réponse.
- [ ] CA2 — Tous les cas de `TeamsEntitlementServiceTest` sont rejoués avec `VIGIE` et donnent la même
      réponse (aucun plan n'inclut la Vigie ; Gold porte l'option ; accès offert ; administrateur).
- [ ] CA3 — Un Gold actif : Forge vrai **sans** consulter l'accès offert ; Vigie faux sans option.
- [ ] CA4 — Les deux classes absorbées n'existent plus ; aucun appelant n'importe l'une d'elles.
- [ ] CA5 — `TeamsToolCatalogTest` (administrateur sans principal → outils `teams_*`),
      `AtelierAccessServiceTest`, `AtelierOptionServiceTest` et les tests d'intégration Forge / Teams /
      Radar restent verts.
- [ ] CA6 — Aucune réponse d'API modifiée (aucun DTO touché).

---

## Périmètre

### Hors scope (explicite)

- Gold Vigie, Gold complet, option Vigie, montants (SF-107-03).
- Code d'accès par espace, essai Vigie, réserve (SF-107-04).
- Supplément par client par espace (SF-107-05).
- Renommage des colonnes `atelier_option_*` / `teams_option_status` et des DTO (aucune migration).
- Renommage d'`AtelierAccessService` / `TeamsAccessService` (services d'accès HTTP, hors droit).

---

## Valeurs initiales

Aucune.

## Contraintes de validation

Aucun champ saisi.

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | SELECT | inchangé |
| `users` | SELECT (`role`) | inchangé |
| `access_codes` | SELECT | inchangé |

### Migration Liquibase

- [x] Non applicable

### Composants impactés

- Backend : `EntitlementSpace` (nouveau), `SpaceEntitlementService` (nouveau),
  `AtelierEntitlementService` et `TeamsEntitlementService` (supprimés), `AtelierAccessService`,
  `AtelierOptionService`, `TeamsAccessService`, `RunnerTeamsMomentController`,
  `AdministratorEntitlement` (javadoc).
- Frontend : aucun.

### Préoccupations transversales

- [x] **Plans / limites** — tous les lecteurs d'un droit de fonctionnalité :
  `AtelierAccessService` (garde de toutes les routes Forge, `AtelierAgentController`,
  `AtelierChatController`…), `AtelierOptionService` (`describe`, `startCheckout`),
  `TeamsAccessService` → `TeamsAccessController`, `TeamsToolCatalog`, `RadarController`,
  `RadarSyncPlanner`, `RadarExchangeAnalyzer`, Vigie (F-106) ; `RunnerTeamsMomentController` (direct).
  Gates de quota (`EntitlementService`, `QuotaService`) : **non touchés**. Non-régression : suites
  unitaires rejouées cas par cas + suite backend complète.
- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non.
- [ ] Navigation / routing — non.

---

## Plan de test

### Tests unitaires

- [ ] `SpaceEntitlementServiceTest` — portage **intégral** des deux suites (FORGE et VIGIE), plus :
      espace nul refusé ; `isOptionCarrier` par espace ; Gold → Forge sans lecture d'accès offert.
- [ ] `AtelierAccessServiceTest`, `AtelierOptionServiceTest`, `TeamsToolCatalogTest` — construits sur
      le nouveau service, assertions inchangées.

### Tests d'intégration

- [ ] Suite existante : `AtelierOptionBillingApiIntegrationTest`, `ByokPlanApiIntegrationTest`,
      intégration Teams / Radar / Vigie — vertes sans modification d'assertion.

### Isolation

- [x] Applicable — aucune lecture nouvelle : l'abonnement est lu par `getOrCreateForUser(userId)` du
      contexte de sécurité ou du tour, jamais d'un paramètre client.

---

## Dépendances

### Subfeatures bloquantes

- F-106 SF-106-01 (livrée) ; SF-107-06 (livrée, règle conservée).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Suppression plutôt que façades** : garder les deux classes en délégation laisserait trois portes
  vers le même droit ; les suites de tests sont portées cas par cas, ce qui prouve l'invariance des
  réponses.
- **Enum propre au paquet `billing`** : le droit ne doit pas dépendre du modèle des postes.

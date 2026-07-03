# Mini-spec — [F-21 / SF-21-01] Tokens rachetés (bonus) dans le quota

## Identifiant — Parente — Statut

`F-21 / SF-21-01` — `F-21` Rachat de tokens (top-up) — `ready`

## Date — Branche

2026-07-03 — `feat/SF-21-01-quota-bonus-tokens`

---

## Objectif

Permettre à des tokens **rachetés** (top-up) de s'ajouter au quota mensuel : introduire un solde
`bonus_tokens` par période et l'inclure dans le quota effectif — **sans Stripe** (SF-21-02) ni UI
(SF-21-03). Fondation sûre et testable de F-21.

## Comportement attendu

- Le **quota effectif** de la période = quota d'abonnement (F-10) **+** `bonus_tokens` de la période.
- `QuotaService.assertWithinQuota` bloque quand `used >= quota effectif` (donc un utilisateur ayant
  racheté des tokens peut consommer au-delà de son quota d'abonnement).
- `QuotaService.creditBonusTokens(userId, tokens)` crédite le solde bonus de la période courante
  (crée la ligne si absente) ; montant ≤ 0 ignoré.
- `GET /usage` (instantané) reflète le bonus dans `quotaTokens`/`remainingTokens`.

## Critères d'acceptation

- [ ] Un utilisateur à sa limite d'abonnement mais avec un bonus > 0 **n'est pas bloqué** (jusqu'à quota+bonus).
- [ ] Sans bonus, le blocage à la limite est **inchangé** (non-régression F-10).
- [ ] `creditBonusTokens` incrémente `bonus_tokens` de la période ; ≤ 0 ignoré (aucune écriture).
- [ ] L'instantané `/usage` inclut le bonus dans le quota et le restant.
- [ ] Migration `bonus_tokens` (défaut 0) appliquée (H2 + PostgreSQL).

## Périmètre / Hors scope

- **Stripe** (checkout pack de tokens + webhook de crédit) : SF-21-02. **Frontend** (bouton racheter) : SF-21-03.

## Préoccupation transversale — Plans / limites

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `QuotaService.assertWithinQuota` | Quota effectif = base + bonus | Test : bonus élargit, sans bonus inchangé |
| `QuotaService.currentUsage` | Snapshot reflète le bonus | Test snapshot |
| `UsageCounter` | Nouvelle colonne `bonus_tokens` | Migration + entité |
| Endpoints consommant le quota (`/chat`, `/chat/stream`, `/ask`) | Utilisent `assertWithinQuota` → bénéficient du bonus sans changement | Suite existante inchangée |

## Technique

- Migration `032-usage-counters-bonus.xml` : `usage_counters.bonus_tokens BIGINT NOT NULL DEFAULT 0`.
- `UsageCounter.bonusTokens`. `QuotaService` : `effectiveQuota` (base + bonus), `currentPeriodBonus`,
  `creditBonusTokens`.

### Migration

- [x] Oui — `032-usage-counters-bonus.xml` (addColumn, réversible par dropColumn).

## Plan de test

- [ ] `QuotaBonusTest` — bonus élargit le quota ; sans bonus bloqué ; `creditBonusTokens` incrémente / ignore ≤ 0 ; snapshot inclut le bonus.
- [ ] `QuotaServiceTest` inchangé (non-régression). `UsageApiIntegrationTest` (migration H2).

### Isolation

- [x] Le bonus est par `user_id`+période (même clé que l'usage) ; aucune fuite entre utilisateurs.

## Notes

- Découpe volontaire : la mécanique quota (sûre) est livrée avant l'intégration Stripe (argent), qui sera testée soigneusement en SF-21-02.

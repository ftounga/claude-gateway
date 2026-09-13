# Mini-spec — [F-107 / SF-107-05] Le supplément par client, par espace

---

## Identifiant

`F-107 / SF-107-05`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage validé : `CADRAGE-F-107-l-offre-par-espace.md`, §4, §5, §9 ; `docs/TARIFS.md` §7 bis, §8.2)

## Statut

`done` — livrée le 2026-09-13 (PR #536)

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-05-supplement-par-espace`

---

## Objectif

Le supplément par client (F-65) devient **un supplément par espace**, servi sur tous les plans BYOK
compris : **Forge** 39 / 29 / 19 € avec 2 / 1,5 / 1 M jetons (0 jeton en BYOK), **Vigie** 39 € fixe ;
un mois-client se compte **par espace** (`host_seat_months.space`) ; montants en configuration, price
IDs vides.

---

## Contexte

F-65 a livré le mécanisme **inerte** (`tokens-per-extra-seat` = 0, `quota-tiers` vide, `price-id` vide)
et un décompte unique par poste. Le cadrage F-107 §4 (révisé avec le PO) : un client en plus dans la
Forge coûte des jetons, un client en plus suivi par la Vigie coûte une synchro chaque nuit — **chaque
espace facture ce qu'il coûte** ; l'appairage et la clôture de mission restent uniques. OQ-16 point 8
est tranché par la grille §9.

---

## Comportement attendu

### Cas nominal

1. **Configuration Forge** (`app.seat`, clés existantes) — défauts décidés :
   `included-seats` 1 ; `quota-tiers` = suppléments 1-2 (2ᵉ-3ᵉ client) **2 000 000 jetons, 39 €** ;
   3-5 (4ᵉ-6ᵉ) **1 500 000, 29 €** ; 6 et au-delà (7ᵉ+) **1 000 000, 19 €** (un palier porte désormais
   `display-price`) ; `price-id` **vide**.
2. **Configuration Vigie** (`app.seat.vigie`, nouveau) : `included-seats` 1 (l'option Vigie comprend un
   client suivi), `display-price` **39**, `price-id` **vide**. Aucun jeton de conversation : un client
   suivi a sa **réserve de synchro** (3 M / mois, SF-107-04).
3. **Les jetons suivent la facturation** : un supplément Forge n'apporte ses jetons au quota **que si
   son price est branché** (`price-id` non vide). Tant que le PO n'a pas créé le price, le quota reste
   exactement celui d'avant — aucune part gratuite. En BYOK, **0 jeton** (règle F-41 inchangée).
4. **Comptage par espace** : `SeatSource.billableSeats(userId, space)` — postes non clôturés **activés
   dans l'espace** (poste sans ligne d'espace = Forge) ; début de facturabilité : création du poste
   (Forge) ou **activation dans la Vigie** (Vigie).
5. **Mois-client par espace** (migration `095`) : `host_seat_months.space` (défaut `FORGE` pour
   l'existant) ; unicité `(host_id, space, period_start)`. La **clôture de mission** (geste unique)
   note un mois-client dans **chaque espace** du poste ; la **réouverture** de même ; le **retrait
   d'un espace** en cours de mois note le mois engagé **dans cet espace** ; la suppression du poste
   efface tout.
6. **API** : `GET /api/billing/seats?space=FORGE|VIGIE` (défaut `FORGE`) ; la réponse gagne `space`,
   `displayPrice` par client en supplément, et `tokensApply` (faux en BYOK).
7. **Écran d'abonnement** : volet « Clients — Forge » (existant, montants par palier) et volet
   « Clients — Vigie » (affiché si au moins un client y est compté).
8. **Docs** : `TARIFS.md` — la grille §7 bis entre dans les sections servies (§1, §3, §8.2), §7 bis
   devient l'historique de la décision ; `OPEN_QUESTIONS.md` OQ-16 point 8 **livré**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `space` inconnu | `invalid_client_space` (existant) | 400 |
| Non authentifié | 401 | 401 |
| Price vide | supplément non facturé, **0 jeton**, l'écran le dit | 200 |
| Offre BYOK | montants affichés, **0 jeton** apporté | 200 |
| Clôture d'un poste retiré entre-temps d'un espace | un seul mois-client par espace (unicité) | — |

---

## Critères d'acceptation

- [ ] CA1 — défauts : 3 clients Forge sur Solo sans price → quota inchangé, `grantedTokens` 0, prix
      affichés 39 / 39.
- [ ] CA2 — price Forge branché : 2ᵉ et 3ᵉ client +2 M chacun, 4ᵉ +1,5 M, 7ᵉ +1 M (proratisation
      inchangée).
- [ ] CA3 — BYOK avec price branché : `tokensApply=false`, aucune part au quota.
- [ ] CA4 — un client Forge seul n'apparaît pas dans `space=VIGIE` ; un client activé dans la Vigie y
      compte depuis son activation ; `includedSeats` Vigie = 1, prix 39.
- [ ] CA5 — clôture d'un poste des deux espaces : un mois-client Forge **et** un Vigie ; réouverture le
      même mois : rien de plus.
- [ ] CA6 — retrait de la Vigie en cours de mois : le client reste compté dans la Vigie jusqu'à la fin
      du mois (clos), plus le mois suivant.
- [ ] CA7 — migration : lignes existantes lues `FORGE`.
- [ ] CA8 — isolation `user_id` : un autre compte ne voit ni ne compte ces clients.
- [ ] CA9 — `TARIFS.md` servie, OQ-16 point 8 livré.

---

## Périmètre

### Hors scope (explicite)

- Création des prices Stripe (dégressif Forge, fixe Vigie) et envoi des quantités à Stripe (F-65 ne
  pousse aucune quantité ; inchangé).
- Réserve de synchro apportée par un client Vigie de plus : la réserve est déjà **par client suivi**
  (SF-107-04) ; aucune enveloppe supplémentaire à créer.
- Garde « Vigie sans Forge » sur les routes postes et terminal (question au PO, SF-107-04).
- Maintien de tarif : aucun abonnement concerné.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `app.seat.quota-tiers` | 2 : 2 M / 39 € · 5 : 1,5 M / 29 € · 6 : 1 M / 19 € | §9 |
| `app.seat.display-price` | `39` | premier palier |
| `app.seat.price-id` | vide | aucun price créé par un agent |
| `app.seat.vigie.included-seats` | `1` | §9 : l'option comprend un client suivi |
| `app.seat.vigie.display-price` | `39` | §9 |
| `app.seat.vigie.price-id` | vide | aucun price créé par un agent |

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `space` (query) | absent → `FORGE` ; `FORGE`/`VIGIE`, casse indifférente ; autre → 400 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/seats?space=` | Oui | USER — `space`, `tokensApply`, `displayPrice` par client |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `host_seat_months` | ALTER + INSERT/SELECT | `space varchar(16) not null default 'FORGE'`, unicité `(host_id, space, period_start)` |
| `host_spaces` | SELECT | espaces d'un poste, date d'activation |
| `runner_hosts` | SELECT | inchangé |

### Migration Liquibase

- [x] `095-host-seat-months-space.xml` — colonne avec défaut (existant = FORGE), remplacement de la
      contrainte d'unicité ; rollback : contrainte d'origine rétablie, colonne supprimée.

### Composants impactés

- Backend : `SeatProperties` (+ `Vigie`, `QuotaTier.displayPrice`), `SeatSource`, `RunnerHostSeatSource`,
  `SeatQuotaService`, `SeatLedgerService`, `SeatUsage`, `HostSeatMonth`, `HostSeatMonthRepository`,
  `SeatsResponse`, `BillingController`, `RunnerHostService` (inchangé en signature), `HostSpaceService`
  (retrait d'espace), `EntitlementService` (lecture Forge seule), `application.yml`.
- Frontend : `seat.models.ts`, `seat.service.ts`, `billing.component.{ts,html}`.
- Docs : `TARIFS.md`, `OPEN_QUESTIONS.md`, `ARCHITECTURE_CANONIQUE.md`.

### Préoccupations transversales

- [x] **Plans / limites** — le supplément touche le quota. Appels : `EntitlementService.resolveEffectiveMonthlyTokenQuota`
  → `SeatQuotaService.grantedTokens` (pré-vol `QuotaService`, jauge `UsageController`, alertes
  `QuotaAlertService`, fenêtre `QuotaWindowService`) ; `BillingController.seats` ; écriture
  `SeatLedgerService` depuis `RunnerHostService.setMissionStatus`, `deleteWithCredentials` et
  `HostSpaceService.remove`. Non-régression : sans price, quota strictement identique (tests existants
  `SeatDefaultsApiIntegrationTest`, `QuotaService`).
- [ ] Auth / Principal, tenant, navigation — non.

---

## Plan de test

### Tests unitaires

- [ ] `SeatPropertiesTest` — paliers avec montants, Vigie par défaut, montant par rang.
- [ ] `SeatQuotaServiceTest` — par espace ; jetons nuls sans price ; montant par rang ; BYOK.
- [ ] `SeatLedgerServiceTest` — clôture / réouverture par espace ; retrait d'espace.

### Tests d'intégration

- [ ] `SeatApiIntegrationTest` — paliers de la grille ; `space=VIGIE` (activation, retrait, clôture) ;
      isolation ; 400 sur espace inconnu.
- [ ] `SeatDefaultsApiIntegrationTest` — défauts : quota inchangé, 0 jeton, prix 39.

### Frontend

- [ ] `billing.component.spec` — volet Vigie affiché quand des clients y comptent ; montant par client.

### Isolation

- [x] Applicable — toutes les lectures partent du `userId` du contexte ; test d'un second compte.

---

## Dépendances

### Subfeatures bloquantes

- F-106 SF-106-01 (`host_spaces`), SF-107-04 (réserve par client suivi).

### Questions ouvertes impactées

- [x] **OQ-16 point 8** — tranché par le PO le 2026-09-13 (cadrage §9), **livré ici**.

---

## Notes et décisions

- **Jetons conditionnés au price** : les montants et jetons décidés sont servis en configuration, mais
  une part de jetons sans supplément facturé serait un cadeau ; le branchement du price par le PO ouvre
  les deux ensemble.
- **Vigie sans jeton de conversation** : ce qu'apporte un client suivi est sa réserve de synchro, déjà
  par client ; la dupliquer en configuration de facturation ferait deux vérités.

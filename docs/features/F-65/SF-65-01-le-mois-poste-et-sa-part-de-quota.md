# Mini-spec — SF-65-01 · Le mois-poste, compté et doté de sa part de quota

## Identifiant

`F-65 / SF-65-01`

## Feature parente

`F-65` — Supplément par poste supplémentaire (cadrage : `docs/features/F-65/F-65-cadrage.md`)

## Statut

`done` — PR #364, mergée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-65-01-mois-poste-quota`

---

## Objectif

Compter, par utilisateur et par mois, les **postes facturables** (tout sauf `CLOSED`), en déduire le
nombre de **suppléments** au-delà du poste inclus dans l'abonnement, et ajouter au quota de la
période la **part de jetons** que ces suppléments apportent — le tout piloté par une configuration
dont les **défauts ne changent rien** au comportement actuel.

---

## Comportement attendu

### Cas nominal

1. Un utilisateur possède *n* postes ; ceux dont `mission_status <> CLOSED` sont **facturables**.
2. Pour la période courante (mois calendaire UTC, même définition que F-10), le service construit
   l'ensemble des **mois-postes** : les postes facturables aujourd'hui, **plus** ceux qui ont une
   ligne `host_seat_months` sur cette période (ils ont été facturables plus tôt dans le mois, même
   s'ils sont clôturés maintenant).
3. Chaque mois-poste porte une date de **début de facturabilité** :
   - ligne présente → la date qu'elle porte ;
   - sinon → `max(premier jour du mois, date de création du poste)`.
4. Les mois-postes sont ordonnés par date de début croissante ; les `included-seats` premiers
   (défaut **1**) sont **couverts par le plan** ; les suivants sont des **suppléments**, numérotés
   1, 2, 3… dans cet ordre.
5. Le supplément de rang *r* apporte `tokens(r) × fraction` jetons, où :
   - `tokens(r)` = premier palier `quota-tiers` dont `up-to-seats >= r`, sinon le dernier palier,
     sinon `tokens-per-extra-seat` (défaut **0**) ;
   - `fraction` = `(jours du mois − jour de début + 1) ÷ jours du mois`, ou **1** si
     `proration: NONE` ;
   - le résultat est **tronqué** à l'entier inférieur (on n'arrondit jamais un quota vers le haut).
6. Ce total s'ajoute au quota mensuel de l'abonnement, **au même titre** que les jetons rachetés
   (F-21), partout où le quota est calculé : pré-vol (`assertWithinQuota`), jauge (`GET /usage`),
   seuil d'alerte (F-42).
7. `GET /billing/seats` rend l'état complet : postes comptés, lequel est inclus, part de jetons de
   chacun, total, et si le supplément est **réellement facturé** (price ID configuré) ou non.

### Écritures dans `host_seat_months` — deux, et deux seulement

| Transition | Écriture | Pourquoi |
|---|---|---|
| facturable → `CLOSED` | insère une ligne si absente, début = `max(début de période, création du poste)` | sans elle, le poste clôturé disparaîtrait du mois qu'il a déjà engagé |
| `CLOSED` → facturable | insère une ligne si absente, début = **aujourd'hui** ; **si présente, ne touche à rien** | c'est toute la règle de réouverture : le mois-poste est déjà payé |

Aucune écriture à la création d'un poste : sa date de création suffit à dater son début.
Aucune écriture périodique, aucun job planifié : un mois sans ligne se lit « facturable depuis le
premier jour », ce qui est vrai par construction.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Utilisateur non authentifié sur `GET /billing/seats` | Rejet par le filtre JWT | 401 |
| Aucun poste | Réponse valide, listes vides, totaux à 0 | 200 |
| `tokens-per-extra-seat` non configuré (défaut 0) | Mécanisme **inerte** : postes comptés et affichés, **aucun jeton** ajouté | 200 |
| Abonnement en essai, résilié, incomplet | **Aucun** apport de jetons (le supplément est un abonnement payant, pas un cadeau d'essai) | 200 |
| Offre **BYOK** | **Aucun** apport : la plateforme n'alloue aucun jeton sur cette offre (F-41), et son zéro doit rester un zéro | 200 |
| Palier mal configuré (`up-to-seats <= 0`, jetons négatifs) | Entrée ignorée au binding, jamais de quota négatif | — |
| Poste supprimé | Ses mois-postes sont supprimés avec lui : supprimer un poste détruit, clôturer range | 204 |

---

## Critères d'acceptation

1. Un utilisateur avec **1 poste** facturable a exactement le quota de son plan — **inchangé**.
2. Avec `tokens-per-extra-seat = T` et 3 postes facturables depuis le début du mois, le quota est
   `plan + 2 × T`.
3. Un poste créé le 16 d'un mois de 30 jours apporte `T × 15/30` (tronqué).
4. **Réouverture** : clôturer puis rouvrir un poste dans le même mois laisse le quota et le nombre
   de postes comptés **strictement identiques** — aucun jeton en plus, aucune ligne réécrite.
5. **Clôture** : clôturer un poste en cours de mois ne retire **ni** le poste du décompte du mois,
   **ni** les jetons déjà apportés ; le mois suivant, il n'est plus compté.
6. Un poste clôturé **avant** le début du mois n'est jamais compté.
7. Avec la configuration par défaut (rien de configuré), le quota de tout utilisateur est **le même
   qu'avant la livraison** — vérifié par un test dédié.
8. Offre BYOK et essai : aucun apport de jetons quel que soit le nombre de postes.
9. `GET /billing/seats` ne renvoie que les postes **du demandeur** (isolation `user_id`), et jamais
   un price ID Stripe.
10. Aucun appel Stripe n'est émis par ce chemin.

---

## Plan de test minimal

### Unitaires

- `SeatQuotaServiceTest` : 1 poste = 0 supplément ; 3 postes = 2 suppléments ; proratisation (jour
  1, jour 16, dernier jour) ; `proration: NONE` ; paliers (rang sous le premier palier, entre deux,
  au-delà du dernier) ; liste de paliers vide → apport plat ; `tokens-per-extra-seat = 0` → 0.
- `SeatLedgerServiceTest` : écriture à la clôture ; écriture à la réouverture quand aucune ligne ;
  **non-écriture** à la réouverture quand une ligne existe ; idempotence d'une double clôture.
- `SeatPropertiesTest` : défauts (1 poste inclus, 0 jeton, `DAILY`, aucun palier), valeurs
  aberrantes neutralisées.
- `EntitlementServiceTest` : quota effectif = plan + apport ; aucun apport en essai, BYOK, résilié.

### Intégration

- `SeatQuotaIntegrationTest` (`@SpringBootTest`) : deux utilisateurs, postes croisés — chacun ne
  voit et ne reçoit que **ses** postes ; `GET /billing/seats` sur le compte A ne montre aucun poste
  de B ; `GET /usage` reflète l'apport ; cycle clôture → réouverture sans effet.

### Isolation utilisateur

- Toute lecture de `host_seat_months` et de `runner_hosts` filtre sur `user_id`.
- Purge de compte (F-11) : les mois-postes disparaissent avec le compte.

---

## Tables / endpoints / composants impactés

| Élément | Nature |
|---|---|
| `host_seat_months` (migration **070**) | **nouvelle table** — `id`, `user_id`, `host_id`, `period_start`, `billable_from`, `created_at` ; unicité `(host_id, period_start)` ; index `(user_id, period_start)` |
| `fr.claudegateway.billing.seat.*` | **nouveau package** — `SeatProperties`, `SeatProration`, `HostSeatMonth`, `HostSeatMonthRepository`, `SeatSource`, `SeatLedgerService`, `SeatQuotaService`, `SeatUsage`, `dto/SeatsResponse` |
| `RunnerHostSeatSource` (`runner.host`) | **nouveau** — implémente `SeatSource` : les postes facturables du propriétaire |
| `RunnerHostService.setMissionStatus` / `delete` | **modifié** — informe le registre des mois-postes |
| `EntitlementService` | **modifié** — `resolveEffectiveMonthlyTokenQuota` = plan + apport des suppléments |
| `QuotaService`, `QuotaAlertService` | **modifiés** — utilisent le quota effectif |
| `BillingController` | **modifié** — `GET /billing/seats` |
| `AccountService` | **modifié** — purge des mois-postes |
| `application.yml` | **modifié** — bloc `app.seat` (défauts inertes) |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | Non | `GET /billing/seats` prend l'identité du JWT comme les autres routes de `BillingController` |
| Contexte tenant | **Oui** | `SeatQuotaService`, `SeatLedgerService`, `RunnerHostSeatSource`, `HostSeatMonthRepository`, `BillingController` — tous filtrent sur le `user_id` du contexte de sécurité, jamais un paramètre client |
| **Plans / limites** | **Oui** | Le quota effectif change de formule. Composants revus un à un : `QuotaService.assertWithinQuota` (pré-vol), `QuotaService.currentUsage` (jauge `GET /usage`), `QuotaAlertService.evaluateAfterUsage` et `currentAlert` (seuil F-42), `EntitlementService.resolveMonthlyTokenQuota` (conservé **pur**, allocation du plan seule, utilisé par `GET /billing/plans` qui doit continuer d'annoncer le quota du **plan**). Non concernés et vérifiés comme tels : `assertWithinSandboxLimit` (secondes, pas jetons), `AtelierEntitlementService` (droit d'accès, pas allocation) |
| Navigation / routing | Non (backend) | — |

---

## Hors périmètre

- Tout montant, tout quota chiffré, tout palier réel : **PO + Stripe** (défauts inertes).
- Tout appel à Stripe : aucune quantité poussée, aucun price créé, aucun abonnement modifié.
- Toute sanction : un supplément non payé ne coupe rien — F-65 compte, il ne bloque pas.
- L'écran : c'est SF-65-02.

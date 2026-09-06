# Mini-spec — [F-41 / SF-41-03] Le plan BYOK sur l'écran de facturation

## Identifiant

`F-41 / SF-41-03`

## Feature parente

`F-41` — Plan BYOK (plateforme seule)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-41-03-byok-ecran-facturation`

---

## Objectif

Faire dire à l'écran de facturation ce que l'offre BYOK est réellement — **aucun jeton inclus, vos
appels sur votre clé** — au lieu de « 0 tokens inclus / mois » et d'une jauge à 100 % marquée
« Quota atteint », et rappeler à l'abonné BYOK **sans clé** où déposer la sienne.

---

## Comportement attendu

### Ce que l'écran affiche aujourd'hui, et pourquoi c'est faux

Depuis SF-41-01, le plan `BYOK` arrive dans `GET /billing/plans` avec `tokens: 0`, et
`GET /usage` rend `quotaTokens: 0` pour son abonné. L'écran de facturation, tel quel :

- affiche **« 0 tokens inclus / mois »** sur la carte d'offre — techniquement exact, commercialement
  absurde : on dirait une offre vide, pas une offre où le client apporte ses jetons ;
- affiche la jauge à **100 %** avec le badge **« Quota atteint »** (`usagePercent()` rend 100 quand
  `quotaTokens <= 0`, `quotaReached()` est vrai dès que `used >= 0`) — c'est-à-dire exactement le
  message d'un compte bloqué, servi à un client parfaitement à jour.

C'est la verrue annoncée en SF-41-01, et le fait qu'elle vive **à l'écran** ne la rend pas moins
grave : le premier réflexe d'un client qui lit « Quota atteint » est d'écrire au support.

### Cas nominal

1. **Carte d'offre BYOK** (section « Abonnements mensuels ») : prix venu de l'API, puis
   « **Aucun jeton inclus** — vos appels passent par votre clé Anthropic, facturés sur votre compte »,
   et la mention « Atelier inclus » (le droit est porté par le plan depuis SF-41-01).
2. **Bandeau de statut**, quand l'offre courante est BYOK : la jauge de quota est remplacée par une
   ligne qui dit la vérité — « Servi par votre clé Anthropic · aucun quota plateforme » — suivie de
   la consommation de la période **à titre indicatif**, sans reste ni pourcentage.
3. **Rappel actionnable**, quand l'offre courante est BYOK **et** qu'aucune clé active n'est
   enregistrée : un encart visible dit que les appels sont refusés et propose un lien vers
   **Paramètres** (`/settings`), là où la clé se dépose. Le même vocabulaire que le refus serveur
   (`byok_key_required`, SF-41-02) : l'écran et l'API racontent la même histoire.
4. Dès qu'une clé active existe, l'encart disparaît sans rechargement de page (relecture du statut).

### La source de vérité de « mon offre est BYOK »

Le frontend **ne devine pas** l'offre depuis la chaîne `"BYOK"`. `GET /billing/subscription` gagne un
champ `customerKeyBilled`, rempli par le prédicat serveur exact qui décide du comportement
(`EntitlementService.isCustomerKeyBilled`). L'écran reflète la décision du serveur au lieu de la
re-dériver — une seule source de vérité, y compris si le catalogue évolue.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /user/api-key` en échec | Encart de rappel **masqué**, écran de facturation pleinement utilisable (échec non bloquant, comme les recharges et l'option Atelier) | — |
| `GET /billing/subscription` en échec | Comportement actuel inchangé : snackbar d'erreur, aucune section BYOK affichée | — |
| Offre BYOK, clé présente mais **désactivée** (mode Hosted, SF-03-03) | Encart affiché : une clé inactive ne sert aucun appel (même règle que le serveur) | — |
| Offre Hosted (Solo/Pro/Gold/Daily) ou essai | **Aucun** changement : jauge de quota, cartes et libellés identiques à aujourd'hui | — |

---

## Critères d'acceptation

- [ ] `GET /api/billing/subscription` expose `customerKeyBilled` (booléen), calculé par `EntitlementService.isCustomerKeyBilled` — jamais recalculé côté client.
- [ ] La carte d'offre BYOK affiche « Aucun jeton inclus » et la mention Atelier, **jamais** « 0 tokens inclus / mois ».
- [ ] Les cartes des offres Hosted gardent exactement leur libellé « N tokens inclus / mois ».
- [ ] Quand `customerKeyBilled` est vrai, le bandeau de statut **n'affiche ni jauge, ni pourcentage, ni badge « Quota atteint »** ; il affiche la consommation de la période et la mention « aucun quota plateforme ».
- [ ] Quand `customerKeyBilled` est vrai **et** qu'aucune clé active n'est enregistrée, l'encart de rappel s'affiche avec un lien vers `/settings`.
- [ ] Quand une clé active est enregistrée, l'encart ne s'affiche pas.
- [ ] Quand `customerKeyBilled` est faux, l'encart ne s'affiche **jamais**, même sans clé (non-régression Hosted).
- [ ] L'échec de `GET /user/api-key` masque l'encart sans casser l'écran.
- [ ] Design system : couleurs et polices de `DESIGN_SYSTEM.md` uniquement (jetons `--cg-*`), aucun `window.confirm`/`alert`, notifications par `MatSnackBar`.

---

## Périmètre

### Hors scope (explicite)

- Tout écran **autre** que la facturation : les Paramètres (dépôt de la clé) existent déjà (F-03) et
  ne sont pas retouchés ; l'écran de refus de l'Atelier a été traité en SF-40-03.
- La traduction du refus `byok_key_required` **dans le chat et l'Atelier** : ces écrans affichent
  déjà le message serveur, qui est actionnable depuis SF-41-02.
- Toute modification du quota, du catalogue, du prix ou du garde-fou (livrés en SF-41-01 / SF-41-02).

---

## Valeurs initiales

Sans objet : aucune entité créée.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `customerKeyBilled` (réponse API) | Oui | — | booléen, jamais `null` | — | — |
| condition « clé active » (client) | — | — | `present === true && mode === 'BYOK'` (le backend met `mode` à `BYOK` **ssi** la clé est active) | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/subscription` | Oui | USER — enrichi de `customerKeyBilled` |
| GET | `/api/user/api-key` | Oui | USER — **existant**, consommé par l'écran de facturation |

### Tables impactées

Aucune (lectures existantes).

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable**

### Composants Angular (si applicable)

- `BillingComponent` — carte d'offre BYOK, bandeau de statut sans jauge, encart de rappel.
- `billing.component.html` / `.scss` — l'encart réutilise les classes existantes ; **aucune couleur
  nouvelle** (jetons `--cg-accent`, `--cg-text-secondary`, `--cg-surface`).
- `billing.models.ts` — `SubscriptionView.customerKeyBilled`.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `SubscriptionResponse` — `customerKeyBilled` vrai pour un abonnement BYOK en cours, faux sinon.

### Tests d'intégration (backend)

- [ ] `GET /api/billing/subscription` (BYOK actif) → `customerKeyBilled: true`.
- [ ] `GET /api/billing/subscription` (Solo actif) → `customerKeyBilled: false`.
- [ ] `GET /api/billing/subscription` (BYOK résilié) → `customerKeyBilled: false`.

### Tests unitaires (frontend, `billing.component.spec.ts`)

- [ ] Offre BYOK sans clé ⇒ `showByokKeyReminder()` vrai.
- [ ] Offre BYOK avec clé active ⇒ `showByokKeyReminder()` faux.
- [ ] Offre BYOK avec clé **désactivée** (`mode: 'HOSTED'`) ⇒ `showByokKeyReminder()` vrai.
- [ ] Offre Hosted sans clé ⇒ `showByokKeyReminder()` faux (non-régression).
- [ ] Échec de `getStatus()` ⇒ encart masqué, écran utilisable.
- [ ] Le rendu d'une offre BYOK ne contient ni « Quota atteint » ni « 0 tokens inclus ».
- [ ] Le rendu d'une offre Hosted contient toujours la jauge et « tokens inclus » (non-régression).

### Isolation utilisateur

- [x] Applicable — côté backend : `customerKeyBilled` est calculé depuis l'abonnement du `userId` du
  contexte de sécurité, jamais d'un paramètre client ; l'endpoint `GET /billing/subscription` est
  déjà couvert par les tests d'isolation de F-09. Côté frontend, aucun identifiant utilisateur n'est
  manipulé (le JWT porte l'identité).

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|--------------|--------|---------------------|
| **Auth / Principal** | Non | Aucun changement. |
| **Contexte tenant** | Non | Aucun changement. |
| **Plans / limites** | **Oui** | Le contrat de `GET /billing/subscription` change (champ **ajouté**, aucun retiré). Consommateurs, **tous revus** : `BillingController.subscription()` et `.changePlan()` (les deux appels à `SubscriptionResponse.from`), `BillingService.getSubscription()` / `.changePlan()` (frontend), `SubscriptionView` (modèle), `BillingComponent` (seul écran qui lit l'abonnement). Aucun autre écran ne consomme ce DTO. |
| **Navigation / routing** | Non | Aucune route ajoutée : l'encart pointe vers `/settings`, route existante et déjà gardée. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-41-01`, `SF-41-02` — statut : **done** (sur `main`).
- `SF-03-01` (écran de clé API dans les Paramètres) — statut : **done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Pourquoi un champ serveur et pas un test sur la chaîne `"BYOK"`.** L'écran pourrait comparer
  `subscription.planCode === 'BYOK'`, ou chercher le `providerMode` dans le catalogue. Les deux
  re-dérivent une décision que le serveur a déjà prise, et divergeront le jour où le catalogue
  changera. `customerKeyBilled` renvoie le résultat du **prédicat exact** qui gouverne le
  comportement serveur : une seule source de vérité, et l'écran ne peut plus se tromper tout seul.
- **Le champ est ajouté, jamais substitué** : aucun consommateur existant ne casse
  (`SubscriptionView` gagne une propriété).
- **Écart connu (hérité)** : `billing.component.scss` dépasse déjà le budget souple de 4 kB
  (5 033 octets avant cette subfeature, écart tracé par SF-40-03). L'encart réutilise les classes
  existantes pour ne pas l'aggraver inutilement.

# Mini-spec — F-43 / SF-43-03 — La bascule mensuel / annuel

## Identifiant

`F-43 / SF-43-03`

## Feature parente

`F-43` — Facturation annuelle

## Statut

`done`

## Date de création

2026-09-07

## Branche Git

`feat/SF-43-03-bascule-mensuel-annuel`

---

## Objectif

Rendre l'engagement annuel cliquable sur l'écran de facturation : une bascule Mensuel / Annuel qui
change les prix affichés, montre l'économie réelle, et fait enfin apparaître le prix du Pass journée.

---

## Comportement attendu

### Cas nominal

1. La section des offres porte une bascule **Mensuel / Annuel** (`mat-button-toggle-group`), affichée
   **seulement si au moins un plan** du catalogue a `yearlyAvailable = true`. Sans offre annuelle
   configurée, l'écran est **strictement identique** à aujourd'hui : aucun contrôle inerte.
2. Position par défaut : **Mensuel**. L'utilisateur choisit d'aller vers l'engagement, on ne l'y met
   pas d'office.
3. En position **Annuel**, une carte dont le plan propose l'annuel affiche :
   - le prix annuel (`yearlyPriceEur`) suivi de « / an » ;
   - l'équivalent mensuel (`yearlyPriceEur / 12`, arrondi à l'entier) — la seule façon de comparer
     honnêtement 240 € à 24 € ;
   - un drapeau d'économie calculé à partir des **deux prix renvoyés par le serveur** :
     `12 × priceEur − yearlyPriceEur`, exprimé en mois offerts quand le compte est rond
     (« 2 mois offerts »), en pourcentage sinon (« −17 % »). Aucun taux n'est écrit en dur.
4. En position **Annuel**, une carte dont le plan **ne propose pas** l'annuel garde son prix mensuel
   et le dit explicitement (« Mensuel uniquement ») : elle n'est ni masquée ni grisée — la masquer
   ferait disparaître une offre valable d'un simple clic sur une bascule.
5. Le bouton d'action envoie la périodicité **effective de cette carte** : `YEARLY` seulement si ce
   plan la propose et que la bascule est sur Annuel, `MONTHLY` sinon. Un plan sans offre annuelle
   reste donc achetable au mois, même en position Annuel.
6. Le **Pass journée** affiche enfin son prix (anomalie corrigée en SF-43-01), suivi de « la journée »
   et non « / mois ». Le libellé de périodicité vient du plan, il n'est plus écrit en dur dans le
   gabarit.
7. Le bloc de statut affiche la périodicité de l'abonnement en cours quand elle est connue
   (« Offre Solo · engagement annuel ») — sans quoi rien à l'écran ne dirait à un abonné annuel
   qu'il l'est.

### Ce que la bascule ne change pas

8. **Le quota affiché reste mensuel** : les cartes annoncent toujours « N tokens inclus / mois »,
   position Annuel comprise. C'est la règle produit de F-43, et l'écran ne doit pas laisser croire
   l'inverse — le seul endroit où l'utilisateur pourrait comprendre qu'il achète douze mois de
   jetons d'avance est précisément celui-ci.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le serveur refuse l'annuel (`yearly_not_available`, 409) | Message explicite : « Cette offre n'est pas proposée à l'année. » ; la bascule revient sur Mensuel et le catalogue est rechargé (l'offre a pu être dépubliée) |
| `billing_unavailable` (503) | Message existant, inchangé |
| `no_active_subscription` (409) | Message existant, inchangé |
| Échec de chargement des offres | Comportement existant, inchangé (message + écran utilisable) |
| Aucun plan avec `yearlyAvailable` | Pas de bascule, écran identique à aujourd'hui |

---

## Critères d'acceptation

- [ ] La bascule n'est **pas rendue** quand aucun plan n'a `yearlyAvailable = true`.
- [ ] La bascule est rendue et positionnée sur **Mensuel** dès qu'un plan a `yearlyAvailable = true`.
- [ ] En position Annuel, une carte annualisable affiche le prix annuel, « / an », l'équivalent
      mensuel et le drapeau d'économie.
- [ ] Le drapeau d'économie est **calculé** à partir des deux prix du serveur ; aucun taux ni montant
      n'est écrit en dur dans le composant.
- [ ] En position Annuel, une carte non annualisable reste **visible**, à son prix mensuel, avec la
      mention « Mensuel uniquement ».
- [ ] Le bouton envoie `period = 'YEARLY'` pour une carte annualisable en position Annuel, et
      `period = 'MONTHLY'` dans tous les autres cas — y compris pour une carte non annualisable en
      position Annuel.
- [ ] Le quota affiché sur les cartes reste **« / mois »** en position Annuel.
- [ ] Le Pass journée affiche son prix suivi de « la journée ».
- [ ] Le statut courant affiche « engagement annuel » quand `billingPeriod = 'YEARLY'`, et rien de
      particulier quand la périodicité est absente.
- [ ] Un refus `yearly_not_available` affiche un message explicite et ne laisse pas la bascule sur
      Annuel.
- [ ] Couleurs, polices et espacements exclusivement issus des jetons `--cg-*` du design system ;
      aucune couleur nouvelle.
- [ ] Aucun `window.confirm` / `alert` ; notifications via `MatSnackBar`.

---

## Périmètre

### Hors scope (explicite)

- Toute modification backend (livrée en SF-43-01 et SF-43-02).
- La migration automatique des abonnés mensuels vers l'annuel et le prorata (hors F-43).
- L'option Atelier, qui reste mensuelle et n'est pas touchée.
- Les recharges ponctuelles (top-up), inchangées.
- Le prix du pack de top-up en dur dans le composant (`packPrice`) : dette antérieure, hors sujet ici.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `selectedPeriod` (signal) | `'MONTHLY'` | L'utilisateur choisit d'aller vers l'engagement ; on ne l'y met pas d'office |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `selectedPeriod` | Oui (interne) | `'MONTHLY'` ou `'YEARLY'` | typée par `BillingPeriodChoice` |
| `Plan.yearlyPriceEur` | Non (API) | chaîne numérique ou `null` | `Number(...)` ; non finie ⇒ aucune économie affichée |
| `Plan.priceEur` | Non (API) | chaîne numérique ou `null` | idem |

Notes :
- L'économie n'est affichée que si les deux prix sont des nombres finis et que l'économie est
  strictement positive. Un prix illisible ou une remise nulle n'affiche **rien** plutôt qu'un
  « −0 % » ou un `NaN`.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Les contrats de SF-43-01 et SF-43-02 sont consommés :
`GET /billing/plans` (`yearlyPriceEur`, `yearlyAvailable`), `POST /billing/checkout` et
`POST /billing/subscription/change` (`period`), `GET /billing/subscription` (`billingPeriod`).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `BillingComponent` — bascule de périodicité, prix et économie par carte, périodicité transmise aux
  deux actions d'achat, libellé de périodicité du Pass journée, engagement affiché sur le statut.
- `BillingService` — `startCheckout` et `changePlan` acceptent une périodicité optionnelle.
- `billing.models.ts` — `BillingPeriod` gagne `'YEARLY'` ; `Plan` gagne `yearlyPriceEur` et
  `yearlyAvailable` ; `SubscriptionView` gagne `billingPeriod` ; les requêtes gagnent `period`.

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [ ] La bascule n'est pas rendue sans plan annualisable (assertion sur le DOM).
- [ ] La bascule est rendue dès qu'un plan est annualisable, position initiale `MONTHLY`.
- [ ] `displayPrice(plan)` renvoie le prix mensuel en position Mensuel, l'annuel en position Annuel.
- [ ] `displayPrice(plan)` renvoie le prix **mensuel** en position Annuel pour un plan non annualisable.
- [ ] `monthlyEquivalent(plan)` = annuel / 12, arrondi.
- [ ] `savingsLabel(plan)` : « 2 mois offerts » pour 24 → 240 ; pourcentage quand le compte n'est pas
      rond ; `null` si prix illisible, économie nulle ou négative.
- [ ] `periodFor(plan)` : `YEARLY` seulement si annualisable **et** bascule sur Annuel.
- [ ] `onPlanAction` transmet la périodicité au checkout (utilisateur sans abonnement).
- [ ] `onPlanAction` transmet la périodicité au changement de plan (utilisateur abonné).
- [ ] Un refus `yearly_not_available` affiche le message dédié et remet la bascule sur Mensuel.
- [ ] Le quota affiché reste « / mois » en position Annuel (assertion sur le DOM).
- [ ] Le Pass journée affiche « la journée » et non « / mois ».
- [ ] `commitmentLabel()` : « engagement annuel » pour `YEARLY`, `null` pour `null`.

### Tests d'intégration

Sans objet côté frontend : les contrats consommés sont couverts par les tests d'intégration backend
de SF-43-01 et SF-43-02. Le service `BillingService` est testé sur les URLs et les corps envoyés
(`HttpTestingController`).

### Isolation utilisateur

- [x] Non applicable au sens strict — aucun identifiant d'utilisateur ou de ressource n'est manipulé
      par l'écran : le JWT porté par l'`authInterceptor` détermine seul l'abonnement lu et modifié
      côté serveur. La périodicité est une donnée d'achat, jamais un identifiant.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---------------|--------|---------------------|
| Auth / Principal | Non | Aucun changement : l'écran reste derrière le guard existant, l'identité vient du JWT |
| Contexte tenant | Non | Aucun identifiant d'utilisateur ou de ressource envoyé par l'écran |
| Plans / limites | **Oui** | Voir la liste ci-dessous |
| Navigation / routing | Non | Aucune route, aucun guard, aucune redirection ajoutés |

### Plans / limites — composants impactés

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `BillingComponent` (cartes d'offre) | **Modifié** — prix, drapeaux, périodicité transmise | Tests unitaires du composant |
| `BillingComponent` (option Atelier) | **Aucun** — la section option n'est pas touchée, elle reste mensuelle | Tests existants doivent rester verts |
| `BillingComponent` (recharges) | **Aucun** | Tests existants |
| `BillingComponent` (jauge de quota) | **Aucun** — le quota reste mensuel et son affichage inchangé | Test « / mois » en position Annuel |
| `BillingService` | **Modifié** — périodicité optionnelle sur deux méthodes | Tests du service |
| `billing.models.ts` | **Modifié** — champs ajoutés, aucun champ existant renommé ni retypé | Compilation stricte |
| Écran Atelier / guards d'accès | **Aucun** — le droit d'Atelier ne dépend pas de la périodicité | Tests existants |

---

## Dépendances

### Subfeatures bloquantes

- `SF-43-01` — **done** · `SF-43-02` — **done**.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Une carte non annualisable reste visible en position Annuel.** La masquer serait la solution la
  plus « propre » visuellement et la pire pour l'utilisateur : une offre valable disparaîtrait au
  clic sur une bascule, sans explication.
- **L'économie est calculée, jamais écrite.** Le taux vit en configuration serveur (SF-43-01) ; le
  figer dans l'écran ferait mentir l'affichage le jour où le PO changerait la remise sans
  redéployer le frontend.
- **« 2 mois offerts » plutôt que « −17 % » quand le compte tombe juste.** C'est la formulation qui
  correspond à la décision commerciale réelle (dix mois payés sur douze), et elle se comprend sans
  calcul. Le pourcentage reste le repli pour toute autre remise.
- **Le quota reste annoncé « / mois » en position Annuel**, et c'est délibéré : c'est le seul écran
  où un utilisateur pourrait croire qu'un engagement annuel lui donne douze mois de jetons d'avance.

# Mini-spec — [F-40 / SF-03] L'option Atelier sur l'écran de facturation

---

## Identifiant

`F-40 / SF-03`

## Feature parente

`F-40` — Option Atelier (droit découplé du plan)

## Statut

`done` — livrée le 2026-09-07

## Date de création

2026-09-07

## Branche Git

`feat/SF-40-03-option-ecran-facturation`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Mettre l'option Atelier **sur l'écran de facturation** — son prix, son état, un bouton pour la
souscrire et un pour la résilier — et corriger les deux endroits de l'interface qui disent encore
que l'Atelier est « réservé à l'offre Gold ».

---

## Contexte

SF-40-01 a changé la règle, SF-40-02 l'a rendue achetable par l'API. Rien de tout cela n'est visible :
la seule façon d'ajouter l'option est un appel HTTP à la main.

Deux textes de l'interface sont désormais **faux** :

- `atelier.component.html` — l'écran de refus dit « L'Atelier (Claude Code Lite) est réservé à
  l'offre Gold » et propose « Voir l'offre Gold ». C'est précisément la falaise ×8 que F-40 supprime :
  laisser ce texte reviendrait à livrer la feature sans la montrer, là où l'utilisateur bute.
- `billing.component.html` — la carte Gold annonce « Atelier (Claude Code Lite) inclus », ce qui reste
  vrai, mais n'apprend à personne que l'Atelier s'obtient autrement.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de `/billing`, l'écran charge l'état de l'option (`GET /billing/atelier-option`) en
   même temps que l'abonnement, l'usage, les plans et les recharges. **Échec non bloquant** : la
   section reste simplement masquée, comme la section des recharges.
2. Une section « Option Atelier » affiche le prix venu de l'API (jamais une constante d'écran), ce que
   l'option débloque, et **le fait qu'elle ne change aucun quota** — c'est l'information qui évite le
   malentendu le plus probable.
3. L'état pilote un seul bouton :
   - **droit inclus à l'offre** (Gold) → aucun bouton, mention « Incluse dans votre offre » ;
   - **option en cours** → bouton « Résilier l'option », et, si une résiliation est déjà programmée,
     la date de fin et plus aucun bouton ;
   - **sinon** → bouton « Ajouter l'option », qui redirige vers le paiement.
4. La résiliation passe par une **confirmation `MatDialog`** (jamais `window.confirm`) qui dit
   explicitement que l'accès reste ouvert jusqu'à la fin de la période payée. À la confirmation,
   l'état est rafraîchi depuis la réponse et un `MatSnackBar` le dit.
5. L'écran de refus de l'Atelier propose désormais **les deux chemins** : ajouter l'option, ou passer
   à Gold — et son titre ne parle plus d'une offre réservée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /billing/atelier-option` échoue | Section masquée, aucun message d'erreur bruyant (l'écran reste utilisable) | — |
| Paiement non configuré (`available` faux) | Section visible, bouton **désactivé**, mention « bientôt disponible » | — |
| Souscription refusée `no_active_subscription` | Snackbar : souscrire une offre Solo ou Pro d'abord | 409 |
| Souscription refusée `atelier_option_included` | Snackbar : l'Atelier est déjà inclus ; l'état est rafraîchi | 409 |
| Souscription refusée `atelier_option_already_active` | Snackbar ; l'état est rafraîchi | 409 |
| Souscription refusée `billing_unavailable` | Snackbar : facturation momentanément indisponible | 503 |
| Résiliation refusée `atelier_option_not_active` | Snackbar ; l'état est rafraîchi | 409 |
| Confirmation annulée dans le dialog | **Aucun appel réseau** | — |
| Double clic pendant un appel | Bouton désactivé, un seul appel | — |

---

## Critères d'acceptation

- [x] La section « Option Atelier » affiche le prix **renvoyé par l'API**, jamais une valeur en dur
      dans le composant (un test le prouve en changeant le prix simulé).
- [x] Un abonné Gold voit « Incluse dans votre offre » et **aucun bouton d'achat**.
- [x] Un abonné Solo sans option voit « Ajouter l'option » ; le clic appelle l'API et redirige vers
      l'URL de paiement renvoyée.
- [x] Un abonné optionnaire voit « Résilier l'option » ; le clic ouvre un `MatDialog`, et **annuler
      ne déclenche aucun appel**.
- [x] Après résiliation, la date de fin est affichée et le bouton disparaît.
- [x] `available` faux ⇒ bouton désactivé, aucune promesse d'achat impossible.
- [x] L'écran de refus de l'Atelier ne dit plus « réservé à l'offre Gold » et propose **les deux**
      chemins.
- [x] Aucune couleur ni police hors `DESIGN_SYSTEM.md` ; aucun `window.confirm`/`alert`/`prompt` ;
      espacements par les jetons `--cg-space-*`.
- [x] Les erreurs sont rendues par `MatSnackBar` avec un message actionnable, jamais un code brut.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **backend** (livré en SF-40-01 et SF-40-02).
- Le **prix des packs de recharge** codé en dur dans le composant (`packPrice`, TODO existant de
  F-21) : c'est une dette voisine, pas celle-ci.
- La **reprise** d'une résiliation programmée (« annuler l'annulation ») : non exposée par l'API.
- Toute refonte de l'écran de facturation au-delà de l'ajout de la section.

---

## Contraintes de validation

Aucun champ saisi par l'utilisateur : les deux actions sont des boutons sans paramètre. Rien à
valider côté entrée.

---

## Technique

### Endpoint(s)

Aucun endpoint créé. Consommés : `GET /api/billing/atelier-option`,
`POST /api/billing/atelier-option/checkout`, `POST /api/billing/atelier-option/cancel`.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `BillingComponent` — section « Option Atelier » : état, souscription, résiliation confirmée
- `BillingService` — trois méthodes d'accès à l'API d'option
- `billing.models.ts` — `AtelierOptionView`
- `AtelierComponent` (`atelier.component.html`) — l'écran de refus propose les deux chemins
- `ConfirmDialogComponent` — **réutilisé tel quel**, aucun dialog nouveau

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | Aucun changement d'authentification ; les trois appels passent par l'`authInterceptor` existant, comme les six autres appels de `BillingService`. |
| Contexte tenant | Non | Le frontend n'envoie **aucun** identifiant d'utilisateur : les trois endpoints n'ont pas de corps. L'isolation est entièrement portée par le backend (testée en SF-40-02). |
| **Plans / limites** | **Oui** | L'écran est le lieu où les limites se **disent**. Composants revus : <br>• `BillingComponent` — la section d'option est ajoutée **à côté** des plans et des recharges, sans toucher `planActionLabel`, `onPlanAction`, `hasActiveSubscription` ni la jauge de quota ; les tests existants du composant doivent passer inchangés.<br>• La carte **Gold** garde sa mention « Atelier inclus » : elle reste vraie, et c'est ce qui rend lisible le choix entre 199 € et 24 € + option.<br>• **Aucun affichage de quota n'est modifié** : la section dit explicitement que l'option ne change pas le quota, ce qui est la règle backend. |
| **Navigation / routing** | **Oui** | Aucune route ajoutée. Mais `AtelierComponent` **redirige déjà** vers `/billing` depuis son écran de refus (`goToBilling()`) : ce chemin est conservé et doublé d'un second bouton vers la même route. Vérification : les tests existants d'`AtelierComponent` sur la redirection passent inchangés. |

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

`billing.component.spec.ts` (existant, étendu) :

- [x] Le prix affiché est celui de l'API (deux valeurs simulées différentes ⇒ deux rendus différents)
- [x] Gold : « Incluse » et aucun bouton d'achat
- [x] Solo sans option : bouton d'ajout, clic ⇒ `startAtelierOptionCheckout` appelé puis redirection
- [x] Optionnaire : bouton de résiliation, dialog ouvert, **annulation ⇒ aucun appel**
- [x] Confirmation ⇒ `cancelAtelierOption` appelé, état rafraîchi, snackbar
- [x] Résiliation déjà programmée ⇒ date affichée, plus de bouton
- [x] `available` faux ⇒ bouton désactivé
- [x] Échec de chargement ⇒ section masquée, écran toujours utilisable
- [x] Chaque code d'erreur d'API produit son message (409 ×3, 503)
- [x] Les tests existants du composant passent **sans modification**

`billing.service.spec.ts` (existant, étendu) :

- [x] Les trois méthodes tapent les bonnes URL et les bons verbes

`atelier.component.spec.ts` (existant, étendu) :

- [x] L'écran de refus ne contient plus « réservé à l'offre Gold »
- [x] Les deux boutons mènent à `/billing`

### Tests d'intégration

Sans objet : cette subfeature ne touche aucun endpoint. Les contrats consommés sont couverts par
`AtelierOptionBillingApiIntegrationTest` (SF-40-02).

### Isolation utilisateur

- [x] Applicable — vérifiée **côté backend** (SF-40-02, test Alice/Bob) : le frontend n'envoie aucun
      identifiant et ne peut donc pas en usurper. Le composant n'affiche que ce que l'API du porteur
      du JWT lui renvoie.

---

## Dépendances

### Subfeatures bloquantes

- `SF-40-01` — **done** (PR #264)
- `SF-40-02` — **done** (PR #265)

### Questions ouvertes impactées

- [x] Aucune.

---

## Notes et décisions

- **D10 — La section dit explicitement « sans changer votre quota ».** C'est la question que
  l'utilisateur se posera en premier devant un supplément de 40 € : le taire aurait produit des
  déceptions et des demandes de remboursement.
- **D11 — L'écran de refus de l'Atelier propose les deux chemins, l'option en premier.** C'est le
  point exact où la falaise ×8 se rencontrait ; y laisser « Voir l'offre Gold » seul aurait livré la
  feature sans la mettre là où elle sert.
- **D12 — Le dialog de confirmation est celui qui existe** (`ConfirmDialogComponent`). En créer un
  second pour une phrase différente aurait dupliqué un composant conforme au design system pour rien.

- **D13 — La carte de l'option réutilise `billing__card`.** Elle est simplement posée en large plutôt
  qu'en colonne (`--option`), au lieu d'un bloc de styles parallèle : même fond, même bordure, même
  ombre que les offres, donc une seule chose à maintenir le jour où la charte bouge.
- **Écart tracé (non bloquant)** : `billing.component.scss` dépasse de **212 octets** le budget
  souple de 4 kB du build. Deux composants voisins le dépassent déjà de 1,2 kB et 6,3 kB ; le
  ramener sous la barre aurait demandé de retirer des règles de mise en page utiles pour un
  avertissement que le projet tolère déjà. Signalé plutôt que contourné.

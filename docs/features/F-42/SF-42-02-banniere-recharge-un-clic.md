# Mini-spec — [F-42 / SF-02] La bannière d'alerte et la recharge en un clic

---

## Identifiant

`F-42 / SF-02`

## Feature parente

`F-42` — Alerte de quota & recharge en un clic

## Statut

`done` — livrée le 2026-09-07

## Date de création

2026-09-07

## Branche Git

`feat/SF-42-02-banniere-alerte-quota`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Présenter l'alerte de consommation levée par SF-42-01 dans une bannière de la coquille applicative,
visible depuis **n'importe quel écran authentifié**, avec un bouton qui part **directement** au
paiement du pack de recharge recommandé — un seul clic — et un bouton qui l'écarte pour la période.

---

## Contexte

SF-42-01 a posé la règle et l'a exposée : `GET /api/usage/alert` dit s'il faut prévenir, avec les
chiffres, la date de reprise du quota et le pack recommandé ; `POST /api/usage/alert/dismiss`
l'écarte. Rien ne la montre encore.

L'alerte doit être vue **là où la consommation se produit** — dans le chat, dans l'Atelier — pas
seulement sur l'écran de facturation que l'utilisateur ne visite pas quand il travaille. Elle vit
donc dans la **coquille applicative** (`ShellComponent`), au-dessus du contenu, sur tous les écrans
authentifiés. Aucune route n'est ajoutée : c'est une bande, pas une page.

Le « un clic » est littéral : le bouton appelle `POST /api/billing/topup/checkout` avec le code du
pack porté par l'alerte, et redirige vers Stripe. Aucune sélection de pack, aucune étape
intermédiaire — l'écran de facturation reste disponible pour qui veut choisir un autre pack.

---

## Comportement attendu

### Cas nominal

1. Un écran authentifié se charge ; la coquille demande `GET /api/usage/alert`.
2. `raised: false` (cas courant) → **rien n'est rendu** : aucune bande, aucun décalage de mise en page.
3. `raised: true` → la bannière s'affiche sous la barre de navigation :
   - une icône d'avertissement et un texte qui dit **où en est l'utilisateur et jusqu'à quand** :
     « Vous avez consommé **85 %** de votre quota (850 000 / 1 000 000 jetons). Il vous reste
     **150 000 jetons** jusqu'au 1 août 2026. » ;
   - une barre de progression qui matérialise la part consommée ;
   - un bouton d'action principale « **Recharger — 1 M jetons** » (libellé issu du pack renvoyé
     par l'API, jamais écrit en dur) ;
   - un bouton « Ignorer ».
4. Clic sur « Recharger » : `POST /api/billing/topup/checkout` avec le code du pack, puis redirection
   vers l'URL de paiement. Le bouton passe à « Redirection… » et se désactive pendant l'appel.
5. Clic sur « Ignorer » : `POST /api/usage/alert/dismiss`, la bannière disparaît immédiatement et ne
   revient pas avant la période suivante.
6. Si l'alerte ne porte **aucun** pack (code configuré inconnu du catalogue), la bannière s'affiche
   **sans** bouton de recharge : elle informe quand même.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /usage/alert` en échec (réseau, 5xx) | Aucune bannière, aucun message d'erreur : l'absence d'alerte ne doit jamais parasiter l'écran de travail | — |
| `GET /usage/alert` en `401` | Aucune bannière ; la redirection vers `/login` reste celle de l'intercepteur existant, inchangée | 401 |
| Checkout de recharge en échec `billing_unavailable` | `MatSnackBar` « La facturation est momentanément indisponible. » ; la bannière **reste** affichée et le bouton redevient actif | 503 |
| Checkout de recharge en échec (autre) | `MatSnackBar` « Impossible de démarrer le rachat de jetons. » ; bannière conservée, bouton réactivé | 4xx/5xx |
| `POST /alert/dismiss` en échec | La bannière est **quand même** masquée localement : l'utilisateur a demandé à ne plus la voir, on ne la lui réimpose pas parce que le serveur a hoqueté. Elle réapparaîtra au prochain chargement — comportement acceptable et sans perte. | 4xx/5xx |
| Double clic sur « Recharger » | Le second clic est ignoré (garde `redirecting`) : une seule session de paiement créée | — |

---

## Critères d'acceptation

- [ ] Aucune bannière tant que `raised` est faux — et **aucun élément DOM** rendu (pas de bande vide).
- [ ] Bannière affichée quand `raised` est vrai, avec le pourcentage consommé, les jetons consommés
      et restants, et la date de reprise du quota — **tous issus de l'API**, aucun recalcul côté client.
- [ ] Le libellé du bouton de recharge vient du pack renvoyé par l'API ; aucun code ni libellé de
      pack en dur dans le composant.
- [ ] Un clic sur « Recharger » déclenche `POST /api/billing/topup/checkout` avec **le code du pack
      de l'alerte** puis la redirection — **un seul clic**, aucune étape de sélection.
- [ ] Un double clic ne crée qu'une seule session de paiement.
- [ ] Un clic sur « Ignorer » appelle `POST /api/usage/alert/dismiss` et masque la bannière.
- [ ] La bannière disparaît aussi si le `dismiss` échoue (masquage local).
- [ ] Un échec de chargement de l'alerte ne rend rien et n'affiche aucune erreur.
- [ ] Une alerte sans pack s'affiche sans bouton de recharge.
- [ ] **Design system** : couleurs et polices exclusivement issues de `DESIGN_SYSTEM.md`
      (jetons `--cg-*`) ; espacements multiples de 4 px ; `MatSnackBar` pour les notifications ;
      **aucun** `window.confirm` / `window.alert`.
- [ ] Le frontend n'envoie **aucun identifiant utilisateur** : l'isolation reste portée par le JWT.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la règle d'alerte côté serveur (livrée en SF-42-01).
- L'écran de facturation, son catalogue de packs et son bouton de rachat existants (F-21) — inchangés.
- Toute nouvelle route Angular, tout guard, toute redirection.
- Un rappel par e-mail ou notification navigateur.
- Le rafraîchissement en temps réel de l'alerte pendant qu'un écran est ouvert : elle est chargée à
  l'entrée dans la coquille, ce qui suffit (voir §Notes et décisions).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `alert` (signal) | `null` | Aucune bannière tant que l'API n'a pas répondu — l'écran ne « clignote » pas |
| `dismissed` (signal) | `false` | Passe à `true` au clic sur « Ignorer », masquage immédiat |
| `redirecting` (signal) | `false` | Garde anti-double-clic pendant le checkout |

---

## Contraintes de validation

> Aucun champ de saisie : ce composant n'accepte aucune entrée utilisateur libre.

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `topUp.code` (lu de l'API) | Non | — | code de pack renvoyé par le serveur ; absent ⇒ pas de bouton de recharge | — | aucune — passé tel quel au checkout |
| `usedPercent` (lu de l'API) | Oui | — | entier 0–100 **calculé côté serveur** | — | aucune — jamais recalculé côté client |

---

## Technique

### Endpoint(s)

Aucun endpoint créé. Consommés :

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/usage/alert` | Oui (JWT) | USER |
| POST | `/api/usage/alert/dismiss` | Oui (JWT) | USER |
| POST | `/api/billing/topup/checkout` | Oui (JWT) | USER (existant, F-21) |

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `QuotaAlertBannerComponent` — **créé** : charge l'alerte, l'affiche, recharge, écarte.
- `QuotaAlertService` (`core/services`) — **créé** : `getAlert()`, `dismissAlert()`.
- `quota-alert.models.ts` (`core/models`) — **créé** : contrat figé de `GET /api/usage/alert`.
- `ShellComponent` — **modifié** : la bannière est insérée entre la barre de navigation et le contenu.

---

## Plan de test

### Tests unitaires

- [ ] `QuotaAlertService` — `getAlert()` appelle `GET /api/usage/alert` sans aucun paramètre client.
- [ ] `QuotaAlertService` — `dismissAlert()` appelle `POST /api/usage/alert/dismiss`.
- [ ] `QuotaAlertBannerComponent` — `raised: false` ⇒ **aucun** élément de bannière dans le DOM.
- [ ] `QuotaAlertBannerComponent` — `raised: true` ⇒ pourcentage, jetons restants et libellé du pack
      affichés.
- [ ] `QuotaAlertBannerComponent` — « Recharger » ⇒ `startTopUpCheckout` appelé avec le code du pack,
      puis redirection.
- [ ] `QuotaAlertBannerComponent` — double clic ⇒ un seul `startTopUpCheckout`.
- [ ] `QuotaAlertBannerComponent` — checkout en échec ⇒ snackbar, bannière conservée, bouton réactivé.
- [ ] `QuotaAlertBannerComponent` — « Ignorer » ⇒ `dismissAlert` appelé et bannière masquée.
- [ ] `QuotaAlertBannerComponent` — `dismiss` en échec ⇒ bannière masquée quand même.
- [ ] `QuotaAlertBannerComponent` — chargement en échec ⇒ rien rendu, aucune erreur affichée.
- [ ] `QuotaAlertBannerComponent` — alerte sans pack ⇒ pas de bouton de recharge.
- [ ] `ShellComponent` — la bannière est présente dans la coquille (non-régression de la navigation).

### Tests d'intégration

> Sans objet côté backend : aucun endpoint n'est créé ni modifié. Les endpoints consommés sont
> couverts par les tests d'intégration de SF-42-01 (`QuotaAlertApiIntegrationTest`) et de F-21.

### Isolation utilisateur

- [x] Applicable — test : le frontend n'envoie **aucun** identifiant utilisateur (`params` vide sur
      le `GET`, corps vide sur le `POST`). L'isolation est portée par le JWT ajouté par
      l'`authInterceptor` et appliquée côté serveur, comme pour tous les autres appels.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|--------------|--------|---------------------|
| **Auth / Principal** | Non | Aucun changement d'authentification. Les deux appels passent par l'`authInterceptor` existant, comme tous les autres. |
| **Contexte tenant** | Non | Aucun identifiant utilisateur transmis par le client ; le tenant reste résolu côté serveur par le JWT. |
| **Plans / limites** | Non | Aucun gate, aucun quota, aucune limite modifiée : ce composant **lit** un état déjà calculé et **propose** un pack déjà au catalogue. |
| **Navigation / routing** | **Oui** | La bannière est insérée dans `ShellComponent`, la coquille de **tous** les écrans authentifiés. Composants impactés, tous vérifiés : `ShellComponent` (template et spec), et par conséquent chaque route enfant qu'elle enveloppe — Chat, Atelier, Bibliothèque, Q&A, Templates, Rapports, Facturation, Réglages, Profil, Administration. **Aucune route n'est ajoutée, modifiée ou gardée** ; aucune redirection n'est introduite. Le seul effet possible sur ces écrans est la présence d'une bande au-dessus du contenu, et uniquement lorsque `raised` est vrai — la spec de `ShellComponent` est étendue pour figer la non-régression de la navigation. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-42-01` — statut : **done** (PR #270)
- F-21 / SF-21-02 (`POST /billing/topup/checkout`) — `done`

### Questions ouvertes impactées

- [ ] `OQ-08` — dépassement facturé : **non tranchée**, et non touchée par cette subfeature.

---

## Notes et décisions

**D1 — La bannière vit dans la coquille, pas sur l'écran de facturation.** Un utilisateur qui
consomme son quota est en train de travailler dans le chat ou l'Atelier ; il ne visite pas la page de
facturation à ce moment-là. Mettre l'alerte ailleurs que sur son chemin reviendrait à ne pas la
donner.

**D2 — Pas de rafraîchissement périodique.** L'alerte est chargée une fois, à l'entrée dans la
coquille. Un sondage régulier ajouterait du trafic à chaque écran pour gagner, au mieux, quelques
minutes d'avance sur un seuil qui n'est pas une urgence à la seconde. `PROJECT.md` §14.2 (Simplicity
First) tranche : on charge une fois.

**D3 — Le `dismiss` masque localement même s'il échoue.** L'utilisateur a demandé à ne plus voir la
bannière ; la lui réimposer parce que le serveur a hoqueté serait le punir d'une panne. Le pire cas
est qu'elle réapparaisse au prochain chargement — sans perte de donnée ni d'argent.

**D4 — Aucun pourcentage recalculé côté client.** `usedPercent` et `thresholdPercent` viennent du
serveur, arrondis par la même règle qui a levé l'alerte : l'affichage ne peut donc pas dire 79 %
quand la règle a jugé 80 %.

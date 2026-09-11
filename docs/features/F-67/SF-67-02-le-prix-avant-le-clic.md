# Mini-spec — F-67 / SF-67-02 — Le prix avant le clic

## Identifiant

`F-67 / SF-67-02`

## Feature parente

`F-67` — Le prix avant le clic

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-67-02-prix-avant-le-clic`

---

## Objectif

Afficher le montant d'une recharge **avant** le clic d'achat, en lisant le prix que le serveur
envoie — et supprimer le barème écrit en dur dans l'écran, qui affichait un montant que personne
n'avait décidé.

---

## Comportement attendu

### Cas nominal

1. L'écran de facturation liste les recharges. Pour chaque pack dont le serveur envoie un
   `priceEur`, le montant est affiché tel quel, suivi de « € » et de la mention « une fois »
   (paiement unique), comme aujourd'hui.
2. La bannière d'alerte des 80 % (F-42) propose la recharge en un clic. Quand le pack porte un
   montant, le **bouton le dit** : « Recharger — Recharge 200 k tokens · 4,99 € ». Le client sait
   ce qu'il engage **avant** d'être redirigé.
3. Le composant ne connaît **aucun** montant : `packPrice()` et son barème en dur disparaissent.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `priceEur` absent / `null` (cas du pack 1 M livré) | Le pack **reste affiché et achetable**, sans montant, avec la phrase « **Prix indiqué à l'étape de paiement** » à la place du prix. Aucun chiffre, aucun zéro, aucun « — € ». |
| `priceEur` chaîne vide renvoyée malgré tout | Traitée exactement comme `null` (l'écran ne fait pas confiance à la forme du champ). |
| Bannière d'alerte, pack sans montant | Le bouton garde son libellé actuel « Recharger — *libellé* », sans montant inventé. |
| `GET /api/billing/topups` en échec | Inchangé : la section des recharges n'est pas rendue. |

---

## Critères d'acceptation

- [ ] `packPrice()` et le barème `{ DAY: '4,99', STANDARD: '29' }` sont **supprimés** du composant :
      `grep -r "4,99\|'29'" frontend/src/app/billing` ne renvoie plus de montant de recharge.
- [ ] Le modèle `TopUpPack` porte `priceEur: string | null`.
- [ ] Un pack avec montant affiche « 4,99 € · une fois » ; un pack sans montant affiche
      « Prix indiqué à l'étape de paiement » et **aucun chiffre**.
- [ ] Un pack sans montant reste **listé et son bouton « Acheter » reste actif** (il est vendable).
- [ ] Le bouton de la bannière d'alerte porte le montant quand il existe, et seulement alors.
- [ ] Aucune couleur, police ou espacement hors `docs/DESIGN_SYSTEM.md` : la mention de repli
      réutilise les classes de l'écran existant.
- [ ] La mention de repli est lisible par un lecteur d'écran (texte réel, pas un attribut).

---

## Périmètre

### Hors scope (explicite)

- **Décider un montant.** L'écran affiche ce que le serveur envoie, ou rien.
- Masquer un pack sans prix (arbitrage du cadrage : il reste vendable).
- Changer la mise en page de l'écran de facturation, l'ordre des packs, la section des plans.
- Formater ou convertir le montant (le serveur envoie la chaîne à afficher).

---

## Contraintes de validation

| Champ | Obligatoire | Format | Normalisation |
|---|---|---|---|
| `TopUpPack.priceEur` | Non | `string \| null` | Chaîne vide ou blanche traitée comme absente |

---

## Technique

### Endpoint(s) consommés

| Méthode | URL | Changement |
|---|---|---|
| GET | `/api/billing/topups` | Lecture du nouveau champ `priceEur` (SF-67-01) |
| GET | `/api/usage/alert` | Lecture de `topUp.priceEur` (SF-67-01) |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- `core/models/billing.models.ts` — `TopUpPack.priceEur`.
- `billing/billing.component.ts` — suppression de `packPrice()`, ajout de `topUpPrice(pack)`
  (normalisation `null`/vide) et `hasTopUpPrice(pack)`.
- `billing/billing.component.html` — montant ou mention de repli.
- `layout/quota-alert-banner/quota-alert-banner.component.ts|html` — libellé du bouton.

---

## Plan de test

### Tests unitaires (Jasmine/Karma)

- [ ] `billing.component` — un pack avec `priceEur` rend le montant et l'unité « une fois ».
- [ ] `billing.component` — un pack sans `priceEur` rend « Prix indiqué à l'étape de paiement » et
      **aucun chiffre de prix**.
- [ ] `billing.component` — un pack sans `priceEur` garde un bouton « Acheter » actif.
- [ ] `billing.component` — `priceEur: ''` est traité comme absent.
- [ ] `quota-alert-banner` — le bouton porte le montant quand le pack en a un.
- [ ] `quota-alert-banner` — le bouton reste sans montant quand le pack n'en a pas.

### Tests d'intégration

- [ ] `billing.service.spec` — le catalogue désérialisé porte `priceEur`.

### Isolation utilisateur

- [x] Non applicable — écran, aucune donnée nominative lue ou écrite. Les deux endpoints consommés
      sont déjà authentifiés et isolés côté serveur (SF-67-01).

---

## Dépendances

### Subfeatures bloquantes

- `SF-67-01` — **doit être mergée d'abord** : sans `priceEur`, l'écran n'aurait rien à lire.

### Questions ouvertes impactées

- [ ] **OQ-16 point 1** — non tranchée. L'écran est justement ce qui doit se comporter proprement
      tant qu'elle ne l'est pas.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | **Affichage seulement** | Deux écrans lisent un pack de recharge : `billing.component` et `quota-alert-banner`. Les **deux** sont traités ici ; aucun autre composant n'importe `TopUpPack` (vérifié par `grep`). |
| Navigation / routing | Non | Aucune route ajoutée ou modifiée. |

---

## Notes et décisions

1. **« Prix indiqué à l'étape de paiement » plutôt que masquer le pack.** C'est vrai — Stripe
   Checkout affiche le montant avant toute saisie de carte — et cela laisse vendable le pack que la
   bannière des 80 % propose en un clic.
2. **Aucun formatage côté écran.** Le serveur envoie `4,99` ; l'écran ajoute « € ». Formater
   reviendrait à interpréter un montant que l'application ne calcule pas.

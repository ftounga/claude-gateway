# Cadrage — F-67 « Le prix avant le clic »

**Date** : 2026-09-11 · **Source** : `docs/PRODUCT_SPEC.md` (F-67) · **Grille** : `docs/TARIFS.md` §2, §7

---

## Le constat

Deux packs de recharge sont vendus à l'unité (`TopUpCatalog`) : `DAY` (200 000 jetons) et
`STANDARD` (1 000 000 jetons). Le second est **vendable** — son price ID vient de l'environnement —
mais **aucun montant n'est exposé par l'API** : `TopUpPackResponse` ne porte que `code`, `label` et
`tokens`, au motif que « le prix vit côté fournisseur ».

Ce que l'analyse F-64 n'avait pas vu, et que le cadrage de F-67 a trouvé : **l'écran affiche quand
même un prix**, et il est **écrit en dur dans le composant** :

```ts
// frontend/src/app/billing/billing.component.ts
packPrice(code: string): string | null {
  const prices: Record<string, string> = { DAY: '4,99', STANDARD: '29' };
  return prices[code] ?? null;
}
```

Les **29 €** du pack 1 M ne viennent d'aucune source : ni configuration, ni Stripe, ni
`docs/TARIFS.md` — qui, lui, écrit « **À CONFIRMER PAR LE PO** ». Le produit affiche donc
aujourd'hui, à côté d'un bouton d'achat, **un montant que personne n'a décidé**. C'est le cas
exact que la règle de `TARIFS.md` §0 nomme : *un chiffre inventé dans une grille publiée est
opposable par un client*. Le défaut est plus grave que celui décrit au backlog : il ne s'agit pas
d'une absence d'affichage, mais d'un **affichage faux**.

## Ce que F-67 livre

Le **mécanisme**, au même patron que les plans (`app.billing.stripe.display-prices`) :

1. une clé de configuration `app.billing.stripe.topup-display-prices` (code de pack → montant) ;
2. `TopUpPackResponse` porte `priceEur`, exactement comme `PlanResponse` porte `priceEur` ;
3. l'écran de facturation et la bannière d'alerte (F-42) affichent **ce que le serveur envoie**,
   et rien d'autre.

**Défauts livrés** : `DAY` = `4,99` (valeur relevée en note de livraison SF-09-04, reprise par
`TARIFS.md` §2), `STANDARD` = **vide**. Le pack 1 M cesse donc d'afficher 29 €.

## Ce que F-67 ne décide pas

**Le montant du pack 1 M.** Il reste `À CONFIRMER PAR LE PO` (`TARIFS.md` §7 point 1, OQ-16).
Aucun price Stripe n'est créé, lu ni modifié : F-67 n'appelle pas Stripe.

## L'arbitrage central : que montre l'écran quand le montant manque ?

Trois options étaient ouvertes :

| Option | Écartée / retenue |
|---|---|
| Masquer le pack | **Écartée.** Le pack est vendable et c'est celui que la bannière des 80 % propose en un clic (`app.quota.alert.top-up-pack` = `STANDARD`). Le masquer supprimerait la seule recharge proposée au moment où le client en a besoin. |
| Afficher `0 €` ou un tiret nu | **Écartée.** Un zéro est un prix, et c'est un prix faux. |
| **Afficher le pack sans montant, avec une phrase qui dit où le prix sera donné** | **Retenue.** « Prix indiqué à l'étape de paiement » — c'est vrai (Stripe Checkout affiche le montant avant toute saisie de carte), c'est honnête, et cela laisse le pack vendable. |

## Découpage

| SF | Titre | Portée |
|---|---|---|
| SF-67-01 | Le montant des recharges, en configuration | Backend : clé de configuration, `priceEur` dans l'API des packs et dans l'alerte de quota |
| SF-67-02 | Le prix avant le clic | Frontend : suppression du prix en dur, affichage du montant serveur, repli explicite |

**Backend mergé avant le frontend** : l'écran ne peut lire `priceEur` qu'une fois l'API livrée.

## Hors périmètre

- Créer ou modifier quoi que ce soit dans Stripe (price, produit, montant) — interdit explicite.
- Décider du montant du pack 1 M, ou de tout autre montant commercial.
- Filtrer le catalogue des packs sur la présence d'un price ID : un pack sans price ID reste
  listé comme aujourd'hui. Le dépôt ne peut pas vérifier quels price IDs sont réellement injectés
  en production (secret hors dépôt) ; filtrer risquerait de **retirer une recharge vendue**.
  Le comportement d'un pack non configuré (erreur au checkout) est inchangé.
- Le prix des plans, de l'option Atelier, du supplément par poste : déjà exposés.

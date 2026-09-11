# Mini-spec — SF-66-04 · L'écran annonce l'essai qu'on sert

## Identifiant

`F-66 / SF-66-04`

## Feature parente

`F-66` — L'essai tient sa promesse (cadrage : `docs/features/F-66/F-66-cadrage.md`)

## Statut

`done` — PR #375, mergée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-66-04-ecran-dit-la-duree`

---

## Objectif

Faire dire à chaque écran la **durée** et l'**allocation** que le serveur sert réellement, au lieu de
chiffres écrits en dur — et ne laisser subsister qu'**une seule** constante de durée, celle de la
page publique, qui ne peut pas interroger l'API.

---

## Le désordre à supprimer

Trois voix, trois chiffres, aujourd'hui :

| Où | Ce qui est annoncé | Ce qui est servi |
|---|---|---|
| `landing.component.html` / `.ts` | « 14 jours d'essai — aucune carte requise », « Essai gratuit 14 jours » | 14 ✅ (depuis SF-66-02) |
| `billing.component.html` | « essai **5 jours** » | **14** ❌ |
| `billing.component.html` | « **200 000** tokens pour découvrir » | 200 000 ✅ — mais en dur, donc faux le jour où la valeur bouge |

La page de facturation lit désormais `trialDays` et `trialTokens` de l'API
(`GET /billing/subscription`, SF-66-02 / SF-66-03). La page d'accueil est **publique** : elle ne peut
appeler aucun endpoint authentifié, elle garde donc un littéral — mais **un seul**, partagé par ses
deux mentions et nommé pour ce qu'il est.

---

## Comportement attendu

### Cas nominal

1. Le modèle `SubscriptionView` porte `trialDays` et `trialTokens`, renvoyés par l'API.
2. La carte « Gratuit » de l'écran Facturation affiche la **durée servie** (« essai 14 jours ») et
   l'**allocation servie** (« 200 000 tokens pour découvrir »), formatée comme les autres offres
   (pipe `number`).
3. Tant que l'abonnement n'est pas chargé (premier rendu, erreur réseau), la carte affiche la durée
   **annoncée publiquement** (`ADVERTISED_TRIAL_DAYS`) : jamais de trou, jamais « essai 0 jour ».
4. La page d'accueil tient ses deux mentions depuis cette **même** constante, exportée d'un seul
   fichier documenté qui dit d'où elle vient et avec quoi elle doit rester alignée.
5. La page d'aide (« Compte, offres et quotas ») dit que l'allocation d'un **essai** couvre toute sa
   durée et ne se renouvelle pas au 1er du mois — ce que SF-66-01 a rendu vrai.

### Cas d'erreur

| # | Situation | Comportement attendu |
|---|---|---|
| E1 | `GET /billing/subscription` échoue ou n'a pas encore répondu | Repli sur `ADVERTISED_TRIAL_DAYS` (14) et sur l'allocation par défaut affichée jusque-là (200 000). L'écran ne montre ni vide, ni zéro. |
| E2 | Le serveur sert une durée différente de la promesse publique (configuration d'environnement) | L'écran **Facturation** dit la durée **servie** — c'est lui qui fait foi pour un client connecté. L'écart avec la page publique devient visible, ce qui est le but : il doit se voir, pas se cacher. |

---

## Critères d'acceptation

1. La carte « Gratuit » affiche « essai 14 jours » quand l'API renvoie `trialDays: 14`, et « essai
   7 jours » quand elle renvoie 7.
2. La carte affiche l'allocation renvoyée par l'API, formatée (« 500 000 tokens pour découvrir » si
   l'API renvoie 500 000).
3. Sans abonnement chargé, la carte affiche 14 jours et ne montre ni `null`, ni `0`, ni `NaN`.
4. La page d'accueil annonce 14 jours dans ses deux mentions, toutes deux issues de la constante.
5. Aucune couleur, police ou espacement hors `DESIGN_SYSTEM.md` : la modification est **textuelle**.
6. Aucun montant commercial décidé côté écran : tous les chiffres viennent de l'API ou de la
   constante publique documentée.

---

## Plan de test minimal

### Unitaires (Karma/Jasmine)

- `billing.component.spec.ts` : durée et allocation issues de l'API affichées dans la carte ; repli
  sur 14 jours sans abonnement chargé ; une durée configurée différente (7) est affichée telle
  quelle.
- `landing.component.spec.ts` : les deux mentions annoncent la durée de la constante.

### Intégration

Sans objet côté frontend : le contrat d'API est couvert par `BillingApiIntegrationTest`
(SF-66-02 / SF-66-03).

### Isolation utilisateur

Sans objet : les deux champs décrivent l'offre, ils sont identiques pour tous les utilisateurs et ne
portent aucune donnée d'un autre compte.

---

## Tables / endpoints / composants impactés

### Tables / endpoints

Aucun. Cette subfeature **consomme** `GET /billing/subscription` sans le modifier.

### Composants

| Composant | Changement |
|---|---|
| `core/trial-offer.ts` (nouveau) | `ADVERTISED_TRIAL_DAYS` — la seule constante de durée du frontend, documentée et rattachée à `app.billing.trial-days`. |
| `core/models/billing.models.ts` | `SubscriptionView` : `trialDays`, `trialTokens`. |
| `billing/billing.component.ts` | Deux accesseurs : durée et allocation d'essai, avec repli. |
| `billing/billing.component.html` | Carte « Gratuit » : durée et allocation lues, plus écrites. |
| `landing/landing.component.ts` / `.html` | Les deux mentions lisent la constante. |
| `backend/src/main/resources/help/08-compte-plans-et-quotas.md` | Une phrase : l'allocation d'un essai couvre toute sa durée. |

---

## Préoccupation transversale — **Navigation / routing**

Non déclenchée : aucune route, aucun guard, aucune redirection touchés. Les deux écrans modifiés sont
atteints par les chemins existants (`/` et `/billing`).

---

## Hors périmètre

- Modifier un montant, un quota ou une durée : tous viennent du serveur ou de la constante publique.
- Afficher l'essai comme une offre du catalogue `GET /billing/plans` (il n'en est pas une).
- Refondre la carte « Gratuit » : la modification est textuelle, le design system est inchangé.

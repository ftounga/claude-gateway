# Mini-spec — SF-65-02 · L'écran dit quels postes sont comptés

## Identifiant

`F-65 / SF-65-02`

## Feature parente

`F-65` — Supplément par poste supplémentaire (cadrage : `docs/features/F-65/F-65-cadrage.md`)

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-65-02-ecran-postes-comptes`

---

## Objectif

Montrer sur l'écran de facturation quels postes sont **comptés pour le mois**, lequel l'abonnement
couvre, ce que les suppléments apportent en jetons — et écrire noir sur blanc la règle de
réouverture, pour qu'aucun utilisateur n'ait à la deviner.

---

## Comportement attendu

### Cas nominal

1. L'écran `/billing` appelle `GET /billing/seats` au chargement, à côté des appels existants.
2. Un volet **« Postes »** apparaît, après l'option Forge et avant les recharges :
   - une phrase d'état : *« Votre abonnement couvre N poste(s). M poste(s) compté(s) ce mois-ci. »* ;
   - la liste des postes comptés : nom, date de début de facturabilité, et l'un des trois libellés —
     **« Inclus dans l'abonnement »**, **« Supplément n° k »**, ou **« Clôturé — compté jusqu'à la
     fin du mois »** ;
   - les jetons apportés par les suppléments, quand il y en a ;
   - la règle de réouverture, **écrite** : *« Un poste compté l'est pour tout le mois : le rouvrir
     avant la fin du mois ne le refacture pas, et le clôturer ne rembourse pas le mois engagé. »*
3. **Quand le supplément n'est pas encore facturé** (`billed: false` — l'état livré), l'écran le dit
   explicitement : *« Aucun supplément n'est facturé pour l'instant. »* Il ne laisse **jamais**
   croire à une facture qui n'existe pas.
4. Quand il l'est, il affiche le montant mensuel par poste supplémentaire tel que la configuration
   le donne — **aucun montant n'est écrit dans le frontend**.
5. Un lien mène à `/postes`, là où la clôture se déclare : la règle et le geste au même endroit.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `GET /billing/seats` échoue | Le volet **ne s'affiche pas** ; le reste de l'écran de facturation fonctionne normalement (même patron que l'option Forge et le code d'accès) |
| Aucun poste | Volet affiché avec *« Aucun poste pour l'instant »* et un lien vers `/postes` |
| Un seul poste | Volet affiché, poste marqué « Inclus dans l'abonnement », aucun supplément |
| Poste sans nom lisible | Repli *« Poste supprimé »* plutôt qu'une ligne vide |

---

## Critères d'acceptation

1. Le volet montre le nombre de postes comptés et le nombre de postes couverts par l'abonnement.
2. Chaque poste compté porte **un libellé écrit** — jamais une couleur seule (`DESIGN_SYSTEM` §10).
3. Le poste couvert par l'abonnement est distingué des suppléments, numérotés.
4. Un poste clôturé mais encore compté est signalé comme tel, avec la raison (mois engagé).
5. La règle de réouverture est visible **sans interaction** (pas derrière une infobulle).
6. Quand `billed` est faux, l'écran dit qu'aucun supplément n'est facturé ; aucun montant n'apparaît.
7. Aucun montant, aucun quota, aucune règle tarifaire n'est codé en dur dans le frontend.
8. Un échec de l'appel ne casse pas l'écran de facturation.
9. Les couleurs, polices et espacements respectent `docs/DESIGN_SYSTEM.md`.

---

## Plan de test minimal

### Unitaires (Jasmine/Karma)

- `BillingComponent` : le volet se peuple depuis `GET /billing/seats` ; libellé « Inclus » sur le
  premier poste et « Supplément n° 1 » sur le second ; mention « clôturé » ; message
  « aucun supplément facturé » quand `billed` est faux ; montant affiché quand il est vrai ;
  l'échec de l'appel laisse l'écran fonctionnel et le volet masqué ; cas « aucun poste ».
- `SeatService` : appelle bien `/billing/seats`.

### Intégration

- Couverte côté backend par `SeatApiIntegrationTest` (contrat de l'endpoint). Côté frontend,
  `HttpTestingController` vérifie l'URL et la projection.

### Isolation utilisateur

- Garantie côté serveur (l'endpoint ne rend que les postes du porteur du JWT) ; le frontend n'envoie
  **aucun** identifiant d'utilisateur.

---

## Tables / endpoints / composants impactés

| Élément | Nature |
|---|---|
| `frontend/src/app/core/models/seat.models.ts` | **nouveau** — `SeatsView`, `SeatView` |
| `frontend/src/app/core/services/seat.service.ts` | **nouveau** — `GET /billing/seats` |
| `frontend/src/app/billing/billing.component.{ts,html,scss}` | **modifié** — volet « Postes » |
| Backend | **aucun changement** (contrat livré par SF-65-01) |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | Non | L'appel passe par l'intercepteur JWT existant, comme les autres appels de l'écran |
| Contexte tenant | Non (frontend) | Aucun `userId` n'est envoyé : le serveur le prend du jeton |
| Plans / limites | Non | L'écran **montre** le décompte, il ne le calcule pas — aucune règle tarifaire côté client |
| Navigation / routing | Non | Aucune route ajoutée ; un lien vers `/postes`, route existante |

---

## Hors périmètre

- Tout montant écrit dans le frontend (ils viennent de la configuration, via l'API).
- L'achat du supplément (aucun parcours de paiement : Stripe n'est pas touché par F-65).
- Un écran d'administration des postes facturés — la console admin est F-61.

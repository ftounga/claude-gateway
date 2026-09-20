# Mini-spec — F-133 / SF-133-12 — L'alerte dans la Forge

## Identifiant
`F-133 / SF-133-12`

## Feature parente
`F-133` — Le coût réel Anthropic

## Statut
`draft` — demandé par le PO le 2026-09-20

## Branche Git
`feat/SF-133-12-alerte-dans-la-forge`

---

## Objectif

Montrer les alertes de dépassement **là où l'on travaille** — dans la Forge — au lieu de les
réserver à un écran d'administration que personne n'ouvre.

---

## Le défaut

SF-133-06 a placé les alertes dans `/admin`, et le cadrage le disait explicitement : *« un bandeau
dans la console d'admin, et rien ailleurs »*.

**C'était le mauvais choix**, et le PO l'a relevé : il travaille dans la Forge. Une alerte qu'il
faut aller chercher n'alerte personne.

---

## Comportement attendu

### Cas nominal
1. En ouvrant la Forge, un bandeau signale les postes dont le budget hebdomadaire est dépassé, ou
   proche de l'être.
2. Le bandeau s'**écarte** d'un clic.
3. Il **revient à la connexion suivante** tant que le dépassement dure — écarté pour la session, pas
   pour toujours.
4. Sans alerte, **aucun bandeau** : la Forge reste ce qu'elle est.

### Qui voit quoi

| | Voit l'alerte | Voit les montants |
|---|---|---|
| Administrateur | oui | **oui** |
| Consultant | **oui** | **non** |

**Décision, prise par défaut et à renverser d'un mot** : le coût reste une information
d'administration. Un consultant lit *« Ce poste a dépassé son budget hebdomadaire »* et peut agir ;
il n'apprend pas ce que la mission coûte à la plateforme. C'est cohérent avec le reste de F-133, où
le montant ne quitte jamais le serveur pour un non-administrateur.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| API en échec | **aucun bandeau**, aucune erreur affichée : une alerte est un confort, elle ne doit jamais abîmer l'écran |
| Aucun budget posé | aucun bandeau — on ne dépasse pas ce qui n'existe pas |
| Poste d'un autre compte | invisible (isolation `user_id`) |

---

## Critères d'acceptation

- [ ] `GET /api/cost/alerts/mine` rend les alertes des postes de l'appelant, **sans exiger le rôle administrateur**.
- [ ] Les montants (`spentEur`, `budgetEur`) sont **absents de la réponse** pour un non-administrateur — vérifié sur le JSON.
- [ ] Le pourcentage, lui, reste visible pour tous : il dit l'ampleur sans dire l'argent.
- [ ] Un bandeau apparaît dans la Forge dès qu'une alerte existe, et pas sinon.
- [ ] Le bouton « Masquer » le retire **pour la session**, et il revient à la connexion suivante.
- [ ] Une erreur d'API n'affiche **rien** et ne casse pas l'écran.
- [ ] Un compte ne voit jamais les alertes d'un autre.
- [ ] Design system : aucune couleur, police ou espacement hors charte ; pas de `window.alert`.

---

## Périmètre

### Hors scope
- La notification par courriel ou push.
- Le blocage : une alerte informe, elle ne coupe rien (F-133 / SF-133-04).
- L'écran d'administration, qui garde ses alertes complètes.

---

## Technique

| Classe | Changement |
|---|---|
| `CostAlertService` | `currentWeekForOwner(userId)` — mêmes alertes, **sans** garde d'administration |
| **`CostAlertResponse`** *(nouveau)* | DTO qui **retire les montants** quand l'appelant n'est pas administrateur |
| **`CostAlertController`** *(nouveau)* | `GET /api/cost/alerts/mine`, tout utilisateur authentifié |
| **`ForgeCostAlertComponent`** *(nouveau)* | le bandeau, écartable |
| `PostesComponent` | l'insère en tête de `/forge` — **l'écran de travail**, et non la Vitrine `/forge/clients` comme d'abord envisagé : c'est là que le PO passe ses journées |
| `postes.component.spec.ts` | fournit `HttpClient` de test — l'écran porte désormais un enfant qui appelle l'API |

Aucune table, aucune migration.

---

## Plan de test

### Backend
- [ ] La route répond à un utilisateur ordinaire (200, pas 403).
- [ ] Les montants sont **absents** du JSON pour lui, **présents** pour l'administrateur.
- [ ] Un compte ne voit pas les postes d'un autre.
- [ ] Sans budget, la liste est vide.

### Frontend
- [ ] Bandeau affiché quand une alerte existe, absent sinon.
- [ ] « Masquer » le retire ; l'état tient dans la session.
- [ ] Une erreur d'API n'affiche rien.
- [ ] Le texte s'adapte : avec montants pour l'administrateur, sans pour les autres.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Route **ouverte à tous** les authentifiés — première de F-133 dans ce cas. L'isolation ne repose donc plus sur le rôle mais sur `user_id`, et les montants sont retirés à la sortie. `CostAlertService`, `CostAlertResponse`, `CostBudgetController` (inchangé, garde admin conservée) |
| **Contexte tenant** | **oui** | `HostCostService`, `CostBudgetService` — filtrés `user_id` |
| **Navigation / routing** | **oui** | `MesClientsComponent` — vérifier que le bandeau ne décale pas la mise en page des postes |

---

## Estimation

**1 jour.**

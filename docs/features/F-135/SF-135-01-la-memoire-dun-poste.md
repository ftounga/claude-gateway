# Mini-spec — F-135 / SF-135-01 — La mémoire d'un poste : son état, et son activation en un geste

## Identifiant
`F-135 / SF-135-01`

## Feature parente
`F-135` — Aucun poste n'apprend à vide

## Statut
`draft` — 2026-09-21, suite de l'audit

## Branche Git
`feat/SF-135-01-poste-sans-memoire`

---

## Objectif

Qu'un poste sans mémoire se **voie** et s'**active en un geste**, et qu'aucun agent ne reçoive plus
une doctrine décrivant une carte absente de sa machine.

---

## Le défaut, mesuré

| Poste | Activation | Dépôt réel | Faits |
|---|---|---|---|
| CAGIP | oui | oui (16 fichiers) | 2 593 |
| **EDENRED** | oui | **jamais** (`applied_at` nul) | 0 |
| **FREE** | **aucune** | — | 0 |
| **Richemont** | **aucune** | — | 0 |

Deux causes distinctes :

1. **L'embarquement n'est pas rétroactif.** `embarkDefaults` ne tourne qu'à la **création** d'un
   poste (`GovernanceHostLifecycleListener:49`). FREE et Richemont sont nés avant que le paquet ne
   devienne un défaut : ils ne l'auront jamais.
2. **Les règles partent sans les fichiers.** `activeOn` ne regarde pas si le dépôt a eu lieu
   (`GovernanceActivationService:216`). EDENRED reçoit donc une doctrine qui lui parle d'une carte
   qui n'existe pas sur sa machine — l'agent y cherche des fichiers absents.

---

## Le piège à ne pas tomber dedans

**Filtrer sur `status == APPLIED` serait une régression grave.** CAGIP est en `PENDING` **avec**
`applied_at` rempli : ses fichiers sont bien posés, mais le paquet a été **mis à jour** (v2) depuis,
ce qui a remis le statut en attente. Filtrer sur le statut lui retirerait ses règles.

Le bon critère est **`applied_at != null`** — dont le contrat dit exactement ce qu'on cherche :
*« Instant du dernier dépôt abouti ; null tant que rien n'a pu être écrit. »* Un paquet à mettre à
jour garde ses règles ; un paquet jamais posé n'en donne aucune.

---

## Comportement attendu

### Cas nominal
1. Une lecture donne, pour chaque poste de l'utilisateur, l'**état de sa mémoire** : active, en
   attente de dépôt, ou absente — et le nombre de faits déjà accumulés quand il y en a.
2. Un geste unique **met en mémoire** un poste qui ne l'est pas : il embarque les paquets par défaut
   **et** dépose les fichiers, en un appel.
3. Les règles de gouvernance ne rejoignent la consigne système que pour les paquets dont **les
   fichiers sont réellement posés**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Machine éteinte au moment du geste | l'activation est **enregistrée**, le dépôt échoue proprement, l'état rendu est « en attente de dépôt » — rien n'est perdu |
| Poste « Hébergé » (sans machine) | état **sans objet** : pas de racine, donc pas de carte. Aucun appel ne part |
| Aucun paquet par défaut au catalogue | le geste ne fait rien et le dit ; il ne crée pas d'activation vide |
| Poste d'un autre compte | invisible (isolation `user_id`) |

---

## Critères d'acceptation

- [ ] `GET /api/governance/hosts/memory` rend l'état de mémoire de **tous** les postes de l'appelant.
- [ ] Un poste sans activation est rendu `ABSENT` ; un poste activé sans dépôt est rendu `PENDING` ; un poste dont les fichiers sont posés est rendu `ACTIVE`.
- [ ] `POST /api/governance/hosts/{hostRef}/memory` embarque **et** dépose en un appel, et rend le nouvel état.
- [ ] Un paquet **jamais déposé** (`applied_at` nul) n'injecte **aucune règle** dans la consigne système.
- [ ] Un paquet **déposé puis mis à jour** (`PENDING` avec `applied_at`) **garde** ses règles — non-régression CAGIP, test dédié.
- [ ] Le poste « Hébergé » ne déclenche aucun appel machine.
- [ ] Un compte ne voit jamais l'état d'un poste d'un autre compte.

---

## Périmètre

### Hors scope
- L'écran : c'est **SF-135-02**, planifiée avec celle-ci.
- Le choix du paquet : le geste embarque **les défauts du catalogue**, il n'ouvre pas de sélecteur.
- La réparation d'un dépôt partiel — `apply` existe déjà et reste le geste correctif.

---

## Technique

| Classe | Changement |
|---|---|
| `GovernanceActivationService.activeOn` | filtre sur **`appliedAt != null`** ; une méthode séparée garde la vue non filtrée pour les écrans |
| **`HostMemoryService`** *(nouveau)* | l'état de mémoire par poste, et le geste « mettre en mémoire » |
| **`HostMemoryState`** *(nouveau)* | `ABSENT` · `PENDING` · `ACTIVE` · `UNSUPPORTED` |
| **`HostMemoryController`** *(nouveau)* | `GET /governance/hosts/memory`, `POST /governance/hosts/{hostRef}/memory` |
| `GovernanceMapGrowthRepository` | lecture des faits déjà accumulés, par poste |

Aucune table, aucune migration.

---

## Plan de test

- [ ] `activeOn` ignore un paquet jamais déposé.
- [ ] `activeOn` **garde** un paquet déposé puis mis à jour (le cas CAGIP).
- [ ] L'état est `ABSENT` sans activation, `PENDING` sans dépôt, `ACTIVE` avec dépôt.
- [ ] Le geste embarque puis dépose ; machine éteinte ⇒ `PENDING`, pas d'erreur remontée à l'écran.
- [ ] Poste « Hébergé » ⇒ `UNSUPPORTED`, aucun appel machine.
- [ ] Isolation : deux comptes, deux postes homonymes.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Les deux routes partent du JWT et passent par `GovernanceHostScope`, qui vérifie la possession du poste — le même chemin que toutes les routes de gouvernance existantes. Aucun identifiant brut venu du client n'atteint un dépôt. |
| **Plans / limites** | **oui** *(indirect)* | `activeOn` change de résultat : **tous** ses appelants sont concernés — `GovernanceRulesProvider` (consigne système), `GovernanceMapDestinations` (fichiers de carte attendus), `GovernanceCheckpointDelegate` (contrôles de fin de tour). Chacun est vérifié : un paquet jamais déposé ne doit ni donner de règles, ni faire attendre des fichiers, ni armer un contrôle. |
| Navigation / routing | non | — |

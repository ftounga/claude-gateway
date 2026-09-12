# Mini-spec — F-94 / SF-94-03 — Le juge branché en fin de tour

## Identifiant

`F-94 / SF-94-03`

## Feature parente

`F-94` — Le juge indépendant

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-94-03-le-juge-branche`

---

## Objectif

**Brancher le juge** : un nouveau contrôle de fin de tour qui consulte le second appel **seulement
si un tour a écrit** dans un projet du poste, et qui rend sa réponse comme ce qu'elle est — une
**liste à vérifier**, jamais une autorité.

---

## Comportement attendu

### 1. Il ne coûte que quand il peut servir

C'est le marqueur `.infra-dirty` du prompt d'origine, transposé. Le prompt ne déclenche l'audit que
si la session a touché un sujet ; ici, **le contrôle ne consulte le juge que si le tour a écrit**.

| Ce que porte le contexte de fin de tour | Décision |
|---|---|
| `writtenPaths` **vide** | **passe**, aucun appel — un tour qui n'a rien écrit n'a rien fait apparaître |
| `writtenPaths` non vide, **déjà jugés** | **passe**, aucun appel — on ne repose pas deux fois la même question |
| `writtenPaths` non vide et nouveaux | le juge est consulté |

Le produit n'a pas besoin d'un fichier témoin : **la boucle sait déjà ce que le tour a écrit**
(`AtelierCheckpointContext.writtenPaths`, F-50 / SF-50-02). Poser un `.infra-dirty` sur la machine
d'un client pour réapprendre ce que la gateway sait déjà serait un fichier de plus à nettoyer.

**La mémoire des questions déjà posées** (`JugeMemo`) est bornée, à durée de vie courte, et vit en
mémoire du processus : c'est une **économie**, pas une garantie. Un autre pod repose la question, et
c'est sans conséquence — au pire un appel de plus.

### 2. Ce qu'il rend au modèle

| Avis | Verdict du contrôle |
|---|---|
| `RIEN` | **passe** — le juge a regardé et n'a rien trouvé |
| `PAS_DE_MATIERE` / `INDISPONIBLE` | **passe** — rien à comparer, ou le juge n'a pas pu être consulté |
| `ELEMENTS` | **bloque**, avec la liste **présentée comme un filet best-effort** |
| `VERDICT_ILLISIBLE` | **bloque** — *le repli qui alerte* |

**Le message porte son action corrective**, parce qu'il est lu par un modèle qui doit corriger :

> **filet best-effort — à vérifier avant d'agir.** Un second regard signale : « bastion bst-01 —
> cité dans migration-dns/STATE.md ». Pour chacun : vérifie-le dans le fichier cité ; s'il est
> durable et réellement absent de la carte, range-le dans l'un de ces fichiers (`acces.md`,
> `reseau.md`, …) et trace-le coché dans `STATE.md` (« - [x] … -> promu dans … ») ; s'il y figure
> déjà ou s'il n'est pas durable, ignore-le et reprends ta réponse.

Et pour le repli :

> **filet best-effort — à vérifier avant d'agir.** Le second regard n'a **pas** rendu de verdict
> lisible : rien n'a donc été vérifié. Relis toi-même les `.md` de ce projet, promeus dans la carte
> du poste (`acces.md`, …) ce qui est durable et n'y figure pas, puis reprends ta réponse.

### 3. Comment il se compose avec `JugeFinDeTourControl`

**On ne supprime rien.** Les deux ne disent pas la même chose, et c'est pourquoi ils tiennent
ensemble :

| | `juge-fin-de-tour` (F-52) | `juge-independant` (F-94) |
|---|---|---|
| Ce qu'il lit | le **marqueur** que le modèle pose | **la carte et les notes**, sur la machine |
| Ce qu'il attrape | le modèle qui **sait** qu'il n'a pas promu | le modèle qui a **oublié** — et qui oubliera aussi de le déclarer |
| Ce qu'il coûte | rien | un appel, et seulement si le tour a écrit |
| Son verdict | une **forme** absente ou une promotion déclarée | une **liste à vérifier** |

Ils ne peuvent pas se contredire : le premier juge une **déclaration**, le second des **fichiers**.
Et l'ordre du paquet les range du moins cher au plus cher — le premier blocage l'emporte (F-50), donc
**le juge indépendant n'est consulté que si les contrôles gratuits sont passés**.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Contexte nul, `userId`/`workspaceId` nul | **passe** |
| Tour sans écriture | **passe**, aucun appel |
| Question déjà posée | **passe**, aucun appel |
| Juge indisponible (coupe-circuit, fournisseur, délai) | **passe** — la session se termine normalement |
| Le service du juge lève malgré tout | **passe** — F-50 isole déjà, on n'y ajoute rien de fragile |
| La carte n'a pas pu être listée pour le message | le message reste vrai sous sa forme générique |
| Le contrôle bloque en boucle | F-50 **rend la main** après trois refus — un juge ne prend jamais un message en otage |

---

## Critères d'acceptation

- [x] Un tour **sans écriture** ne consulte jamais le juge
- [x] Un tour qui a écrit consulte le juge **une fois** ; le même jeu d'écritures ne le reconsulte pas
- [x] `RIEN`, `PAS_DE_MATIERE`, `INDISPONIBLE` laissent passer
- [x] `ELEMENTS` bloque, cite les éléments **et leur source**, nomme les fichiers de carte réels, et
      dit **« filet best-effort — à vérifier avant d'agir »**
- [x] `VERDICT_ILLISIBLE` bloque avec un message qui dit **ce qu'il faut relire soi-même**
- [x] Le contrôle est déclaré au registre sous l'identifiant `juge-independant`
- [x] Le paquet « savoir-durable » le cite **en dernier**, après les contrôles gratuits
- [x] Le texte de règles et `GOUVERNANCE.md` annoncent le nouveau contrôle
- [x] `JugeFinDeTourControl` **n'est ni supprimé ni modifié**
- [x] Le contrôle ne lève jamais
- [x] **Isolation** : le couple `(userId, workspaceId)` vient du contexte de F-50, construit après
      `requireOwned` ; la mémoire est indexée par ce couple

---

## Périmètre

### Hors scope (explicite)

- Tout écran : le refus est déjà rendu par le bloc de point de contrôle du terminal (SF-39-17)
- Toute persistance des avis du juge : un avis est une liste à vérifier, pas une donnée
- Tout changement de `JugeFinDeTourControl` ou de `PromotionDetteBloquanteControl`

---

## Contraintes de validation

| Champ | Obligatoire | Valeur | Règle |
|---|---|---|---|
| identifiant du contrôle | Oui | `juge-independant` | **immuable** : un paquet publié le référence |
| entrées de la mémoire | — | 500 | LRU : au-delà, la plus ancienne sort |
| durée de vie d'une entrée | — | 30 min | au-delà, la question se repose |
| chemins retenus par entrée | — | 200 | borne héritée de `AtelierCheckpointContext` |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun — le refus s'affiche dans le bloc de point de contrôle existant du terminal.

---

## Plan de test

### Tests unitaires

- [x] `JugeIndependantControlTest` — tour sans écriture → passe, aucun appel
- [x] `JugeIndependantControlTest` — contexte nul → passe
- [x] `JugeIndependantControlTest` — `RIEN` → passe
- [x] `JugeIndependantControlTest` — `PAS_DE_MATIERE` / `INDISPONIBLE` → passe
- [x] `JugeIndependantControlTest` — `ELEMENTS` → bloque, « best-effort », éléments et destinations
- [x] `JugeIndependantControlTest` — `VERDICT_ILLISIBLE` → bloque avec son geste
- [x] `JugeIndependantControlTest` — la même question n'est pas reposée
- [x] `JugeIndependantControlTest` — une écriture nouvelle repose la question
- [x] `JugeIndependantControlTest` — le service qui lève ne casse rien
- [x] `JugeMemoTest` — LRU, durée de vie, isolation par utilisateur et projet
- [x] `GovernancePackageSeederTest` — le paquet cite le contrôle **en dernier**
- [x] `EndOfTurnControlsTest` — les deux juges se composent sans se contredire

### Tests d'intégration

- [x] `GovernanceSeededPackageIntegrationTest` — le paquet semé porte quatre contrôles connus du
      registre

### Isolation workspace

- [x] Applicable — `JugeMemoTest` vérifie qu'une entrée d'un utilisateur ne répond jamais pour un
      autre, ni pour un autre projet.

---

## Dépendances

### Subfeatures bloquantes

- `SF-94-01` — la matière et le bloc de verdict — **done**
- `SF-94-02` — le second appel — **done**

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Touchée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | aucun changement |
| Contexte tenant | **Oui (lecture)** | le couple `(userId, workspaceId)` vient d'`AtelierCheckpointContext` (F-50), construit après `requireOwned`. La mémoire est indexée par ce couple — jamais par le seul projet |
| **Plans / limites** | **Oui (indirect)** | le contrôle déclenche le décompte de SF-94-02 (`QuotaService.recordUsage`). **Aucun gate nouveau** : `AtelierChatService` reste le seul à poser le gate de quota du tour, et le juge ne refuse rien à personne |
| Navigation / routing | Non | aucun écran |

---

## Notes et décisions

**D1 — Le déclencheur est `writtenPaths`, pas un fichier témoin.** La boucle sait déjà ce que le
tour a écrit. Poser un `.infra-dirty` sur la machine d'un client serait un fichier de plus à
nettoyer, et une seconde source de vérité pour une information qu'on a déjà.

**D2 — Une mémoire en processus, assumée comme une économie.** Elle évite de reposer la même
question au même tour. Elle n'est **pas** une garantie : un autre pod repose la question, et au pire
cela coûte un appel. En faire une table aurait ajouté une écriture par tour pour économiser un appel
qui, lui, n'arrive que sur les tours qui écrivent.

**D3 — Le juge est cité en dernier dans le paquet.** Le premier blocage l'emporte (F-50) : ranger le
contrôle payant après les contrôles gratuits fait qu'il n'est consulté que si la forme est déjà
bonne. L'ordre n'est pas cosmétique, c'est le garde-fou de dépense.

**D4 — On ne supprime pas `JugeFinDeTourControl`.** Le marqueur est gratuit et attrape un cas que
l'audit ne verra pas mieux : le modèle qui **sait** qu'il n'a pas promu. Les deux jugent des choses
différentes — une déclaration, des fichiers — et ne peuvent donc pas se contredire.

# Mini-spec — F-52 / SF-52-02 — Le juge de fin de tour et la dette de promotion

## Identifiant

`F-52 / SF-52-02`

## Feature parente

`F-52` — Premier paquet de gouvernance

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-52-02-juge-de-fin-de-tour`

---

## Objectif

Empêcher un tour de se clore tant que ce qu'il a fait apparaître de **durable** n'a pas rejoint la
carte du projet — en lisant un **marqueur** que le modèle pose en fin de réponse, jamais en jugeant
le sens à sa place.

---

## Comportement attendu

### Cas nominal

1. La règle du paquet demande au modèle de terminer chaque réponse par un marqueur, de forme exacte :

   ```
   <!-- fin-de-tour: promotion=aucune; dette=0 -->
   ```

   - `promotion` — ce que le tour a fait apparaître de **durable** et qui n'est **pas** dans la carte
     du projet (`PLAN-ACTION.md`), séparé par des virgules ; `aucune` s'il n'y a rien.
   - `dette` — le nombre de cases `- [ ]` restées **non cochées** dans la carte du projet.

2. En fin de tour, le contrôle **`juge-fin-de-tour`** lit le **dernier** marqueur de la réponse :
   - marqueur **absent ou illisible** → il **bloque en alertant** : la fin du tour est refusée et le
     modèle reçoit la forme exacte attendue. **Il ne laisse jamais passer en silence** — c'est
     l'exigence écrite de la feature.
   - `promotion` **non vide** → il bloque : promeus ces éléments dans la carte du projet, puis
     reprends la réponse avec `promotion=aucune`.
   - sinon → il passe.

3. Le contrôle **`promotion-dette-bloquante`** lit le même marqueur :
   - `dette` **non nul** → il bloque : une case non cochée empêche de clore.
   - marqueur absent → il **passe** : compter n'est pas policer la forme, et le juge s'en charge
     déjà. Un paquet qui n'active que ce contrôle-là accepte de ne rien compter faute de marqueur.
   - sinon → il passe.

4. Le blocage est celui de F-50 / SF-50-02 : la correction est déposée comme message utilisateur, la
   boucle repart, et le tout est **borné** — après `MAX_END_OF_TURN_BLOCKS` refus, la main est rendue
   au modèle. C'est ce qui fait du juge un avis et **jamais une autorité** : il ne peut pas prendre
   le message d'un utilisateur en otage.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Réponse vide ou blanche | Traitée comme « marqueur absent » : le juge alerte | — |
| `dette=trois` (non numérique) | Marqueur **illisible** : le juge alerte, la dette n'est pas comptée | — |
| `dette=-1` | Illisible : une dette négative ne veut rien dire | — |
| Marqueur cité deux fois (exemple puis vrai marqueur) | Le **dernier** fait foi — c'est celui qui clôt la réponse | — |
| `promotion=` vide (`promotion=; dette=0`) | Vaut `aucune` : ne rien avoir à promouvoir s'écrit aussi par le vide | — |
| Liste de promotion démesurée | Bornée à 10 éléments et 300 caractères dans le message correctif | — |

---

## Critères d'acceptation

- [x] `FinDeTourMarker.parse` rend le **dernier** marqueur de la réponse, ou « absent ».
- [x] La casse du marqueur et les espaces autour des `=` et `;` sont tolérés.
- [x] `promotion=aucune`, `promotion=none` et `promotion=` valent tous « rien à promouvoir ».
- [x] `juge-fin-de-tour` bloque sur marqueur absent, et son message porte la **forme exacte** attendue.
- [x] `juge-fin-de-tour` bloque sur `promotion` non vide, et son message **nomme les éléments**.
- [x] `juge-fin-de-tour` passe sur `promotion=aucune; dette=3` — compter la dette n'est pas son rôle.
- [x] `promotion-dette-bloquante` bloque sur `dette=2`, passe sur `dette=0` et sur marqueur absent.
- [x] Les deux contrôles se déclarent sur `END_OF_TURN` et portent un identifiant stable.
- [x] Isolation : les deux contrôles ne lisent **aucune donnée** — ils jugent le texte du contexte,
      dont le couple `(userId, workspaceId)` est déjà vérifié par la boucle.

---

## Périmètre

### Hors scope (explicite)

- **Un second appel au fournisseur pour juger** (arbitrage A2 du cadrage) : il doublerait le coût de
  chaque tour, et un juge lent est un juge qu'on décroche.
- **La lecture de la carte du projet par la gateway** : le contrôle ne relit ni le stockage ni la
  machine de l'utilisateur (contrat de contexte F-50). Il lit ce que le modèle déclare.
- **Un verdict opposable** : le juge est *best-effort*. Le modèle déclare, F-50 borne, personne n'est
  bloqué indéfiniment.
- **La forme des gabarits** (`STATE.md`, `PLAN-ACTION.md`) : elle arrive en SF-52-03.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Marqueur | `<!-- fin-de-tour: … -->`, insensible à la casse, espaces libres ; le dernier de la réponse fait foi |
| `promotion` | Liste séparée par des virgules ; `aucune` / `none` / vide = rien ; éléments vides ignorés |
| `dette` | Entier ≥ 0 ; toute autre valeur rend le marqueur illisible |
| Éléments cités dans le message correctif | 10 au plus, 300 caractères au plus — au-delà, l'action corrective devient un inventaire |
| Longueur du verdict | Bornée par `AtelierCheckpointVerdict.MAX_CORRECTION_CHARS` (2 000), déjà en place |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Classes créées (`fr.claudegateway.governance.control`)

- `FinDeTourMarker` — la forme du marqueur, son analyse, et la forme rendue au modèle.
- `JugeFinDeTourControl` — `juge-fin-de-tour`.
- `PromotionDetteBloquanteControl` — `promotion-dette-bloquante`.

### Classes modifiées

Aucune.

### Composants Angular

Aucun — F-52 n'apporte pas d'écran (§7 du cadrage). Le marqueur est un commentaire HTML : le rendu
Markdown de la réponse ne l'affiche pas.

---

## Plan de test

### Tests unitaires

- `FinDeTourMarkerTest` : présence, absence, dernier marqueur retenu, casse, espaces, `aucune` /
  `none` / vide, dette non numérique, dette négative, liste de promotion.
- `JugeFinDeTourControlTest` : les trois verdicts, la forme exacte dans le message d'alerte, les
  éléments nommés, le contexte nul, la réponse vide.
- `PromotionDetteBloquanteControlTest` : dette nulle, dette non nulle, marqueur absent, contexte nul.

### Tests d'intégration (boucle)

- `AtelierChatServiceEndOfTurnCheckpointTest` couvre déjà le branchement du point d'accroche et son
  bornage (F-50 / SF-50-02) ; SF-52-02 n'y touche pas. Un test de bout en bout est ajouté sur le
  contrôle réel : une réponse sans marqueur repart, une réponse avec marqueur propre s'arrête.

### Isolation workspace / utilisateur

- Les deux contrôles ne lisent aucune donnée : un test vérifie qu'ils rendent le même verdict quels
  que soient `userId` et `workspaceId`, y compris absents.

---

## Dépendances

### Subfeatures bloquantes

- F-50 / SF-50-02 (le crochet de fin de tour et son bornage) — **Done**.
- F-51 / SF-51-04 (la délégation vers les paquets actifs) — **Done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Pourquoi un marqueur plutôt qu'un juge qui appelle le fournisseur** : le coût. Un juge appelé à
  chaque fin de tour double le nombre d'appels d'un projet gouverné, pour une décision que le modèle
  peut rendre dans la réponse qu'il écrit déjà. Le contrôle, lui, reste **mécanique** : il lit une
  forme, il ne juge pas un sens.
- **Pourquoi deux contrôles et non un** : ils ne défendent pas la même chose. Le juge défend la
  **promotion** (ce qui est durable rejoint la carte) ; l'autre défend la **dette** (une case non
  cochée empêche de clore). Un utilisateur peut vouloir l'un sans l'autre — c'est tout l'intérêt d'un
  catalogue.
- **Pourquoi le juge alerte au lieu de laisser passer** : c'est l'exigence écrite. Un filet sémantique
  qui se tait quand il ne comprend pas ne protège de rien ; il donne seulement l'illusion d'un
  contrôle.

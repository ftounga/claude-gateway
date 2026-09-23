# Mini-spec — F-121 / SF-121-19 Suivi de fraîcheur des fichiers (read/write-before-edit)

## Identifiant

`F-121 / SF-121-19`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-19-fraicheur-fichiers`

---

## Objectif

> En une phrase : refuser d'éditer/écraser à l'aveugle un fichier du projet en suivant, par fichier du
> fil, une empreinte de fraîcheur, pour que le modèle raisonne sur un contenu qu'il a réellement lu.

---

## Comportement attendu

### Cas nominal

Un suivi de fraîcheur **thread-scoped** (`AtelierFileFreshness`) mémorise, pour chaque chemin **lu ou
écrit dans le fil**, qu'il est « connu », et — pour un contenu **plein** vu dans le tour — son empreinte
**SHA-256** (calculée dans la gateway, sur le contenu déjà en main : **aucun aller-retour runner**).
Le suivi est **amorcé** au début du tour à partir des `tool_use` `read_file`/`write_file`/`edit_file`
déjà rejoués dans l'historique (`buildReplayMessages`) — donc la connaissance survit d'un message à
l'autre du même fil.

Avant d'émettre une écriture de fichier, une **garde** tranche (activée par le drapeau existant
`app.atelier.file-state-hints`, défaut actif). Le refus dur est **gardé par la preuve d'existence** —
même critère pour les deux écritures, ce qui ne bloque jamais la création d'un fichier neuf ni un poste
sans index :
- `read_file` → marque le chemin connu ; met à jour l'empreinte ; si une **relecture plein-fichier**
  diffère de l'empreinte connue → une **note** est jointe au résultat (« ce fichier a changé depuis ta
  lecture précédente dans ce fil ; tu en reçois ici la version à jour »).
- `edit_file` / `write_file` sur un chemin **jamais lu dans ce fil** dont l'**existence est prouvée**
  (`repo_index` amorcé le liste) → **REFUS** avant émission (aucune écriture, aucun aller-retour) :
  read-before-edit / read-before-overwrite (« lis-le avant de l'éditer / de remplacer son contenu »).
- `edit_file` sur un chemin **jamais lu** dont l'existence **n'est pas prouvée** (index muet, fichier
  peut-être neuf) → **autorisé** avec un **rappel doux** de lecture-avant-édition (SF-119-05 conservé,
  jamais bloquant).
- `write_file` sur un chemin **jamais lu** dont l'existence **n'est pas prouvée** → **autorisé** (repli
  sûr : création d'un fichier neuf).
- Une écriture aboutie (`write_file`/`edit_file`) marque le chemin connu et rafraîchit l'empreinte.

Lire puis éditer le même fichier (même tour ou tour précédent du fil) ne déclenche **aucun refus** :
c'est le comportement encouragé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `edit_file`/`write_file` d'un fichier **existant (indexé)** jamais lu | Refus (tool_result en erreur), message read-before-edit/overwrite, **write jamais appelé** |
| `edit_file` d'un fichier **non prouvé existant** jamais lu | Autorisé + **rappel doux** SF-119-05 (jamais bloquant) |
| `write_file` d'un fichier neuf / index non amorcé / indéterminable | Autorisé (repli sûr) |
| Drapeau `file-state-hints=false` (coupe-circuit) | Aucune garde, aucun rappel, aucune note : comportement d'avant |
| Relecture d'un fichier au contenu changé | Note « a changé depuis ta lecture » jointe au résultat (jamais un refus) |
| Chemin absent/vide dans l'appel | Garde inerte (l'exécution habituelle tranche) |
| Lecture paginée (`offset`/`limit`) | Pas de note de changement (empreinte partielle non comparée), chemin quand même marqué connu |

---

## Critères d'acceptation

- [ ] Un `edit_file`/`write_file` d'un fichier **jamais lu mais indexé** (existence prouvée) est **refusé** avant émission ; `writeFile` / le runner ne sont **pas** appelés.
- [ ] Un `edit_file` d'un fichier **non prouvé existant** jamais lu **écrit** avec un **rappel doux** (non-régression SF-119-05).
- [ ] Un `read_file` puis `edit_file` du même fichier dans le même tour **n'est pas refusé** et n'a **aucun rappel**.
- [ ] Un fichier lu dans un **tour précédent** du fil (amorçage depuis l'historique rejoué) rend l'`edit_file` du tour courant **autorisé sans rappel**.
- [ ] Un `write_file` d'un fichier **neuf / index non amorcé** est **autorisé** (repli sûr).
- [ ] Le coupe-circuit `app.atelier.file-state-hints=false` **désactive** la garde, le rappel et la note.
- [ ] Une relecture plein-fichier d'un contenu **différent** joint la note « a changé depuis ta lecture » ; un contenu **identique** ne joint rien.
- [ ] La garde et l'empreinte n'entraînent **aucun appel runner supplémentaire** (empreinte calculée sur le contenu déjà rendu ; existence via `repo_index` en base).
- [ ] Isolation : le suivi est **par (userId, fil)** — reconstruit du seul historique de CE fil.

---

## Périmètre

### Hors scope (explicite)

- **Détection d'une modification externe silencieuse au moment de l'édition** (fichier changé hors de la
  boucle entre la lecture et l'édition, sans relecture) : non détectable sans **stat/hash runner** à
  l'édition ; l'audit F-121 proscrit l'aller-retour. Le filet existant reste : `edit_file` échoue
  nativement si son `old_string` ne correspond plus. À revisiter si une empreinte disque bon marché
  devient disponible.
- Aucune nouvelle table, aucune persistance dédiée (le suivi vit le temps du fil, reconstruit du trace).
- Aucun changement du prompt système / préfixe (cache F-134 préservé).
- Aucun changement de la discipline d'effort/escalade F-119 (SF-119-01/02/03) ni du contrat runner.
- Frontend : aucun (la garde parle au modèle via un `tool_result`, déjà rendu à l'écran).

---

## Valeurs initiales

Sans objet (aucune entité créée, aucun état persisté).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| empreinte fichier | — | SHA-256 hex du contenu **plein** rendu (read) ou écrit (write) | calcul interne, jamais exposé au modèle |
| `app.atelier.file-state-hints` | Non | Boolean, défaut `true` (repli : absent ⇒ actif) | drapeau existant (SF-119-05), sémantique étendue à la garde dure |

Notes :
- La garde ne fabrique **jamais** de contenu ni ne « voit » un fichier à la place du modèle
  (Gateway-First / Provider-First) : elle relaie une consigne (« lis-le d'abord ») au modèle.
- L'empreinte réutilise l'approche SHA-256 déjà employée par `PromptSourceStore` (contenu en main).

---

## Technique

### Endpoint(s)

Aucun (logique interne de la boucle `AtelierChatService`).

### Tables impactées

Aucune. `repo_index_paths` est **lue** (existence d'un chemin), jamais écrite par cette SF.

### Migration Liquibase

- [x] Non applicable (dernière migration : `128`).

### Composants impactés (préoccupation transversale — analyse d'impact)

- `AtelierFileFreshness` (**neuf**) — suivi thread-scoped : connu + empreinte + garde + note.
- `AtelierChatService` — remplace le `Set<String> knownFiles` (turn-scoped, SF-119-05) par le suivi
  thread-scoped ; amorçage depuis l'historique ; **garde avant émission** d'une écriture ; note jointe
  au `tool_result` d'un `read_file` ; retrait du `withFileStateHint` doux au profit de la garde.
- `RepoIndex` (+ `RepoIndexProvider`, `RepoIndex.NONE`) — nouvelle méthode `indexed(userId, workspace,
  path)` (existence exacte d'un chemin), repli `false` (NONE / index non amorcé) ⇒ garde write inerte.
- **Contexte tenant** : le suivi est **par userId+fil** (aucune fuite inter-utilisateur) — reconstruit
  du seul historique du fil courant, filtré `userId` (déjà le cas dans `buildReplayMessages`).
- **Plans / limites** : aucun impact quota ; **zéro appel runner ajouté** (empreinte en mémoire,
  existence en base). Un refus consomme une itération de tour, bornée par `maxIterations` (existant).
- **Navigation / routing** : aucun (pas d'UI).
- **Auth / Principal** : inchangé.

---

## Plan de test

### Tests unitaires (`AtelierFileFreshnessTest`)

- [ ] `edit_file` chemin inconnu **existant** → refus (message read-before-edit).
- [ ] `write_file` chemin inconnu **existant** → refus d'écrasement.
- [ ] `edit_file` chemin inconnu **non prouvé existant** → pas de refus, **rappel doux**.
- [ ] `write_file` chemin inconnu **non prouvé existant** → pas de refus, pas de rappel (repli sûr).
- [ ] `read` (ou `write`) puis `edit_file` même chemin → pas de refus, pas de rappel.
- [ ] Garde désactivée → jamais de refus ni rappel ni note.
- [ ] Relecture plein-fichier contenu changé → note ; contenu identique → aucune note ; 1re lecture → aucune note.
- [ ] Lecture paginée → chemin connu, pas de note de changement.
- [ ] `seedKnown` (amorçage historique) → un `edit_file` du chemin amorcé n'est ni refusé ni rappelé.

### Tests d'intégration (`AtelierChatServiceTest`, cible SANDBOX)

- [ ] Édition à l'aveugle (index NONE) → **exécutée** avec rappel doux (non-régression SF-119-05).
- [ ] `read_file` puis `edit_file` → écrit sans rappel (non-régression).
- [ ] Coupe-circuit `file-state-hints=false` → édition à l'aveugle **exécutée** sans rappel (non-régression).
- [ ] `AtelierPropertiesTest` : défaut `file-state-hints` actif (déjà couvert, non-régression).

> Le refus dur (existence prouvée) est couvert en **unitaire** (`AtelierFileFreshnessTest`) plutôt qu'en
> intégration : amorcer l'index de repo dans la boucle relève d'`AtelierChatServiceRepoIndexTest` et la
> logique de refus est isolée dans `AtelierFileFreshness`.

### Isolation

- [x] Applicable — le suivi est reconstruit du seul historique du fil de l'utilisateur (filtre `userId`
  déjà en place dans `buildReplayMessages`) ; test couvrant l'amorçage depuis l'historique du même fil.

---

## Dépendances

### Subfeatures bloquantes

- `F-121` en cours (SF-121-01/03/04/05/21 livrées) — aucune dépendance dure ; extension de SF-119-05.
- Réutilise `repo_index` (F-148 / SF-148-07, livré) et l'historique rejoué (F-119 / SF-119-03).

### Questions ouvertes impactées

- Aucune (la détection de modif externe à l'édition est explicitement hors scope, tracée ci-dessus).

---

## Notes et décisions

- **Décision A (pas de nouvelle table)** : le suivi vit le temps du fil, reconstruit du trace déjà
  persisté — conforme « peu de surface » et « prio basse » de l'audit.
- **Décision B (drapeau réutilisé)** : `app.atelier.file-state-hints` gouverne désormais la **garde
  dure** en plus du rappel doux d'origine ; coupe-circuit à `false` = repli sûr. Évite d'alourdir le
  record `AtelierProperties` et ses constructeurs de compatibilité, et garde un seul knob pour la
  discipline de fraîcheur (continuité SF-119-05 → SF-121-19).
- **Décision C (empreinte sans aller-retour)** : SHA-256 calculé sur le contenu déjà rendu (read) /
  écrit (write) ; existence via `repo_index` en base — conforme à l'audit (« réutilise SHA
  PromptSourceStore/repo_index pour éviter des allers-retours runner »).
- **Décision D (refus dur gardé par la preuve d'existence)** : le refus read-before-edit/overwrite ne
  se déclenche que si l'existence du fichier est **prouvée** par l'index de repo, **sans aller-retour**.
  Motifs : (1) la tâche cadre « jamais lu » sur *un écrasement*, et notre `edit_file` **relit** déjà le
  disque et exige la correspondance `old_string` (corruption impossible, hypothèse fausse → échec
  propre) ; (2) « repli sûr » — ne jamais bloquer la création d'un fichier neuf ni un poste sans index ;
  (3) zéro `stat`/`hash` runner ajouté. À défaut de preuve, l'édition à l'aveugle garde le **rappel
  doux** SF-119-05. **Variante assumée vs la lettre de la feuille de route** (« refuser edit_file sans
  Read récent ») : le refus reste conditionné à la preuve d'existence — documenté ici et dans la PR.
- **Décision E (détection de modif externe à l'édition — hors scope)** : « modifié depuis la dernière
  lecture » n'est pas détectable au moment de l'édition sans `stat`/`hash` runner, proscrit par l'audit.
  Couvert par : `edit_file` relit + `old_string` (échec propre si périmé) et la **note de changement**
  à la relecture. À revisiter si une empreinte disque bon marché est exposée.
- **Provider Independence** : aucune dépendance modèle ; pure logique de boucle. **Gateway-First** : la
  garde relaie une consigne, ne « voit » pas le fichier. **Aucun composant cluster.**

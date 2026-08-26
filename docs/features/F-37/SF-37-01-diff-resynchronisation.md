# Mini-spec — [F-37 / SF-01] Calcul du diff à la resynchronisation (backend)

---

## Identifiant

`F-37 / SF-01`

## Feature parente

`F-37` — Voir les modifications (diff unifié du tour d'exécution)

## Statut

`done` — livrée le 2026-08-26 (PR #181)

## Date de création

2026-08-26

## Branche Git

`feat/SF-37-01-diff-tour-execution`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Calculer, **au moment de la resynchronisation** — le seul instant où l'ancienne et la nouvelle version
d'un fichier coexistent —, le **diff unifié borné** de chaque fichier réécrit, le relayer dans le flux
d'exécution et le **persister avec le tour**.

---

## Contexte

À la fin d'un run, le tour porte `changedFiles` : une liste de chemins. Pour savoir ce que l'agent a
écrit, il faut rouvrir chaque fichier et le relire de mémoire. Le geste le plus fréquent avant
d'accepter un travail est donc celui qui manque.

Le moment du calcul existe déjà sans rien ajouter. Dans `AtelierSessionService.run`, étape 6, la
Gateway tient l'**ancien** contenu (lisible depuis le stockage, l'index de remap de chemin étant déjà
construit) et le **nouveau** (téléchargé depuis la session) juste avant `writeFile`. Les deux versions
sont en main au même instant.

Aucune bibliothèque de comparaison n'est présente dans le projet, et en ajouter une pour comparer des
lignes de texte serait disproportionné : le calcul est écrit à la main, sur une **plus longue
sous-séquence commune (LCS)** appliquée aux lignes.

Deux pièges du cadrage sont traités ici, pas ailleurs :

- **« réécrit » ≠ « modifié »** (D5). Une session persistante réexpose toutes ses sorties à chaque
  tour. Le registre incrémental `syncedOutputs` filtre déjà les sorties **déjà rapatriées**, mais après
  un redémarrage d'instance (registre en mémoire) une sortie identique repasse : annoncer ce fichier
  comme modifié serait faux. Un contenu **strictement identique** est donc exclu du diff **et** de
  `changedFiles`, et n'est pas réécrit.
- **le coût mémoire.** La comparaison ligne à ligne est quadratique. Elle est bornée **avant** de
  comparer, jamais seulement à l'affichage.

---

## Comportement attendu

### Cas nominal

1. Étape 6 du run (resynchronisation), pour chaque sortie **non encore rapatriée** :
   1. le chemin cible est résolu comme aujourd'hui (`resolveOutputPath`) ;
   2. l'**ancien** contenu est lu depuis le stockage si le chemin existe déjà dans l'arborescence du
      workspace, sinon le fichier est **nouveau** ;
   3. le **nouveau** contenu est celui qui vient d'être téléchargé ;
   4. si les deux sont **identiques** ⇒ rien n'est écrit, le fichier n'apparaît ni dans `changedFiles`
      ni dans les diffs ;
   5. sinon, le fichier est écrit comme aujourd'hui, ajouté à `changedFiles`, et un **diff unifié**
      est produit.
2. Le diff est **unifié**, avec **3 lignes de contexte** autour de chaque bloc de changement, en-têtes
   de section `@@ -a,b +c,d @@`.
3. Un fichier **nouveau** produit un diff d'**ajout intégral** (toutes ses lignes en `+`), borné de la
   même façon, et porte `added: true`.
4. Le diff est **borné par fichier** à `max-diff-lines` lignes (défaut **400**) : au-delà, il est
   tronqué sur une frontière de ligne et `omittedLines` porte le nombre de lignes de diff omises.
5. Le nombre de **fichiers** portant un diff est borné à `max-diff-files` (défaut **50**) ; les
   fichiers au-delà sont réécrits et listés dans `changedFiles` comme aujourd'hui, mais sans diff.
6. Les diffs sont relayés dans le flux, sur l'événement SSE **`done`**, sous la clé `diffs`
   (**champ additif**).
7. Les diffs sont **persistés avec le tour**, dans le document d'affichage `terminal_json` de
   `atelier_messages`, sous la clé `diffs` (**champ additif**) — comme la transcription (SF-30-09) et
   comme la marque d'interruption (F-32).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Contenu non textuel (octet `NUL` présent d'un côté ou de l'autre) | Aucune comparaison ; entrée portant `unreadable: true` et un diff vide. Le fichier est **quand même écrit** et listé dans `changedFiles` |
| Ancien contenu illisible (fichier disparu du stockage entre l'inventaire et la lecture) | Traité comme un fichier **nouveau** (ajout intégral) — jamais d'exception |
| Comparaison trop volumineuse (produit des longueurs > 2 000 000 après élagage du préfixe/suffixe commun) | Repli **sans LCS** : la région changée est rendue en bloc retiré puis bloc ajouté, borné comme le reste |
| Diff dépassant `max-diff-lines` | Tronqué ; `omittedLines > 0` |
| Plus de `max-diff-files` fichiers modifiés | Les suivants n'ont pas de diff ; `changedFiles` reste complet |
| Sérialisation du document du tour en échec | Inchangé : best-effort, le tour est livré, le document non écrit |
| Échec de lecture de l'ancien contenu pour une autre raison | Le diff dégrade en ajout intégral ; le run n'échoue jamais pour un motif d'affichage |
| Fichier réécrit à l'identique | Ni écriture, ni `changedFiles`, ni diff (D5) |

---

## Critères d'acceptation

- [x] Un fichier existant modifié produit un diff unifié portant les lignes retirées (`-`) et ajoutées
      (`+`) et 3 lignes de contexte, avec en-tête `@@`
- [x] Un fichier **nouveau** produit un diff d'ajout intégral et `added: true`
- [x] Un fichier réécrit **à l'identique** n'est ni écrit, ni listé dans `changedFiles`, ni porteur
      d'un diff
- [x] `addedLines` / `removedLines` comptent exactement les lignes `+` / `-` du diff produit
- [x] Un diff dépassant `max-diff-lines` est tronqué sur une frontière de ligne, `omittedLines > 0`
- [x] Au-delà de `max-diff-files` fichiers, les suivants sont réécrits sans diff, `changedFiles` reste
      complet
- [x] Un contenu contenant un octet `NUL` produit `unreadable: true` sans exception ni comparaison
- [x] Une comparaison hors borne mémoire retombe sur le repli en blocs, sans exception
- [x] L'événement SSE `done` porte `diffs` (**additif** : un client antérieur l'ignore)
- [x] Le document `terminal_json` du tour porte `diffs` ; un tour écrit avant cette SF n'en porte pas
      et reste lisible
- [x] L'isolation `user_id` est inchangée : lecture et écriture passent par `WorkspaceService`, après
      `requireOwned`
- [x] Aucune dépendance nouvelle (pas de bibliothèque de diff), aucune dépendance Anthropic dans le
      calcul
- [x] Aucun contenu de fichier ni aucune clé n'est journalisé

---

## Périmètre

### Hors scope

- Affichage du diff à l'écran → **SF-37-02**
- Diff entre deux tours quelconques
- Annulation d'une modification depuis le diff (retour en arrière) → hors F-37
- Coloration syntaxique à l'intérieur du diff
- Diff des fichiers **supprimés** par l'agent : la Files API n'expose que des sorties écrites, une
  suppression n'y apparaît pas
- Diff des projets Git côté fournisseur (le dépôt vit dans la sandbox ; la comparaison GitHub reste
  le chemin de lecture après publication)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `max-diff-lines` | Entier > 0, défaut **400** (`APP_ATELIER_AGENT_MAX_DIFF_LINES`) ; valeur absente/≤ 0 ⇒ défaut |
| `max-diff-files` | Entier > 0, défaut **50** (`APP_ATELIER_AGENT_MAX_DIFF_FILES`) ; valeur absente/≤ 0 ⇒ défaut |
| Lignes de contexte | **3**, constante (non configurable : le format unifié standard) |
| Borne mémoire de la LCS | **2 000 000 cellules** après élagage du préfixe et du suffixe communs, constante |
| Détection « illisible » | Présence d'un octet `NUL` dans l'un des deux contenus |
| `path` | Chemin **relatif** au workspace, celui déjà résolu par `resolveOutputPath` |
| Compatibilité | Tous les champs ajoutés sont **additifs** |

---

## Technique

### Contrat API (figé — importé tel quel par SF-37-02)

Évolution **additive** de l'événement SSE `done` de `POST /api/workspaces/{id}/agent/stream` :

| Champ | Type | Sens |
|-------|------|------|
| `diffs` | `FileDiff[]` | Modifications du tour, dans l'ordre de resynchronisation. Peut être vide |

`FileDiff` :

| Champ | Type | Sens |
|-------|------|------|
| `path` | `string` | Chemin relatif au workspace |
| `added` | `boolean` | Le fichier n'existait pas avant ce tour |
| `diff` | `string` | Diff unifié (lignes `@@`, ` `, `-`, `+`), séparateur `\n`. Vide si `unreadable` |
| `addedLines` | `number` | Nombre de lignes `+` du diff produit |
| `removedLines` | `number` | Nombre de lignes `-` du diff produit |
| `omittedLines` | `number` | Lignes de diff omises par la borne ; `0` si le diff est complet |
| `unreadable` | `boolean` | Contenu non textuel : aucune comparaison possible |

Exemple :

```json
{
  "reply": "C'est fait.",
  "changedFiles": ["src/app/jwt.service.ts"],
  "inputTokens": 1200, "outputTokens": 300, "activeSeconds": 42,
  "interrupted": false, "budgetReached": false,
  "diffs": [
    {
      "path": "src/app/jwt.service.ts",
      "added": false,
      "diff": "@@ -1,4 +1,4 @@\n const a = 1;\n-const b = 2;\n+const b = 3;\n const c = 4;",
      "addedLines": 1, "removedLines": 1, "omittedLines": 0, "unreadable": false
    }
  ]
}
```

Évolution **additive** du document `terminal_json` renvoyé par `GET /api/workspaces/{id}/chat` : clé
`diffs`, même forme. Absente des tours écrits avant cette SF.

Aucun nouvel endpoint, aucun code d'erreur nouveau.

### Tables impactées / Migration

**Aucune.** Les diffs vivent dans le document d'affichage `terminal_json` de la table
`atelier_messages` existante — même choix qu'en F-32 et F-35. **Pas de migration Liquibase.**

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/FileDiff.java` | **Nouveau** — une modification de fichier (record) |
| `atelier/agent/UnifiedDiff.java` | **Nouveau** — calcul du diff unifié (LCS sur les lignes), borné |
| `atelier/agent/AtelierSessionService.java` | Étape 6 : ancien contenu lu, identique écarté, diff produit ; persistance dans `terminal_json` |
| `atelier/agent/AtelierSessionResult.java` | Porte les diffs du tour |
| `atelier/agent/AtelierAgentProperties.java` | `maxDiffLines`, `maxDiffFiles` |
| `atelier/AtelierAgentController.java` | `diffs` dans l'événement SSE `done` |
| `resources/application.yml` | Valeurs par défaut des deux bornes |

---

## Plan de test

### Tests unitaires (`UnifiedDiff`)

- [x] Contenus identiques ⇒ aucun diff produit
- [x] Une ligne modifiée ⇒ un `-`, un `+`, contexte autour, en-tête `@@`
- [x] Fichier nouveau ⇒ toutes les lignes en `+`, `added = true`
- [x] Ajout en fin de fichier ⇒ pas de contexte fantôme après la dernière ligne
- [x] Deux zones de changement éloignées ⇒ **deux** sections `@@`
- [x] Deux zones proches (< 2 × contexte) ⇒ **une seule** section
- [x] `addedLines` / `removedLines` comptent exactement les lignes du diff
- [x] Diff plus long que la borne ⇒ tronqué, `omittedLines > 0`, dernière ligne complète
- [x] Contenu avec octet `NUL` ⇒ `unreadable = true`, `diff` vide, aucune exception
- [x] Comparaison hors borne mémoire ⇒ repli en blocs, aucune exception
- [x] Fichier vidé (nouveau contenu vide) ⇒ toutes les lignes en `-`

### Tests unitaires (`AtelierSessionService`)

- [x] Sortie réécrivant un fichier existant ⇒ `writeFile` appelé, `changedFiles` contient le chemin,
      un diff est produit
- [x] Sortie **identique** au contenu existant ⇒ **aucun** `writeFile`, `changedFiles` vide, aucun diff
- [x] Sortie sur un chemin inconnu ⇒ diff `added = true`
- [x] Plus de `max-diff-files` sorties ⇒ diffs bornés, `changedFiles` complet
- [x] Le tour persisté porte `diffs` dans `terminal_json`
- [x] Aucun diff et aucune transcription ⇒ document du tour toujours `null` (comportement d'avant)

### Tests d'intégration

- [x] Flux SSE d'un run ⇒ l'événement `done` porte `diffs`
- [x] Run sans modification ⇒ `diffs` vide, `changedFiles` vide

### Isolation utilisateur

- [x] **Applicable** — chemin inchangé : `requireOwned(userId, workspaceId)` reste la première
  instruction du run ; la lecture de l'ancien contenu passe par `WorkspaceService.readFile(userId, …)`
  et l'écriture par `writeFile(userId, …)`, tous deux ré-appliquant l'isolation. Aucun chemin ne vient
  du client : il est résolu depuis la sortie du fournisseur et l'arborescence du workspace **possédé**.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. |
| Contexte tenant | **Non** | Aucun nouveau chemin de résolution du tenant : le `userId` déjà résolu est passé à chaque appel `WorkspaceService`, comme aujourd'hui. |
| Plans / limites | **Non** | Aucun montant décompté ne change. Le diff est calculé **après** le run, à partir de contenus déjà en mémoire ; il n'entraîne aucun appel fournisseur supplémentaire. Appels vérifiés : `assertWithinQuota`, `assertWithinSandboxLimit`, `recordUsage`, `recordSandboxSeconds` — tous inchangés. |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucun écran. |

---

## Notes

**Pourquoi une lecture supplémentaire du stockage.** Le remap de chemin construit déjà l'inventaire
(`tree`), mais pas les contenus. Lire l'ancien contenu d'un fichier **sur le point d'être écrit** coûte
un `getFile` par fichier réellement resynchronisé — borné de fait par le nombre de sorties d'un tour.
C'est le prix du seul instant où la comparaison est possible ; la seule alternative serait de conserver
une copie de l'ancien état, plus coûteuse et plus fragile.

**Pourquoi `max-diff-files`.** Le cadrage ne borne que le **par-fichier** (D3). Un tour qui réécrit
trois cents fichiers produirait pourtant un document de tour démesuré dans une colonne qui porte déjà
la transcription. Le plafond de fichiers est une **décision par défaut** prise ici (réversible : c'est
une valeur de configuration), tracée pour être contredite si l'usage montre qu'elle gêne.

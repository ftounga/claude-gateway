# Mini-spec — F-149 / SF-149-02 — Déléguer l'audit/lecture lourde de dépôt

## Identifiant

`F-149 / SF-149-02`

## Feature parente

`F-149` — Déléguer l'audit lourd, politique de modèle, réparer l'activation

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-149-02-doctrine-delegation-audit`

---

## Objectif

Apprendre à l'agent, par une **doctrine factuelle et stable** dans la description de l'outil `explore`,
qu'une **revue/audit de dépôt** doit être **déléguée** à `explore` (avec `read_file`/`grep`/`glob`)
plutôt que lue **fichier par fichier en `bash`** dans la boucle principale — pour que le volume de
lecture reste **hors** du contexte principal.

---

## Constat

`explore` (sous-boucle lecture-seule, F-39) n'a **pas** `bash` (`READ_ONLY_TOOLS` =
`read_file/list_files/search_files/grep/glob`). Quand l'agent audite un dépôt en `bash` (usage réel à
95 % bash), il ne **peut pas** déléguer : les lectures restent en boucle principale, réémises à chaque
aller-retour (tour réel : 19 fichiers × ~22 tours → 4,32 M tokens d'entrée). La voie sûre retenue est
**la doctrine** (prompt/description d'outil), pas l'ouverture d'un `bash` lecture-seule dans `explore`
(drapeau risque du cadrage, laissé hors périmètre).

---

## Comportement attendu

### Cas nominal

La description de l'outil `explore` (envoyée au modèle, partie du **préfixe caché**) porte une phrase
impérative supplémentaire : pour **lire/auditer/comprendre un dépôt** (revue de code ou d'infra),
**délègue à `explore`** avec `read_file`/`grep`/`glob` plutôt que de lire fichier par fichier en `bash`
dans la boucle principale ; les lectures restent chez l'agent délégué et ne gonflent pas ce contexte.

### Cas d'erreur / de bord

| Situation | Comportement attendu |
|-----------|----------------------|
| La doctrine de groupement (SF-39-22/SF-148-04) est déjà présente | Conservée, la nouvelle phrase s'ajoute sans la casser |
| L'outil `explore` n'est pas déclaré (`maxDelegations = 0`) | Rien à afficher — pas de doctrine (inchangé) |

---

## Critères d'acceptation

- [ ] La description de l'outil `explore` contient la doctrine de **délégation d'audit de dépôt**
      (mots-clés vérifiables : « auditer », « dépôt », « read_file/grep/glob », « fichier par fichier »,
      « bash »).
- [ ] La doctrine de **groupement** existante (INDÉPENDANTES / SYSTÉMATIQUEMENT / MÊME tour) reste
      présente (non-régression).
- [ ] La garantie de base reste dite (LECTURE SEULE ; ni écrire ni exécuter de commande).
- [ ] Le texte est **statique** (aucune donnée variable par tour) → **préfixe stable** (cache F-134
      préservé).

---

## Périmètre

### Hors scope (explicite)

- **Voie (b)** du cadrage : `bash` **lecture-seule** dans `explore` — laissée hors périmètre (drapeau
  risque : garantir « lecture seule » sur `bash` est difficile).
- Aucune modification du jeu d'outils de la sous-boucle (`READ_ONLY_TOOLS` inchangé).
- Aucune UI, aucune table, aucun endpoint.

---

## Contraintes de validation

Aucun champ. Modification d'une chaîne statique de description d'outil.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

Aucun.

### Fichier touché

- `AtelierChatService.buildToolsFull` — description de l'outil `explore` (~`:4218`).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest` — nouveau : la description de l'outil `explore` contient la
      doctrine de délégation d'audit de dépôt.
- [ ] `AtelierChatServiceSystemPromptTest` — conservé : `theExploreToolTeachesGroupingIndependentExplorations`
      (non-régression de la doctrine de groupement).

### Tests d'intégration

- [ ] N/A (prompt only) ; non-régression de la suite prompt.

### Isolation

- [ ] Non applicable — aucune donnée accédée (chaîne statique).

---

## Préoccupations transversales

Aucune : ni Auth/Principal, ni contexte tenant, ni plans/limites, ni navigation/routing. Modification
d'une chaîne de description d'outil.

---

## Dépendances

### Subfeatures bloquantes

`SF-149-01` — done (prérequis F-149).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Cache de prompt (F-134)** : la doctrine est un **littéral stable** ajouté au préfixe (description
  d'outil), identique à chaque tour → aucune volatilité, cache préservé.
- **F-119 intacte** : la doctrine dit *où* faire la lecture (déléguée), pas *s'il faut* lire — la
  discipline « lire avant d'agir » est inchangée. Factuel : décrit un mécanisme réel (`explore`
  possède `read_file`/`grep`/`glob`, la boucle principale a `bash`).
- **Voie retenue = (a) doctrine**, décision du cadrage ; (b) `bash` lecture-seule laissée en réserve.

# Mini-spec — F-148 / SF-148-04 — Renforcer le groupement des `explore` indépendants

## Identifiant

`F-148 / SF-148-04`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-04-grouper-explore-independants`

---

## Objectif

> En une phrase : durcir la **doctrine** (texte de la description de l'outil `explore`) pour que le
> modèle groupe **systématiquement** dans un même tour les explorations **indépendantes** — sans
> jamais grouper une exploration qui dépend d'une autre — afin de recouvrir leur temps de mur via le
> parallélisme déjà en place.

---

## Constat / existant (honnêteté sur l'existant)

- **Étend SF-39-22.** La doctrine de groupement existe déjà dans la description de l'outil `explore`
  (`AtelierChatService.java:4042-4045`) : « Quand tu as plusieurs questions INDÉPENDANTES… émets tous
  les appels explore dans le MÊME tour… Ne groupe PAS une exploration qui a besoin du résultat d'une
  autre ».
- Le **parallélisme d'exécution existe déjà** (`exploreConcurrently`, `AtelierChatService.java:1659`,
  F-39 / SF-39-21) : le moteur exécute en parallèle les `explore` d'un même tour. Le gain n'apparaît
  **que si** le modèle les **émet groupées**.
- Reliquat : la formulation actuelle est **permissive** (« Quand tu as… »). SF-148-04 la rend
  **impérative et systématique** pour les cas indépendants, tout en conservant la garde « ne pas
  grouper une dépendance ». **Prompt/doctrine uniquement — aucun changement de moteur.**

---

## Comportement attendu

### Cas nominal

- La description de l'outil `explore` déclarée au modèle contient une consigne **impérative** de
  grouper **systématiquement** les explorations indépendantes dans le **même tour** (parallélisme).
- La description conserve la **garde de dépendance** : une exploration qui a besoin du résultat d'une
  autre **n'est pas groupée** et s'enchaîne au **tour suivant**.
- Les garanties de base de l'outil restent dites : **LECTURE SEULE**, ni écriture ni exécution de
  commande.
- Le texte reste **factuel** (F-119) : il décrit un mécanisme réel (parallélisme SF-39-21), il ne
  promet rien de faux.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `maxDelegations = 0` (outil `explore` retiré) | Aucune régression : la doctrine ne s'affiche pas car l'outil n'est pas déclaré (comportement existant, inchangé) |
| Une exploration dépend d'une autre | La doctrine dit explicitement de NE PAS la grouper et de l'enchaîner au tour suivant |

---

## Critères d'acceptation

- [ ] La description de l'outil `explore` contient une consigne de groupement **systématique** des
      explorations **indépendantes** dans le **même tour** (marqueur `SYSTÉMATIQUEMENT` + `INDÉPENDANTES`
      + `MÊME tour` + `en parallèle`).
- [ ] La description conserve la **garde de dépendance** (marqueur d'exception + `tour suivant`).
- [ ] La description conserve les garanties `LECTURE SEULE` et « ni écrire, ni exécuter de commande ».
- [ ] Le test de catalogue/prompt (`AtelierChatServiceSystemPromptTest.theExploreToolTeachesGroupingIndependentExplorations`)
      vérifie le durcissement (nouveaux marqueurs) et reste vert.
- [ ] Aucune autre modification : pas de moteur, pas de config, pas d'infra.

---

## Périmètre

### Hors scope (explicite)

- Le **moteur** de parallélisme (`exploreConcurrently`, `exploreParallelism`) : inchangé.
- Le **nombre** d'explorations (`maxDelegations`) : traité par SF-148-01, hors sujet ici.
- Aucun changement d'infra, aucun composant cluster, aucune migration, aucune mise à jour runner.
- La **mesure** d'impact (temps de mur, explorations/tour) : opérationnelle, post-déploiement.

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Texte de doctrine | Constant (littéral compilé) → **préfixe/panoplie stable** entre messages : cache de prompt préservé |
| Factualité | La consigne décrit un mécanisme réel (parallélisme SF-39-21), aucune promesse fausse (F-119) |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `AtelierChatService.buildToolsFull` (description outil `explore`, ~`:4042-4045`) | Durcir le texte de doctrine |
| `AtelierChatServiceSystemPromptTest` | Étendre l'assertion existante au durcissement |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, aucune migration Liquibase.

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest.theExploreToolTeachesGroupingIndependentExplorations` — la
      description de `explore` contient les marqueurs durcis (`SYSTÉMATIQUEMENT`, `INDÉPENDANTES`,
      `MÊME tour`, `en parallèle`), la garde de dépendance (exception + `tour suivant`), et les
      garanties de base (`LECTURE SEULE`, « ni écrire, ni exécuter de commande »).

### Tests d'intégration

- Non applicable : changement de texte de doctrine dans une description d'outil, aucun endpoint ni
  comportement de moteur nouveau. Le test de catalogue/prompt ci-dessus couvre le contrat déclaré au
  modèle.

### Isolation workspace / tenant

- [x] Non applicable : doctrine globale de l'outil, aucun accès données. `user_id`/`host_id` non
  touchés.

---

## Préoccupations transversales

- **Cache de prompt** (déclencheur) : la doctrine vit dans la description de l'outil `explore`, un
  **littéral constant**. Composant impacté : `AtelierChatService.buildToolsFull`. La panoplie d'outils
  déclarée est identique d'un message à l'autre → **stabilité préservée**, aucun impact sur le gain
  F-134. Aucun autre composant (préfixe système `buildSystemPrompt`) n'est touché.
- **Isolation `user_id`+`host_id`** : inchangée (aucun accès données).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF-39-21/22 déjà livrées).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Provider-First / Gateway-First** : on n'implémente aucun moteur ; on **oriente** le modèle via une
  description d'outil plus impérative. Le parallélisme réel est déjà fourni par la boucle (SF-39-21).
- **Effet attendu** : recouvre le temps de mur des explorations indépendantes ; ne réduit pas le
  nombre de tours *logiques* (le cadrage F-148 le dit explicitement).

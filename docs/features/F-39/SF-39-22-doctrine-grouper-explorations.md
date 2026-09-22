# Mini-spec — F-39 / SF-39-22 Doctrine : grouper les explorations

## Identifiant

`F-39 / SF-39-22`

## Feature parente

`F-39` — L'Atelier comme harnais (sous-boucle d'exploration)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-39-22-doctrine-grouper-explorations`

---

## Objectif

> En une phrase : apprendre à l'agent, dans la **description de l'outil `explore`**, à **regrouper en
> un seul tour** les explorations **indépendantes** (elles s'exécutent alors en parallèle, SF-39-21)
> et à **ne PAS grouper** une exploration qui dépend du résultat d'une autre.

---

## Comportement attendu

### Cas nominal

Le moteur de SF-39-21 exécute en parallèle les `explore` d'un même tour, mais le gain n'apparaît que
lorsque le modèle **émet** effectivement plusieurs `explore` dans le même tour. Cette SF rend ce
comportement systématique par la **doctrine**, sans nouveau code moteur :

- La **description de l'outil `explore`** (ce que le modèle lit pour décider comment l'appeler) gagne
  deux phrases factuelles : grouper les explorations **indépendantes** dans un même tour (parallélisme) ;
  **ne pas** grouper quand l'une a besoin du résultat d'une autre (l'enchaîner au tour suivant).
- Le ton est **factuel et sobre** (F-119 : décrire l'établi, pas vendre une capacité).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une exploration dépend d'une autre | La doctrine dit **explicitement** de ne pas les grouper (sinon on sérialise à raison, et une exploration partirait sans son entrée). |
| L'outil `explore` est désactivé (`maxDelegations = 0`) | Aucune régression : l'outil n'est pas déclaré, la doctrine ne s'affiche pas (elle vit **dans** la description de l'outil). |

---

## Critères d'acceptation

- [ ] La description de l'outil `explore` contient la consigne de **grouper** les explorations
      indépendantes dans un même tour.
- [ ] Elle contient la consigne de **ne pas grouper** quand une exploration dépend d'une autre.
- [ ] Le reste de la description (lecture seule, ne peut ni écrire ni exécuter) est **inchangé**.
- [ ] Aucun autre outil, aucun endpoint, aucun schéma, aucune migration touché.
- [ ] Tests existants (`AtelierChatServiceSystemPromptTest`, explore) **verts**.

---

## Périmètre

### Hors scope (explicite)

- Tout changement du **moteur** (SF-39-21 fait déjà l'exécution parallèle).
- Le paramètre d'entrée de `explore` (toujours `question` + `path` facultatif) — on ne crée pas un
  outil « multi-questions ».
- Toute modification du protocole runner.

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierChatService` (déclaration de l'outil `explore`) | modif | Deux phrases ajoutées à la description de l'outil. |

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest` — la description de `explore` porte la doctrine de
      groupement (indépendantes → même tour) et l'exception (dépendantes → tours séparés).

### Tests d'intégration

- Sans objet (doctrine dans la description d'outil ; comportement couvert par le test de catalogue).

### Isolation workspace

- [x] Non applicable — raison : aucun accès aux données, seule la description d'un outil change.

---

## Préoccupations transversales

- Aucune (pas d'auth, pas de tenant, pas de plan/limite, pas de routing). Le seul texte modifié est la
  description d'un outil existant.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-21` — moteur d'explorations concurrentes — statut : done (le gain de la doctrine repose dessus).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D-39-22-1** : la doctrine vit dans la **description de l'outil** plutôt que dans le system prompt —
  c'est là que le modèle décide **comment** appeler `explore`, et cela reste testable via le catalogue
  d'outils sans dépendre du montage complet du prompt système. Le cadrage autorise « system prompt
  et/ou description » ; on choisit la description, plus ciblée et sans coût de cache supplémentaire sur
  le préfixe stable.

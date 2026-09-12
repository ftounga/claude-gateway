# Mini-spec — F-81 / SF-81-02 — Aucun DTO d'échange ne reste strict

## Identifiant

`F-81 / SF-81-02`

## Feature parente

`F-81` — Le contrat runner ↔ gateway ne peut plus dériver en silence

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-81-02-dto-tolerants`

---

## Objectif

Exiger, par un test qui lit les **classes compilées** des deux côtés, que tout **DTO d'échange**
porte `@JsonIgnoreProperties(ignoreUnknown = true)` — et poser l'annotation là où elle manque.

---

## Ce qu'est « un DTO d'échange » — la définition, et comment elle est tracée

> Ce point décide de la valeur de la subfeature. Trop large, le test devient du bruit qu'on
> désactivera ; trop étroit, il rate le prochain `StoredToken`.

**Définition retenue :**

> Un **DTO d'échange** est une classe qu'un côté **désérialise depuis une charge utile écrite par un
> processus qu'il ne redéploie pas en même temps que lui** — le runner installé sur la machine d'un
> client, ou un autre pod de la gateway pendant une bascule progressive.

C'est la <b>désérialisation</b> qui est visée, jamais l'écriture : `PairResponse` n'a pas besoin de
l'annotation, la gateway ne la relit jamais. C'est `StoredToken`, du côté qui **lit**, qui devait la
porter.

**L'inventaire n'est pas une liste tenue à la main.** Il est reconstruit à chaque exécution, par deux
sondes mécaniques sur les classes compilées :

| Sonde | Ce qu'elle trouve | Pourquoi elle ne vieillit pas |
|---|---|---|
| **Les corps de requête** — le type de tout paramètre `@RequestBody` d'un contrôleur du paquet `fr.claudegateway.runner..` | ce que la gateway lit du runner (appairage) et d'un autre pod (relais) | un nouvel endpoint est automatiquement couvert |
| **Les cibles de lecture** — lecture du bytecode (ASM) : toute classe passée à `ObjectMapper.readValue` / `treeToValue` / `convertValue` dans le module runner et dans `fr.claudegateway.runner..` | ce que le runner lit de la gateway | **c'est elle qui attrape le prochain `StoredToken`** : un nouveau type lu par Jackson est vu le jour où la ligne de lecture est écrite, qu'il porte ou non des annotations |

À quoi s'ajoute la **fermeture transitive** : les types maison déclarés comme composants ou champs
d'un DTO d'échange en sont un aussi — un champ inconnu au troisième niveau fait échouer la lecture
tout autant.

**Ce qui est délibérément hors de l'inventaire, et pourquoi.** Les DTO que le **navigateur** poste à
la gateway. Le risque F-81 est celui d'un émetteur *plus récent* que son récepteur ; pour le
navigateur, le sens dangereux serait la réponse JSON lue par TypeScript, qui **ignore nativement** ce
qu'il ne connaît pas. Un onglet resté ouvert, lui, envoie **moins** de champs, pas plus. Le risque
n'est pas symétrique, et étendre la règle à toute la surface HTTP du produit la rendrait assez
bruyante pour être désactivée. Les contrôleurs du paquet runner, eux, y sont **tous**, y compris ceux
que le navigateur appelle : la règle mécanique vaut mieux qu'une liste d'exceptions.

---

## Comportement attendu

### Cas nominal

Le test vit dans `contract-tests/` — le seul module qui voie les deux côtés. Il construit
l'inventaire, puis vérifie **deux choses de nature différente** :

1. **La forme** — chaque DTO d'échange porte `@JsonIgnoreProperties(ignoreUnknown = true)`.
2. **L'effet** — chaque DTO d'échange se relit **sans erreur** depuis une charge utile ne contenant
   qu'un champ inconnu, avec un mapper **délibérément strict**
   (`FAIL_ON_UNKNOWN_PROPERTIES` activé). C'est la vérification qui compte : elle prouve que la
   tolérance tient à la classe, et non à une propriété Spring que personne n'a écrite.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Un DTO d'échange ne porte pas l'annotation | Le test échoue en nommant la classe et en disant ce qu'elle casserait |
| Un DTO d'échange la porte avec `ignoreUnknown = false` | Le test échoue — la forme est là, l'effet n'y est pas |
| Un nouveau type est lu par `readValue` quelque part dans le runner | Il entre dans l'inventaire **tout seul**, et le test le réclame |
| Aucun DTO n'est trouvé (sondes cassées par un refactoring) | Le test échoue plutôt que de passer à vide |

---

## Critères d'acceptation

- [ ] L'inventaire est **reconstruit mécaniquement**, jamais écrit à la main, et le test échoue s'il
      est vide.
- [ ] Chaque DTO d'échange, des **deux côtés**, porte `@JsonIgnoreProperties(ignoreUnknown = true)`.
- [ ] L'annotation est **posée là où elle manquait** (code de production modifié en conséquence).
- [ ] Le test prouve l'**effet** et pas seulement la forme : lecture réussie sous mapper strict.
- [ ] **Vérification dans les deux sens** : l'annotation est réellement retirée d'une classe, le test
      est vu **rouge**, elle est remise, le test est vu **vert**. Trace portée dans la PR.
- [ ] Le comportement fonctionnel est inchangé : suites backend, runner et contrat vertes.

---

## Périmètre

### Hors scope (explicite)

- Les DTO postés par le **navigateur** hors du paquet runner (voir la justification ci-dessus).
- Rendre tolérants les **enums** et les valeurs (une valeur d'enum inconnue est un autre sujet, avec
  une autre réponse : `READ_UNKNOWN_ENUM_VALUES_AS_NULL`).
- Versionner l'API du runner, changer le contenu du contrat, forcer une mise à jour.

---

## Technique

### Endpoints

Aucun.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Fichiers touchés

- `contract-tests/src/test/java/fr/claudegateway/contract/ExchangeDtoToleranceTest.java` — nouveau.
- Les DTO d'échange auxquels l'annotation manque — à établir par la première exécution du test.

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] L'inventaire des DTO d'échange n'est pas vide et contient `PairRequest` et `StoredToken` — les
      deux extrémités du défaut du 2026-09-10.
- [ ] Chaque DTO d'échange porte l'annotation, avec `ignoreUnknown = true`.
- [ ] Chaque DTO d'échange se relit sous mapper strict depuis `{"champInconnu": …}`.

### Tests d'intégration

Le test lit les **classes compilées telles qu'elles partiront en production**, pas un contexte de
test — c'est la leçon explicite de SF-79-02, dont le premier garde-fou passait alors que le défaut
était là, parce qu'il interrogeait le scanner de Spring plutôt que les classes.

### Isolation utilisateur

- [x] Non applicable — aucune donnée, aucun accès base, aucun endpoint.

---

## Dépendances

### Subfeatures bloquantes

- `SF-81-01` — **done** (le module `contract-tests` est le seul endroit d'où les deux côtés sont
  visibles).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

Aucune (ni auth, ni tenant, ni plans, ni routing). L'ajout d'une annotation de **tolérance en
lecture** ne peut pas restreindre un comportement existant : elle n'autorise qu'à ignorer davantage.

---

## Notes et décisions

**D1 — Pourquoi l'annotation sur des classes que le mapper de Spring lit déjà sans broncher.**
Spring Boot désactive `FAIL_ON_UNKNOWN_PROPERTIES` par défaut : côté gateway, la tolérance existe
donc aujourd'hui — mais elle tient à une **configuration**, pas à la classe. Une propriété
`spring.jackson.deserialization.fail-on-unknown-properties` ajoutée un jour, ou un
`new ObjectMapper()` construit à la main (le backend en compte déjà plusieurs), la fait disparaître
sans qu'aucun test ne le dise. Portée par la classe, la tolérance voyage avec elle, quel que soit le
mapper qui la lit. C'est exactement ce que le test « effet » vérifie, en lisant **volontairement**
avec un mapper strict.

**D2 — Pourquoi lire le bytecode plutôt que le code source.**
Un balayage du texte source trouverait les `readValue` par expression régulière et se tromperait au
premier saut de ligne. Le bytecode, lui, dit exactement quelle classe est passée à quel appel.
`org.springframework.asm` est déjà sur le chemin de classes (spring-core) : aucune dépendance
nouvelle.

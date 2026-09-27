# Mini-spec — F-142 / SF-142-12 — Un type inconnu doit se voir

## Identifiant
`F-142 / SF-142-12` — feature parente `F-142`

## Objectif
Qu'un composant dont l'icône est introuvable apparaisse comme **une forme visible et nommée**, au
lieu de disparaître du schéma sans que l'image le montre.

## Le défaut, constaté en production le 2026-09-27
Appel réel au moteur `cloud` déployé, avec un nœud `aws.managedworkflowsforapacheairflow` :
**HTTP 200, image rendue, et le composant absent de l'image** — seule son étiquette flotte dans le
vide, sans forme ni icône.

La cause est à une ligne : le repli est `diagrams.generic.blank.Blank`, dont l'icône est **un PNG
vide**. Le commentaire du code annonce pourtant *« une boîte NEUTRE, et on le DIT »*. L'intention
était juste ; l'implémentation rend l'inverse.

**Ce n'est pas le signal qui manque.** `cloud.py` émet `UNKNOWN_TYPES=…`, `server.js` le passe en
en-tête `X-Cg-Unknown-Types`, et `DiagramToolExecutor` en fait un avertissement pour l'agent. Toute
la chaîne fonctionne. **C'est l'image qui ment**, et c'est elle qui part dans le livrable.

## Pourquoi c'est grave
Un livrable client peut partir avec des composants **manquants** et une image d'apparence normale.
Le lecteur ne voit pas un trou : il voit un schéma qu'il croit complet. C'est pire qu'un refus —
c'est la version dessinée du défaut que F-157 nomme « débranché en silence ».

## L'arbitrage, tranché ici
**Une forme visible, pas un refus.** Refuser ferait perdre un schéma juste à 95 % pour un
composant exotique, et le mode `strict` existe déjà pour qui veut le refus. La règle de F-142 reste
entière : **jamais une icône approchante** — un composant faux est pire qu'un composant générique.

## Comportement attendu
1. Un type non résolu est dessiné comme une **forme générique visible**, portant son étiquette.
2. Elle est **visuellement distincte** d'une vraie icône : on doit voir au premier coup d'œil que
   l'icône manque, sans lire le texte.
3. L'avertissement à l'agent (`UNKNOWN_TYPES`) est **conservé tel quel**.
4. `strict: true` continue de **refuser**, avec ses suggestions.
5. Aucune icône approchante n'est jamais substituée.

| Cas | Comportement |
|---|---|
| Type résolu | icône officielle, inchangé |
| Type inconnu, mode normal | **forme visible nommée** + avertissement |
| Type inconnu, `strict: true` | refus nommé avec suggestions, inchangé |
| Type inconnu, étiquette vide | forme visible portant le type demandé |

## Le SECOND défaut, trouvé en éprouvant le premier
`strict: true` ne refusait plus : **il plantait**. SF-142-09 avait laissé un **doublon mort** de
`suggestions()` — la seconde définition masquait la bonne et lisait un `CATALOG` supprimé. Vérifié
sur le type réel, avant correctif :

```
$ echo '{"strict":true,…,"type":"aws.managedworkflowsforapacheairflow"}' | python3 cloud.py
NameError: name 'CATALOG' is not defined      → code 3 (« échec technique »), pas un refus
```

Le critère nº 5 (« `strict: true` refuse toujours ») était donc **faux** avant cette subfeature. Le
doublon part ; la bonne version — jamais exécutée depuis SF-142-09 — reprend sa place, et un test
la tient désormais. C'est le même motif que le défaut principal : **le signal existait, il ne
sortait pas.**

## Critères d'acceptation
- [x] Un type inconnu produit une forme **non vide** — vérifié sur les pixels, pas sur l'absence d'erreur.
- [x] La forme porte l'étiquette du nœud.
- [x] Elle se distingue d'une icône officielle.
- [x] `UNKNOWN_TYPES` est toujours émis et remonte à l'agent.
- [x] `strict: true` refuse toujours.
- [x] Un schéma entièrement résolu est **identique** à avant (non-régression).

## Plan de test minimal
**Unitaires (renderer)** — un type inconnu : l'image produite contient des pixels non transparents
à l'emplacement du nœud · `UNKNOWN_TYPES` contient le type · `strict` lève toujours · un schéma
tout résolu n'émet pas de marqueur.
**Non-régression** — le rendu d'un schéma sans type inconnu est inchangé.

## Technique
| Élément | Changement |
|---|---|
| `cloud.py` | `FALLBACK = generic.blank.Blank` disparaît ; `unknown_component()` instancie un **`Node` sans icône** — graphviz le dessine lui-même : cadre **pointillé**, `#B03A2E` sur `#FDECEA`, étiquette centrée |
| `cloud.py` | `unknown_factory(kind)` : sans étiquette, la forme porte **le type demandé** |
| `cloud.py` | le **doublon mort** de `suggestions()` (lisant un `CATALOG` supprimé) est retiré |
| `diagram-renderer/tests/test_cloud.py` | **nouveau** — 11 tests, dont 4 qui comptent des **pixels** |

**Rien d'autre ne bouge** : `server.js`, `DiagramRenderer`, `HttpDiagramRenderer`,
`DiagramToolExecutor` et le protocole `UNKNOWN_TYPES` / `X-Cg-Unknown-Types` sont **inchangés** —
la chaîne du signal fonctionnait, c'est l'image qui mentait.

**Aucune migration, aucun endpoint, aucun changement de protocole, aucun composant nouveau,
aucun fichier Java ni Angular touché.**

## Vérifié pour de vrai, pas seulement en test
Rendu du schéma du défaut (`aws.managedworkflowsforapacheairflow` → `aws.s3`). **Avant** :
l'étiquette « Airflow » flotte seule, la flèche pointe sur rien. **Après** : un cadre pointillé
nommé « Airflow », la flèche y aboutit, et un nœud sans étiquette porte `aws.inconnu` ;
`UNKNOWN_TYPES` liste les deux. `check_catalog.py` (contrôle joué au build) : **1342 icônes
atteignables**, vert.

## Préoccupations transversales
**Aucune.** Aucun accès aux données : le programme lit une description sur son entrée standard et
écrit un PNG — aucun filtre `user_id` n'est en jeu. L'isolation du dépôt de l'image reste celle de
`ProjectFileDeposit` (`userId` + `Workspace` du tour), **non touchée**. Ni auth/Principal, ni
contexte tenant, ni plan/limite, ni navigation.

## Hors périmètre
Enrichir le catalogue de types · le choix du moteur (**SF-142-11**) · draw.io (**SF-142-13**).

**Laissé tel quel, volontairement** : la *qualité* des suggestions de `strict`. Restaurée telle
qu'écrite en SF-142-09 (le comportement attendu dit « inchangé »), elle reste faible sur un nom très
long (`aws.managedworkflowsforapacheairflow` → « aws.sf ») ou très court (liste alphabétique). Le
message **existe** et **ne plante plus** ; l'affiner est une autre subfeature, pas un correctif
glissé dans celle-ci.

## Déploiement
Le correctif vit dans l'image **`diagram-renderer`** : il exige une **reconstruction et un
redéploiement de cette image** pour être actif en production. **NON déployé** ici.

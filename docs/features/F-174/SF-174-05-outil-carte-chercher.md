# Mini-spec — [F-174 / SF-174-05] L'outil `carte_chercher`

## Identifiant

`F-174 / SF-174-05`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D8 validée le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-05-carte-chercher`

---

## Objectif

Donner à l'agent de la Forge un outil serveur `carte_chercher` qui interroge la carte du poste sur l'index, sans aller-retour vers la machine, et l'inviter à s'en servir avant de fouiller la carte en `bash`.

---

## Comportement attendu

### Cas nominal

1. **Déclaration** : l'outil est ajouté à la panoplie d'un projet rattaché à un **poste réel**, index allumé (`APP_MAP_INDEX_ENABLED`). Condition stable d'un tour à l'autre (ni contenu de carte, ni heure) ; définition littérale fixe → préfixe de cache stable. Offert aussi en mode Réponse/Plan (lecture seule).
2. **Doctrine** : la description de l'outil dit de l'utiliser **avant** de fouiller les fichiers de carte en `bash`/`grep`/`read_file`, et rappelle que la carte est un pointeur (revérifier un fait critique). `read_file` et `bash` restent permis : rien n'est retiré. Le bloc de faits du tour, s'il est tronqué, renvoie aussi vers `carte_chercher`.
3. **Entrées** (toutes facultatives, au moins une) : `requete` (libre), `type` (compte_aws, cluster, depot, forge…), `identifiant` (exact).
4. **Exécution côté gateway** (`HostMapSearchTool`) : faits portant l'identifiant exact, recherche hybride SF-174-03/04 sur la requête, ressources du type demandé ; rend **Ressources** (type, identifiants, état, attributs, `[fichier § section]`), **Liens**, **Faits** sourcés avec marques piège / échéance ; 20 faits, 15 ressources, 15 liens, 8 000 caractères au plus ; pied « la carte est un pointeur ».
5. **Index pas encore construit** pour ce poste : recherche lexicale F-137 dans `host_map_files`.
6. **Mesure** : chaque appel écrit une ligne `host_map_lookups` `kind = TOOL` (`HYBRID` ou `LEXICAL`), qui ne compte que les faits rendus.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun argument | Erreur d'outil « donne une requête, un type ou un identifiant », carte non interrogée |
| Rien trouvé | Message « la carte ne répond rien (peut être incomplète) : vérifie sur le poste » |
| Erreur interne | Erreur d'outil qui renvoie vers `read_file`, jamais d'échec du tour |
| Projet « Hébergé » / index éteint | Outil non déclaré |

---

## Critères d'acceptation

- [x] Outil déclaré sur un poste réel index allumé, absent sinon.
- [x] Un appel de l'agent obtient la réponse de l'index dans son résultat d'outil.
- [x] Appel vide refusé sans lecture.
- [x] Journal `TOOL` écrit, faits seuls comptés ; repli lexical tant que l'index n'existe pas.
- [x] Isolation : la carte interrogée est celle du poste du projet, `(user_id, host_id)`.

---

## Périmètre

### Hors scope (explicite)

- Écran (F-173). Écriture de la carte (D1).

---

## Technique

### Composants backend

- `atelier/HostKnowledgeSource` : méthodes par défaut `mapSearchAvailable`, `searchMap` (aucun impact sur `NONE` ni sur les mocks existants).
- `atelier/AtelierChatService` : `MAP_SEARCH_TOOL` (littéral stable), déclaration conditionnelle, aiguillage serveur avant le Radar, étape d'écran « search », liste blanche Réponse/Plan.
- `governance/map/HostMapKnowledgeProvider` : implémentation (poste réel + index allumé ; journal TOOL ; repli lexical) ; `HostMapSearchTool` injecté par mutateur.
- `governance/map/index/HostMapSearchTool` ; `HostMapFactsBlock` (renvoi vers l'outil).

### Tables / endpoints / écrans

Aucun.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | poste résolu par `GovernanceHostScope.hostOf(userId, workspaceId)` (déjà isolé, `requireOwned`) ; `HostMapSearchTool` et ses requêtes `(user_id, host_id)` |
| Auth, plans, routing | Non | outil serveur sans coût fournisseur (hors embedding de la requête si la clé existe, comme `recall`) |

---

## Plan de test

- [x] `HostMapSearchToolTest` (ressources, liens, faits marqués ; par type ; par identifiant ; sans index).
- [x] `HostMapKnowledgeProviderToolTest` (disponibilité ; réponse + journal TOOL/HYBRID ; repli LEXICAL).
- [x] `AtelierChatServiceMapSearchToolTest` (déclaré et servi ; non déclaré ; appel vide refusé).
- [x] Suite complète verte.

## Dépendances

SF-174-03, SF-174-04 mergées.

## Notes et décisions

- **Arbitrage (réversible)** : la doctrine vit dans la **description** de l'outil (stable, déclarée seulement là où l'outil existe) plutôt que dans le sommaire de carte du bloc système, qui est aussi servi quand l'index est éteint.
- **Arbitrage (réversible)** : outil non offert aux sous-boucles `explore` (leur panoplie reste fichiers seuls) ; l'agent principal l'a.

# Mini-spec — F-39 / SF-39-20 — L'agent qui explore retrouve de quoi chercher

## Identifiant

`F-39 / SF-39-20`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`in-review`

## Date de création

2026-09-08

## Branche Git

`feat/SF-39-20-explorer-en-cible-runner`

---

## Objectif

> Rendre à la sous-boucle d'exploration, **en cible `RUNNER`**, les deux outils sans lesquels elle ne
> peut rien trouver : lister les fichiers et chercher dedans.

---

## Déclencheur

Deux décisions justes, prises séparément, qui se sont annulées l'une l'autre.

**SF-39-05 (D4 du cadrage, `bash`-first)** retire `list_files` et `search_files` de la panoplie
déclarée en cible `RUNNER` : `ls`, `find` et `grep -n` font strictement mieux, et deux définitions de
moins, ce sont deux définitions qu'on ne paie plus dans le préfixe caché à chaque itération.

**SF-39-14 (D2)** interdit `bash` à la sous-boucle d'exploration : une commande venue d'un agent dont
l'utilisateur ignore l'existence n'a rien à faire devant la porte de confirmation (SF-38-08).

Prises ensemble, sur la cible qui porte **95 % de l'usage réel**, elles laissent l'exploration avec
un seul outil. Le calcul est mécanique :

```java
List<AgentTool> readTools = buildTools(workspace).stream()          // RUNNER : read_file, write_file,
        .filter(tool -> READ_ONLY_TOOLS.contains(tool.name()))      // edit_file, bash, explore, set_plan
        .toList();                                                  // ⇒ [read_file]
```

`READ_ONLY_TOOLS` nomme bien `list_files` et `search_files`, mais on les cherche dans une liste qui ne
les contient plus. La sous-boucle peut donc lire un fichier **dont elle connaît déjà le chemin exact**,
et rien d'autre : ni lister, ni chercher, ni exécuter. Sa consigne système lui promet pourtant
*« liste les fichiers, lis-les, cherche dedans »* — trois capacités annoncées, une seule tenue.

Ce que l'agent principal en retire : une délégation qui consomme du budget (elle appartient au tour,
D4 de SF-39-14) et rend « je n'ai rien trouvé ». C'est pire qu'une capacité absente — c'est une
capacité qui échoue en silence, et le tour continue sur sa réponse vide.

Le défaut ne se voyait pas parce que le seul test qui exerce la panoplie de la délégation
(`theExplorationOnlyEverGetsReadTools`) vérifie **ce que la sous-boucle n'a pas** (`bash`) et jamais
ce qu'elle a. Une assertion négative laisse passer l'ensemble vide.

---

## L'option retenue, et les deux écartées

| Option | Pourquoi non / oui |
|---|---|
| Redéclarer `list_files` / `search_files` dans la panoplie principale en `RUNNER` | Défait D4 pour tout le monde : on rallonge le préfixe caché de chaque itération de la boucle principale — qui, elle, a `bash` — pour servir une sous-boucle qui tourne quelques fois par jour |
| Donner à l'exploration un `bash` restreint aux lectures | Rouvre D2 de SF-39-14. Une liste blanche de commandes « en lecture » est une frontière poreuse (`find -exec`, `awk 'system()'`, redirections) et la porte de confirmation resterait à trancher. Sécurité : non |
| **Une panoplie propre à l'exploration** | **Retenue** : la sous-boucle a son propre prompt, sa propre conversation et aucun enjeu de cache — ses outils n'ont aucune raison d'être un sous-ensemble de ceux du travail principal |

D4 vaut pour la boucle qui a `bash` ; D2 vaut pour la boucle qui ne l'a pas. Les deux tiennent, à
condition de cesser de dériver l'outillage de l'une depuis celui de l'autre.

---

## Comportement attendu

### Cas nominal

1. L'agent principal appelle `explore` sur un projet en cible `RUNNER`.
2. La sous-boucle démarre avec **exactement trois outils** : `list_files`, `read_file`,
   `search_files` — les mêmes des deux côtés, quelle que soit la cible.
3. Elle liste, cherche, lit ; chaque appel est **relayé vers la machine** par le chemin existant
   (`RunnerToolGateway`), avec le confinement à la racine et les exclusions `.runnerignore` du
   runner (F-38 / SF-38-04).
4. Chaque appel est **journalisé** comme les autres (SF-38-08) : le journal d'audit ne distingue pas
   ce qui vient de la sous-boucle de ce qui vient du travail principal — ce qui touche la machine se
   trace, sans exception.
5. Seule sa **réponse** revient à l'agent principal, ses bornes de SF-39-14 inchangées.

### Ce qui ne change pas

- La panoplie déclarée à la **boucle principale** : `RUNNER` garde `read_file` / `write_file` /
  `edit_file` / `bash`, `SANDBOX` garde la sienne. D4 est intact.
- L'interdiction de `bash`, `write_file`, `edit_file`, `set_plan` et `explore` à la sous-boucle.
- Les bornes : 3 délégations par tour, 10 itérations, 4 000 caractères, budget de temps et
  consommation du tour.
- La porte de confirmation : elle ne concerne que `bash`, qu'aucune exploration n'appelle.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| La sous-boucle appelle un outil hors de sa panoplie (`bash`, `write_file`, `explore`…) | Résultat d'outil en erreur « Outil indisponible en exploration : *nom* » ; la sous-boucle continue | 200 |
| Le runner est déconnecté pendant l'exploration | Erreur relayée comme résultat d'outil ; l'exploration rend ce qu'elle a, le tour principal n'échoue pas | 200 |
| `search_files` sans `query` | Erreur d'outil relayée telle quelle (`invalid_input` du runner) ; l'exploration continue | 200 |
| Cible `SANDBOX` avec l'exécution fermée (SF-39-16) | Comportement inchangé : l'appel est refusé comme pour la boucle principale | 200 |
| Recherche sans résultat | « Aucun résultat. » — une réponse, pas une erreur | 200 |

---

## Critères d'acceptation

- [ ] En cible `RUNNER`, la sous-boucle d'exploration reçoit **`list_files`, `read_file`,
      `search_files`** — vérifié par une assertion **positive** sur les outils réellement envoyés au
      fournisseur.
- [ ] En cible `SANDBOX`, elle reçoit la même panoplie de trois outils (comportement inchangé).
- [ ] La sous-boucle ne reçoit **jamais** `bash`, `write_file`, `edit_file`, `set_plan` ni `explore`.
- [ ] Un `list_files` demandé par la sous-boucle en cible `RUNNER` est **relayé à la machine** et sa
      sortie revient à la sous-boucle.
- [ ] Un `search_files` demandé par la sous-boucle en cible `RUNNER` est relayé de même.
- [ ] Un appel de la sous-boucle qui touche la machine est **journalisé** au journal d'audit.
- [ ] La panoplie de la **boucle principale** est inchangée sur les deux cibles (non-régression D4).
- [ ] Les bornes de SF-39-14 sont inchangées (3 délégations, 10 itérations, 4 000 caractères).
- [ ] Isolation `user_id` inchangée : la sous-boucle travaille sur un workspace **déjà possédé**,
      vérifié en amont du tour.
- [ ] Zéro régression : un tour sans `explore` est inchangé.

---

## Périmètre

### Hors scope

- Toute réouverture de D4 : la boucle principale reste `bash`-first en cible `RUNNER`.
- Toute réouverture de D2 : pas de `bash`, même restreint, dans l'exploration.
- Le parallélisme des délégations (SF-39-14, D1) et la délégation récursive.
- Le chemin **Managed Agents** : le fournisseur tient sa propre boucle, il n'est pas concerné.
- Toute évolution du protocole runner : `list_files` et `search_files` y existent depuis SF-38-04 et
  ne bougent pas. **Aucun runner installé n'a à être mis à jour.**

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Normalisation |
|-------|-------------|-------------|---------------|
| `question` (outil `explore`) | Oui | 2 000 | `trim()` ; vide ⇒ erreur d'outil — **inchangé** |
| `path` (outil `explore`) | Non | 1 000 | `trim()` ; portée indicative — **inchangé** |
| `query` (`search_files` en sous-boucle) | Oui | — | Validée par le runner (`invalid_input` si vide) — **inchangé** |

Aucune contrainte nouvelle : cette subfeature ne crée ni champ, ni entrée utilisateur.

---

## Technique

### Endpoint(s)

Aucun. Aucun contrat HTTP ni trame runner n'est touché.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable** — aucun changement de schéma.

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierChatService` | `explorationTools()` : la panoplie de la sous-boucle est **construite**, plus dérivée par filtrage de celle du travail principal |

### Frontend

Aucun composant. La sous-boucle écoute `AtelierProgressListener.NOOP` : elle n'émet aucune étape,
aucune sortie, aucune demande d'autorisation. Rien de visible ne change à l'écran — c'est le
contrat de la délégation depuis SF-39-14 (seule sa réponse remonte).

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun accès aux données n'est modifié : `explore` reçoit le `userId` du tour et le workspace déjà validé par `requireOwned` en entrée de `chat()` |
| Plans / limites | Non | Les bornes de SF-39-14 et le plafond de SF-39-15 sont inchangés ; la dépense de la sous-boucle appartient toujours au tour |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires (backend)

- [ ] En cible `RUNNER`, la sous-boucle reçoit exactement `list_files`, `read_file`, `search_files`
      (assertion **positive** sur les outils de la requête envoyée au fournisseur).
- [ ] En cible `SANDBOX`, la sous-boucle reçoit la même panoplie.
- [ ] La sous-boucle ne reçoit ni `bash`, ni `write_file`, ni `edit_file`, ni `set_plan`, ni
      `explore` (assertion négative conservée).
- [ ] La panoplie de la boucle principale est inchangée sur les deux cibles.
- [ ] Un `list_files` de la sous-boucle en cible `RUNNER` appelle `RunnerToolGateway.listFiles` et sa
      sortie revient dans la conversation de la sous-boucle.
- [ ] Un `search_files` de la sous-boucle en cible `RUNNER` appelle `RunnerToolGateway.searchFiles`.
- [ ] Un appel de la sous-boucle est enregistré au journal d'audit.
- [ ] Un outil hors panoplie appelé par la sous-boucle rend une erreur d'outil, sans arrêter le tour.

### Tests d'intégration

Aucun nouveau : aucun endpoint, aucune trame, aucun schéma ne change. Les tests d'intégration
existants de l'Atelier valent comme non-régression.

### Isolation workspace

- [x] Couverte par l'existant — la sous-boucle n'ouvre aucun chemin d'accès nouveau : elle emprunte
      `executeTool`, avec le `userId` du tour et un workspace déjà possédé.

---

## Notes et décisions

**D1 — La panoplie de l'exploration est construite, jamais filtrée.** C'est la cause du défaut, pas
son symptôme : dériver l'outillage d'une sous-boucle par intersection avec celui d'une autre rend son
contenu **dépendant d'une décision étrangère**. Le jour où D4 a retiré deux outils du travail
principal, la sous-boucle a perdu les deux tiers de sa panoplie sans qu'une ligne la concernant soit
touchée. Une méthode `explorationTools()` énonce ce qu'elle a ; toute évolution future de la boucle
principale la laisse intacte.

**D2 — Les mêmes trois outils sur les deux cibles.** L'alternative — une panoplie qui varie selon la
cible — n'achèterait rien : la sous-boucle n'a pas de `bash` de toute façon, donc pas d'argument
`bash`-first, et son prompt est reconstruit à chaque délégation sans enjeu de cache. Un seul jeu
d'outils, c'est un seul comportement à tester.

**D3 — Aucun assouplissement de D2 de SF-39-14.** L'interdiction de `bash` en exploration n'est pas
un effet de bord qu'on corrige ici : c'est la décision qui protège l'utilisateur d'autoriser des
commandes venues d'un agent dont il ignore l'existence. Ce que cette subfeature corrige, c'est
qu'elle avait été prise en supposant `list_files` et `search_files` disponibles.

**D4 — Les appels de la sous-boucle restent journalisés.** On aurait pu les taire, au motif qu'ils ne
viennent pas d'une intention explicite de l'utilisateur. Ce serait l'inverse du besoin : ce sont
précisément les appels qu'il ne voit pas passer à l'écran, donc ceux dont la trace vaut le plus.

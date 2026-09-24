# Mini-spec — F-157 / SF-157-01 — Les témoins de branchement

## Identifiant
`F-157 / SF-157-01` — feature parente `F-157`

## Objectif
Pouvoir distinguer **« branchée mais jamais déclenchée »** de **« débranchée par une modification du
code »** — deux situations que F-156 confond aujourd'hui sous le mot « dormante ».

## Le défaut
Une capacité peut être **présente** (ses fichiers existent, la garde de SF-156-01 le vérifie) et
pourtant **plus appelée** : un remaniement a retiré l'appel, la garde, la branche. Rien ne casse,
aucun test ne tombe, et le diagnostic dit « dormante » — ce qui envoie chercher un réglage là où il
manque une ligne de code.

**Ce n'est pas de l'analyse de code.** C'est la vérification d'un **fait déclaré** : « cet appel doit
se trouver ici ». Aucun modèle, aucun jeton.

## Comportement attendu
1. Chaque capacité de la carte peut déclarer un ou plusieurs **témoins** : un **chemin** et un
   **fragment littéral** qui doit s'y trouver.
2. Un témoin porte aussi **ce qu'il prouve**, en une phrase — sans quoi un fragment de code isolé
   serait illisible dans un rapport.
3. Les témoins sont **facultatifs** : une capacité sans témoin reste diagnosticable comme avant.
4. **Une garde de build** vérifie que **chaque témoin déclaré est réellement présent** aujourd'hui.
   Un témoin faux ferait conclure « débranchée » sur une capacité qui marche — pire que pas de
   témoin du tout.
5. Le chemin d'un témoin doit être **l'un des chemins déclarés** par sa capacité : un témoin qui
   pointe ailleurs que là où la capacité vit n'a pas de sens, et échapperait à la garde des chemins.

| Cas d'erreur | Comportement |
|---|---|
| Fragment absent du fichier | **échec du build** — la carte mentirait |
| Chemin hors des chemins de la capacité | **échec du build** |
| Fragment vide ou explication vide | **échec du build** |

## Critères d'acceptation
- [ ] `ProductCapability` porte une liste de **témoins**, vide par défaut.
- [ ] Un témoin porte **chemin**, **fragment**, **ce qu'il prouve**.
- [ ] Les capacités dont le branchement est vérifiable en déclarent au moins un.
- [ ] **Chaque fragment déclaré existe vraiment** dans son fichier — vérifié par un test, et la
      garde est **falsifiée puis restaurée** pour prouver qu'elle mord.
- [ ] Le chemin d'un témoin appartient aux chemins de sa capacité — vérifié.
- [ ] Les gardes existantes de SF-156-01 restent vertes.

## Hors scope
La **lecture à l'exécution** (**SF-157-02**) · le **verdict débranchée** (**SF-157-03**) · la
**lecture raisonnée** (**SF-157-04**) · l'**écran** (**SF-157-05**).

## Technique
| Élément | Changement |
|---|---|
| `ProductCapability.Wiring` (record) | chemin, fragment, ce qu'il prouve |
| `ProductCapability` | un champ `wirings` |
| `CapabilityMap` | les témoins des capacités vérifiables |
| `CapabilityMapTest` | la **garde** : fragment présent, chemin cohérent, champs non vides |

**Aucune migration, aucune route, aucun appel fournisseur.**

## Plan de test
- [ ] Chaque fragment est **trouvé** dans son fichier (lecture du dépôt depuis le test).
- [ ] Chaque chemin de témoin est l'un des chemins de sa capacité.
- [ ] Fragment / explication vides → échec.
- [ ] **Falsification** : un fragment inventé fait échouer la garde, avec un message qui dit pourquoi.
- [ ] Les tests de SF-156-01 restent verts (rien n'est cassé par le champ ajouté).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucune identité — la carte décrit le produit |
| Contexte tenant | non | **aucune donnée utilisateur** |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |

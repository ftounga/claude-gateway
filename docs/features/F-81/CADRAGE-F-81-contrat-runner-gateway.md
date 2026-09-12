# F-81 — Le contrat entre le runner et la gateway ne peut plus dériver en silence

> Cadrage du 2026-09-12, écrit après une panne d'appairage de **deux jours** que personne n'avait vue.

## 1. Ce qui s'est passé

Le 2026-09-10, SF-48-01 renomme un champ de la réponse d'appairage : `workspaceId` → `hostId`.
Le changement est juste — le poste a remplacé le projet comme unité. Il est fait **d'un seul côté**.

Le runner, lui, continue d'attendre `workspaceId`. Son `ObjectMapper` est en configuration par
défaut, donc **stricte**, et `StoredToken` ne porte pas `@JsonIgnoreProperties`. Le `hostId` reçu est
un champ **inconnu** : la lecture échoue.

**Aucun appairage de machine neuve n'a fonctionné entre le 2026-09-10 et le 2026-09-12.**

Le défaut a été trouvé par le PO, chez un client, en installant un runner — et il a d'abord été
attribué au proxy Zscaler du poste, ce qui a coûté une heure de diagnostic sur la mauvaise piste.

## 2. Pourquoi rien ne l'a vu

Trois raisons se sont additionnées, et chacune suffit à expliquer le silence.

**Le chemin n'était pas testé du tout.** Il n'existait aucun test sur `PairingClient` ni sur la
lecture de `StoredToken`. Le dossier de tests du runner n'en contenait pas une ligne.

**Les deux modules compilent séparément.** `backend/` et `runner/` sont deux projets Maven distincts.
Rien ne les fait se rencontrer : renommer un champ dans l'un ne casse **rien** dans l'autre, ni à la
compilation, ni aux tests, ni à la construction de l'image.

**Chaque côté avait raison.** Le `PairResponse` du backend est juste. Le `StoredToken` du runner
était juste **pour la gateway de la veille**. Aucune revue de l'un ne pouvait révéler le défaut, car
il ne vit dans aucun des deux fichiers : il vit **entre** les deux.

**Et la panne ne se voyait pas non plus côté serveur** : la gateway **réussissait**. Elle créait le
jeton, et `recordDeclaration` — appelé pendant l'appel HTTP — écrivait la racine et le système sur le
poste. L'écran montrait donc une machine « connue » dont le canal ne s'ouvrirait jamais, pendant que
le terminal affichait « Réponse d'appairage illisible ». Deux affichages contradictoires, **aucun
faux**.

SF-48-04 a corrigé le champ et ajouté quatre tests. **Mais ces tests jouent une chaîne JSON écrite à
la main** : si les deux côtés dérivent à nouveau, ils continueront de passer. Le trou est bouché là
où il s'est manifesté, pas là où il se forme.

## 3. Ce que F-81 livre

### SF-81-01 — Un test qui fait se rencontrer les deux côtés

Un test qui, **dans une même exécution**, sérialise le DTO du **backend** et le fait lire par le DTO
du **runner** — et l'inverse pour ce que le runner envoie.

Ce n'est pas une chaîne recopiée : c'est l'objet réel, produit par le vrai `ObjectMapper` de
l'émetteur, lu par le vrai mapper du récepteur. Un champ renommé d'un seul côté fait **échouer les
tests**, à la minute où il est renommé.

**Le point à trancher — où vit ce test ?** Les deux modules ne se voient pas. Trois voies :

| Voie | Ce qu'elle coûte |
|---|---|
| Le **backend** dépend du runner en portée `test` | Une dépendance Maven de plus ; le runner doit être installé localement avant le backend |
| Un **troisième module** `contract-tests` qui dépend des deux | Un module de plus dans la réaction en chaîne du build ; le plus propre |
| Un test **par instantané** : chaque côté écrit son JSON dans un fichier versionné, comparé | Aucune dépendance ; mais compare des **formes**, pas des lectures réelles |

*Recommandation* : le **troisième module**. Il porte le contrat, il ne sert qu'à ça, et il dit dans
le dépôt que ce contrat existe — ce que ni `backend/` ni `runner/` ne disent aujourd'hui.
**À CONFIRMER PAR LE PO.**

### SF-81-02 — Aucun DTO d'échange ne reste strict

`StoredToken` n'était pas le seul à pouvoir exploser sur un champ inconnu : c'était **le seul du
runner à ne pas porter** `@JsonIgnoreProperties(ignoreUnknown = true)`, mais rien ne l'imposait.

Un test lit les classes compilées et exige que **tout DTO d'échange** le porte, des deux côtés.

**La raison n'est pas cosmétique** : un runner installé chez un client vit **plus longtemps** que la
gateway qu'il a connue. Un champ ajouté demain ne doit pas paralyser les postes déjà déployés.
C'est le même raisonnement que le garde-fou des constructeurs Spring livré en SF-79-02 — lire ce qui
partira en production, et non ce qui tourne en test.

### SF-81-03 — Le journal dit quand un runner parle une langue plus ancienne

La gateway sait quelle version de runner lui parle (la trame d'ouverture la porte). Aujourd'hui elle
n'en fait rien. Une ligne de journal quand un runner se déclare avec une version antérieure à un
seuil, et la version rendue dans la vue d'ensemble du poste, suffisent à ce que la question
« est-ce que son runner est à jour ? » ait une réponse — au lieu d'être devinée.

**Hors périmètre** : forcer la mise à jour, ou refuser un runner ancien. Un poste qui travaille ne
doit pas s'arrêter parce qu'une version a bougé.

## 4. Hors périmètre

- Versionner l'API du runner (`/v1/`, négociation de version). C'est la réponse lourde ; la
  tolérance aux champs inconnus couvre le cas réel à un centième du coût.
- Changer quoi que ce soit au **contenu** du contrat : F-81 le **gèle et le surveille**, elle ne le
  redessine pas.
- La mise à jour automatique du runner sur le poste d'un client.

## 5. Impact transversal

| Préoccupation | Composants |
|---|---|
| Aucune (ni auth, ni tenant, ni plans, ni routing) | — |
| Construction | ajout possible d'un module Maven — la réaction en chaîne et l'image backend doivent suivre |
| Backend | `PairResponse` et les DTO de trame, **inchangés** — seulement lus par le test |
| Runner | `StoredToken` et les DTO de trame, **inchangés** |

## 6. Plan de test minimal

- Le DTO du backend, sérialisé par son mapper, est lu par le DTO du runner : **tous les champs
  arrivent**.
- Un champ **renommé** d'un seul côté fait échouer le test — vérifié en le renommant réellement,
  comme SF-79-02 a vérifié son garde-fou dans les deux sens. **Sans cette vérification, le test ne
  prouve rien.**
- Un champ **ajouté** d'un seul côté ne fait **pas** échouer : c'est la tolérance qu'on veut.
- Tout DTO d'échange porte `@JsonIgnoreProperties(ignoreUnknown = true)`.

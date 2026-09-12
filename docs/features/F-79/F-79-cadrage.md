# Cadrage — F-79 — Supprimer un projet, quel que soit le nombre de fichiers

**Date** : 2026-09-12
**Source de vérité** : `docs/PRODUCT_SPEC.md`, ligne F-79. Les faits y sont **observés en
production**, pas supposés. Ils ne sont ni rediscutés ni « améliorés » ici.

---

## Ce qui s'est passé (recopié de PRODUCT_SPEC, non discuté)

En supprimant les projets d'essai du PO le 2026-09-12, `DELETE /workspaces/{id}` a rendu **500**
sur un projet, et un seul : celui qui contenait **7 288 objets** (la sortie d'un script
d'inventaire AWS, 39 Mo). `S3WorkspaceStorage.deletePrefix` liste les clés du projet puis les
envoie **toutes dans un seul appel** `DeleteObjects`. S3 **plafonne cet appel à 1 000 clés** :
au-delà, il refuse la requête **entière**, et le message qu'il rend — *« The XML you provided was
not well-formed »* — ne dit rien du plafond.

Le projet était donc **indéracinable par l'application**. Le préfixe a été purgé à la main pour
débloquer la suppression ; **le code est inchangé**, la panne est donc toujours là.

Ce qui est en jeu dépasse le confort : un projet qu'on ne peut pas supprimer est un projet dont on
ne peut pas retirer les fichiers — et depuis F-73, ce sont des fichiers qui ont pu venir de la
machine d'un client.

---

## Ce qui existe déjà (constaté dans le code — aucun `kubectl`, aucun appel AWS)

| Fait | Où | Conséquence |
|---|---|---|
| `deletePrefix` envoie **toutes** les clés dans **un seul** `DeleteObjects` | `S3WorkspaceStorage` l.82-93 | C'est la panne. Au-delà de 1 000 clés, **rien** n'est effacé. |
| La réponse de `DeleteObjects` porte une liste d'erreurs **par clé** (`response.errors()`) qui **ne lève aucune exception** | idem l.90 | Aujourd'hui **ignorée en silence** : une clé refusée (permission, verrou) laissait `delete()` continuer et le projet disparaître de la base en laissant ses fichiers. |
| `listKeys` **pagine déjà** : `continuationToken` bouclé tant que `isTruncated` | `S3WorkspaceStorage` l.61-75 | **Rien à corriger ici** — la seconde moitié du soupçon de F-79 est levée par lecture du code. Le défaut était bien du seul côté de la suppression. Un test de non-régression fige cette pagination pour qu'elle ne se perde pas. |
| `deletePrefix` n'est appelé **qu'une fois**, par `WorkspaceService.delete` | `WorkspaceService` l.479 | Un seul chemin à corriger. |
| `listKeys` est appelé par `tree()` (l.354) et `exportZip()` (l.532) | `WorkspaceService` | Les deux héritent de la pagination : un projet de plus de 1 000 fichiers est déjà listé et exporté **en entier**. |
| Aucun autre endroit du dépôt n'appelle `listObjectsV2` ni `deleteObjects` | `grep` sur `backend/src/main/java` | Textract (`TextractOcrProvider`) ne fait que `putObject`. Le balayage demandé est clos. |
| `WorkspaceService.delete` est `@Transactional` et efface **le stockage d'abord**, la ligne du projet **ensuite** | `WorkspaceService` l.477-484 | Une exception du stockage **annule la transaction** : le projet reste en base, donc **réessayable**. C'est le bon ordre, il ne change pas. |

---

## Les trois décisions de conception

### D1 — Des lots de 1 000 au plus, et on ne rend la main qu'une fois tout envoyé

Le plafond n'est pas une limite de confort : c'est un refus **global** de la requête. `deletePrefix`
découpe donc en lots de **1 000 clés** (`DELETE_BATCH_SIZE`) et boucle jusqu'à épuisement. 7 288
objets = 8 appels. La méthode ne rend la main qu'après le **dernier** lot — aucune suppression
asynchrone, aucun plafond sur la taille d'un projet (hors périmètre, PRODUCT_SPEC le dit).

### D2 — Un échec qui dit ce qui est parti et ce qui reste

Deux façons d'échouer, traitées différemment :

- **Erreurs par clé** (`response.errors()`, sans exception) : le lot est passé, certaines clés ont
  résisté. On **continue les lots suivants** — ces refus sont propres à une clé, pas au compte — et
  on les accumule.
- **Exception sur un lot** (`SdkException` : droits, réseau, bucket) : c'est **systémique**. On
  **arrête** — insister enverrait sept requêtes vouées au même échec — et on rend compte.

Dans les deux cas, `deletePrefix` lève `WorkspaceStorageDeletionException` portant **combien de
clés ont été effacées** et **combien restent**. Un 500 muet sur une suppression partielle est pire
que l'échec : il laisse croire que rien n'a bougé, alors que des fichiers sont partis.

### D3 — Le message dit des nombres au client, des clés au journal

Le client reçoit des **compteurs** (« 6 288 effacés, 1 000 restants ») et la conduite à tenir
(réessayer). Les **clés** en échec et la cause S3 vont au **journal serveur** uniquement
(`log.warn`), échantillonnées — c'est la règle maison : un message d'erreur décrit une règle, pas
une donnée (`CODING_RULES` §6).

---

## Ce que le contrat visible devient (rien, ou presque)

`DELETE /api/v1/atelier/workspaces/{id}` rend **204** au succès comme avant, **404** sur un projet
non possédé comme avant, **500** sur échec de stockage comme avant. Seul **le corps du 500** cesse
de mentir : `internal_error` / « Une erreur interne est survenue » devient
`storage_partial_delete` / le décompte. Aucun endpoint, aucun champ de réponse au succès, aucune
table, aucun écran ne change. **Pas de migration Liquibase.**

---

## Hors périmètre (explicite, aligné sur PRODUCT_SPEC)

- La suppression **asynchrone** d'un très gros projet (le geste reste synchrone).
- Un quelconque **plafond** sur la taille ou le nombre de fichiers d'un projet.
- Le **cycle de vie S3** (règle d'expiration côté bucket) : c'est de l'infrastructure.
- **Toucher au cluster** : aucun `kubectl`, aucun déploiement.
- La reprise **automatique** d'une suppression partielle : c'est l'utilisateur qui réessaie, et le
  projet est toujours là pour ça.

---

## Découpage

| SF | Titre | Portée |
|---|---|---|
| SF-79-01 | La suppression du stockage part par lots et rend compte | Backend seul. Aucune UI : l'écran de suppression existe déjà (F-69) et son contrat ne bouge pas. |

Une seule subfeature : le défaut est d'un seul tenant (un appel, une boucle, un compte-rendu), et
le découper ferait livrer une moitié qui ne répare rien.

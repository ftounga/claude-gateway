# SF-48-04 — Le runner sait lire la réponse d'appairage

## Objectif

Rétablir l'appairage d'une machine neuve, cassé depuis le 2026-09-10 par SF-48-01.

## Le défaut, observé et reproduit

SF-48-01 (PR #313, mergée le **2026-09-10**) a renommé le champ de `PairResponse` :
`workspaceId` → `hostId`. Le runner n'a pas suivi : `StoredToken` déclare toujours `workspaceId`,
son `ObjectMapper` est en configuration par défaut (donc **stricte**), et le record **ne porte pas**
`@JsonIgnoreProperties(ignoreUnknown = true)` — le seul DTO du runner dans ce cas, `SessionMemory`
l'ayant.

Reproduit hors réseau, avec le jar de production :

```
UnrecognizedPropertyException: Unrecognized field "hostId" (class StoredToken),
not marked as ignorable (3 known properties: "workspaceId", "token", "expiresAt")
```

Ce que voit l'utilisateur : `ERREUR  Réponse d'appairage illisible`. Ce que voit la gateway : un
appairage **réussi**, jeton créé en base. D'où un poste qui apparaît « connu » — racine, système,
heure, écrits par `recordDeclaration` pendant l'appel HTTP — et dont le canal ne s'ouvre jamais.

`workspaceId` n'est lu **nulle part** dans le runner : c'est un champ mort qui fait échouer
l'appairage.

**Pourquoi rien ne l'a vu** : il n'existe aucun test sur `PairingClient`. Le contrat a dérivé des
deux côtés sans que rien ne proteste.

## Comportement attendu

- Un appairage sur une gateway à jour aboutit : le jeton est lu, persisté, le canal s'ouvre.
- Le runner conserve l'identifiant du **poste** reçu (`hostId`), sous son vrai nom.
- Un champ **inconnu** dans la réponse n'empêche plus jamais de lire le jeton : une gateway plus
  récente que le runner reste utilisable.

## Cas d'erreur

| Réponse | Attendu |
|---|---|
| `{"token":"…","hostId":"…","expiresAt":"…"}` | lue ; jeton persisté |
| champ supplémentaire inconnu | **lue** ; le champ est ignoré |
| `{"hostId":"…"}` (sans jeton) | refus « Réponse d'appairage sans jeton » |
| corps non JSON (page d'un portail captif) | refus « Réponse d'appairage illisible » |

## Critères d'acceptation

1. `StoredToken` déclare `hostId` et non `workspaceId`.
2. `StoredToken` porte `@JsonIgnoreProperties(ignoreUnknown = true)`.
3. Un test lit la réponse **exacte** que produit `PairResponse` aujourd'hui et obtient le jeton.
4. Un test ajoute un champ inconnu et obtient toujours le jeton.
5. Un test vérifie qu'une réponse sans jeton est refusée avec son message propre.
6. Un jeton déjà persisté sous l'ancienne forme (`workspaceId`) reste lisible — on ne casse pas les
   postes appairés avant le 2026-09-10.

## Plan de test

- Unitaires sur la lecture de `StoredToken` : forme courante, champ inconnu, jeton absent, ancienne
  forme.
- Unitaire sur `PairingClient` avec un `HttpClient` bouchonné : 200 lisible, 401, 500, corps non
  JSON. **C'est le test qui manquait.**
- Non-régression : rien d'autre du runner ne lit `workspaceId` (vérifié : aucune occurrence).

## Impacté

`runner/…/StoredToken.java`, `runner/…/PairingClient.java` (aucun changement de logique attendu),
tests du runner. **Aucune table, aucun endpoint, aucun écran.**

## Hors périmètre

Le repli de transport qui ne s'est pas déclenché, et le poste rempli par un appairage dont le canal
n'existe pas : ce sont deux constats distincts, portés au cadrage de F-80.

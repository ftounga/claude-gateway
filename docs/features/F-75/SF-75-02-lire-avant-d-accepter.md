# Mini-spec — F-75 / SF-75-02 — Lire avant d'accepter : le contenu et le différentiel

## Identifiant

`F-75 / SF-75-02`

## Feature parente

`F-75` — La gouvernance s'active par poste, pas par projet

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-75-02-lire-avant-d-accepter`

---

## Objectif

Ouvrir à la lecture, **avant** d'accepter, le contenu exact de chaque fichier qu'un paquet déposerait,
et montrer le **différentiel** avec ce qui existe déjà sur les projets du poste — puisque le dépôt
n'écrase jamais et laisse l'existant en place.

---

## Comportement attendu

### Cas nominal

1. `GET /governance/hosts/{hostRef}/{packageId}/file?path=…` rend le contenu **du fichier tel que le
   paquet l'apporte**, et, pour chaque projet du poste où un fichier porte déjà ce chemin, le contenu
   **actuel** de ce fichier.
2. La réponse dit, par projet : `identical` (rien ne changerait et rien ne serait laissé de
   différent), `different` (le fichier restera tel qu'il est, celui du paquet ne sera pas écrit), ou
   `missing` (le fichier sera créé).
3. Le contenu est rendu **tel quel**, jamais interprété, et n'est **jamais journalisé** : c'est le
   fichier de l'utilisateur, sur la machine d'un client.
4. Chaque contenu est **borné** : au-delà de 200 000 caractères il est tronqué et la réponse le dit
   (`truncated`), afin qu'aucun fichier ne fasse d'une lecture d'écran une réponse illisible.
5. Le nombre de projets inspectés est **borné** (20 par défaut) : au-delà, la réponse dit combien de
   projets n'ont pas été lus. Lire le même fichier sur cinquante machines pour préparer un clic serait
   payer un balayage de disque au prix d'une infobulle.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `path` absent ou vide | « Chemin requis » | 400 |
| `path` hors du paquet | « Fichier introuvable » — on ne lit **que** ce que le paquet apporte | 404 |
| `path` qui sort du projet (`..`, absolu) | Refusé par `GovernancePath` avant toute lecture | 404 |
| Poste d'un autre compte | « Poste introuvable » | 404 |
| Paquet non publié | « Paquet introuvable » | 404 |
| Projet illisible (machine éteinte) | Pas une erreur : ce projet est rendu avec `readable: false` | 200 |
| Utilisateur sans accès Atelier | Refus | 403 |

---

## Critères d'acceptation

- [ ] L'endpoint rend le contenu du fichier **du paquet** pour un chemin que le paquet apporte.
- [ ] Pour chaque projet du poste, il rend le contenu **existant** du même chemin, ou `missing`.
- [ ] Un fichier identique est signalé comme tel — le différentiel est alors vide, et on le dit.
- [ ] Un fichier différent est signalé comme **laissé en place** : c'est ce que le dépôt fera.
- [ ] Un chemin absent du paquet rend 404, même s'il existe dans le projet : on n'offre pas une
      lecture arbitraire du disque du client sous couvert de gouvernance.
- [ ] Contenus tronqués au-delà de la borne, avec `truncated` à vrai.
- [ ] Aucun contenu de fichier n'apparaît dans les journaux.
- [ ] **Isolation** : le poste est vérifié possédé, chaque projet est lu via `WorkspaceService`
      filtré `user_id`.

---

## Périmètre

### Hors scope (explicite)

- Le calcul du diff ligne à ligne — il est fait **à l'écran** (SF-75-03) : deux contenus suffisent, et
  un diff calculé au serveur serait une seconde représentation à tenir.
- L'édition d'un fichier (le paquet se rédige côté admin, F-51).
- La lecture d'un fichier quelconque du projet.

---

## Tables / endpoints / composants impactés

### Tables

Aucune.

### Endpoints

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/governance/hosts/{hostRef}/{packageId}/file?path=…` | Contenu apporté + contenu existant par projet |

### Composants backend

`GovernanceHostController`, `GovernanceDepositService` (ou service de lecture dédié),
`GovernanceProjectFiles` (lecture d'un fichier), DTO `GovernanceFileComparison`,
`GovernanceFileComparisonEntry`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** — nouvelle lecture de fichiers utilisateur | `GovernanceProjectFiles.read` (reçoit un `Workspace` **déjà vérifié possédé**), `GovernanceHostScope` (vérifie le poste), `WorkspaceService.readFile` (filtre `user_id`) |
| Plans / limites | non | — |
| Navigation / routing | non | — |

---

## Plan de test minimal

### Unitaires

- `GovernanceFileReadingServiceTest` : chemin hors paquet → introuvable ; fichier identique →
  `identical` ; fichier différent → contenu existant rendu ; fichier absent → `missing` ; projet
  illisible → `readable: false` ; troncature au-delà de la borne ; plafond de projets inspectés.

### Intégration

- `GovernanceHostApiIntegrationTest` (complété) : lecture d'un fichier d'un paquet actif ; 400 sans
  `path` ; 404 sur un chemin inconnu ; 404 sur le poste d'autrui.

### Isolation utilisateur

- Un second utilisateur qui demande le même chemin sur le poste du premier reçoit 404 et **aucun
  contenu**.

---

## Contraintes de validation

| Champ | Contrainte |
|---|---|
| `path` | non vide, normalisé par `GovernancePath`, **doit** appartenir au paquet |
| Taille de contenu rendue | ≤ 200 000 caractères, sinon tronquée et signalée |
| Projets inspectés | ≤ 20, le reste compté et annoncé |

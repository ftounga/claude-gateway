# Mini-spec — F-51 / SF-51-02 — Mon catalogue, et l'activation par projet

## Identifiant

`F-51 / SF-51-02`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-02-catalogue-personnel`

---

## Objectif

Donner à chaque utilisateur **son** catalogue : retenir des paquets publiés, les **activer projet par
projet**, et marquer ceux qui doivent être **appliqués par défaut** à tout nouveau projet.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur lit le catalogue publié (SF-51-01) et **retient** un paquet : il entre dans son
   catalogue personnel.
2. Il peut marquer ce paquet **appliqué par défaut**. Rien n'est imposé : le drapeau vaut pour
   **ses** projets à venir, jamais pour ceux de quelqu'un d'autre.
3. Sur un projet qu'il possède, il **active** un paquet retenu. L'activation mémorise la **version**
   du paquet appliquée et naît à l'état **`PENDING`** — le dépôt des fichiers, lui, arrive en
   SF-51-03.
4. Il lit l'état de gouvernance d'un projet : ce qui y est actif, dans quelle version, et ce que son
   catalogue contient sans y être activé.
5. Il **désactive** : le paquet cesse de s'appliquer au projet. **Les fichiers déjà déposés restent**
   (décision D4 du cadrage) — ils appartiennent au projet dès qu'ils y sont.

### Ce qui se produit tout seul

Un paquet **appliqué par défaut** peut être activé sur un projet sans que l'utilisateur ait rien
coché sur ce projet. SF-51-02 fournit l'opération (`embarkDefaults`) et la teste ; **son
déclenchement à la création d'un projet est câblé en SF-51-03**, avec le dépôt qui l'accompagne.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Appelant non authentifié | Refus de la chaîne de sécurité | 401 |
| Sans droit Atelier (F-40) | Accès refusé | 403 |
| Paquet inexistant **ou non publié** | « Paquet introuvable. » — un brouillon n'existe pas pour un utilisateur | 404 |
| Projet inexistant **ou appartenant à un autre** | « Projet introuvable. » — jamais 403, qui révélerait son existence | 404 |
| Activer un paquet **absent de son catalogue** | Refus : on active ce qu'on a retenu | 409 |
| Retenir un paquet déjà retenu | Idempotent : met à jour le drapeau, ne duplique pas | 200 |
| Activer un paquet déjà actif | Idempotent : rend l'activation existante, sans dupliquer ni régresser la version | 200 |
| Désactiver un paquet non actif | Idempotent : rien à faire | 204 |
| Retirer de son catalogue un paquet actif sur des projets | Accepté ; **les activations restent** (voir arbitrage A2) | 204 |

---

## Critères d'acceptation

- [ ] Retenir un paquet publié le fait apparaître dans `GET /governance/selection`.
- [ ] Retenir deux fois le même paquet ne crée qu'une ligne et met à jour `defaultApplied`.
- [ ] Retenir un paquet **non publié** rend 404.
- [ ] Activer un paquet retenu sur un projet possédé crée une activation `PENDING` portant la
      **version courante** du paquet.
- [ ] Activer un paquet **non retenu** rend 409.
- [ ] Activer sur un projet **d'un autre utilisateur** rend 404 — et **aucune** ligne n'est créée.
- [ ] `GET /workspaces/{id}/governance` ne rend que les activations **de l'appelant** sur **son**
      projet.
- [ ] Désactiver retire l'activation ; une seconde désactivation reste un succès.
- [ ] `embarkDefaults` crée une activation pour chaque paquet marqué par défaut, et **rien** pour les
      autres ; rejoué, il ne duplique pas.
- [ ] Aucune lecture ni écriture de ces deux tables ne s'effectue sans filtre `user_id`.

---

## Périmètre

### Hors scope (explicite)

- L'**aperçu** (« ce qui va être écrit et où ») et le **dépôt** des fichiers → SF-51-03.
- Le **déclenchement** de `embarkDefaults` à la création d'un projet → SF-51-03.
- L'injection des règles et le branchement des contrôles → SF-51-04.
- Tout écran → SF-51-05.
- Le partage d'un catalogue entre comptes — **F-17, V3, hors périmètre**.

---

## Impacts

### Tables

| Table | Changement |
|---|---|
| `governance_selections` | **créée** — `id`, `user_id`, `package_id`, `default_applied`, `created_at`, `updated_at` ; unicité `(user_id, package_id)` |
| `governance_activations` | **créée** — `id`, `user_id`, `workspace_id`, `package_id`, `applied_version`, `status`, `applied_at`, `created_at`, `updated_at` ; unicité `(user_id, workspace_id, package_id)` |

Migration Liquibase `066-governance-selection-and-activation.xml` (PostgreSQL + H2, rollback par
changeSet).

**Isolation `user_id`** : les deux tables la portent, elle est dans chaque index d'unicité et dans
**chaque** méthode de repository. Le projet est en outre vérifié par
`WorkspaceService.requireOwned(userId, id)` avant toute écriture.

### Endpoints

| Méthode | Chemin | Effet |
|---|---|---|
| `GET` | `/governance/selection` | Mon catalogue personnel |
| `PUT` | `/governance/selection/{packageId}` | Retenir / mettre à jour le drapeau « par défaut » |
| `DELETE` | `/governance/selection/{packageId}` | Ne plus retenir |
| `GET` | `/workspaces/{id}/governance` | Ce qui est actif sur ce projet, et ce qui pourrait l'être |
| `POST` | `/workspaces/{id}/governance/{packageId}` | Activer |
| `DELETE` | `/workspaces/{id}/governance/{packageId}` | Désactiver |

### Composants

`GovernanceSelection`, `GovernanceActivation`, `GovernanceActivationStatus`, leurs repositories,
`GovernanceSelectionService`, `GovernanceActivationService`, `GovernanceUserController`,
`GovernanceSelectionRequest` + vues, et le branchement des exceptions déjà posé en SF-51-01.

---

## Arbitrages de cette subfeature

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| A1 | Activer sans avoir retenu | **Refusé** (409) | Le catalogue personnel est l'étage qui donne son sens à la feature : « chacun compose sa sélection ». Activer en la court-circuitant la viderait de son rôle et rendrait le drapeau « par défaut » incohérent | oui |
| A2 | Retirer de son catalogue un paquet actif | **Les activations restent** | Décocher dans une liste ne doit pas éteindre en silence la gouvernance de projets en cours. Le geste qui éteint un projet est la **désactivation**, sur ce projet | oui |
| A3 | État d'une activation | `PENDING` → `APPLIED` | Le dépôt des fichiers peut attendre une machine allumée (décision D2 du cadrage). L'état est posé dès SF-51-02 pour que SF-51-03 n'ait pas à migrer une colonne le lendemain | oui |

---

## Plan de test minimal

### Unitaires

- `GovernanceSelectionServiceTest` : retenir, re-retenir (idempotent), paquet non publié → 404,
  retrait.
- `GovernanceActivationServiceTest` : activation `PENDING` avec la version courante, refus si non
  retenu, idempotence, désactivation idempotente, `embarkDefaults` (crée les défauts, ignore le
  reste, ne duplique pas).

### Intégration (MockMvc, base H2)

- Parcours : retenir → activer → lire l'état du projet → désactiver.
- 404 sur paquet non publié ; 409 sur activation non retenue.
- 401 anonyme.

### Isolation

- Un utilisateur B ne voit **aucune** ligne de A : `GET /governance/selection` et
  `GET /workspaces/{id}/governance` sur le projet de A rendent respectivement une liste vide et 404.
- Activer sur le projet de A depuis le compte de B rend 404 **et** ne crée aucune ligne (vérifié en
  base).

---

## Contraintes de validation

`defaultApplied` : booléen, défaut `false`. `applied_version` : entier, copié du paquet à
l'activation. `status` : `PENDING` | `APPLIED`. Aucune borne nouvelle à trancher ; aucune question
ouverte de `docs/OPEN_QUESTIONS.md` impactée.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | non | Aucun changement du Principal ni de la chaîne ; l'identité vient de `CurrentUser.requireId()` comme partout |
| **Contexte tenant** | **oui** | Deux tables nouvelles portant `user_id`. Composants qui le résolvent ici : `GovernanceSelectionService` et `GovernanceActivationService` (identité reçue en paramètre, jamais devinée), `GovernanceUserController` (`CurrentUser.requireId()`), et `WorkspaceService.requireOwned` pour le projet. Aucun composant existant ne change de façon de résoudre le tenant ; les repositories n'exposent **aucune** méthode sans `userId` |
| Plans / limites | **oui** (garde d'accès) | Les endpoints passent par `AtelierAccessService.requireAccess()` (F-40), comme `/runner-hosts/**` et `/atelier/**`. Aucun quota, aucun nouveau gate ; le gate existant est réutilisé tel quel |
| Navigation / routing | non | Aucun écran dans cette subfeature |

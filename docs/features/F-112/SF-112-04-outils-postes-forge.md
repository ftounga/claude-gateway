# Mini-spec — F-112 / SF-112-04 : Les outils Postes et Forge

## Identifiant

`F-112 / SF-112-04`

## Feature parente

`F-112` — Le serveur MCP : une IA pilote toute l'application

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-04-outils-postes-forge`

---

## Objectif

Exposer sur le serveur MCP les outils **Postes et Forge** (cadrage F-112 §5) qui relaient les
capacités existantes de la gateway (liste et détail des postes, projets, carte de gouvernance, état
de gouvernance, vérification et mise à jour du runner), avec périmètres, accès poste par poste,
schémas de sortie structurés et filtre de secrets.

---

## Comportement attendu

### Cas nominal

Un client MCP authentifié (jeton OAuth ou personnel) appelle un outil. L'outil :
1. lit l'identité et les périmètres depuis `McpCallContext` (jamais d'un paramètre) ;
2. refuse si le périmètre requis (`postes:lire` ou `postes:agir`) n'est pas accordé ;
3. pour un outil ciblant un poste, refuse si le poste n'est pas accessible (accès poste par poste) ;
4. relaie le **service existant** avec `ctx.user()` (isolation `user_id`/`host_id` par le service) ;
5. renvoie un contenu textuel + un **contenu structuré**, secrets masqués en sortie.

Outils livrés :

| Outil | Périmètre | Relaie | Annotations |
|---|---|---|---|
| `postes_lister` | `postes:lire` | `RunnerHostOverviewService.overview` | readOnly, idempotent |
| `poste_detail` | `postes:lire` | overview filtré par `host_id` + `RunnerStatusService.hostStatus` | readOnly, idempotent |
| `projets_lister` | `postes:lire` | `WorkspaceService.list` / `listByHost` | readOnly, idempotent |
| `projet_detail` | `postes:lire` | `WorkspaceService.requireOwned` + statut | readOnly, idempotent |
| `carte_lire` | `postes:lire` | `GovernanceActivationService.describe` (carte de gouvernance) | readOnly, idempotent |
| `gouvernance_etat` | `postes:lire` | `GovernanceActivationService.hosts` | readOnly, idempotent |
| `poste_verifier` | `postes:lire` | `RunnerStatusService.hostStatus` + `RunnerUpdateAdvisor` (verdict de santé du poste, lecture) | readOnly, idempotent |
| `poste_mettre_a_jour_runner` | `postes:agir` | `RunnerUpdateService.request` (F-111) | destructif, non idempotent |

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Périmètre requis absent | `CallToolResult` `isError=true`, message nommant le périmètre manquant ; aucun service appelé |
| Poste non accessible (accès poste par poste) | `isError=true`, message « poste non accessible » ; aucun service appelé |
| Poste inexistant / non possédé | `isError=true`, message clair (pas de stacktrace) ; isolation `user_id` respectée |
| Paramètre `host_id` absent ou mal formé (outil ciblant un poste) | `isError=true`, message clair |

---

## Critères d'acceptation

- [ ] Les 8 outils sont découverts par `tools/list` avec leurs annotations et un schéma d'entrée.
- [ ] `postes_lister` renvoie les postes du **porteur du jeton** et rien d'un autre utilisateur.
- [ ] Un outil dont le périmètre n'est pas accordé refuse avec un message nommant le périmètre, sans
      appeler le service.
- [ ] Un poste non accessible par le porteur (jeton personnel poste par poste) est refusé sans appel
      au service.
- [ ] `poste_mettre_a_jour_runner` exige `postes:agir` et porte l'annotation `destructiveHint`.
- [ ] Un secret présent dans un champ de sortie est masqué (filtre de secrets §6.4).
- [ ] Isolation : deux jetons de deux utilisateurs distincts obtiennent des vues distinctes ; un
      appel sur un `host_id` d'un autre utilisateur est refusé.

---

## Périmètre

### Hors scope (explicite)

- Outils Terminaux (SF-112-05), Vigie/Radar (SF-112-06), Pages/Courriel/Compte/Admin (SF-112-07),
  ressources et prompts (SF-112-08).
- Câblage du consentement OAuth **poste par poste** (foundation SF-112-02/03) : voir « Notes ».
- Toute nouvelle capacité métier : les outils **relaient** l'existant (Gateway-First).

---

## Contraintes de validation

| Champ (entrée) | Obligatoire | Format | Normalisation |
|---|---|---|---|
| `host_id` | Oui (outils ciblant un poste) | UUID | rejet si non-UUID |
| `project_id` | Oui (`projet_detail`) | UUID | rejet si non-UUID |
| `force` (`poste_mettre_a_jour_runner`) | Non | booléen, défaut `false` | — |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint HTTP. Les outils sont exposés via le serveur MCP existant (`/api/mcp`).

### Tables impactées

Aucune création. Lectures via services existants (`runner_hosts`, `workspaces`, gouvernance…),
écriture de mise à jour via `RunnerUpdateService` (F-111, tables existantes).

### Migration Liquibase

- [ ] Non applicable — aucune nouvelle table.

### Composants Angular

Aucun (subfeature backend).

---

## Plan de test

### Tests unitaires

- [ ] `McpSecretFilter` — masque jeton `sk-ant-…`, `ghp_…`, JWT `eyJ…`, `cgmcp_…`, `AKIA…`,
      récursivement dans Map/List ; laisse le texte ordinaire intact.
- [ ] `McpHostAccess` — jeton personnel : accès restreint à `hostIds` ; OAuth : postes possédés.

### Tests d'intégration (client MCP réel du SDK, jetons mintés avec périmètres)

- [ ] `tools/list` expose les 8 outils avec annotations.
- [ ] `postes_lister` avec `postes:lire` → OK ; sans le périmètre → erreur nommée.
- [ ] `poste_detail` sur un poste possédé → OK ; sur un poste d'un autre utilisateur → erreur.
- [ ] `poste_mettre_a_jour_runner` sans `postes:agir` → erreur ; annotation destructive présente.
- [ ] Isolation : jeton A ne voit pas les postes de B.

### Isolation utilisateur

- [ ] Applicable — chaque service est appelé avec `ctx.user().id()` ; test croisé A/B.

---

## Dépendances

### Subfeatures bloquantes

- `SF-112-01/02/03` — done (fondation : serveur, OAuth, jetons/contexte/journal).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Accès poste par poste et OAuth (foundation).** Le contexte d'appel porte `hostIds` renseigné
  pour les **jetons personnels** (accès poste par poste, SF-112-03) et **vide pour OAuth** (le
  consentement poste par poste n'est pas encore câblé côté OAuth — état de fondation documenté dans
  `McpAuthDetails`). `McpHostAccess` applique donc : jeton personnel → intersection avec `hostIds` ;
  OAuth → postes **possédés** par l'utilisateur (l'ownership reste vérifié par chaque service via
  `requireOwned`, donc l'isolation `user_id` est intacte). Le durcissement du poste par poste pour
  OAuth est un suivi de la fondation, tracé en risque résiduel — il ne relâche jamais l'isolation
  inter-utilisateurs.
- **`poste_verifier` en lecture.** La « vérification guidée » Teams (F-100) déclenche un aller-retour
  runner (asynchrone) et relève de la Vigie ; l'outil `poste_verifier` de la ligne « Postes et Forge »
  rend ici un **verdict de santé du poste** agrégé sans aller-retour (connectivité, version du
  runner, mise à jour requise) — conforme à la garde « pas de traitement lourd synchrone ». Requiert
  `postes:lire`. La mise à jour du runner (action) reste `poste_mettre_a_jour_runner` (`postes:agir`).
- **Filtre de secrets** partagé (`McpSecretFilter`) : même esprit que le journal (§6.4), réutilisé par
  les subfeatures suivantes.

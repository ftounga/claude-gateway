# Mini-spec — F-75 / SF-75-01 — L'activation change de grain : le poste

## Identifiant

`F-75 / SF-75-01`

## Feature parente

`F-75` — La gouvernance s'active par poste, pas par projet

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-75-01-activation-au-poste`

---

## Objectif

Déplacer l'activation d'un paquet de gouvernance du **projet** au **poste**, de sorte qu'activer une
fois sur un client vaille pour tous ses dossiers — présents et à venir — sans aucune dérogation par
dossier.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur active un paquet **sur un poste** : `POST /governance/hosts/{hostRef}/{packageId}`.
   `hostRef` vaut l'identifiant d'un poste possédé, ou le mot réservé `hosted` — le poste « Hébergé »
   de F-71, qui regroupe les projets sans machine.
2. La gateway vérifie que le paquet est **retenu** dans le catalogue personnel (règle A1 de F-51),
   crée **une** ligne d'activation `(user_id, host_id, package_id)`, puis **dépose les fichiers du
   paquet dans chacun des projets du poste**. Le dépôt reste idempotent : il crée ce qui manque et
   n'écrase jamais rien.
3. Les **règles** du paquet rejoignent la consigne système de **tous** les projets du poste, et ses
   **contrôles** se branchent sur les crochets de F-50 pour tous ces projets. La résolution se fait
   projet → poste → activations.
4. **Un projet ajouté demain sous ce poste hérite** : à la création d'un projet, les paquets actifs
   sur son poste y sont déposés, sans nouvelle activation ni geste de l'utilisateur.
5. `GET /governance/hosts` liste les postes gouvernables (postes réels + « Hébergé » s'il porte au
   moins un projet). `GET /governance/hosts/{hostRef}` rend ce qui s'applique à ce poste et ce qui
   pourrait s'y appliquer.
6. **Reprise des activations existantes** : la migration reporte chaque activation par projet sur le
   poste du projet, en dédoublonnant (n projets d'un même poste → une seule activation). Une
   activation portée par un projet **sans poste** est reportée sur le poste « Hébergé » : rien n'est
   perdu, et aucun projet ne se retrouve gouverné par une ligne inaccessible.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `hostRef` n'est ni un UUID ni `hosted` | « Poste introuvable » | 404 |
| Poste appartenant à un autre compte | « Poste introuvable » — jamais « interdit » (pas d'oracle d'existence) | 404 |
| Paquet non publié / inconnu | « Paquet introuvable » | 404 |
| Paquet non retenu dans le catalogue personnel | Message « retenez-le avant de l'activer » | 409 |
| Utilisateur sans accès Atelier | Refus | 403 |
| Projet du poste illisible (machine éteinte) | **Pas une erreur** : activation créée, statut `PENDING`, geste « appliquer » offert | 200 |
| Réactivation d'un paquet déjà actif sur le poste | Idempotent : aucune ligne dupliquée, aucune régression de version | 200 |

---

## Critères d'acceptation

- [ ] Activer un paquet sur un poste crée **une** activation `(user_id, host_id, package_id)` et
      dépose ses fichiers dans **chaque** projet du poste.
- [ ] Aucune route d'activation par projet ne subsiste : `/workspaces/{id}/governance*` a disparu.
- [ ] Les règles et les contrôles d'un paquet actif sur un poste s'appliquent à **tous** ses projets,
      et à eux seuls.
- [ ] Un projet créé sous un poste déjà gouverné reçoit les fichiers des paquets actifs de ce poste,
      sans nouvelle activation.
- [ ] Le poste « Hébergé » est gouvernable via le mot réservé `hosted` ; son identifiant reste **nul**
      dans toute réponse d'API (décision F-71 : aucun identifiant constant exposé).
- [ ] Un paquet marqué « appliqué par défaut » est embarqué à la **création d'un poste**, et à la
      création d'un projet sans poste (poste « Hébergé »).
- [ ] La migration reporte les activations existantes sur le poste de leur projet, dédoublonnées ;
      celles des projets sans poste vont sur « Hébergé ».
- [ ] La table d'origine `governance_activations` n'est ni modifiée ni supprimée : la reprise est
      réversible.
- [ ] **Isolation** : toute lecture et toute écriture de `governance_host_activations` filtrent
      `user_id` ; le poste est vérifié possédé avant toute écriture.
- [ ] Supprimer un poste efface ses activations.

---

## Périmètre

### Hors scope (explicite)

- L'affichage du **contenu** des fichiers et le différentiel → SF-75-02.
- L'écran Angular → SF-75-03.
- Le contenu des paquets (F-52) et le catalogue lui-même (F-51).
- Toute dérogation par dossier : **tranché par le PO — il n'y en a aucune**.

---

## Tables / endpoints / composants impactés

### Tables

| Table | Changement |
|---|---|
| `governance_host_activations` | **créée** (migration 072) : `id`, `user_id`, `host_id`, `package_id`, `applied_version`, `status`, `applied_at`, `created_at`, `updated_at` ; index unique `(user_id, host_id, package_id)` |
| `governance_activations` | **inchangée**, laissée en place comme vestige de reprise (rollback possible) |

### Endpoints

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/governance/hosts` | Postes gouvernables |
| `GET` | `/governance/hosts/{hostRef}` | Ce qui s'applique à ce poste |
| `GET` | `/governance/hosts/{hostRef}/{packageId}/preview` | Ce qui serait écrit, projet par projet |
| `POST` | `/governance/hosts/{hostRef}/{packageId}` | Active + dépose |
| `POST` | `/governance/hosts/{hostRef}/{packageId}/apply` | Rejoue le dépôt |
| `DELETE` | `/governance/hosts/{hostRef}/{packageId}` | Désactive (les fichiers déposés restent) |
| — | `/workspaces/{id}/governance**` | **supprimés** |

### Composants backend

`GovernanceActivation`, `GovernanceActivationRepository`, `GovernanceActivationService`,
`GovernanceDepositService`, `GovernanceHostRef` (nouveau), `GovernanceHostScope` (nouveau),
`GovernanceHostController` (nouveau, remplace `GovernanceProjectController`),
`GovernanceRulesProvider`, `GovernanceCheckpointDelegate`, `GovernanceWorkspaceCreatedListener`,
`GovernanceSelectionService`, `WorkspaceService`, `RunnerHostController`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** — le grain d'isolation de la gouvernance passe de `workspace_id` à `host_id` | `GovernanceActivationRepository` (toutes signatures portent `user_id`), `GovernanceActivationService`, `GovernanceDepositService`, `GovernanceRulesProvider` (résout le poste du projet), `GovernanceCheckpointDelegate` (idem), `WorkspaceService.delete` (ne purge plus d'activation — elles ne vivent plus sur le projet), `RunnerHostController.delete` (purge les activations du poste) |
| Plans / limites | non | — |
| Navigation / routing | non (SF-75-03) | — |

---

## Plan de test minimal

### Unitaires

- `GovernanceActivationServiceTest` : activation idempotente sur un poste ; refus si non retenu (409) ;
  désactivation idempotente ; `embarkDefaults` sur un poste ; poste d'un autre compte → introuvable.
- `GovernanceHostRefTest` : `hosted` reconnu, UUID reconnu, valeur libre refusée ; l'identifiant
  réservé n'est jamais rendu.
- `GovernanceDepositServiceTest` : dépôt sur **plusieurs** projets d'un poste ; `KEEP` sur fichier
  existant ; un projet illisible laisse l'activation `PENDING` ; poste sans projet → `APPLIED`.
- `GovernanceRulesProviderTest` : les règles d'un paquet actif sur le poste s'appliquent à un projet
  du poste, et pas à un projet d'un autre poste.

### Intégration

- `GovernanceHostApiIntegrationTest` : parcours complet retenir → activer sur un poste → lire →
  désactiver ; 404 sur un poste d'autrui ; 409 sans sélection ; `hosted` accepté ;
  `/workspaces/{id}/governance` répond 404 (route retirée).

### Isolation utilisateur

- Un second utilisateur ne voit ni n'atteint les activations du premier (404 sur son poste, listes
  vides sur les siennes).

---

## Contraintes de validation

| Champ | Contrainte |
|---|---|
| `hostRef` | UUID d'un poste possédé **ou** le mot réservé `hosted` ; rien d'autre |
| `status` | `PENDING` \| `APPLIED` (inchangé) |
| `applied_version` | entier ≥ 1, figé à l'activation, réaligné par un dépôt abouti |
| Unicité | `(user_id, host_id, package_id)` |
| Statut repris par la migration | `PENDING` — le grain du dépôt change, on ne prétend pas que tout est en place |

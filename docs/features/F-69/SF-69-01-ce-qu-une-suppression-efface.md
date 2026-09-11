# Mini-spec — F-69 / SF-69-01 · Ce qu'une suppression efface, et ce qu'elle refuse

## Identifiant

`F-69 / SF-69-01`

## Feature parente

`F-69` — Supprimer un projet, supprimer un poste

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-69-01-suppression-portee`

---

## Objectif

Faire dire au serveur, et prouver par des tests, que supprimer un projet efface **tout** ce qui est
côté gateway — journal compris — et **rien** sur la machine de l'utilisateur ; et refuser la
suppression d'un poste tant qu'il porte des projets, en disant combien et où.

---

## Comportement attendu

### Cas nominal — `DELETE /api/workspaces/{id}`

1. Droit Atelier exigé, identité prise dans le JWT (`CurrentUser`), jamais dans un paramètre.
2. `requireOwned(userId, id)` — 404 si le projet est inconnu **ou** appartient à quelqu'un d'autre.
3. La session sandbox est terminée (best-effort, inchangé — F-30 / SF-30-04).
4. Sont effacés, dans cet ordre : les fichiers du **stockage objet de la gateway** sous le préfixe du
   projet, la **conversation** (`atelier_messages`), les **réglages** de gouvernance
   (`governance_activations`), **le journal du runner** (`runner_audit`) — *nouveau* —, puis la
   **ligne du projet**.
5. `204 No Content`.

**Ce qui n'est pas touché, et qui est vérifié par un test :**

- **Le dossier sur la machine.** Aucun appel au canal runner, aucune commande émise, aucun chemin de
  machine lu. Le `project_path` n'est pas relu sur ce chemin.
- **Le poste** et **ses jetons** : la machine reste appairée ; supprimer un projet n'est pas
  débrancher une machine.
- **`usage_turns`** : pièces de facturation (F-61). La dépense a eu lieu ; l'écran d'usage sait déjà
  nommer « supprimé » un projet absent.
- Les **autres projets** du même poste, et ceux des autres comptes.

### Cas nominal — `DELETE /api/runner-hosts/{hostId}`

1. Droit Atelier, `requireOwned(userId, hostId)` — 404 si inconnu ou non possédé.
2. **Garde nouvelle** : si le poste porte au moins un projet (`workspaces.host_id = hostId`,
   isolation `user_id`) → **409**, et **rien n'est modifié** : ni jeton révoqué, ni liaison coupée,
   ni projet détaché.
3. Sinon : coupe-circuit (jetons révoqués, liaison coupée), codes d'appairage effacés, mois-postes
   oubliés, ligne du poste supprimée → `204`.

Le détachement automatique des projets (`detachAllFromHost`) **disparaît** : il devient inatteignable
puisqu'un poste ne se supprime plus qu'à zéro projet, et le laisser laisserait croire qu'une
troisième voie existe encore.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Projet inconnu ou d'un autre compte | `not_found`, indiscernables | 404 |
| Poste inconnu ou d'un autre compte | `not_found`, indiscernables | 404 |
| Poste portant N ≥ 1 projets | `host_has_projects` — message nommant le poste, le **nombre** de projets et **où** les trouver ; aucun effet de bord | 409 |
| Droit Atelier absent | `atelier_forbidden` | 403 |
| Sandbox injoignable à la suppression du projet | ignoré (best-effort), la suppression aboutit | 204 |

---

## Critères d'acceptation

- [ ] `DELETE /workspaces/{id}` efface aussi les lignes de `runner_audit` du projet (isolation
      `user_id` **et** `workspace_id`).
- [ ] `DELETE /workspaces/{id}` n'émet **aucune** commande vers le runner, y compris sur un projet en
      cible `RUNNER` avec un `project_path` renseigné — vérifié par un test qui échoue si le canal
      runner est sollicité.
- [ ] Après suppression d'un projet, le poste existe toujours et ses jetons runner sont intacts.
- [ ] Après suppression d'un projet, ses lignes `usage_turns` existent toujours.
- [ ] Le journal, la conversation et les activations d'un **autre** projet du même compte ne sont pas
      touchés.
- [ ] `DELETE /runner-hosts/{hostId}` répond 409 tant qu'il reste au moins un projet ; le message
      contient le nombre de projets restants.
- [ ] Ce 409 ne révoque aucun jeton, ne coupe aucune liaison, ne détache aucun projet (état relu et
      comparé après l'appel).
- [ ] `DELETE /runner-hosts/{hostId}` répond 204 quand il ne reste aucun projet, et le poste disparaît.
- [ ] Isolation : le projet et le poste d'un autre compte donnent 404, jamais 403, jamais 409.
- [ ] La suppression de compte (SF-11-03) continue de passer — elle supprime les postes par le
      repository, pas par ce chemin, donc la garde ne la bloque pas.

---

## Périmètre

### Hors scope (explicite)

- Toute action sur les fichiers de la machine.
- La corbeille, la restauration, l'archivage.
- Toute modification de `usage_turns` et des mois-postes (F-65).
- L'écran : c'est SF-69-02.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| DELETE | `/api/workspaces/{id}` | JWT + droit Atelier | Purge du journal ajoutée. Contrat inchangé. |
| DELETE | `/api/runner-hosts/{hostId}` | JWT + droit Atelier | **409** si projets restants (nouveau). Plus de détachement. |

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `runner_audit` | DELETE | nouveau — filtré `user_id` + `workspace_id` |
| `atelier_messages`, `governance_activations`, `workspaces` | DELETE | inchangé |
| `workspaces` | SELECT | nouveau — compte des projets d'un poste avant suppression |
| `usage_turns` | — | **aucune** opération, volontairement |

### Migration Liquibase

- [x] **Non applicable** — aucun changement de schéma : la purge et la garde s'écrivent sur les
      tables et index existants (`idx_runner_audit_*` de la migration 064 couvre le filtre).

---

## Plan de test

### Tests unitaires

- [ ] `WorkspaceServiceTest` — la suppression appelle la purge du journal avec `userId` **et**
      `workspaceId`.
- [ ] `WorkspaceServiceTest` — la suppression ne touche pas `usage_turns`.

### Tests d'intégration

- [ ] `DELETE /api/workspaces/{id}` → 204 ; journal, conversation, activations du projet vidés ;
      poste, jetons, `usage_turns` et projet voisin intacts.
- [ ] `DELETE /api/workspaces/{id}` sur un projet en cible `RUNNER` → 204 sans **aucun** appel au
      canal runner.
- [ ] `DELETE /api/workspaces/{id}` d'un autre compte → 404, et le projet existe toujours.
- [ ] `DELETE /api/runner-hosts/{hostId}` avec 2 projets → 409, corps contenant « 2 », état du poste
      et des jetons inchangé.
- [ ] Les projets supprimés, le même appel → 204.
- [ ] `DELETE /api/runner-hosts/{hostId}` d'un autre compte → 404.

### Isolation utilisateur

- [x] Applicable — toute lecture et toute suppression filtrent `user_id` ; les tests couvrent le
      couple projet/poste d'un second compte.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | aucun — l'identité reste `CurrentUser` |
| Contexte tenant | **Oui** | `WorkspaceService.delete` (+ `RunnerAuditRepository`), `RunnerHostController.delete`. Les deux gestes filtrent `user_id` ; aucun autre résolveur de tenant n'est modifié. |
| Plans / limites | Non | le mois-poste (F-65) suit le chemin existant `seatLedgerService.forgetHost` |
| Navigation / routing | Non | aucune route |

---

## Dépendances

- SF-11-03 (purge de compte) — `done` : la purge de projet devient son pendant à l'échelle d'un projet.
- F-48 / SF-48-01 (postes) — `done`.
- F-61 / SF-61-02 (usage par client) — `done` : sait déjà nommer un projet supprimé.

---

## Notes et décisions

- **Pourquoi la garde vit dans le contrôleur** : `RunnerHostService` ne connaît pas les projets, et
  l'a écrit dans sa javadoc depuis F-48. Le contrôleur orchestre déjà jetons, codes et projets ; la
  garde s'ajoute là où l'orchestration vit.
- **Le message du 409 ne nomme aucun projet** : il donne un nombre et un chemin d'écran. Lister des
  noms dans un message d'erreur ferait de l'erreur une vue.
- **Défaut trouvé en écrivant le test, et corrigé ici** (hors mini-spec initiale, tracé comme le
  veut l'étape 3) : `DELETE /runner-hosts/{hostId}` répondait **500** dès qu'il allait au bout. Les
  trois effacements — jetons, codes, ligne du poste — vivaient dans le contrôleur, **hors
  transaction** ; `tokenRepository.deleteByHostId` levait `TransactionRequiredException`. Personne ne
  l'avait vu parce qu'**aucun écran n'appelait ce chemin** — c'est exactement ce que F-69 vient
  changer. Correctif : `RunnerHostService.deleteWithCredentials`, **une seule transaction** pour les
  jetons, les codes, le mois-poste et la ligne. Effet de bord bienvenu : on ne peut plus supprimer un
  poste en laissant vivre un jeton qui l'authentifie.
- **`detachAllFromHost` retiré** de `WorkspaceService` : plus aucun appelant, et le laisser
  suggérerait qu'une troisième voie — ni cascade ni refus — existe encore.

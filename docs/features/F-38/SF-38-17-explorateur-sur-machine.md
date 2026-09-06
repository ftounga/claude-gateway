# Mini-spec — F-38 / SF-38-17 — L'explorateur lit la machine, il ne la copie pas

## Identifiant

`F-38 / SF-38-17`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready` — développement différé, après SF-38-15 et SF-38-16

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-17-explorateur-sur-machine`

---

## Objectif

> Rendre l'explorateur de fichiers utile en mode runner, en lui faisant lire l'arborescence et le
> contenu **directement sur la machine** via le runner — sans jamais copier le projet dans notre
> stockage.

---

## Déclencheur

En cible `RUNNER`, l'explorateur lit le stockage objet, **vide par construction** : les fichiers
vivent sur la machine de l'utilisateur. L'écran affiche donc un projet inexistant. Le besoin est
réel — visualiser et parcourir les fichiers pendant que l'agent travaille.

Deux moyens existaient ; le choix est motivé au §Notes, D1.

---

## Comportement attendu

### Cas nominal

1. L'explorateur d'un projet en cible `RUNNER` demande l'arborescence : la gateway relaie
   `list_files` au runner (méthode **déjà existante** de `RunnerToolGateway`, inchangée par
   SF-39-05 qui n'a touché qu'aux outils exposés au modèle).
2. Ouvrir un fichier relaie `read_file`. Le contenu s'affiche comme aujourd'hui.
3. Les exclusions `.runnerignore` (SF-38-10) s'appliquent **telles quelles** : un fichier exclu
   n'apparaît pas dans l'arborescence et sa lecture est refusée. La garde est celle du runner, pas
   une seconde règle côté gateway.
4. Rien n'est écrit dans le stockage objet, ni mis en cache côté serveur.

### Écriture depuis l'écran

**Hors périmètre de cette subfeature.** L'explorateur est en **lecture seule** sur un projet local.
Écrire supposerait de trancher qui, du terminal ou de l'écran, tient la main sur un fichier au même
instant — la question que F-31 a mis trois subfeatures à régler sur les projets Git. Elle sera posée
séparément, si le besoin se confirme.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Runner déconnecté | « Projet hors ligne : lancez le runner pour parcourir les fichiers » — jamais une arborescence vide qui laisserait croire à un projet vide | 409 |
| Fichier exclu par `.runnerignore` | Refus, message nommant l'exclusion (pas le contenu) | 403 |
| Fichier binaire ou trop volumineux | Message explicite ; bornes du runner inchangées (8 Mo en lecture, 512 Ko rendus) | 413 |
| Appel lent (machine occupée) | Indicateur de chargement ; le délai reste borné par le dispatcher existant | 200 |
| Projet non `RUNNER` | Comportement actuel, inchangé (lecture du stockage) | 200 |

---

## Critères d'acceptation

- [ ] Sur un projet en cible `RUNNER`, l'arborescence affichée est **celle de la machine**.
- [ ] Ouvrir un fichier affiche le contenu réel du disque.
- [ ] Aucun octet du projet n'est écrit dans le stockage objet (vérifié : aucun appel d'écriture).
- [ ] Les exclusions `.runnerignore` s'appliquent, en listage **et** en lecture.
- [ ] Runner déconnecté ⇒ message d'état, jamais une arborescence vide trompeuse.
- [ ] Les projets `ARCHIVE` et `GIT` sont strictement inchangés.
- [ ] Isolation : `requireOwned` premier geste ; l'appel runner passe par le workspace possédé.
- [ ] Chaque lecture déclenchée par l'écran est **journalisée à l'audit** comme les autres accès
      (SF-38-08), distinguée de celles décidées par l'agent.

---

## Périmètre

### Hors scope

- L'écriture et le renommage depuis l'explorateur sur un projet local.
- Toute synchronisation ou cache serveur du contenu (voir D1).
- La recherche plein texte dans l'explorateur.

---

## Technique

### Endpoint(s)

| Méthode | URL | Changement |
|---------|-----|-----------|
| GET | `/api/workspaces/{id}/files` | Route vers le runner si la cible est `RUNNER` |
| GET | `/api/workspaces/{id}/files/content` | Idem |

Le contrat des endpoints ne change pas : c'est leur **source** qui change. L'écran n'a rien à savoir.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_audit` | INSERT | Une ligne par lecture déclenchée par l'écran, marquée comme telle |

### Migration Liquibase

- [x] Non applicable — la colonne d'origine de l'appel existe déjà si l'audit distingue déjà les
      sources ; à confirmer au dev, sinon migration triviale.

### Composants Angular

Aucun changement fonctionnel : l'explorateur consomme les mêmes endpoints. Seuls s'ajoutent l'état
« hors ligne » et l'indicateur de chargement.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | Un nouveau chemin d'accès aux fichiers apparaît (via runner). Composants revus : `WorkspaceController` (lecture), `WorkspaceService.requireOwned`, `RunnerCallRouter` (résolution du runner par workspace **possédé**). Test à deux utilisateurs : B ne lit rien de la machine de A. |
| Plans / limites | Non | Une lecture d'explorateur ne consomme ni tokens ni bac à sable |
| Navigation / routing | Non | Chemins inchangés |

---

## Plan de test

### Tests unitaires

- [ ] Cible `RUNNER` ⇒ l'arborescence vient du runner ; le service de stockage n'est **pas** appelé.
- [ ] Cible `SANDBOX` ⇒ comportement actuel, le runner n'est pas sollicité.
- [ ] Runner absent ⇒ 409 avec message d'état, pas de liste vide.
- [ ] Fichier exclu ⇒ 403, message sans contenu.
- [ ] Chaque lecture écrit une ligne d'audit marquée « écran ».

### Tests d'intégration

- [ ] `GET /files` sur un projet `RUNNER` avec runner simulé → arborescence de la machine.
- [ ] `GET /files` sans runner → 409.
- [ ] `GET /files` sur le projet d'un autre utilisateur → 404.

### Isolation workspace

- [x] Applicable — test explicite à deux utilisateurs sur les deux endpoints.

---

## Dépendances

- `SF-38-15`, `SF-38-16` — à livrer d'abord.
- `SF-38-10` (exclusions) — done, réutilisée telle quelle.

---

## Notes et décisions

**D1 — Lire à la demande plutôt que synchroniser.** La proposition initiale était de faire
synchroniser les deux répertoires par le runner. Écartée, pour quatre raisons qui se cumulent :

| | Synchroniser | **Lire à la demande** |
|---|---|---|
| Sources de vérité | deux (disque + stockage) | une |
| Divergence possible | oui | non |
| Code privé de l'utilisateur dans notre stockage | tout le projet | rien |
| Volume, quota, purge | à gérer | sans objet |
| Travail à faire | synchro, conflits, purge, reprise | brancher deux endpoints sur des méthodes **déjà écrites** |
| Runner éteint | consultation possible | « projet hors ligne » |

Le seul avantage réel de la synchronisation est la dernière ligne. Il ne compense pas la première :
« deux vérités » est exactement le défaut qu'il a fallu trois subfeatures pour corriger sur les
projets Git (SF-31-08, 12 et 13). Recréer une troisième copie relancerait la même bataille, avec en
prime le code source de l'utilisateur monté chez nous sans qu'il l'ait demandé.

Si la consultation hors ligne devient un besoin avéré, elle sera un **cache explicite et consenti**,
activé par l'utilisateur projet par projet — pas un effet de bord de l'architecture.

**D2 — L'écran reste en lecture seule sur un projet local.** L'agent écrit par ses outils, l'écran
lit. Autoriser l'écriture rouvrirait la question du fichier tenu des deux mains, que F-31 a réglée
au prix de trois subfeatures. Ce sera un sujet à part, si le besoin se manifeste.

**D3 — Les lectures d'écran sont auditées, et distinguées.** Le journal d'audit doit permettre de
répondre à « qu'est-ce qui a été lu sur ma machine, et par qui ». Confondre les lectures de l'agent
et celles de l'utilisateur rendrait le journal moins utile, pas plus court.

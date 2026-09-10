# Mini-spec — F-51 / SF-51-03 — L'annonce, puis le dépôt idempotent

## Identifiant

`F-51 / SF-51-03`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-03-annonce-et-depot`

---

## Objectif

Dire **exactement ce qu'un paquet va écrire et où** avant qu'il n'écrive, puis déposer ses fichiers de
façon **idempotente** — créer ce qui manque, ne jamais écraser — que le projet vive en stockage ou sur
la machine de l'utilisateur.

---

## Comportement attendu

### L'annonce, d'abord

`GET /workspaces/{id}/governance/{packageId}/preview` rend, pour chaque fichier du paquet, **le chemin
exact** et ce qui va lui arriver :

| Verdict | Sens |
|---|---|
| `CREATE` | le fichier n'existe pas : il sera créé |
| `KEEP` | le fichier existe déjà : il sera **laissé tel quel** |
| `UNKNOWN` | le projet n'a pas pu être lu (machine éteinte) : on ne peut pas dire, et on ne le prétend pas |

L'aperçu ne modifie **rien**. C'est l'exigence de la feature : un paquet écrit sur la machine de
l'utilisateur, donc l'écran annonce avant.

### Le dépôt, ensuite

1. **À l'activation** (`POST /workspaces/{id}/governance/{packageId}`, SF-51-02) le dépôt est tenté
   immédiatement dans la foulée.
2. Chaque fichier manquant est **créé**. Chaque fichier déjà présent est **laissé tel quel** — jamais
   écrasé, jamais fusionné, jamais renommé, même si son contenu diffère de celui du paquet.
3. Si tout ce qui devait être écrit l'est, l'activation passe **`APPLIED`** et `applied_at` est
   horodaté. Sinon elle reste **`PENDING`**.
4. `POST /workspaces/{id}/governance/{packageId}/apply` **rejoue** le dépôt : c'est le geste offert
   quand la machine était éteinte, ou quand le paquet a été republié depuis. Il réaligne aussi
   `applied_version` sur la version courante — **sans jamais écraser** un fichier existant.
5. Un projet **neuf** embarque la sélection par défaut : à sa création, les activations sont créées et
   le dépôt est tenté, **après validation** de la création et **sans jamais la faire échouer**.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Appelant non authentifié / sans droit Atelier | Refus | 401 / 403 |
| Projet inexistant ou appartenant à un autre | « Projet introuvable. » | 404 |
| Paquet inexistant, non publié, ou **non actif** sur ce projet (pour `apply`) | « Paquet introuvable. » | 404 |
| Machine éteinte ou injoignable | L'activation **reste** `PENDING`, l'aperçu dit `UNKNOWN`, la réponse dit pourquoi. **Jamais** une erreur : le paquet est bien actif, seuls ses fichiers attendent | 200 |
| Écriture refusée sur un fichier (chemin exclu par `.runnerignore`, disque plein) | Ce fichier reste à déposer ; les autres sont écrits ; l'activation reste `PENDING` | 200 |
| Projet en stockage qui refuse l'écriture (projet local sans runner) | Idem : `PENDING`, rien n'échoue | 200 |

---

## Critères d'acceptation

- [ ] L'aperçu rend un `CREATE` pour un fichier absent et un `KEEP` pour un fichier déjà présent.
- [ ] L'aperçu **n'écrit rien** (vérifié : l'arborescence est inchangée après appel).
- [ ] L'aperçu d'un projet illisible rend `UNKNOWN` pour chaque fichier et signale que le projet n'a
      pas pu être lu.
- [ ] Le dépôt crée les fichiers manquants et **ne touche pas** à un fichier existant, contenu
      différent compris.
- [ ] Le dépôt rejoué une seconde fois n'écrit **rien** et laisse le projet identique.
- [ ] Une activation dont tous les fichiers sont en place passe `APPLIED` avec `applied_at` renseigné.
- [ ] Une activation dont un fichier n'a pas pu être écrit reste `PENDING`.
- [ ] Un paquet **sans fichier** (règles seules) passe `APPLIED` immédiatement — il n'y a rien à
      attendre.
- [ ] `apply` sur un paquet non actif rend 404 ; sur le projet d'un autre, 404 **sans écriture**.
- [ ] Un projet créé embarque les paquets marqués par défaut, et sa création **aboutit** même si le
      dépôt échoue entièrement.
- [ ] En cible runner, le dépôt passe par le runner (aucune écriture en stockage) ; en cible stockage,
      par le stockage (aucun appel runner).

---

## Périmètre

### Hors scope (explicite)

- L'injection des règles dans la consigne système et le branchement des contrôles → SF-51-04.
- Les écrans → SF-51-05 / SF-51-06.
- **Supprimer** un fichier déposé à la désactivation : jamais (décision D4).
- **Mettre à jour** un fichier existant quand le paquet change : jamais non plus. Republier un paquet
  ne réécrit rien chez personne ; `apply` ne crée que ce qui manque.

---

## Impacts

### Tables

**Aucune migration.** Les colonnes `status` et `applied_at` de `governance_activations` existent
depuis SF-51-02, précisément pour que celle-ci n'ait rien à migrer.

### Endpoints

| Méthode | Chemin | Effet |
|---|---|---|
| `GET` | `/workspaces/{id}/governance/{packageId}/preview` | **Annonce** : ce qui sera écrit, et où |
| `POST` | `/workspaces/{id}/governance/{packageId}/apply` | Rejoue le dépôt |

`POST /workspaces/{id}/governance/{packageId}` (SF-51-02) gagne le dépôt dans la foulée de
l'activation.

### Composants

- `GovernanceProjectFiles` — lit et écrit **là où les fichiers vivent réellement** : par le runner en
  cible `RUNNER`, par le stockage sinon. Même geste que `AtelierChatService.safeTree` /
  `readOptional`, isolé pour être testable seul.
- `GovernanceDepositService` — le plan (annonce) et le dépôt (application).
- `GovernanceDepositPlan`, `GovernanceDepositEntry`, `GovernanceDepositAction`.
- `WorkspaceCreatedEvent` (publié par `WorkspaceService`) + `GovernanceWorkspaceCreatedListener`.
- `GovernanceProjectController` — deux endpoints de plus.

---

## Arbitrages de cette subfeature

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| B1 | Un fichier existe déjà avec un autre contenu | **Laissé tel quel**, dit `KEEP` | C'est la promesse d'idempotence de la feature, et la seule qui protège le travail de l'utilisateur. Écraser un `STATE.md` rempli parce qu'un paquet en apporte un vide serait une perte de données | non |
| B2 | Embarquement des défauts à la création | **Événement après validation**, best-effort, ne fait jamais échouer la création | Un projet doit se créer même si la gouvernance a un hoquet. L'événement évite aussi le cycle `WorkspaceService` ↔ `GovernanceActivationService`, qu'une injection directe créerait | oui |
| B3 | Machine injoignable | `PENDING`, **200**, et l'écran offre « appliquer » | Le paquet **est** actif : ses règles et ses contrôles s'appliquent déjà. Rendre une erreur laisserait croire que rien n'a pris | oui |
| B4 | Republication d'un paquet | `apply` réaligne la version et **crée seulement ce qui manque** | Réécrire les fichiers d'un projet parce que l'admin a republié serait exactement l'écrasement que B1 interdit | oui |

---

## Plan de test minimal

### Unitaires

- `GovernanceDepositServiceTest` : plan `CREATE` / `KEEP` / `UNKNOWN` ; dépôt qui n'écrase pas ;
  second dépôt qui n'écrit rien ; passage `APPLIED` ; échec partiel qui laisse `PENDING` ; paquet sans
  fichier immédiatement `APPLIED` ; `apply` sur paquet non actif → 404.
- `GovernanceProjectFilesTest` : cible runner → runner uniquement ; cible stockage → stockage
  uniquement ; lecture impossible → « illisible », jamais une arborescence vide mensongère.

### Intégration (MockMvc, base H2)

- Aperçu puis activation sur un projet en stockage : les fichiers apparaissent, l'activation passe
  `APPLIED`.
- Un fichier préexistant n'est pas écrasé, et l'aperçu l'annonçait en `KEEP`.
- `apply` rejoué : aucune écriture, réponse stable.

### Isolation

- L'aperçu et `apply` sur le projet d'un autre rendent **404**, et **aucun fichier** n'est écrit dans
  ce projet (vérifié en lisant son arborescence après coup).

---

## Contraintes de validation

Les chemins sont déjà validés à la publication (SF-51-01) et re-normalisés avant écriture — un chemin
stocké avant un durcissement de la règle ne doit pas pouvoir sortir du projet. Bornes reprises de
SF-51-01 : ≤ 50 fichiers, ≤ 64 000 caractères par fichier. Aucune question ouverte impactée.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | non | Aucun changement ; identité par `CurrentUser.requireId()` |
| **Contexte tenant** | **oui** | Nouveaux accès aux fichiers d'un projet. Composants concernés : `GovernanceProjectFiles` (reçoit un `Workspace` **déjà vérifié comme possédé**, jamais un identifiant brut), `GovernanceDepositService` (appelle `WorkspaceService.requireOwned` avant tout), `GovernanceWorkspaceCreatedListener` (utilise le `userId` porté par l'événement, celui du créateur). Aucun composant existant ne change de façon de résoudre le tenant |
| Plans / limites | **oui** (garde d'accès) | Les deux nouveaux endpoints passent par `AtelierAccessService.requireAccess()`, comme les autres de `GovernanceProjectController`. Aucun quota nouveau ; les écritures runner restent soumises aux gardes existantes (`.runnerignore`, `PathGuard`) |
| Navigation / routing | non | Aucun écran |

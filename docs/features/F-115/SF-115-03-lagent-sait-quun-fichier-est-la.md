# Mini-spec — F-115 / SF-115-03 — L'agent sait qu'un fichier est là

> Base : `docs/features/F-115/CADRAGE-F-115-glisser-des-fichiers-dans-le-terminal.md` §2 (« le tour
> reçoit le chemin, jamais un contenu réinjecté ») et §6 (SF-115-03). S'appuie sur la table
> `atelier_deposited_files` (SF-115-01) et sur les échecs **nommés** de l'endpoint de dépôt (SF-115-01).

## Identifiant

`F-115 / SF-115-03`

## Feature parente

`F-115` — Glisser des fichiers dans tous les terminaux, comme dans Claude Code

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-115-03-lagent-sait`

---

## Objectif

Porter dans la **consigne du tour** la liste des fichiers déposés depuis le dernier tour — **chemins
seulement, jamais le binaire réinjecté** (comme Claude Code) —, que l'agent lit ensuite avec
`read_file`.

---

## Comportement attendu

### Cas nominal

Au début d'un tour (`AtelierChatService.runLoop`), les dépôts **non consommés** du terminal
(`(user_id, workspace_id)`) sont lus, une **note** listant leurs **chemins** est ajoutée à la consigne
**envoyée au modèle** (pas à la parole persistée de l'utilisateur), et les dépôts sont marqués
**consommés** (`consumed_at`). L'agent lit ensuite les fichiers avec `read_file` (outil existant). Un
tour suivant ne revoit pas ces dépôts.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun dépôt en attente | consigne **inchangée** (comportement d'avant F-115) |
| Un dépôt a échoué (poste hors ligne, dossier non inscriptible, taille dépassée) | l'échec a été **nommé** à l'utilisateur au dépôt (SF-115-01 : `runner_offline` 409, `deposit_failed` 502, `file_too_large` 413) et **aucune ligne** n'a été créée → la consigne n'est **pas** polluée |
| Lecture de la table en échec (best-effort) | le tour continue sans la note (jamais de tour cassé pour ça) |

---

## Critères d'acceptation

- [ ] La consigne du tour envoyée au fournisseur porte les **chemins** des fichiers déposés depuis le
      dernier tour (test sur la requête reçue par le fournisseur).
- [ ] **Aucun contenu binaire** n'est réinjecté : seule la liste de chemins entre dans la consigne (la
      note dit explicitement que le contenu n'est pas inclus).
- [ ] Les dépôts portés dans une consigne sont **marqués consommés** ; un tour suivant ne les revoit
      pas.
- [ ] La **parole persistée** de l'utilisateur (`atelier_messages.content`) reste son texte, sans la
      note.
- [ ] Sans dépôt, la consigne est **inchangée**.
- [ ] Les **échecs sont nommés** (poste hors ligne / dossier non inscriptible / taille dépassée) —
      garantis par les erreurs nommées de l'endpoint de dépôt (SF-115-01), et un dépôt échoué ne crée
      pas de ligne, donc ne pollue pas la consigne.
- [ ] Isolation `(user_id, workspace_id)` sur la lecture/consommation.

---

## Périmètre

### Hors scope (explicite)

- L'écriture du dépôt et l'endpoint (SF-115-01) ; l'UI (SF-115-02).
- La lecture effective du fichier par l'agent : elle passe par `read_file`, outil **existant**,
  inchangé (Provider-First : on ne réimplémente rien).
- Toute analyse du fichier (OCR/RAG).

---

## Technique

### Composants gateway impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `DepositConsumptionService` | **créé** | lit les dépôts non consommés, rend la note (chemins), marque consommé ; isolation `(user_id, workspace_id)` |
| `AtelierChatService` | modifié | injecte la note dans le message **envoyé** au modèle (`runLoop`), persiste la parole utilisateur inchangée ; service branché par **mutateur null-safe** (comme `permissionService`, SF-121-02) — `null` ⇒ comportement d'avant F-115 |
| `AtelierDepositedFileRepository` | réutilisé | requête `findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc` (SF-115-01) |

### Endpoints / tables / migration

- **Aucun endpoint, aucune migration** (la table `atelier_deposited_files` existe depuis SF-115-01 ;
  la colonne `consumed_at` y est déjà).

### Préoccupations transversales

- **Auth / tenant : oui** — lecture/consommation filtrées `(user_id, workspace_id)` ; le tour se
  résout déjà par `requireOwned` en tête de `runLoop`. Aucun nouveau moyen de résoudre le tenant.
- **Plans / limites : non** — aucun quota consommé (la note est du texte de consigne ; les tokens du
  tour sont comptés comme d'habitude).
- **Navigation / routing : non** (aucun écran).

---

## Plan de test

### Tests unitaires

- [ ] `DepositConsumptionServiceTest` — note contenant les chemins + `read_file`, dépôts marqués
      consommés (`consumed_at` renseigné, `saveAll` appelé) ; aucun dépôt → `null`, aucun `saveAll`.

### Tests d'intégration (boucle maison, requête reçue par le fournisseur)

- [ ] `AtelierChatServiceDepositTest` — la consigne envoyée porte le chemin déposé + « le contenu
      n'est pas inclus » + le texte de l'utilisateur ; **aucun binaire** ; `saveAll` (consommation).
- [ ] `AtelierChatServiceDepositTest` — la **parole persistée** (`AtelierMessage` role USER) ne porte
      que le texte de l'utilisateur, jamais le chemin.
- [ ] `AtelierChatServiceDepositTest` — **sans dépôt**, la consigne ne porte pas la note.

### Isolation utilisateur

- [x] Applicable — lecture/consommation par `(user_id, workspace_id)` ; les dépôts d'un autre terminal
      ou d'un autre utilisateur ne sont jamais lus.

---

## Dépendances

### Subfeatures bloquantes

- SF-115-01 (table `atelier_deposited_files`, endpoint, échecs nommés) — done (PR #651).
- SF-115-02 (dépôt côté écran) — done (PR #652).

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **D1 — la note augmente le message ENVOYÉ, pas la parole PERSISTÉE** : le fil affiche déjà le bloc
  « fichier déposé » (SF-115-02) ; la consigne du modèle reçoit les chemins pour ce tour, mais
  `atelier_messages.content` reste la phrase de l'utilisateur — l'historique rejoué n'est pas pollué.
- **D2 — consommation au début du tour** : les dépôts sont marqués consommés quand la note est bâtie,
  pour qu'un tour suivant ne les reliste pas (« depuis le dernier tour »). Le fichier reste sur
  disque/S3 ; seule sa **mention** est à usage unique, comme la référence de Claude Code.
- **D3 — chemins seulement, jamais le binaire** (Provider-First, `PROJECT.md` §3.3) : l'agent lit le
  contenu s'il le décide, avec `read_file` — on ne pousse ni octets ni coût inutiles au modèle.
- **D4 — branché par mutateur null-safe** : `null` ⇒ consigne d'avant F-115, sans toucher aux
  nombreuses constructions de `AtelierChatService` dans les tests (précédent `permissionService`).

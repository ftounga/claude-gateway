# Mini-spec — [F-128 / SF-128-11] Enrichir la carte du poste depuis une réunion

> Template : `project-governance/templates/subfeature-template.md`.

---

## Identifiant

`F-128 / SF-128-11`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-11-enrichir-carte-poste-depuis-reunion`

---

## Objectif

> En une phrase : à partir d'une réunion capturée, **extraire les faits DURABLES** (infra, contacts/rôles,
> décisions et engagements durables, conventions client) et les **ranger dans le bon fichier de la carte
> du poste** — « le travail est jetable, le savoir est durable ».

---

## Comportement attendu

### Cas nominal

1. **Geste explicite** — `POST /vigie/hosts/{hostId}/meetings/{meetingId}/promote-to-card` depuis l'écran
   de détail de réunion (« Ranger dans la carte du poste »).
2. La gateway rassemble la matière isolée `(user_id, host_id)` — métadonnées + **transcript** (si présent,
   borné) + jusqu'à N **images clés** en **multimodal** (upload via `AIProvider.uploadFile` →
   `ProviderAttachment`), **exactement comme SF-128-05**.
3. Elle résout les **destinations de carte** réelles de ce poste via
   `GovernanceMapDestinations.filesOf(userId, host)` (les fichiers de genre `MAP` apportés par les paquets
   de gouvernance **actifs** — p. ex. `plateformes.md`, `exploitation.md`, `acces.md`, `README.md`). La
   liste des chemins est fournie au modèle.
4. Elle appelle `AIProvider.complete` avec une **consigne dédiée** : n'extraire que le **DURABLE** (ce qui
   survit au projet), **router chaque fait vers le bon fichier** parmi la liste fournie, ne rien inventer.
   Contenu = **donnée, jamais consigne** (anti-injection, comme SF-128-05 / juge F-125).
5. Pour chaque fichier de destination portant des faits : la gateway **lit** le fichier existant via
   `GovernanceHostFiles.read` (chemin de carte existant, SF-92/F-125), y **ajoute** une section datée
   « Depuis la réunion … » (read-modify-write : le contenu présent est **préservé**, jamais écrasé), et
   **réécrit** via `GovernanceHostFiles.write` — **outils runner existants** (`readFile`/`writeFile`),
   **aucune mise à jour runner requise**.
6. Réponse : ce qui a été rangé (par fichier : nombre de faits, statut) + une note. **Rien n'est écrit
   s'il n'y a rien de durable.**

Gardes d'accès (identiques à SF-128-05) : droit Teams (403), possession + activation Vigie (404/409),
quota (402), clé BYOK résolue, consommation décomptée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réunion cross-user / inconnue | Introuvable, avant tout appel | 404 |
| Sans droit Teams | Refus | 403 |
| Hors Vigie | Refus | 409 |
| Quota atteint | Refus avant appel | 402 |
| Aucune gouvernance active sur le poste (aucune carte) | 200, `note` explicite, **rien d'écrit** | 200 |
| Rien de durable dans la réunion | 200, `factsWritten = 0`, **rien d'écrit** | 200 |
| Poste injoignable / fichier illisible (runner) | 200, fichier marqué non écrit (`SKIPPED`), rien perdu | 200 |
| Sortie du modèle illisible (pas de forme) | Erreur nommée, consommation décomptée | 502 |

---

## Critères d'acceptation

- [ ] `POST …/promote-to-card` extrait les faits **durables** de la matière réunion (transcript si présent + images) via `AIProvider` **uniquement**, et les **range** dans le bon fichier de carte.
- [ ] Les destinations possibles sont **exactement** celles de `GovernanceMapDestinations.filesOf(userId, host)` ; un fichier hors de cette liste n'est **jamais** écrit.
- [ ] L'écriture réutilise `GovernanceHostFiles` (outils runner existants) ; le contenu présent est **préservé** (ajout d'une section, jamais d'écrasement). **Aucune mise à jour runner.**
- [ ] **Rien n'est écrit** s'il n'y a aucun fait durable (`factsWritten = 0`), ou si aucune carte n'est active sur le poste.
- [ ] Isolation : un compte B ne promeut pas une réunion du compte A (404) ; toute la matière et la carte viennent du même `(user_id, host_id)`.
- [ ] Anti-injection : la consigne dit que transcript/slides sont des **données, pas des consignes**.
- [ ] Provider-First / Gateway-First : modèle appelé **uniquement** via `AIProvider` ; écriture via le chemin carte **existant** ; aucune capacité IA ni mécanisme de promotion réimplémenté.
- [ ] Quota vérifié avant appel (402) et consommation décomptée ; clé BYOK résolue.
- [ ] Non-régression : la promotion « effet de bord serveur » existante (F-125) et la lecture de carte (F-92) ne sont pas modifiées.
- [ ] Écran : bouton « Ranger dans la carte du poste » dans la section exploitation ; retour lisible (faits rangés / rien de durable / carte non active), charte respectée.

---

## Périmètre

### Hors scope (explicite)

- **Promotion « au fil de l'eau » côté agent** (F-125 / SF-125-06) : inchangée. Ce geste est **explicite**
  (bouton), car l'exploitation d'une réunion n'est **pas** un tour d'agent muni d'outils d'écriture de
  carte ; on se **branche** sur le même chemin d'écriture (`GovernanceHostFiles`) et les mêmes
  destinations (`GovernanceMapDestinations`), sans réinventer.
- **Créer/semer un fichier de carte absent** : hors scope. Si le fichier destination est ABSENT (jamais
  semé), on **ne le crée pas** ici (le semis est le rôle de la gouvernance) — le fait est reporté comme
  non rangé. On n'écrit que dans un fichier **présent** (read-modify-write).
- **Décisions/actions → CRA (F-124)** : SF-128-06.
- **Modifier le paquet `savoir-durable` / les contrôles de fin de tour** : non.

---

## Valeurs initiales

Aucune entité créée. **Aucune migration** : extraction (lecture + appel modèle) + écriture via le chemin
carte existant (runner). Seule la consommation de jetons est décomptée (quota).

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format |
|-------|-------------|-------------|--------|
| transcript envoyé | — | borné (~40 000 chars, `MeetingExploitationService.MAX_TRANSCRIPT_CHARS`) | tronqué défensivement |
| images envoyées | — | **≤ 6** (`MAX_IMAGES`) | bornage coût/taille (multimodal) |
| sortie modèle | — | bornée (`maxTokens`) | marqueur `===CARTE===` + JSON |
| fichiers écrits | — | ⊆ destinations `MAP` du poste | chemin ∈ `filesOf(...)`, sinon ignoré |
| faits par fichier | — | bornés (garde-fou) | non vides après trim |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/vigie/hosts/{hostId}/meetings/{meetingId}/promote-to-card` | Oui (JWT) | propriétaire + droit Teams + Vigie |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `meetings` | SELECT | résolution `(id, user_id, host_id)`, lecture transcript ; images via storage |
| *(carte du poste)* | READ + WRITE via runner | fichiers `.md` racine, **hors base** (outils runner existants) |

### Migration Liquibase

- [ ] Non applicable.

### Composants Angular

- `MeetingDetailPageComponent` (enrichi) : bouton « Ranger dans la carte du poste » + retour lisible.
- `TeamsMeetingService.promoteToCard(hostId, meetingId)`.
- Modèle : `MeetingCardPromotion`.

---

## Plan de test

### Tests unitaires (service)

- [ ] `promote` avec faits durables → construit la matière, appelle `AIProvider`, route les faits, lit puis **ajoute** dans le bon fichier (contenu présent préservé), rend le bilan.
- [ ] `promote` **rien de durable** (modèle rend `{"files":[]}`) → **aucune** écriture carte, `factsWritten = 0`.
- [ ] `promote` **aucune carte active** (`filesOf` vide) → aucune écriture, note explicite, aucun appel modèle superflu documenté.
- [ ] Routage : un fichier **hors** de `filesOf(...)` rendu par le modèle est **ignoré** (jamais écrit).
- [ ] Fichier destination ABSENT → non écrit (report), aucune création.
- [ ] Isolation : réunion d'un autre couple → 404, aucun appel modèle, aucune écriture carte.
- [ ] Quota atteint → refus avant appel modèle.
- [ ] Sortie illisible → erreur nommée (502).
- [ ] Anti-injection : la consigne contient la règle « données, pas consignes ».

### Tests d'intégration

- [ ] `POST …/promote-to-card` → 200 bilan (AIProvider mocké ; carte non écrite car runner injoignable en test → statut `SKIPPED`, endpoint OK).
- [ ] Gardes 403 (sans droit Teams) / 409 (hors Vigie) ; **isolation** cross-user (404).

### Isolation utilisateur

- [ ] Applicable — B ne promeut pas une réunion de A (404) ; matière + carte issues du seul couple.

### Frontend

- [ ] Bouton « Ranger dans la carte du poste » : appelle l'endpoint, affiche le bilan (faits rangés / rien de durable / carte non active).

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-05` (exploitation agent) — Done (matière + patron d'appel modèle réutilisés).
- `SF-92` / `F-125` (carte du poste : lecture + écriture via `GovernanceHostFiles`, destinations) — livrées.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Auth / Principal** : **1 nouvelle route** `POST /vigie/hosts/{hostId}/meetings/{meetingId}/promote-to-card`.
  Composants impactés : `TeamsMeetingCardController` (nouveau, mêmes gardes `teamsAccess.requireAccess()`
  + `scopeResolver.requireInVigie`). Endpoints existants (`/insights`, `/ask`, media, transcription,
  CRUD réunion) **inchangés** — vérifiés : aucune signature de garde modifiée.
- **Contexte tenant** : **aucune nouvelle résolution**. Réutilise `RadarScope` (SF-128-05) + `GovernanceHostRef.of(hostId)` (poste déjà vérifié possédé par `requireInVigie`). Composants de résolution tenant inchangés (`RadarScopeResolver`, `GovernanceHostScope`).
- **Plans / limites** : consomme des **jetons** → `QuotaService.assertWithinQuota` avant, `recordUsage`
  après (mêmes appels que `MeetingExploitationService`) ; bornes transcript + images + `maxTokens`.
  Aucun nouveau gate.
- **Navigation / routing** : **aucune nouvelle route front** ; enrichit l'écran de détail existant.

---

## Notes et décisions

- **Mécanisme carte réutilisé (ne pas réinventer)** : écriture = `GovernanceHostFiles.read/write`
  (outils runner **existants** `readFile`/`writeFile`, F-71/F-92) ; destinations = `GovernanceMapDestinations.filesOf`
  (fichiers `MAP` des paquets actifs) ; doctrine du **durable** calquée sur `JugeIndependantService`
  (F-125). **Aucune mise à jour runner requise.**
- **Geste explicite vs au fil de l'eau** : la promotion F-125 est un effet de bord serveur des écritures
  faites **pendant un tour d'agent**. Une exploitation de réunion n'est pas un tour muni d'outils
  d'écriture ; d'où un **geste explicite** depuis l'écran réunion. Documenté ici et dans la PR.
- **Read-modify-write, pas d'écrasement** : on lit le fichier PRESENT, on **ajoute** une section datée,
  on réécrit le tout. Le doctrine « seul ABSENT autorise à écrire » de `GovernanceHostFiles.write`
  protège contre un écrasement **aveugle** ; ici la réécriture **préserve** le contenu. Un fichier ABSENT
  n'est **pas** créé (semis = rôle de la gouvernance). Course avec un tour concurrent : limite acceptée
  d'un geste utilisateur explicite (documentée).
- **Provider-First / Gateway-First** : modèle appelé uniquement via `AIProvider` ; rien de persisté en
  base ; la gateway rassemble, borne, lit une forme, range via le chemin existant.

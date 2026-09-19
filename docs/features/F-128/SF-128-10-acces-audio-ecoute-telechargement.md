# Mini-spec — [F-128 / SF-128-10] Accès à l'audio d'une réunion (écoute + téléchargement) + deck d'images

> Template : `project-governance/templates/subfeature-template.md`.
> Document validé AVANT le dev.

---

## Identifiant

`F-128 / SF-128-10`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-10-acces-audio-reunion`

---

## Objectif

> En une phrase : rendre l'audio et les images clés d'une réunion capturée **accessibles à son
> propriétaire** — écoute (streaming avec `Range`), téléchargement, et affichage du deck — via un
> **écran de détail de réunion** dans la Vigie.

---

## Comportement attendu

### Cas nominal

Aujourd'hui la capture produit un artefact `STOPPED` avec `audio_key`/`audio_bytes` et `image_count`,
mais **aucune route de lecture** n'existe (seuls des `POST` de dépôt runner). SF-128-10 ouvre la
lecture, côté propriétaire authentifié (JWT), isolée `user_id` + `host_id` :

1. **Audio (écoute/streaming)** — `GET /api/vigie/hosts/{hostId}/meetings/{meetingId}/audio`
   renvoie les octets de l'audio avec le bon `Content-Type` (ex. `audio/webm`), `Accept-Ranges: bytes`
   et le support du **Range** (`206 Partial Content` + `Content-Range` quand un `Range` est présent,
   `200` sinon). Cache privé. Sert la lecture progressive dans un `<audio>`.
2. **Audio (téléchargement)** — même route avec `?download=1` : ajoute
   `Content-Disposition: attachment; filename="…"` (le reste identique). Un seul objet stocké, deux
   intentions.
3. **Deck (liste)** — `GET /api/vigie/hosts/{hostId}/meetings/{meetingId}/images` renvoie la liste des
   identifiants d'images clés de la réunion (ordre stable).
4. **Deck (image)** — `GET /api/vigie/hosts/{hostId}/meetings/{meetingId}/images/{imageId}` renvoie une
   image clé avec son `Content-Type`, cache privé. Miroir de `TeamsMomentController` (F-89).
5. **Frontend** — un **écran de détail de réunion** (`vigie/:hostRef/reunions/:meetingId`, miroir de
   `vigie/:hostRef/sujets/:subjectId`) : lecteur audio HTML5 + bouton **Télécharger**, deck des images
   capturées, métadonnées (titre, dates, rétention, sujet). La liste des réunions passées du panneau de
   capture devient cliquable (route vers le détail).

Gardes d'accès (mêmes que les API Réunions existantes) : droit Teams (403), possession du poste (404),
activation Vigie (409), puis résolution de la réunion par `(id, userId, hostId)` (404 indiscernable).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réunion d'un autre compte / poste (cross-user) | Introuvable (indiscernable de « inexistante ») | 404 |
| Réunion existante mais sans audio (`audio_key` null) | Pas d'audio à servir | 404 |
| Image inexistante ou d'une autre réunion | Introuvable | 404 |
| `Range` non satisfiable (début ≥ taille) | Plage non satisfiable | 416 |
| Compte sans droit Teams | Refus option | 403 |
| Poste non possédé | Introuvable | 404 |
| Poste non activé dans la Vigie | Conflit d'état | 409 |

---

## Critères d'acceptation

- [ ] `GET …/audio` renvoie `200` + octets + `Content-Type` d'origine + `Accept-Ranges: bytes` pour une réunion du propriétaire ayant un audio.
- [ ] `GET …/audio` avec `Range: bytes=…` renvoie `206`, `Content-Range` correct et **uniquement** la plage demandée.
- [ ] `GET …/audio` avec un `Range` hors bornes renvoie `416`.
- [ ] `GET …/audio?download=1` ajoute `Content-Disposition: attachment`.
- [ ] `GET …/audio` sur une réunion **d'un autre couple `user_id`/`host_id`** renvoie `404` (isolation).
- [ ] `GET …/audio` sur une réunion sans audio renvoie `404`.
- [ ] `GET …/images` renvoie la liste des identifiants d'images clés (vide si aucune).
- [ ] `GET …/images/{imageId}` renvoie l'image avec son `Content-Type` ; `404` si l'image n'appartient pas à cette réunion / ce couple.
- [ ] L'écran de détail affiche un lecteur audio, un bouton Télécharger et le deck ; il n'expose aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] Aucune logique métier dans le controller ; l'accès est filtré `user_id` + `host_id` dans le service.

---

## Périmètre

### Hors scope (explicite)

- **Transcription (STT)** → SF-128-04.
- **Exploitation par l'agent** (résumé/décisions/actions/Q&A) → SF-128-05.
- **Vidéo pleine** (jamais remontée — cadrage §7).
- **Rétention/purge active** → SF-128-07.
- **Redécoupage/édition** de l'audio ou des images.
- **URL signées / accès non authentifié** : la lecture passe par le JWT + les gardes existantes.

---

## Valeurs initiales

Aucune entité créée. Aucune migration (colonnes `audio_key`/`audio_bytes`/`image_count` déjà présentes,
migrations 112/113). Lecture seule.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `meetingId` (path) | Oui | UUID | — |
| `hostId` (path) | Oui | UUID (poste) | — |
| `imageId` (path) | Oui | dernier segment sûr (lettres/chiffres/`-`/`_`), jamais un chemin | rejet si `..`/`/` |
| `Range` (header) | Non | `bytes=start-[end]` ; ignoré si mal formé | parsing tolérant |
| `download` (query) | Non | `1`/`true` → pièce jointe | — |

Notes :
- Le `Content-Type` de l'audio est **redéduit de l'extension de la clé** (liste close de `MeetingMediaService`), jamais d'un paramètre client.
- Les identifiants d'image sont vérifiés caractère par caractère (anti-traversée), comme `TeamsMomentImageService`.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/audio` | Oui (JWT) | propriétaire du poste + droit Teams |
| GET | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/images` | Oui (JWT) | idem |
| GET | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/images/{imageId}` | Oui (JWT) | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `meetings` | SELECT | résolution `(id, user_id, host_id)` ; lecture de `audio_key` |
| (object storage) | GET / listKeys | `teams-meetings/{userId}/{hostId}/{meetingId}/audio.*` et `…/frames/*` |

### Migration Liquibase

- [ ] Non applicable (lecture seule).

### Composants Angular

- `MeetingDetailPageComponent` — écran de détail (lecteur audio + download + deck + métadonnées).
- `TeamsMeetingService` — ajout des URLs `audioUrl(hostId, meetingId)`, `imageslist`/`imageUrl`.
- `teams-meeting.models.ts` — `TeamsMeeting` complété (`hasAudio`, `audioBytes`, `imageCount`, `endedAt` déjà présent).
- Route `vigie/:hostRef/reunions/:meetingId` (miroir de `sujets/:subjectId`).
- Panneau de capture : la liste des réunions passées route vers le détail.

---

## Plan de test

### Tests unitaires (service)

- [ ] `MeetingMediaService.findAudio` — rend `(contentType, bytes)` pour l'audio existant ; vide sinon.
- [ ] `MeetingMediaService.listFrames`/`findFrame` — rend les ids ; vide pour un id inconnu ; rejette un id non sûr.
- [ ] Isolation : `findAudio`/`findFrame` avec un couple `user_id`/`host_id` étranger → vide.

### Tests d'intégration

- [ ] `GET …/audio` → `200` + `Accept-Ranges` pour le propriétaire.
- [ ] `GET …/audio` avec `Range` → `206` + `Content-Range` + corps partiel.
- [ ] `GET …/audio` avec `Range` hors bornes → `416`.
- [ ] `GET …/audio?download=1` → `Content-Disposition: attachment`.
- [ ] `GET …/audio` sur réunion sans audio → `404`.
- [ ] `GET …/images` → liste ; `GET …/images/{id}` → image.
- [ ] Gardes : `403` sans droit Teams, `404` poste non possédé, `409` poste non activé Vigie.

### Isolation utilisateur

- [ ] Applicable — un compte B ne peut lire ni l'audio ni une image d'une réunion du compte A (404), même en connaissant les UUID.

### Frontend

- [ ] `MeetingDetailPageComponent` — rend le lecteur, le bouton Télécharger (désactivé sans audio) et le deck ; gère l'état d'erreur de chargement.
- [ ] Le service construit les bonnes URLs.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-01/02/03` — Done (artefact, dépôt audio, dépôt images).

### Questions ouvertes impactées

- Aucune (OPEN_QUESTIONS non touché).

---

## Préoccupations transversales

- **Auth / Principal** : nouvelles routes GET **lecture** sous `/api/vigie/hosts/{hostId}/meetings`.
  Composants impactés : `TeamsMeetingController` (mêmes gardes `teamsAccess.requireAccess()` +
  `scopeResolver.requireInVigie`) — les routes de dépôt runner (`RunnerMeetingAudio/ImageController`)
  **ne sont pas touchées**. Aucun changement au `CurrentUser`/`RadarScope`. Non-régression : les
  endpoints create/stop/pause/resume/list/get restent inchangés.
- **Contexte tenant** : aucune nouvelle résolution de tenant ; réutilise `RadarScopeResolver`.
- **Plans / limites** : aucune nouvelle limite (lecture) ; le droit Teams (`TeamsAccessService`) garde
  la production, la lecture suit la possession du poste (cohérent avec la note « relire ≠ produire » de
  `TeamsMomentController`).
- **Navigation / routing** : nouvelle route `vigie/:hostRef/reunions/:meetingId`. Chemins existants
  vérifiés : le panneau `reunions` reste, ajout d'un lien de liste → détail ; retour vers la Vigie.

---

## Notes et décisions

- **Range servi depuis un `byte[]`** : `WorkspaceStorage` ne fournit que `getFile` (octets complets) ;
  on sert la plage via `ResourceRegion`/`ByteArrayResource` (l'audio de réunion est borné à 200 Mo au
  dépôt). Un vrai streaming objet (S3 Range natif) est une évolution, hors périmètre.
- **Deck servi indépendamment** : les images clés vivent sous `…/{meetingId}/frames/` (SF-128-03), donc
  distinctes des moments F-89 (`teams-moments/…`) — on ajoute leur propre lecture, on ne réutilise pas
  `TeamsMomentController`.
- **Provider-First / Gateway-First** : lecture d'objets stockés, aucune capacité IA. La gateway sert ce
  que le runner a produit.

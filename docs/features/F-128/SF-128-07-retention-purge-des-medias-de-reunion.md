# Mini-spec — [F-128 / SF-128-07] Rétention & purge des médias de réunion

> Template : `project-governance/templates/subfeature-template.md`.

---

## Identifiant

`F-128 / SF-128-07`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-07-retention-purge-medias-reunion`

---

## Objectif

> En une phrase : **purger automatiquement les médias lourds** d'une réunion (audio + images du deck)
> **au-delà de `retention_days`** (déjà stocké sur `meetings`, défaut 30 j), via un **worker planifié**,
> tout en **conservant** l'artefact réunion et son transcript ; suppression **manuelle** possible aussi.

---

## Comportement attendu

### Cas nominal

1. **Purge auto** — un worker `@Scheduled` (patron des purges existantes : `RunnerDiagPurgeWorker`
   SF-132-02, `RadarDormancyWorker`) réclame périodiquement (cron nocturne, désactivable par config) les
   réunions **encore porteuses de médias** et **non déjà purgées**, et pour chacune dont
   `startedAt + retention_days` est **dépassé** :
   - supprime les **médias lourds** du stockage objet : `WorkspaceStorage.deletePrefix(prefixOf(userId, hostId, meetingId))`
     (efface `audio.*` **et** `frames/*` — déjà borné/paginé côté S3),
   - remet `audio_key`, `audio_bytes`, `image_count` à vide, et **horodate** `media_purged_at`,
   - **conserve** l'artefact réunion (titre, dates, sujet, `retention_days`) **et le transcript**.
2. **Purge manuelle** — `DELETE /vigie/hosts/{hostId}/meetings/{meetingId}/media` purge **immédiatement**
   les médias de cette réunion (même effet), et rend l'artefact à jour.
3. **Idempotence** — une réunion déjà purgée (`media_purged_at` renseigné, médias vides) n'est **plus**
   candidate ; rejouer le worker ne refait rien.
4. **Bornage** — le worker traite au plus `BATCH` réunions par cycle (le cycle suivant continue).

### Ce qui est gardé vs purgé (tranché)

| Élément | Décision |
|---------|----------|
| **Audio** (objet stockage) | **PURGÉ** au-delà de la rétention (lourd, sensible — sort du poste) |
| **Images du deck** (objets stockage) | **PURGÉES** (lourdes) |
| `audio_key` / `audio_bytes` / `image_count` | remis à vide (pointeurs sans objet) |
| **Artefact réunion** (métadonnées) | **GARDÉ** (titre, URL, dates, sujet, `retention_days`, état) |
| **Transcript** (+ statut/langue) | **GARDÉ** (texte léger, exploitable ; l'audio d'où il vient est parti) |
| `media_purged_at` | **posé** (trace de la purge, rendu à l'écran) |

> Proposé au cadrage F-128 (D3 « base de rétention ») : purger seulement les **médias lourds** ; garder
> l'artefact + transcript pour que la réunion reste **référencable et exploitable** après purge.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Purge manuelle : réunion cross-user / inconnue | Introuvable | 404 |
| Purge manuelle : sans droit Teams | Refus | 403 |
| Purge manuelle : hors Vigie | Refus | 409 |
| Worker : effacement stockage partiel (`WorkspaceStorageDeletionException`) | La réunion **n'est pas** marquée purgée (retentée au cycle suivant) ; le cycle continue | — |
| Worker : erreur inattendue d'un cycle | Journalisée sans détail, ne tue jamais le planificateur | — |

---

## Critères d'acceptation

- [ ] Une réunion dont `startedAt + retention_days < now` **et** porteuse de médias est purgée par le worker : objets stockage effacés (`deletePrefix`), `audio_key`/`audio_bytes`/`image_count` vidés, `media_purged_at` posé.
- [ ] L'**artefact réunion** et le **transcript** sont **conservés** après purge.
- [ ] Une réunion **dans** la fenêtre de rétention n'est **pas** purgée.
- [ ] **Idempotence** : rejouer le worker ne repurge pas une réunion déjà purgée (aucune candidate).
- [ ] **Isolation** : la clé de stockage et la résolution portent `user_id`+`host_id` ; la purge manuelle d'une réunion d'un autre couple → 404.
- [ ] Purge **manuelle** via `DELETE …/media` : même effet, rend l'artefact à jour ; gardes 403/404/409.
- [ ] Le worker est **borné** (batch) et **best-effort** (une erreur n'arrête pas le planificateur) ; **désactivable** par configuration (déterminisme des tests).
- [ ] Migration Liquibase pour `media_purged_at` (additive, réversible).
- [ ] Écran de détail : quand `media_purged_at` est posé, l'écran **dit** que les médias ont été purgés (au lieu de « aucun audio »), charte respectée.

---

## Périmètre

### Hors scope (explicite)

- **Purge à la suppression de compte / de poste** (cascade lifecycle) : follow-up possible (comme SF-132-02
  a laissé la purge compte hors périmètre) ; ici = rétention par le temps + geste manuel.
- **Purge du transcript / de l'artefact** : non — on garde métadonnées + transcript.
- **Vidéo pleine** (option cadrage) : non capturée en v1, rien à purger.
- **Réglage de `retention_days`** : déjà posé à la création (SF-128-01) ; pas d'écran de réglage ici.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `media_purged_at` | `null` | posé (= `now`) au moment de la purge des médias |

Comportements : à la purge, `audio_key=null`, `audio_bytes=null`, `image_count=0`, `media_purged_at=now`.

## Contraintes de validation

| Champ | Obligatoire | Format |
|-------|-------------|--------|
| cutoff | — | `startedAt + retention_days` comparé à `now` (horloge injectable pour les tests) |
| batch worker | — | borné (`PURGE_BATCH`, ex. 200/cycle) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| DELETE | `/vigie/hosts/{hostId}/meetings/{meetingId}/media` | Oui (JWT) | propriétaire + droit Teams + Vigie |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `meetings` | SELECT + UPDATE | candidats à purger ; vidage des pointeurs média + `media_purged_at` |
| *(stockage objet)* | DELETE | `deletePrefix(teams-meetings/{userId}/{hostId}/{meetingId}/)` |

### Migration Liquibase

- [x] Oui — `116-meetings-media-purged-at.xml` (`meetings.media_purged_at` timestamp nullable ; additive, réversible)

### Composants Angular

- `MeetingDetailPageComponent` : affiche l'état « médias purgés le … » quand `mediaPurgedAt` est posé.
- `TeamsMeeting` (modèle) : `mediaPurgedAt: string | null`.

---

## Plan de test

### Tests unitaires (service)

- [ ] `purgeExpired` : une réunion au-delà de la rétention et porteuse de médias → `deletePrefix` appelé, pointeurs vidés, `media_purged_at` posé, transcript conservé.
- [ ] `purgeExpired` : une réunion **dans** la fenêtre → **non** purgée.
- [ ] `purgeExpired` : **idempotence** — une réunion déjà purgée n'est pas candidate (aucun `deletePrefix`).
- [ ] `purgeExpired` : échec `deletePrefix` (`WorkspaceStorageDeletionException`) → réunion **non** marquée purgée, le cycle continue (autres réunions traitées).
- [ ] `purgeMedia` (manuel) : purge une réunion, rend l'artefact à jour.
- [ ] `purgeMedia` : isolation — réunion d'un autre couple → 404 (`MeetingNotFoundException`), aucun `deletePrefix`.

### Tests d'intégration

- [ ] `DELETE …/media` → 200 artefact à jour (`hasAudio=false`, `imageCount=0`, `mediaPurgedAt` renseigné), objets réellement effacés (in-memory storage).
- [ ] Gardes 403 (sans droit Teams) / 409 (hors Vigie) ; **isolation** cross-user (404).
- [ ] Worker (`MeetingRetentionWorker`) : un cycle purge une réunion expirée (via le service).

### Isolation utilisateur

- [ ] Applicable — clés `user_id`+`host_id` ; purge manuelle cross-user → 404.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-02/03` (audio/images stockés) — Done. `SF-128-10` (écran détail) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Auth / Principal** : **1 nouvelle route** `DELETE /vigie/hosts/{hostId}/meetings/{meetingId}/media`.
  Composants impactés : `TeamsMeetingRetentionController` (nouveau, mêmes gardes `teamsAccess.requireAccess()`
  + `scopeResolver.requireInVigie`). Endpoints existants **inchangés** (le worker n'a pas d'auth : tâche
  système bornée par `user_id`+`host_id` de chaque ligne).
- **Contexte tenant** : **aucune nouvelle résolution**. Le worker itère des lignes déjà porteuses de
  `user_id`+`host_id` ; la purge manuelle réutilise `RadarScope` (requireInVigie). Composants de
  résolution tenant inchangés.
- **Plans / limites** : **aucun** appel aux services de limites (pas de jetons, pas de quota) — purge de
  stockage uniquement.
- **Navigation / routing** : **aucune nouvelle route front** ; enrichit l'écran de détail (affichage de
  l'état purgé).

---

## Notes et décisions

- **Worker réutilisé (ne pas réinventer)** : patron `RunnerDiagPurgeWorker` (SF-132-02) — `@Component`
  `@ConditionalOnProperty(name="app.teams.meeting.purge.enabled", matchIfMissing=true)`,
  `@Scheduled(cron="${app.teams.meeting.purge.cron:0 40 3 * * *}")`, coquille mince + try/catch,
  délègue à un service `@Transactional`. Effacement stockage = `WorkspaceStorage.deletePrefix` (déjà
  borné/paginé/isolé, F-79).
- **Cutoff par ligne** : `retention_days` variant par réunion ; le candidat est filtré en base (médias
  présents & non purgés) puis l'échéance `startedAt + retentionDays` est évaluée par ligne (horloge
  injectable). Le set de candidats est **borné** par les réunions à médias vivants (la fenêtre de
  rétention), avec en plus un `PURGE_BATCH` par cycle.
- **Gardé vs purgé** : on purge le **lourd** (audio + images) ; on **garde** l'artefact + le transcript.
  `media_purged_at` trace la purge et l'écran la **dit** (au lieu de « aucun audio », ce qui serait un
  mensonge).
- **V1 gateway pure / Provider-First** : purge de plomberie stockage, aucune capacité IA.

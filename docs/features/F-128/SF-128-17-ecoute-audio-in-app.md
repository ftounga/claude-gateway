# Mini-spec — [F-128 / SF-128-17] Écoute audio in-app réparée (lecteur grisé / 0:00 / 0:00)

> Template : `project-governance/templates/subfeature-template.md`.
> Document validé AVANT le dev.

---

## Identifiant

`F-128 / SF-128-17`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-17-ecoute-audio-in-app`

---

## Objectif

> En une phrase : réparer l'**écoute in-app** de l'audio d'une réunion capturée — aujourd'hui le
> lecteur affiche `0:00 / 0:00` et ne joue pas, alors que le fichier téléchargé s'écoute
> correctement.

---

## Comportement attendu

### Cas nominal

Constat PO (Test 9, audio 620 Ko valide) : sur l'écran de détail de réunion
(`MeetingDetailPageComponent`, SF-128-10), le `<audio>` reste à `0:00 / 0:00` et ne lit pas ; seul
« Télécharger l'audio » fonctionne.

Diagnostic (vérification de l'existant SF-128-10) :

1. **Backend — Content-Type déjà correct.** `MeetingMediaService.findAudio` redéduit le type de
   l'extension de la clé (`AUDIO_TYPE_BY_EXT`, défaut `audio/webm`) ; `TeamsMeetingMediaController`
   sert cet en-tête avec `Accept-Ranges: bytes` et le support du `Range` (206). **Aucun bug
   Content-Type côté serveur.** On verrouille ce comportement par un test (non-régression) qui
   affirme `Content-Type: audio/webm`.
2. **Cause racine — métadonnées de durée absentes.** Le webm produit par `MediaRecorder` n'écrit
   **pas** l'élément *Duration* dans l'en-tête (flux « live »). Chargé dans un `<audio>` via un
   object URL (`URL.createObjectURL`), Chrome lit alors `duration = Infinity` (ou `NaN`), affiche
   `0:00 / 0:00`, désactive la barre de progression et **refuse de lancer/seek** — le lecteur paraît
   « grisé ». Le téléchargement, lui, n'a pas besoin de décoder ni de connaître la durée : il marche.
3. **Fix front (robuste).**
   - **Ré-typage défensif du Blob** : le Blob alimentant le `<audio>` est (re)créé avec un type
     audio décodable (`audio/webm` par défaut, ou le type audio déjà porté par le Blob s'il est
     valide) — garde contre un Blob au type vide / `application/octet-stream`.
   - **Réparation de durée** : à `loadedmetadata`, si `duration` vaut `Infinity`/`NaN`, on force
     Chrome à calculer la vraie durée par le *seek-to-end* usuel (`currentTime = 1e101`, puis retour
     à `0` au premier `timeupdate`). Après quoi la durée s'affiche et la **lecture** fonctionne
     in-app.
4. **Objectif mesurable** : sur une réunion capturée, le lecteur **joue l'audio in-app** (pas
   seulement le téléchargement). Si la durée reste inconnue malgré la réparation, on **tolère**
   l'affichage `0:00` mais la lecture doit démarrer.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réunion d'un autre couple `user_id`/`host_id` | Introuvable (inchangé SF-128-10) | 404 |
| Réunion sans audio (`audio_key` null) | Pas d'audio à servir (inchangé) | 404 |
| Chargement du Blob en échec réseau | Message d'erreur, pas de lecteur cassé (inchangé) | — |
| `duration` reste `Infinity` après réparation | Lecture tolérée, durée `0:00` affichée sans blocage | — |

---

## Critères d'acceptation

- [ ] `GET …/audio` sert `Content-Type: audio/webm` (test d'intégration de non-régression).
- [ ] Le Blob alimentant le `<audio>` porte un type audio décodable (`audio/webm` si le type reçu
      est vide ou non-audio) — testé côté composant.
- [ ] À `loadedmetadata` avec `duration = Infinity`, le composant déclenche la réparation de durée
      (seek en fin puis retour à 0) — testé sur un élément audio simulé.
- [ ] La source du `<audio>` est un object URL non vide issu d'un Blob typé audio (pas de source
      0-length) — testé côté composant.
- [ ] Non-régression : « Télécharger l'audio » fonctionne toujours (test existant conservé et vert).
- [ ] Aucune couleur/police hors `docs/DESIGN_SYSTEM.md` (aucun changement visuel de charte).
- [ ] Isolation `user_id` + `host_id` inchangée (aucune route touchée côté accès).

---

## Périmètre

### Hors scope (explicite)

- **Remux / réécriture du conteneur webm à l'upload** (côté runner/backend) : traitement lourd,
  écarté ici. La réparation se fait côté navigateur (léger, suffisant). Documenté en « Notes ».
- **Streaming objet natif S3 Range** : hors périmètre (déjà noté SF-128-10).
- **Transcription / exploitation / deck** : inchangés.
- **Nouvelle route ou nouveau champ** : aucun.

---

## Valeurs initiales

Aucune entité créée. Aucune migration. Lecture seule (comportement front + un test backend de
non-régression sur l'en-tête).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| type du Blob audio (front) | Oui | commence par `audio/` ; sinon forcé à `audio/webm` | fallback `audio/webm` |
| `Content-Type` servi (back) | Oui | `audio/webm` (défaut liste close) | redéduit de l'extension |

Notes :
- Le *seek-to-end* n'est déclenché **qu'une fois** par chargement (garde `durationRepairDone`).

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. `GET /api/vigie/hosts/{hostId}/meetings/{meetingId}/audio` inchangé
(verrouillé par un test de non-régression sur le Content-Type).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| (aucune) | — | lecture seule, aucun schéma modifié |

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- `MeetingDetailPageComponent` — ré-typage du Blob audio + handler `loadedmetadata` de réparation de
  durée (seek-to-end). Aucun changement de layout / charte.

---

## Plan de test

### Tests unitaires (composant)

- [ ] Le Blob passé à `URL.createObjectURL` porte un type audio (`audio/webm`) quand le service rend
      un Blob de type vide.
- [ ] Un Blob déjà typé `audio/webm` est conservé tel quel.
- [ ] `onAudioMetadata` sur un élément simulé avec `duration = Infinity` déclenche le seek en fin et
      réinitialise `currentTime` à 0 au premier `timeupdate` (réparation appelée une seule fois).
- [ ] `onAudioMetadata` avec une `duration` finie ne modifie pas `currentTime`.

### Tests d'intégration (backend, non-régression)

- [ ] `GET …/audio` (propriétaire, audio présent) → `200` + `Content-Type: audio/webm` +
      `Accept-Ranges: bytes`.

### Isolation utilisateur

- [x] Non applicable directement (aucune nouvelle route / accès) — l'isolation `user_id`+`host_id`
      de SF-128-10 est inchangée et reste couverte par ses tests existants.

### Frontend (non-régression)

- [ ] Le lecteur et le bouton « Télécharger » restent rendus ; le téléchargement fonctionne.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-10` — Done (route audio + écran de détail).
- `SF-128-02` — Done (dépôt de l'audio).

### Questions ouvertes impactées

- Aucune (OPEN_QUESTIONS non touché).

---

## Préoccupations transversales

- **Auth / Principal** : aucune (aucune route touchée ; le GET audio et ses gardes SF-128-10 restent
  inchangés).
- **Contexte tenant** : aucun changement de résolution ; isolation `user_id`+`host_id` intacte.
- **Plans / limites** : sans objet.
- **Navigation / routing** : aucun changement de route ; même écran de détail.

---

## Notes et décisions

- **Pourquoi la réparation côté front et non un remux backend** : le webm de `MediaRecorder` sans
  Duration est un cas connu ; la réparation navigateur (seek-to-end) est légère, sans traitement
  lourd, et respecte Gateway-First (la gateway sert les octets, elle ne ré-encode pas). Un remux
  serveur serait un traitement lourd asynchrone injustifié pour ce seul affichage.
- **Content-Type** : confirmé déjà correct côté serveur (`audio/webm`) ; on ajoute un test qui le
  verrouille pour éviter toute régression future (ex. passage à `application/octet-stream`).
- **Ré-typage du Blob** : filet défensif contre un environnement (proxy, futur changement) qui
  livrerait un type non audio ; sans coût si le type est déjà bon.

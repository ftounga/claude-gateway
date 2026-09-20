# Mini-spec — [F-128 / SF-128-19] Lecture audio in-app fiable (webm sans durée)

> Template : `project-governance/templates/subfeature-template.md`.
> Document validé AVANT le dev.

---

## Identifiant

`F-128 / SF-128-19`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`ready`

## Date de création

2026-09-20

## Branche Git

`feat/SF-128-19-lecture-audio-webm-sans-duree`

---

## Objectif

> En une phrase : rendre **réellement fiable** l'écoute in-app de l'audio d'une réunion (webm
> `MediaRecorder` sans en-tête *Duration*), car le mécanisme de SF-128-17 laisse toujours le lecteur
> à `0:00 / 0:00` et refuse de jouer, alors que le téléchargement du même fichier fonctionne.

---

## Comportement attendu

### Cas nominal

Sur l'écran de détail de réunion (`MeetingDetailPageComponent`, SF-128-10), le `<audio>` est alimenté
par un object URL issu d'un Blob **typé `audio/webm`**. À `loadedmetadata`, si la durée est inconnue
(`Infinity`/`NaN` — cas systématique d'un webm `MediaRecorder`), le composant force le navigateur à
calculer la vraie durée par le **seek-to-end** (`currentTime = 1e101`), **attend que la durée
devienne finie** (événement `durationchange`, avec `timeupdate` en repli), **puis seulement** remet
`currentTime = 0` et nettoie les écouteurs. Après quoi la durée s'affiche et la lecture / le seek
fonctionnent (Chrome + Safari). Le bouton « Télécharger l'audio » continue de marcher.

### Diagnostic — pourquoi SF-128-17 ne corrige pas

Le handler `onAudioMetadata` de SF-128-17 remet `currentTime = 0` **au premier `timeupdate`**, sans
vérifier que la durée est redevenue finie. Or, pendant le seek forcé vers `1e101`, Chrome émet des
`timeupdate` **alors que `duration` vaut encore `Infinity`** : le retour immédiat à `0` **annule le
seek** avant que le navigateur ait recalculé la durée. Résultat : `duration` reste `Infinity`, le
lecteur reste à `0:00 / 0:00`. De plus, le handler n'écoute **pas** `durationchange` — l'événement
qui signale précisément la durée recalculée. Le fix garde le déclencheur (grand seek) mais **borne
le retour à 0 à une durée finie**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réunion d'un autre couple `user_id`/`host_id` | Introuvable (inchangé SF-128-10) | 404 |
| Réunion sans audio (`audio_key` null) | Pas d'audio à servir (inchangé) | 404 |
| Chargement du Blob en échec réseau | Message d'erreur, pas de lecteur cassé (inchangé) | — |
| `duration` reste `Infinity` malgré la réparation | Lecture tolérée, durée `0:00` affichée sans blocage ; aucun blocage JS | — |
| Blob reçu au type vide / `application/octet-stream` | Re-typé `audio/webm` avant `createObjectURL` (inchangé SF-128-17) | — |

---

## Critères d'acceptation

- [ ] Le Blob alimentant le `<audio>` porte un type audio décodable : `audio/webm` si le type reçu
      est vide ou non-audio, sinon le type audio déjà porté par le Blob — testé côté composant.
- [ ] À `loadedmetadata` avec `duration = Infinity`, le composant déclenche le seek-to-end
      (`currentTime = 1e101`) et s'abonne à `durationchange` **et** `timeupdate` — testé sur élément
      audio simulé.
- [ ] Tant que `duration` reste non finie, le composant **ne remet pas** `currentTime` à 0 (le seek
      n'est pas annulé prématurément) — testé sur élément audio simulé.
- [ ] Dès que `duration` devient finie (via `durationchange` **ou** `timeupdate`), le composant remet
      `currentTime = 0` et retire ses deux écouteurs — testé sur élément audio simulé.
- [ ] La réparation ne se joue qu'**une fois** par chargement (garde `durationRepairDone`) et ne
      touche à rien si la durée est déjà connue (finie) à `loadedmetadata` — testé.
- [ ] Non-régression : « Télécharger l'audio » fonctionne toujours (test existant conservé et vert).
- [ ] Non-régression : `GET …/audio` sert `Content-Type: audio/webm` (test backend existant conservé
      et vert — aucun changement backend).
- [ ] Aucune couleur/police hors `docs/DESIGN_SYSTEM.md` (aucun changement visuel de charte).
- [ ] Isolation `user_id` + `host_id` inchangée (aucune route touchée).

---

## Périmètre

### Hors scope (explicite)

- **Remux / réécriture du conteneur webm** (runner/backend) : traitement lourd, écarté (cf.
  SF-128-17). La réparation reste côté navigateur.
- **Backend** : aucun changement. Le Content-Type servi est déjà `audio/webm` (redéduit de
  l'extension par `MeetingMediaService.findAudio`, verrouillé par un test d'intégration SF-128-17).
- **Streaming objet natif S3 Range**, transcription, exploitation, deck : inchangés.
- **Nouvelle route ou nouveau champ** : aucun.

---

## Valeurs initiales

Aucune entité créée. Aucune migration. Lecture seule (comportement front uniquement).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| type du Blob audio (front) | Oui | commence par `audio/` ; sinon forcé à `audio/webm` | fallback `audio/webm` |
| retour de tête à 0 (front) | Oui | uniquement quand `Number.isFinite(duration)` | — |

Notes :
- Le seek-to-end n'est déclenché **qu'une fois** par chargement (garde `durationRepairDone`).
- `durationchange` est l'événement primaire ; `timeupdate` reste un repli (mêmes gardes de finitude).

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. `GET /api/vigie/hosts/{hostId}/meetings/{meetingId}/audio` inchangé.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| (aucune) | — | lecture seule, aucun schéma modifié |

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `MeetingDetailPageComponent` — `onAudioMetadata()` robustifié (seek-to-end borné à durée finie ;
  écoute `durationchange` + `timeupdate`) ; `ensureAudioType()` conservé.

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|-----------|
| Auth / Principal | Non | aucune route, aucun garde touché |
| Contexte tenant | Non | isolation `user_id`+`host_id` inchangée (aucun accès données modifié) |
| Plans / limites | Non | — |
| Navigation / routing | Non | aucune route ajoutée/modifiée ; l'écran de détail existant est inchangé |

---

## Plan de test

### Tests unitaires (composant, Karma/Jasmine)

- [ ] `ensureAudioType` : Blob sans type → `createObjectURL` reçoit un Blob `audio/webm` (test
      existant conservé).
- [ ] `onAudioMetadata` avec `duration = Infinity` : `currentTime` passé à `1e101`, un écouteur
      `durationchange` **et** un `timeupdate` posés.
- [ ] `onAudioMetadata` : un `timeupdate` reçu **alors que la durée est encore `Infinity`** ne remet
      **pas** `currentTime` à 0 (seek non annulé).
- [ ] `onAudioMetadata` : quand `durationchange` arrive avec une durée **finie**, `currentTime`
      revient à 0 et les deux écouteurs sont retirés.
- [ ] `onAudioMetadata` : repli — quand seul `timeupdate` arrive avec une durée finie, retour à 0.
- [ ] `onAudioMetadata` avec durée déjà finie : `currentTime` intact, aucun écouteur posé.

### Tests d'intégration

- [ ] (Backend, non-régression, existant) `GET …/audio` → `Content-Type: audio/webm`.
- [ ] (Composant) la source du `<audio>` est un object URL `blob:` non vide (test existant conservé).

### Isolation utilisateur

- [ ] Non applicable directement (aucun accès données modifié) — l'isolation `user_id`+`host_id` des
      routes média (SF-128-10) reste couverte par ses tests existants, non touchés.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-10` — accès audio (écoute/téléchargement) — Done.
- `SF-128-17` — écoute audio in-app (1ère tentative) — Done (corrigée ici).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Décision** : le retour de tête à 0 est **borné à une durée finie**. C'est la cause racine du
  non-fonctionnement de SF-128-17 (retour à 0 au premier `timeupdate`, durée encore `Infinity`,
  seek annulé). On écoute `durationchange` (primaire) et `timeupdate` (repli), mêmes gardes.
- **Décision** : on conserve `audio/webm` (et non `audio/webm;codecs=opus`) comme type forcé.
  `audio/webm` suffit au décodage par le navigateur ; ajouter un `codecs=` qui ne correspondrait pas
  exactement aux octets stockés risquerait de faire échouer le décodage — plus de risque que de gain.
- **À VALIDER SUR NAVIGATEUR RÉEL** : le comportement `<audio>`/webm dépend du navigateur et n'est
  pas testable en CI headless. Les tests couvrent la **logique** (détection `Infinity`/`NaN`,
  déclenchement du seek, retour à 0 borné à durée finie, typage du Blob). Le PO re-teste l'écoute
  réelle (Test 9).

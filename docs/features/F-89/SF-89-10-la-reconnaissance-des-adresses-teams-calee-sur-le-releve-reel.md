# Mini-spec — [F-89 / SF-89-10] La reconnaissance des adresses Teams, calée sur le relevé réel

> Base : `project-governance/templates/subfeature-template.md`

---

## Identifiant

`F-89 / SF-89-10`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-89-10-adresses-teams-releve`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Marquer `IGNORED` (au lieu d'`UNKNOWN`) les familles de bruit — télémétrie, présence, auth, config,
enregistrement/abonnement, éditeur assisté, coquille d'app — vues dans le relevé réel du 2026-09-15,
pour que le diagnostic `NOTHING_CLASSIFIED` ne se déclenche pas à tort et que `topUnknownPaths` ne soit
pas pollué, sans toucher aux familles de contenu déjà classées.

---

## Comportement attendu

### Cas nominal

> Entrée → traitement → sortie.

`TeamsUrls.classify(url)` reçoit l'URL complète (hôte compris) d'une réponse observée. Une adresse
appartenant à une famille de bruit du relevé est reconnue comme telle et rend `IGNORED`. En
conséquence, dans `NetworkObserver.count(...)`, elle est comptée dans `ignored` et **non** dans
`unknownMicrosoft` ni dans l'inventaire `unknownPaths` — donc `nothingKind(...)` ne bascule plus vers
`NOTHING_CLASSIFIED` (« le runner doit apprendre ces chemins ») pour du bruit, et `topUnknownPaths`
ne liste plus ces familles.

La reconnaissance porte **sur l'hôte ou le chemin**, jamais sur la requête, jamais sur le corps
(invariant F-87/F-89 inchangé).

Familles marquées `IGNORED` (chaque famille = une ligne de règle dans `TeamsUrls.IGNORE_RULES` +
un exemple du relevé dans `TeamsUrlsTest`) :

- Télémétrie OneCollector : `*.events.data.microsoft.com` ; télémétrie aria (déjà) ; `/telemetry`,
  `/beacon`, `/loggingservice`, `/poll` (déjà).
- Présence : `/presence` (déjà — vérifié, couvre `/ups/emea/v1/presence/*`).
- Éditeur / rédaction assistée : `augloop.office.com` (et `*.augloop.office.com`),
  `editor.svc.cloud.microsoft` (NLEditor).
- Config : `config.teams.microsoft.com`.
- Enregistrement / abonnement : `/ups/emea/v1/pubsub/*`, `/ups/emea/v1/me/endpoints`,
  `/registrar/prod/*`.
- Handshake trouter HTTP : `*.trouter.teams.microsoft.com` (les **sockets** trouter `/v4/c` restent
  comptées à part, hors `classify` — non touchées).
- Auth : `/skypetokenauth`, `/aadtokenauth`, `/api/authsvc/*`, `/trap/tokens`.
- Coquille de l'application : le document `/v2`, `/v2/manifest.json`.
- Surfaces suite / knowledge : `webshell.suite.office.com`, `loki.delve.office.com`,
  `substrate.office.com/userknowledgebase/*`, `admin.microsoft.com`.
- Réglages / listes non-contenu : `/batchedDefinitions`, `/userSettings*`, `/engagementSurfaces`,
  `/usage`, `/discover*`, `/pinnedChannels`, `/settings/meetingConfiguration`,
  `/api/csa/emea/api/v*/teams/users/*/updates`.
- Infra du lecteur SharePoint : `_layouts/15/SPComponentRegistry.ashx`,
  `_layouts/15/spwebworkerproxy.ashx`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| URL nulle / vide | `UNKNOWN` (inchangé) |
| Adresse hors famille Microsoft ou chemin non reconnu | `UNKNOWN` (on ne lit pas le corps) |
| Adresse ambiguë (bruit non manifeste) | reste `UNKNOWN` — jamais `IGNORED` par excès de zèle |
| `/…/conversations/updates` (csa) | reste `CONVERSATION_LIST` — l'ignore de `/updates` exclut `/conversations` |
| `/…/users/{id}/profile` \| `/properties` | reste `PROFILE` — pas d'ignore générique de `/users/` |

---

## Critères d'acceptation

> Chaque critère est vérifiable et reviewé dans la PR.

- [ ] Chaque famille de bruit listée ci-dessus, citée par un exemple exact du relevé du 2026-09-15,
      est classée `IGNORED` par `classify(url)`.
- [ ] `_layouts/15/SPComponentRegistry.ashx` et `spwebworkerproxy.ashx` sont classés `IGNORED`.
- [ ] Non-régression : `CONVERSATION_MESSAGES`, `CONVERSATION_LIST` (dont `/conversations/updates`
      csa), `CALENDAR_EVENT`, `MEETING_DETAILS`, `MEETING_COLLAB_OBJECT`, `PROFILE`,
      `MEETING_TRANSCRIPT`, `ACTIVITY_FEED`, `SEARCH_RESULTS` restent classés comme avant.
- [ ] Les endpoints fichiers F-108 (`_api/v2.1/drives/{id}/items/{id}` et `/content`) ne sont pas
      cassés : ils restent `UNKNOWN` (traités par les règles fichiers), pas `IGNORED`.
- [ ] Chaque règle d'ignore a **son** exemple de test, et chaque exemple est bien classé par cette
      règle (pas de règle sans ligne de test, sur le modèle de `each_rule_has_its_line`).
- [ ] Ce qui reste volontairement `UNKNOWN` (bruit non manifeste) est nommé dans la mini-spec et la PR.
- [ ] `cd runner && ./mvnw -q test` est vert.

---

## Périmètre

### Hors scope (explicite)

- Toute règle de **contenu** nouvelle : la transcription (étape 4) ne produit **aucune** réponse
  réseau de contenu (seulement l'infra SharePoint et le flux vidéo dash+xml) → elle reste lue par
  l'écran (SF-89-06). On n'invente **pas** de règle de transcription réseau.
- Lecture du corps de `readcollabobject` (MEETING_COLLAB_OBJECT) : le relevé ne porte **aucun corps**
  (« ni corps de réponse »). Aucune forme modèle n'est connue → la lire serait l'inventer. **Non fait**
  (voir Risques). Reste `nommé, jamais lu`.
- Les **sockets** trouter `/v4/c` : messages temps réel comptés, jamais lus, hors périmètre.
- L'ignore générique de `/api/mt/emea/beta/users/*` (le relevé le mentionne largement) : **non retenu**
  — il masquerait `PROFILE`. Seuls les feuillets manifestement bruités (`/batchedDefinitions`,
  `/usage`, etc.) sont ignorés ; le bootstrap `/users/{id}` et `/cookiev2` restent `UNKNOWN` par
  prudence.

---

## Technique

### Endpoint(s)

Aucun. Modification interne au runner (`fr.claudegateway.runner.teams`).

### Tables impactées

Aucune. **Aucune migration.**

### Composants impactés

- `runner/.../teams/TeamsUrls.java` — table `IGNORE_RULES` ordonnée + `isIgnorable` réécrit sur cette
  table ; classifieur inchangé pour le reste.
- `runner/.../teams/TeamsUrlsTest.java` — table `IGNORE_EXAMPLES` (règle ↔ exemple du relevé) + tests
  de non-régression.
- `docs/features/F-89/constats/…` (doctrine, point 4) + `docs/PRODUCT_SPEC.md` (étape 6).

Backend applicatif (`backend/`) : **non touché** — `TeamsPayloadKind`/catalogue inchangés (aucune
nature ajoutée ; seules des adresses basculent d'`UNKNOWN` vers `IGNORED`).

### Préoccupations transversales

- Auth / Principal : **non** — pas d'authentification, fonction pure.
- Contexte tenant / `user_id` : **non** — `classify` ne lit qu'un chemin d'URL, aucune donnée tenant ;
  aucune isolation à vérifier (aucun accès données). La reconnaissance porte sur un **motif** commun à
  tous les tenants, jamais sur un nom (invariant existant).
- Plans / limites : **non**. Navigation / routing : **non**.

---

## Plan de test

### Tests unitaires (`TeamsUrlsTest`)

- [ ] Chaque règle d'`IGNORE_RULES` a un exemple du relevé classé `IGNORED` (paramétré).
- [ ] Chaque règle d'ignore est celle qui classe son exemple (pas de chevauchement masquant).
- [ ] `SPComponentRegistry.ashx` / `spwebworkerproxy.ashx` → `IGNORED`.
- [ ] Non-régression : les 5 familles protégées + les autres natures restent classées (dont
      `/conversations/updates` csa → `CONVERSATION_LIST`, `/users/{id}/profile` → `PROFILE`).
- [ ] Endpoints fichiers F-108 (`items/{id}`, `/content`) → `UNKNOWN` (non `IGNORED`).
- [ ] URL nulle/vide/étrangère → `UNKNOWN`.

### Tests d'intégration

Sans objet (pas d'endpoint). La vérité terrain vient du relevé réel et de la sonde de santé.

### Isolation workspace / `user_id`

Non applicable — aucun accès aux données, fonction de classement d'URL sans état ni tenant.

---

## Dépendances

- SF-89-08 (table de règles ordonnée) — Done.
- SF-100-00 (relevé réel du 2026-09-15) — Done (source de vérité).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- La lecture du corps `readcollabobject` est **écartée faute d'échantillon** : le relevé n'expose
  aucun corps ; deviner une forme rendrait un récapitulatif à moitié faux. Documenté en risque.
- Prudence de classement : en cas de doute, `UNKNOWN` plutôt qu'`IGNORED` (un `IGNORED` de trop
  masquerait une future capacité).

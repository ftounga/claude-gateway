# Mini-spec — F-87 / SF-87-01 — Le modèle du domaine Teams et l'adaptateur unique

## Identifiant

`F-87 / SF-87-01`

## Feature parente

`F-87` — Le volet Teams : la liaison

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-87-01-modele-domaine-adaptateur`

---

## Objectif

Poser, **avant toute connexion**, les objets **à nous** qui décrivent Teams (`Message`,
`Conversation`, `Réunion`, `Mention`, `Participant`, et **ce qui n'a pas pu être lu**) et la
**couche unique** qui traduit le JSON observé vers ces objets — le principe `AIProvider` appliqué à
Teams.

---

## Pourquoi cette subfeature d'abord, et pourquoi elle est la plus structurante

F-88 (les outils de lecture) et F-89 (le terminal Teams) démarrent **en parallèle** de F-87 : l'une
remplira ces objets, l'autre les affichera, et **ni l'une ni l'autre ne pourra demander qu'on les
change**. Le modèle est donc livré **complet** ici — y compris les champs que F-87 n'utilise pas
elle-même (transcription, réunion) — plutôt que « au plus juste ». C'est l'arbitrage A1.

---

## Comportement attendu

### Cas nominal

1. Un corps de réponse JSON observé sur le réseau (SF-87-02) est présenté à l'adaptateur avec
   l'**URL** qui l'a porté.
2. `TeamsUrls.classify(url)` rend un `TeamsPayloadKind` :
   `CONVERSATION_MESSAGES`, `CONVERSATION_LIST`, `ACTIVITY_FEED`, `SEARCH_RESULTS`,
   `MEETING_DETAILS`, `MEETING_TRANSCRIPT`, `PROFILE`, `IGNORED` (statique, télémétrie, présence)
   ou `UNKNOWN`.
3. L'adaptateur lit **uniquement les champs qu'il déclare connaître** et rend une
   `TeamsReading<T>` : les objets reconnus, **les manques**, la **fenêtre réellement lue**, et la
   **santé** de la lecture.
4. Rien d'autre dans le produit ne connaît un nom de champ Microsoft ni une URL Teams.

### La règle qui prime : échouer bruyamment, jamais à moitié faux

| Situation à la lecture d'un message | Comportement |
|---|---|
| Tous les champs **obligatoires** lus (`id`, auteur, horodatage, contenu) | le message est rendu |
| Un champ obligatoire manquant ou illisible | **aucun message partiel n'est rendu** — une entrée `TeamsGap` `MISSING_FIELD` le dit |
| Champ **facultatif** absent (mentions, pièces jointes, réactions) | le message est rendu, la liste est vide, **aucun** manque déclaré |
| Genre de message inconnu (`messagetype` non cartographié) | non rendu + `TeamsGap` `UNKNOWN_MESSAGE_KIND` |
| Corps entier non reconnu | liste vide + `TeamsGap` `UNRECOGNIZED_PAYLOAD` + santé `NONE` |

Une lecture dit **toujours** ce qu'elle vaut : `TeamsReading.summary()` produit la phrase du
cadrage — « 47 messages lus, 3 non reconnus, du 5 au 12 septembre ».

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Corps JSON illisible (tronqué, non-JSON) | `TeamsReading` vide + manque `UNRECOGNIZED_PAYLOAD`, **jamais** d'exception qui remonte |
| URL inconnue | `UNKNOWN` ; le corps n'est **pas** lu (on n'invente pas un format) |
| Horodatage non analysable | message non rendu + manque `MISSING_FIELD` nommant le champ d'horodatage |
| Corps porteur d'un champ secret (`skypetoken`, `access_token`, `Set-Cookie`, `authorization`) | le champ **n'est jamais recopié** ; le test de sécurité le prouve, et aucun manque n'est créé (ce n'est pas un défaut de lecture) |
| Pagination annoncée mais lien absent | les objets lus sont rendus + manque `PAGINATION_STOPPED` |

---

## Le modèle du domaine (contrat pour F-88 et F-89)

| Objet | Champs |
|---|---|
| `TeamsParticipant` | `id` (MRI opaque), `displayName`, `email` (peut être `null`), `self` |
| `TeamsMention` | `targetId`, `targetDisplayName`, `kind` (`PERSON`, `TAG`, `CHANNEL`, `EVERYONE`), `text` |
| `TeamsAttachmentRef` | `id`, `name`, `contentType`, `sizeBytes` (`-1` si inconnu), `sourceUrl` — **référence, jamais le contenu** |
| `TeamsReaction` | `kind`, `count` |
| `TeamsMessage` | `id`, `conversationId`, `parentId` (fil), `author`, `sentAt`, `editedAt`, `deleted`, `kind`, `text` (texte plat), `html`, `mentions`, `attachments`, `reactions`, `webUrl` |
| `TeamsMessageKind` | `TEXT`, `RICH_TEXT`, `SYSTEM_EVENT`, `MEETING_EVENT`, `CARD`, `CALL` |
| `TeamsConversation` | `id`, `kind` (`ONE_ON_ONE`, `GROUP`, `CHANNEL`, `MEETING_CHAT`), `topic`, `participants`, `lastActivityAt`, `webUrl` |
| `TeamsMeeting` | `id`, `subject`, `startedAt`, `endedAt`, `organizerId`, `participants`, `conversationId`, `recorded`, `transcriptAvailable`, `webUrl` |
| `TeamsTranscriptCue` | `at`, `durationMs`, `speakerId`, `speakerDisplayName`, `text` |
| `TeamsReadWindow` | `requestedFrom`, `requestedTo`, `actualFrom`, `actualTo`, `cap`, `capReached`, `reachedStartOfConversation` — **D4 : le résultat porte toujours la fenêtre RÉELLEMENT lue** |
| `TeamsGap` | `kind`, `where`, `detail`, `count` |
| `TeamsGapKind` | `UNRECOGNIZED_PAYLOAD`, `UNKNOWN_MESSAGE_KIND`, `MISSING_FIELD`, `PAGINATION_STOPPED`, `SCROLL_EXHAUSTED`, `BODY_UNAVAILABLE`, `CAP_REACHED` |
| `TeamsHealth` | `verdict` (`FULL`, `PARTIAL`, `NONE`), `recognizedFields`, `expectedFields`, `missingFields`, `observedApiVersions`, `detail` |
| `TeamsReading<T>` | `items`, `gaps`, `window`, `health` + `complete()` + `summary()` |

---

## Critères d'acceptation

- [ ] Les objets du domaine ci-dessus existent, sont **immuables** (records), et ne portent **aucun**
      nom de champ Microsoft.
- [ ] `TeamsUrls.classify` reconnaît les sept familles d'URL et rend `UNKNOWN` pour le reste.
- [ ] Un message auquel manque un champ obligatoire **n'est jamais rendu** ; il produit un manque.
- [ ] `TeamsReading.summary()` écrit la phrase « N lus, M non reconnus, du … au … ».
- [ ] `TeamsReading.complete()` est faux dès qu'un manque existe.
- [ ] Un corps de réponse **enrichi de champs secrets** ne laisse **aucune** de ces chaînes dans la
      sortie sérialisée de l'adaptateur (test de sécurité).
- [ ] **Aucun fichier du runner hors du paquet `teams`** ne contient d'URL Teams ni de nom de champ
      Microsoft (test d'architecture).
- [ ] Les échantillons portent un fichier de **provenance** disant qu'ils sont **fabriqués** et
      pourquoi.

---

## Périmètre

### Hors scope (explicite)

- Toute connexion réseau, tout CDP, tout défilement (SF-87-02).
- La sonde de santé jouée au rattachement et l'indicateur (SF-87-03).
- Les outils donnés à l'agent (F-88) et leur affichage (F-89).
- Écrire dans Teams — jamais.

---

## Contraintes de validation

| Champ | Obligatoire | Règle | Normalisation |
|---|---|---|---|
| `TeamsMessage.id` | Oui | non vide | `strip()` |
| `TeamsMessage.author` | Oui | `TeamsParticipant` avec `id` non vide | — |
| `TeamsMessage.sentAt` | Oui | ISO-8601 analysable en `Instant` | UTC |
| `TeamsMessage.text` | Oui | peut être vide **si** une pièce jointe existe | HTML → texte plat |
| `TeamsMessage.html` | Non | ≤ 64 Kio, coupé au-delà | — |
| `TeamsConversation.id` | Oui | non vide | `strip()` |
| `TeamsReadWindow.cap` | Oui | > 0 ; défaut **7 jours ou 500 messages** (D4) | — |
| `TeamsGap.count` | Oui | ≥ 1 | — |

**Plafond par défaut (D4)** : `TeamsReadWindow.DEFAULT_DAYS = 7`,
`TeamsReadWindow.DEFAULT_MAX_MESSAGES = 500`. Annoncé, négociable dans la demande (F-88), jamais
silencieux.

---

## Technique

### Endpoints / tables

Aucun. Subfeature **entièrement** côté runner, sans état persistant.

### Fichiers

`runner/src/main/java/fr/claudegateway/runner/teams/` — domaine public, `TeamsAdapter` public ;
`TeamsAdapterV1`, `TeamsUrls`, `TeamsJson` **package-private** : c'est le compilateur qui tient la
règle « un seul endroit sait ce qu'est Teams ».

`runner/src/test/resources/teams/` — échantillons + `PROVENANCE.md`.

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires

- [ ] `TeamsUrlsTest` — les sept familles + `UNKNOWN` + `IGNORED`.
- [ ] `TeamsAdapterV1Test` — page nominale : messages, auteurs, horodatages UTC, mentions, pièces
      jointes, fenêtre réellement lue.
- [ ] `TeamsAdapterV1Test` — page partielle : le message amputé **n'est pas rendu**, un manque le dit.
- [ ] `TeamsAdapterV1Test` — corps inconnu : zéro objet, santé `NONE`, manque `UNRECOGNIZED_PAYLOAD`.
- [ ] `TeamsAdapterV1Test` — corps illisible : aucune exception.
- [ ] `TeamsReadingTest` — `summary()` et `complete()`.
- [ ] `TeamsReadWindowTest` — plafond atteint → `capReached` + fenêtre réelle ≠ fenêtre demandée.
- [ ] `AucunSecretNeSortTest` — corps empoisonné : aucune chaîne secrète en sortie.
- [ ] `AdaptateurUniqueTest` — aucun fichier hors `teams/` ne cite Teams.

### Tests d'intégration

Sans objet (aucun endpoint). Le branchement réel est la responsabilité de la **sonde de santé**
(SF-87-03) le jour du premier Teams.

### Isolation utilisateur

- [x] Non applicable — aucune donnée n'est persistée ni exposée par une API ici. L'isolation reste
      celle du runner : il ne parle qu'à **son** poste appairé.

---

## Dépendances

Aucune subfeature bloquante. Aucune question ouverte de `docs/OPEN_QUESTIONS.md` impactée.

---

## Préoccupations transversales

Aucune des quatre (auth/Principal, contexte tenant, plans/limites, navigation) n'est touchée : cette
subfeature n'ajoute ni endpoint, ni route, ni gate. Composants impactés : **le seul paquet
`runner/.../teams`**, nouveau.

---

## Notes et décisions

**A1 — Modèle complet plutôt qu'au plus juste.** F-88 et F-89 avancent en parallèle et ne pourront
pas demander de changement : `TeamsMeeting`, `TeamsTranscriptCue` et `TeamsReaction` sont livrés ici
même si F-87 ne les remplit pas. Alternative écartée : ne poser que ce que F-87 utilise — elle
aurait obligé F-88 à modifier le contrat en cours de route. Réversible.

**A2 — Le compilateur tient la règle de l'adaptateur unique.** `TeamsAdapterV1` et `TeamsUrls` sont
package-private, et un test d'architecture interdit toute mention de Teams hors du paquet.
Alternative écartée : une simple consigne d'équipe.

**A3 — Aucun champ inconnu n'est recopié.** L'adaptateur construit ses objets champ par champ ; il
n'existe aucun chemin par lequel un jeton Microsoft pourrait sortir, même si Microsoft en ajoutait
un demain dans un corps de réponse. C'est la garantie de sécurité du volet, tenue **par
construction** et verrouillée par un test.

**A4 — Les échantillons sont fabriqués, et c'est écrit.** Nous n'avons aucun compte Teams de test.
Les échantillons sont écrits à la main d'après la forme publiquement documentée des réponses du
service de conversation. Ils prouvent que l'adaptateur **traduit** correctement ; ils ne prouvent
**pas** que la forme supposée est la bonne. C'est la sonde de santé (SF-87-03) qui confrontera
l'hypothèse au réel au premier branchement, et qui refusera bruyamment si elle ne reconnaît rien.

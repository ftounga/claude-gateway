# Mini-spec — [F-89 / SF-89-12] Relevé « forme » des réponses Teams (noms de champs seulement, jamais les valeurs)

---

## Identifiant

`F-89 / SF-89-12`

## Feature parente

`F-89` — Le volet Teams : le terminal Teams (mini-specs SF-89-01→11)

## Statut

`in-progress`

## Date de création

2026-09-15

## Branche Git

`feat/SF-89-12-releve-forme`

---

## Objectif

Ajouter au relevé Teams (`--releve-teams`) un **mode « forme » opt-in** qui, pour les seules réponses
classées Teams, journalise le **squelette** de leur corps JSON — noms de champs et types JSON — **sans
jamais écrire une seule valeur**, pour recaler `TeamsAdapterV1` sur la forme réelle du poste CAGIP.

---

## Contexte (cause racine déjà confirmée en prod)

Les outils Teams (`teams_find_meetings`, `teams_find_conversations`…) reviennent OK mais **vides** sur
le poste CAGIP. L'observation réseau marche (workers attachés, 333 réponses worker, corps récupérés,
URLs classées `MEETING_DETAILS`/`CALENDAR_EVENT`/`CONVERSATION_LIST`), **mais** `TeamsAdapterV1`
(`runner/.../teams/TeamsAdapterV1.java`) ne sait pas parser la **forme réelle** des corps Teams EMEA :
`arrayAt(body,"value","meetings")` et les alias de champs (`id`, `start`, `end`, `value`,
`conversations`, `members`, `threadProperties`…) ne correspondent pas au JSON réel → `UNRECOGNIZED_PAYLOAD`,
la sonde de santé crie « PARTIAL — 4 champs reconnus sur 12 ». L'adaptateur a été écrit d'après une
forme **documentée/fabriquée** (docstring assumée `TeamsAdapterV1.java:31-35`), sans jamais avoir vu un
vrai compte.

Le relevé actuel (`NetworkSurvey`, docstring 31-36) **ne lit jamais un corps** (`Network.getResponseBody`
non émis) : il ne peut donc pas révéler ce bug de parsing. D'où cette subfeature : révéler la **forme**
(le squelette) sans faire fuiter aucun contenu client.

Le point de vie privée est **non négociable** : le relevé sert à dresser la table des formes de
Microsoft, **les mêmes pour tous les clients** ; ce qui distingue un client (un nom, un jeton, une
adresse, un identifiant) n'a rien à faire dans le rapport et n'y entre pas.

---

## Comportement attendu

### Cas nominal

1. **Mode opt-in explicite.** Un drapeau `--forme` (alias `--mode-forme`) active le mode. **Sans lui, le
   relevé est strictement inchangé** : aucun corps n'est jamais demandé (`Network.getResponseBody` n'est
   pas émis), comportement identique au relevé actuel.
2. **Corps lus, filtrés par classification.** En mode forme, pour chaque réponse dont l'URL est classée
   parmi **{`MEETING_DETAILS`, `CALENDAR_EVENT`, `CONVERSATION_LIST`, `CONVERSATION_MESSAGES`,
   `MEETING_COLLAB_OBJECT`}** — et **rien d'autre** — le relevé récupère le corps
   (`Network.getResponseBody`, déjà dans la liste blanche `CdpCommands`) sur la session qui l'a émis,
   **hors du fil de la socket** (drainage depuis la boucle de commande), et en extrait le **squelette** :
   - récursion à profondeur **bornée** (défaut **4**) ;
   - objet → liste des **NOMS de clés** + le **type JSON** de chaque valeur ;
   - tableau → le type des éléments + le squelette du **PREMIER élément seulement**
     (ex. `value: array<object>[ { id: string, subject: string, … } ]`) ;
   - feuilles → **type uniquement** : une chaîne devient `string`, un nombre `number`, un booléen
     `boolean`, `null` → `null`. **Aucune valeur** de feuille n'est jamais écrite (ni tronquée, ni hachée).
3. **Garde-fous.** Nombre de clés **borné par niveau** (200 ; au-delà : `…(+N clés)`), longueur de nom de
   clé **bornée** (~120 car : une clé anormalement longue est un signal de fuite → le **NOM** est tronqué
   et marqué `…(tronqué)`, **jamais** une valeur). Corps trop volumineux (> `NetworkObserver.MAX_BODY_BYTES`,
   4 Mio) : ignoré, compté comme non lu.
4. **Rendu.** Le squelette est écrit dans le markdown du relevé, dans une section
   « Squelette des réponses classées », **groupé par `TeamsPayloadKind`**, avec le chemin **classé et
   assaini** (`SurveyPaths.hostMotif` + `template` : sans requête, sans identifiant, sans tenant),
   l'origine (`WORKER`/`TAB`/…) et la **version d'API** observée (`TeamsUrls.apiVersions`). Le JSON du
   relevé porte les mêmes squelettes.
5. **Journalisé / consentement.** L'annonce de la commande **dit** qu'en mode forme des corps **seront
   lus** (squelette seulement) ; l'en-tête du rapport **dit** que le mode forme était actif et que des
   corps ont été lus (noms + types seulement). Le mode reste, comme le reste du volet, **opt-in et
   interactif** (un opérateur au clavier, garde `NOT_A_TERMINAL` inchangée).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Réponse classée hors des 5 genres retenus (`PROFILE`, `ACTIVITY_FEED`, `SEARCH_RESULTS`, `IGNORED`, `UNKNOWN`, transcription…) | Corps **jamais** demandé (aucun `getResponseBody`) — hors périmètre du squelette |
| Réponse 401/403 (refusée) | Corps non demandé (le corps est un message d'erreur) ; comptée à part |
| Corps déjà purgé / vide / non-JSON | Compté comme « non lu », aucune ligne de squelette, aucune valeur écrite |
| Corps > 4 Mio | Ignoré, compté comme non lu |
| Objet portant un jeton/nom/adresse en valeur | Seuls les **noms de clés + types** sortent ; aucune valeur, même partielle |
| Clé anormalement longue (> ~120 car) | **Nom** tronqué + marqué ; jamais une valeur |
| Mode forme non demandé | Aucun corps lu ; relevé identique à l'actuel |

---

## Critères d'acceptation

- [ ] **CA1** — `--releve-teams` **sans** `--forme` : aucune commande `Network.getResponseBody` n'est
  jamais émise (relevé actuel intact) ; `--forme` est reconnu par `TeamsSurveyCommand.requested`/`Options`.
- [ ] **CA2** — En mode forme, une réponse `CONVERSATION_LIST` produit un squelette groupé sous
  `CONVERSATION_LIST` listant les **noms de champs** de premier niveau et le type de chacun, plus le
  squelette du **premier élément** du tableau d'enveloppe.
- [ ] **CA3 (VIE PRIVÉE — non négociable)** — Un corps contenant des valeurs sensibles (jeton
  `Bearer …`, nom « Jean Dupont », adresse courriel, identifiant de fil) produit un squelette où
  **aucune** de ces valeurs n'apparaît (markdown **et** JSON) — seulement des noms de clés et les types
  `string`/`number`/`boolean`/`null`/`object`/`array`.
- [ ] **CA4** — Une réponse classée `PROFILE`/`ACTIVITY_FEED`/`SEARCH_RESULTS`/`IGNORED`/`UNKNOWN` (hors
  des 5 genres) ne déclenche **aucun** `getResponseBody` et n'apparaît pas dans le squelette.
- [ ] **CA5** — Profondeur bornée (4) et premier élément de tableau seulement : un tableau imbriqué au
  6ᵉ niveau n'est pas déplié ; un tableau à N éléments ne produit qu'un squelette.
- [ ] **CA6** — Clé anormalement longue (> 120 car) : le **nom** est tronqué et marqué ; nombre de clés
  borné par niveau (200) avec un marqueur `…(+N clés)`.
- [ ] **CA7** — L'annonce (mode forme) prévient que des corps seront lus ; l'en-tête du rapport dit que
  le mode forme était actif et que des corps ont été lus (noms + types seulement).
- [ ] **CA8** — Le chemin du squelette est assaini (`hostMotif` + `template`) : aucune requête, aucun
  identifiant, aucun nom de tenant ; l'origine et la version d'API observée sont rendues.
- [ ] **CA9** — Chaque réponse `MEETING_COLLAB_OBJECT` (par ailleurs « nommé, jamais lu » dans les
  outils) voit **sa forme** relevée ici (c'est l'objet du relevé) — squelette seulement.

---

## Périmètre

### Hors scope (explicite)

- **Recaler `TeamsAdapterV1`** sur la vraie forme (`arrayAt`, alias de champs, `EXPECTED_*_FIELDS`) :
  c'est **SF-89-13**, qui attend la sortie du relevé lancé par le PO.
- Toute **écriture de valeur** de feuille, tout **envoi de contenu** au modèle ou à la gateway.
- Modification des **outils** de lecture Teams (`NetworkObserver`, `TeamsTools`) et de leur diagnostic.
- Lecture de corps pour les transcriptions / enregistrements / fichiers (hors des 5 genres).
- Toute modification backend, frontend, base de données.

---

## Valeurs initiales

Aucune entité. Compteurs de squelette à zéro au début du relevé ; ils vivent et repartent avec le relevé.

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-------------|----------------------------|---------------|
| profondeur de récursion | — | 4 niveaux | au-delà : type sans dépliage | défaut `PayloadShape.DEFAULT_DEPTH` |
| clés par niveau | — | 200 | au-delà : `…(+N clés)` | ordre d'insertion (`fieldNames`) |
| longueur d'un nom de clé | — | ~120 car | au-delà : nom tronqué + `…(tronqué)` | jamais une valeur |
| éléments de tableau dépliés | — | 1 (le premier) | — | type des éléments annoncé |
| corps lu | — | 4 Mio (`MAX_BODY_BYTES`) | JSON uniquement | au-delà / non-JSON : non lu |
| genres capturés | Oui | 5 | `{MEETING_DETAILS, CALENDAR_EVENT, CONVERSATION_LIST, CONVERSATION_MESSAGES, MEETING_COLLAB_OBJECT}` | rien d'autre |

Notes :
- **Aucune valeur de feuille n'est jamais écrite** — c'est le point de vie privée testé explicitement (CA3).
- Le type JSON n'est **pas** une valeur : c'est ce qui permet de recaler `arrayAt` et les alias.

---

## Technique

### Endpoint(s)

Aucun (runner uniquement, commande locale du PO).

### Tables impactées

Aucune. **Aucune migration.**

### Composants

- `runner/teams/PayloadShape` (**nouveau**) — fonction pure : `JsonNode` → chaîne squelette
  (noms + types, profondeur bornée, premier élément, garde-fous). **Cœur du test vie privée.**
- `runner/teams/NetworkSurvey` — champ `shapeMode` (opt-in) ; en mode forme, mise en file des réponses
  des 5 genres, drainage `captureReadyShapes()` (lecture des corps hors socket), stockage des squelettes ;
  `Snapshot` porte les squelettes.
- `runner/teams/SurveyReport` — section « Squelette des réponses classées » (markdown + JSON), en-tête
  « mode forme actif, corps lus (noms + types) ».
- `runner/teams/TeamsSurveyCommand` — drapeau `--forme`/`--mode-forme`, annonce de consentement, appel du
  drainage dans la boucle et avant l'écriture du rapport.
- `runner/teams/CdpCommands` — **inchangé** (`GET_RESPONSE_BODY` déjà dans la liste blanche).

### Préoccupations transversales

- [ ] Auth / Principal — **non** (runner local, aucune session gateway, aucun endpoint).
- [ ] Contexte tenant — **non** (le nom du tenant est explicitement gabarisé par `SurveyPaths` ; aucune
  valeur de corps n'entre dans le rapport).
- [ ] Plans / limites — **non**.
- [ ] Navigation / routing — **non** (aucune navigation provoquée ; le PO navigue lui-même).

---

## Plan de test

### Tests unitaires

- [ ] `PayloadShapeTest` — cas nominal : objet → noms + types ; tableau → `array<object>` + squelette du
  premier élément ; imbrication objet/tableau.
- [ ] `PayloadShapeTest` — **VIE PRIVÉE (CA3)** : un corps portant `Bearer SECRET-JETON`, « Jean Dupont »,
  `jean.dupont@client.fr`, `19:secretthread@thread.v2` en **valeurs** → la sortie ne contient aucune de
  ces chaînes ; seulement noms + types.
- [ ] `PayloadShapeTest` — profondeur bornée (niveau 6 non déplié), premier élément seulement, clés par
  niveau bornées (`…(+N clés)`), nom de clé long tronqué + marqué.
- [ ] `PayloadShapeTest` — feuilles : `string`/`number`/`boolean`/`null` ; objet/tableau vides.

### Tests d'intégration (relevé, navigateur de papier)

- [ ] `NetworkSurveyTest` (ou `NetworkSurveyShapeTest`) — **sans** `--forme` : aucun `getResponseBody`
  émis (CA1). **Avec** `--forme` : une réponse `CONVERSATION_LIST` avec corps produit un squelette groupé
  (CA2) ; une réponse `PROFILE`/`UNKNOWN` ne déclenche aucun `getResponseBody` (CA4) ; le rapport
  (markdown + JSON) ne contient aucune valeur sensible du corps (CA3) ; l'en-tête dit « mode forme »
  (CA7) ; chemin assaini + origine + version d'API (CA8) ; `MEETING_COLLAB_OBJECT` relevé (CA9).
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), dont `NetworkSurveyTest`,
  `AucunCookieNeRemonteTest`, `CdpCommandsTest`, `RunnerReleveFlagTest`.

### Isolation workspace

- [ ] **Non applicable** — runner local mono-utilisateur, aucune donnée gateway. La confidentialité est
  couverte par CA3 (aucune valeur, aucun tenant, aucun identifiant, aucune requête dans la sortie).

---

## Dépendances

### Subfeatures bloquantes

F-87, F-88, F-100 (relevé, écoute des cadres), SF-89-05 (famille Microsoft, sockets) — **Done**.

### Débloque

**SF-89-13** (recaler `TeamsAdapterV1` sur la forme réelle) — attend la sortie du relevé lancé par le PO.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — Capturer le TYPE JSON en plus du nom : OUI.** Un type n'est pas une valeur, et c'est
  indispensable pour recaler `arrayAt` et les alias de `TeamsAdapterV1`.
- **D2 — Profondeur 4, premier élément de tableau seulement, mode opt-in jamais par défaut.**
- **D3 — Drainage hors socket** : les corps sont lus par `captureReadyShapes()` appelé depuis la boucle
  de commande (pas sur le fil de la socket, qui est le seul à pouvoir délivrer la réponse) — même
  raison d'être que l'exécuteur du relevé et que `NetworkObserver.collect()`.
- **D4 — `MEETING_COLLAB_OBJECT` est lu ici** alors qu'il est « nommé, jamais lu » côté outils : c'est
  justement le but du relevé de forme, et le squelette ne porte aucune valeur.
- **D5 — Le mode forme reste gardé comme le relevé** : opt-in, interactif (opérateur au clavier), et
  journalisé (annonce + en-tête du rapport). Le volet Teams applicatif (droit + ADMIN) n'est pas touché,
  le relevé étant une commande locale du PO hors gateway.

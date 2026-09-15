# SF-89-14 — Le relevé « forme » capte aussi le squelette des chemins Microsoft UNKNOWN

> Cadrage du 2026-09-15 (PO). **Cadrage seul, à livrer en autonome sur go.** Sous-feature de F-89,
> extension de SF-89-12 (relevé « forme »).

## Objectif (une phrase)
Étendre le mode `--forme` du relevé (SF-89-12) pour qu'il capte **aussi** le squelette — noms de champs
et types JSON, **jamais une valeur** — des réponses d'hôtes Microsoft classées **UNKNOWN**, afin de
découvrir la forme des endpoints pas encore reconnus (ex. `calendarView`, liste des conversations,
messages) **sans capture brute** de contenu.

## Pourquoi
Le débogage du 2026-09-15 a montré la limite : `teams_find_meetings` interroge
`/api/mt/emea/v2.0/me/calendars/default/calendarView` (la **liste** du calendrier), qui reste **UNKNOWN**
— alors que SF-89-13 a bien recalé `/calendars/events/{id}` (une réunion **ouverte**). Or `--forme` ne lit
aujourd'hui que le corps des chemins **déjà classés** → il **ne peut pas** révéler la forme d'un chemin
UNKNOWN. Poule et œuf : on ne peut ni classer sans la forme, ni obtenir la forme sans classer. Cette SF
casse le cycle, une fois pour toutes, pour **tout** futur endpoint.

## Comportement attendu
- En mode `--forme` uniquement (opt-in, inchangé sinon), pour une réponse : **hôte de la famille
  Microsoft** + **MIME `application/json`** + classification **UNKNOWN** (en plus des genres déjà lus par
  SF-89-12), lire le corps et n'en écrire que le **squelette** (noms + types JSON), regroupé dans une
  **nouvelle section « Squelette des chemins Microsoft non reconnus »**, avec chemin assaini (ids → `{id}`,
  pas de query), origine, version d'API.
- **Garde-fous vie privée identiques à SF-89-12, non négociables** : jamais une valeur de feuille (chaîne
  → `string`, etc.), jamais un en-tête, jamais une chaîne de requête ; nom de clé anormalement long
  tronqué (le nom, jamais la valeur).
- **Bornes** pour ne pas exploser le relevé : nombre de chemins UNKNOWN distincts capturés borné (ex. top
  20), taille de corps lue bornée, profondeur bornée (4), premier élément de tableau seul, nombre de clés
  borné par niveau. Un dépassement est **dit** (« N autres chemins non capturés »).
- Le relevé annonce clairement qu'il a lu des corps UNKNOWN en mode forme (traçabilité/consentement).

## Cas d'erreur
- Corps non-JSON ou indisponible (purgé par Chrome) → compté « indisponible », jamais inventé.
- Aucun chemin UNKNOWN Microsoft JSON → section vide, pas d'erreur.

## Critères d'acceptation
- Un relevé `--forme` ouvrant la **liste du calendrier** produit le squelette de
  `/calendars/default/calendarView` (noms + types), **sans aucune valeur**.
- Un test prouve qu'un corps UNKNOWN contenant des valeurs sensibles ne laisse **aucune valeur** dans la
  sortie.
- Le relevé normal (sans `--forme`) reste **strictement inchangé** (aucun corps lu).

## Plan de test
- Unitaires : capture de squelette sur un corps UNKNOWN Microsoft JSON (noms+types, zéro fuite) ; respect
  des bornes (top N, profondeur, tableau) ; non-`--forme` ⇒ aucun `getResponseBody` sur UNKNOWN ; hôte
  non-Microsoft ignoré.

## Hors périmètre
- **Recaler les adaptateurs** sur les formes découvertes (`calendarView`, conversations, messages) : ce
  sera **SF-89-15**, après un relevé « forme » exerçant la liste du calendrier **et** un fil de messages.
- Lire des corps non-JSON ; capturer des hôtes non-Microsoft.

## Suite prévue
Après livraison : **un** relevé `--forme` sur CAGIP ouvrant (a) la **liste du calendrier** et (b) un
**fil de discussion avec messages** → capture `calendarView` + conversations/messages en une passe →
**SF-89-15** recale `teams_find_meetings` (calendarView) **et** conversations/messages ensemble.

---

## Mini-spec finale (complétée le 2026-09-16 — livraison autonome)

### Bornes retenues (arbitrage)
- **Chemins UNKNOWN distincts capturés : top 40** (`NetworkSurvey.MAX_UNKNOWN_SHAPES = 40`). Le cadrage
  citait « ex. top 20 » à titre d'exemple ; le PO vise **40** pour couvrir tout le catalogue non reconnu
  en une passe. Au-delà : chemins distincts comptés, jamais lus, et **dits** (« N autres chemins non
  capturés »).
- Profondeur bornée à **4** (`SHAPE_DEPTH`, réutilisée de SF-89-12), clés bornées par niveau
  (`PayloadShape.MAX_KEYS_PER_LEVEL`), **premier élément de tableau seul**, taille de corps bornée
  (`MAX_BODY_BYTES`), file d'attente bornée (`MAX_PENDING_SHAPES`) — tous réutilisés de SF-89-12.

### Gate d'éligibilité d'un corps UNKNOWN (en mode `--forme` seulement)
Un corps est lu comme squelette UNKNOWN si, et seulement si, **toutes** ces conditions sont vraies :
1. mode `--forme` actif ;
2. `TeamsUrls.classify(url) == UNKNOWN` (pas un des 5 genres déjà lus, pas IGNORED) ;
3. `MicrosoftDomains.isMicrosoftFamily(url)` vrai ;
4. MIME de base `== application/json` ;
5. statut ≠ 401/403 ;
6. identifiant de requête présent.
Le squelette est rangé **par chemin assaini** (`SurveyPaths.template`, ids → `{id}`, sans requête).

### Composants impactés
- `runner/.../teams/NetworkSurvey.java` — file d'attente et capture des squelettes UNKNOWN ; nouveau
  plafond `MAX_UNKNOWN_SHAPES` ; champs `Snapshot` (`unknownShapes`, `unknownShapesRead`,
  `unknownShapesDropped`).
- `runner/.../teams/SurveyReport.java` — nouvelle section markdown « Squelette des chemins Microsoft non
  reconnus » + ligne de traçabilité + export JSON.
- Tests : `NetworkSurveyShapeTest` (non-fuite, bornes, non-`--forme`, non-Microsoft, non-JSON).
- **Inchangés** : `PayloadShape` (réutilisé tel quel), `TeamsUrls.classify`, `MicrosoftDomains`,
  `SurveyPaths`, `TeamsSurveyCommand` (l'annonce « MODE FORME ACTIF » existe déjà et couvre le
  consentement).

### Préoccupations transversales
- **Auth / Principal** : aucune — outil de diagnostic local `--releve-teams`, aucun endpoint, aucun JWT.
- **Contexte tenant / `user_id`** : aucun accès données serveur ; le relevé est **local au poste**, rien
  ne quitte la machine ; les noms de tenant sont déjà remplacés par `{id}` (`SurveyPaths`).
- **Plans / limites** : aucun.
- **Navigation / routing** : aucun (pas de frontend).

### Garantie vie privée (non négociable)
Le squelette passe **exclusivement** par `PayloadShape` : aucune feuille (`asText`/`asLong`…) n'est
jamais lue ni écrite — seulement noms de champs + type JSON. Jamais d'en-tête, jamais de query. Un test
de non-fuite sur un corps UNKNOWN portant token/nom/adresse/titre de réunion le prouve.

### Hors périmètre (rappel)
Recaler les adaptateurs (SF-89-15) ; corps non-JSON ; hôtes non-Microsoft ; toute UI.

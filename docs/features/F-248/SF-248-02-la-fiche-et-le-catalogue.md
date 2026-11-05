# Mini-spec — F-248 / SF-248-02 La fiche et le catalogue (passerelle)

## Identifiant
`F-248 / SF-248-02`

## Feature parente
`F-248` — Les pancartes (cadrage §4.1, §4.2, §4.6 ; D2, D8, D10 ; portes K5, K6, K7)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j sur le code) · **Branche :** `feat/SF-248-02-catalogue` · **Dépend de :** SF-248-01

## Objectif
À chaque réplique du cerveau rapide, la passerelle tient le **catalogue des faits réellement lus** (attentes, à envoyer,
courriels du bref, personnes de l'univers) et l'émet dans un événement SSE `cards`, **après** la première phrase, sans
aucune lecture, consigne, outil ni appel modèle de plus.

## Comportement attendu

### Cas nominal
1. `JarvisCard` (`id`, `type`, `title`, `status{word,tone}`, `fields[{label,value}]`, `link`, `observedAt`, `source`,
   `group`, `aliases`, `targeted`) — contrat de l'écran (SF-248-03).
2. `JarvisCardTemplates` (données) : liste blanche des attributs par type (3-5 retenus, dans l'ordre), table mot → ton
   (`normal`, `ok`, `attend`, `alerte`, `eteint` ; rouge réservé aux alertes), garde de couverture (type inconnu ⇒
   `GENERIQUE`), filtre des secrets (noms d'attribut de secret et valeurs à forme de jeton écartés, même au générique).
3. `JarvisCardCatalog` : ouvert par le relais de `POST /api/jarvis/converse` sur **son** fil, fermé à la fin ; les
   sources y rangent la fiche de chaque ligne **déjà lue** (`collect`) — rien n'est fabriqué s'il n'est pas ouvert ; une
   fiche n'entre qu'une fois (par `id`) ; une lecture ciblée la promeut ; ≤ 24 fiches, ciblées d'abord.
4. Sources branchées (lignes déjà relues sous `user_id`, aucune requête ajoutée — K5) : `JarvisFastContextBuilder`
   (attentes du sujet courant, à envoyer, personnes de l'univers), `JarvisMailBriefService.contextBlock` (en-tête des
   courriels du bref, jamais le corps).
5. Événement `cards` `{ responseId, targeted, cards[] }` : envoyé **juste après** la première phrase (`text`), puis à
   chaque phrase si le jeu a changé, et en fin de réponse si rien n'a été dit (D10) ; toujours avant `done`.
6. Règle D2 portée par `targeted` : ces sources sont des lectures **d'office** (`targeted=false` : l'écran ne montre que
   les fiches citées, SF-248-05) ; les lectures ciblées (`etat`) arrivent en SF-248-07.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `APP_JARVIS_CARDS_ENABLED=false` | aucun catalogue, aucune fiche fabriquée, aucun événement `cards` (K7) | 200 (flux) |
| Mode présentation | aucun catalogue, aucun événement (§4.6) | 200 (flux) |
| Une fiche en échec de fabrication | omise, la réplique n'est pas touchée | — |
| Aucun fait lu | aucun événement `cards` | — |
| Sujet d'un autre compte | inchangé : 404 avant tout | 404 |

## Contraintes de validation
≤ 24 fiches par réponse ; 5 champs au plus ; valeur ≤ 80 car., titre ≤ 90 car. ; un attribut hors liste blanche ne
quitte jamais le serveur ; `id` = `fait:<famille>:<ref>`.

## Critères d'acceptation
- [x] `cards` part après le premier `text` et avant `done`.
- [x] Le jeu d'Alice ne contient que ses attentes (test à deux utilisateurs, deux sujets).
- [x] Présentation et coupe-circuit : aucun événement `cards`.
- [x] Consigne système identique à l'octet pancartes allumées / éteintes ; aucun identifiant de fiche dans le message ni les outils (K6, D4).
- [x] Liste blanche, secrets écartés, tons, couverture : `JarvisCardTemplatesTest`.

## Plan de test
- Unitaires : `JarvisCardTemplatesTest` (5), `JarvisCardCatalogTest` (3).
- Intégration : `JarvisCardsConverseIntegrationTest` (D10, isolation Alice/Bob, présentation, K6/K7).
- Non-régression : `JarvisFastContextBuilderTest`, `JarvisFastBrainApiIntegrationTest`, suite complète.

## Impact
- Tables : aucune. Endpoints : `POST /api/jarvis/converse` (+ événement SSE `cards`).
- Backend : `jarvis/cards/JarvisCard`, `JarvisCardTemplates`, `JarvisCardCatalog`, `JarvisCardSources` ; `JarvisFastBrainController` (setter facultatif, aucun constructeur ajouté), `JarvisFastContextBuilder`, `JarvisMailBriefService` (une ligne `collect` chacun).
- Préoccupations transversales : **Contexte tenant** — `JarvisFastContextBuilder` (`actions.list`, `returns.toSend`, `universe.universe` sous `user_id`), `JarvisMailBriefService` (`findByUserIdAndTriage…`) : les fiches sont dérivées de ces lignes, jamais relues. Auth, plans, navigation : non.

## Arbitrages (réversibles)
- `targeted` est porté **par fiche** en plus du drapeau d'événement : un même jeu peut mêler lecture ciblée et d'office.
- Les rendez-vous lus par l'outil `agenda` ne font pas de pancarte : la frise s'ouvre déjà (`agendaView`, D9) ; le surlignage des cités est en SF-248-06.
- Le jeu est **renvoyé entier** quand il change (même `responseId`) : l'écran remplace, sans fusion.

## Hors périmètre
L'écran (SF-03 →), les faits du registre F-247 et l'outil `etat` (SF-07), Opus (SF-08), les accès (SF-09).

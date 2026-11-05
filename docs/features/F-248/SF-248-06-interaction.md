# Mini-spec — F-248 / SF-248-06 Interaction

## Identifiant
`F-248 / SF-248-06`

## Feature parente
`F-248` — Les pancartes (cadrage §4.5, §4.6, §4.7 ; D6, D8, D9 ; préoccupation Auth/Principal §9)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j sur le code) · **Branche :** `feat/SF-248-06-interaction` · **Dépend de :** SF-248-05

## Objectif
Toucher une pancarte ouvre son **plein écran**, dont le détail est **lu à l'ouverture** par `GET /api/jarvis/cards/{id}`
(une ligne sous le compte) ; un courriel ouvre sa visionneuse F-242 ; ordres vocaux locaux, comparaison, grand écran.

## Contrat API
`GET /api/jarvis/cards/{id}` — `id` = `fait:<famille>:<ref>` (deux-points en clair).
- 200 `{ card: JarvisCard, fields: [{label,value}] (≤ 12), previous: {value, at}|null, subjectId, subjectName, chantier }`
- 400 identifiant illisible ; 404 famille inconnue, ligne absente ou d'un autre compte ; 503 coupe-circuit éteint ;
  401/403 sans jeton. Ouvert à la portée `jarvis-compagnon` (`GET /jarvis/cards/*`, un segment).

## Comportement attendu

### Cas nominal
1. `JarvisCardDetailService` aiguille la famille vers un `JarvisCardResolver` ; familles livrées : `attente`
   (`TerminalActionRepository.findByIdAndUserId`), `envoi` (`JarvisReturnService.toSend` sous le compte), `courriel`
   (`JarvisMailItemRepository.findByIdAndUserId`, en-têtes seuls), `personne` (univers du compte). Le registre F-247
   (`etat`) en SF-07, les accès en SF-09.
2. Champs du détail passés par `JarvisCardTemplates.detailFields` (vide / secret ⇒ écarté) ; tout texte libre est
   **masqué** (`password=•••`, jetons connus) par `clean` — titre et valeurs, pancarte comme plein écran.
3. Écran : `JarvisCardDetailComponent` (verre des volets) — tous les champs, « Passé de *X* à *Y* — hier 17 h 42 », la
   source, sujet et chantier, « Ouvrir le lien », « Ouvrir sur le grand écran » (cockpit externe ouvert) ; comparaison
   de deux pancartes, champs alignés, « diffère » écrit et souligné. Courriel ⇒ visionneuse F-242 (D9).
4. Ordres vocaux locaux (`cardVoiceCommand`, liste fermée, jeu ouvert) : « ferme ça », « montre le deuxième »,
   « la suite », « compare le premier et le troisième », « en grand » ; le reste part au cerveau rapide (outil
   `afficher` inchangé).
5. Compteurs : `c_open`, `c_cmp`, `c_big`, `c_link`, `c_voice`.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Identifiant illisible | refus | 400 |
| Pancarte d'un autre compte | introuvable | 404 |
| Famille inconnue / référence illisible | introuvable | 404 |
| Coupe-circuit éteint | indisponible | 503 |
| Détail injoignable | le plein écran montre les champs de la pancarte | — |
| Présentation | rien ne s'ouvre, aucun appel | — |

## Contraintes de validation
`id` : `^fait:[a-z]{2,20}:[^\s/\\]{1,280}$` ; ≤ 12 champs, valeur ≤ 400 car.

## Critères d'acceptation
- [x] Alice lit son attente (champs, sujet, chantier, lien), jamais un secret ; Bob reçoit 404.
- [x] 400 / 404 / 503 ; portée compagnon ouverte pour `GET /jarvis/cards/*` seulement.
- [x] Plein écran : valeur précédente, source, sujet ; comparaison « diffère ».
- [x] Ordres vocaux locaux reconnus ; phrase riche ⇒ cerveau rapide.

## Plan de test
- Intégration : `JarvisCardDetailApiIntegrationTest` (4 : détail, isolation Alice/Bob + 400/404/401, 503 + portée, filtre).
- Unitaires : `JarvisCompanionScopeTest` (inchangé, vert), `jarvis-card-detail.spec.ts` (6).
- Non-régression : suites complètes back et front.

## Impact
- Endpoints : `GET /api/jarvis/cards/{id}` (nouveau) ; `JarvisCompanionScope.ROUTES` (+1).
- Backend : `jarvis/cards/JarvisCardDetail`, `JarvisCardResolver`, `JarvisCardDetailService`, `JarvisCardController`, `JarvisCardDirectResolvers` ; `JarvisCardTemplates` (`mask`, `detailFields`).
- Frontend : `cards/jarvis-card-client.ts`, `jarvis-card-detail.component.ts`, `jarvis-card-voice.ts` ; `jarvis.component.ts/html`.
- Préoccupations transversales : **Auth / Principal** — route ajoutée à la liste fermée `JarvisCompanionScope` (test : `DELETE` et deux segments restent fermés) ; endpoints Jarvis existants inchangés. **Contexte tenant** — `TerminalActionRepository.findByIdAndUserId`, `WorkspaceRepository.findByIdAndUserId`, `JarvisMailItemRepository.findByIdAndUserId`, `JarvisReturnService.toSend(userId)`, `JarvisUniverseService.universe(userId)`.

## Arbitrages (réversibles)
- « Ouvrir sur le grand écran » réutilise l'épinglage au cockpit (SF-237-12) de la pancarte choisie.
- Rendez-vous ⇒ frise : aucune pancarte rendez-vous n'est fabriquée à ce jour (l'outil `agenda` ouvre déjà la frise, SF-02) ; le branchement attend une source.
- Zoom : celui du réducteur (visionneuse `pancarte` zoomable) ; double toucher = plein écran via le toucher de la pancarte.

## Hors périmètre
Le registre F-247 (SF-07), Opus (SF-08), les accès (SF-09). Aucune action depuis une pancarte (§10).

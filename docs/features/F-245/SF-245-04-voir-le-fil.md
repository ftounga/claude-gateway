# Mini-spec — F-245 / SF-245-04 Voir le fil

## Identifiant
`F-245 / SF-245-04`

## Feature parente
`F-245` — Le fil central de Jarvis (cadrage `CADRAGE-F-245-le-fil-central-de-jarvis.md`, D8, §6)

## Statut
`in-progress` (2026-11-04) · **Estimation :** 0,75 j · **Branches :** `feat/SF-245-04-voir-le-fil` (backend), `feat/SF-245-04-voir-le-fil-front` (frontend) · **Dépend de :** SF-245-03

## Objectif
Le PO voit son fil Jarvis du jour dans `/jarvis` et ouvre un chantier depuis une ligne-lien.

## Comportement attendu

### Cas nominal
1. **`GET /api/jarvis/thread/today`** : les messages du fil Jarvis du compte depuis minuit (Europe/Paris), dans l'ordre, 200 au plus (les plus récents) : `{ id, role (USER | ASSISTANT | LINK), text, at, fast (bool), link }`. `text` : la parole (USER), la ligne 🎙 ou le début de la réponse (ASSISTANT, 300 caractères au plus), le texte de la ligne-lien (LINK). Le fil n'est **jamais créé** par cette lecture : sans fil, `[]`.
2. **Volet « Fil »** sur `/jarvis` : un bouton « Fil du jour » dans les réglages ouvre un volet (replié par défaut, patron des volets §22 : navy translucide, encre crème). Il liste « Vous » / « Jarvis » / « Opus » et les lignes-liens (« ↗ … »), dans l'ordre. Un toucher sur une ligne-lien ouvre `/atelier/{id}?chantier=N&message=M` (SF-245-03).
3. **Présentation** : le bouton et le volet ne sont jamais montrés (aucun nom de client ne s'affiche).
4. **Vide** : « Rien encore aujourd'hui. »

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Non authentifié | refusé | 401 |
| Pas encore de fil | liste vide, rien créé | 200 |
| Panne de lecture côté écran | « Fil illisible pour l'instant. » | — |
| Fil d'autrui | inaccessible : l'identité vient du principal, aucun paramètre | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| nombre de messages | ≤ 200 (les plus récents du jour) |
| `text` d'une réponse | ≤ 300 caractères |

## Critères d'acceptation
- [ ] Le volet liste les échanges du jour et les liens dans l'ordre.
- [ ] Un toucher sur un lien ouvre le chantier.
- [ ] En présentation, le volet n'apparaît pas.
- [ ] Pas de débordement à 360 × 740 (volet `left/right` à 8 px, `overflow-wrap: anywhere`).
- [ ] Isolation A/B sur `today` : Bob ne voit rien du fil d'Alice.
- [ ] `today` ne crée pas le fil.

## Plan de test
- **Intégration** `JarvisThreadApiIntegrationTest` (+3 : liste du jour dans l'ordre avec lien et texte réduit, pas de création, isolation A/B ; messages de la veille exclus).
- **Unitaires** : réduction du texte (ligne 🎙, coupe).
- **Frontend** : spec du composant (liste, lien → adresse, vide, erreur) ; spec de `/jarvis` (bouton absent en présentation).
- **Suites complètes** + build.

## Impact
- **Endpoint** : `GET /api/jarvis/thread/today` (nouveau). Aucune table.
- **Backend** : `JarvisThreadController`, `JarvisThreadService.today`.
- **Frontend** : `jarvis-thread.service.ts`, `jarvis-thread-panel.component.ts` (nouveau), `jarvis.component.{ts,html}` ; `DESIGN_SYSTEM.md` §22 (une ligne).

**Préoccupations transversales** : Navigation — **oui** : la ligne-lien du volet navigue vers `/atelier/{id}` avec `?chantier=` / `?message=` (traités en SF-245-03 dans `atelier.component.ts`) ; aucune route ajoutée ; `/jarvis` (sélecteur `chooseSubject`, `subjectSwitch`) inchangé. Contexte tenant : `today` lit sous le `user_id` du principal (`findFirstByUserIdAndJarvisThreadUserId`, puis messages filtrés `workspace_id` + `user_id`). Auth, plans : non.

## Arbitrages
- **Bouton dans les réglages** (comme « Mes boîtes », « Mon portrait ») plutôt qu'un volet permanent : l'écran reste dégagé. Réversible.
- **Messages `FAST` inclus** (ce sont les échanges du jour), étiquetés « Jarvis ». Réversible.

## Hors périmètre
La recherche dans le fil ; les jours précédents ; le rafraîchissement en direct pendant que le volet est ouvert (relu à l'ouverture).

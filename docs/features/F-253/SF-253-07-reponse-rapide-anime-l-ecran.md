# Mini-spec — F-253 / SF-253-07 La réponse rapide anime l'écran ; le sujet atteint la 3D

## Identifiant
`F-253 / SF-253-07`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 ligne 7 ; audit #3, #4, #17, #21, §4)

## Statut
`done` · **Estimation :** 1 j · **Branche :** `feat/SF-253-07-reponse-rapide-anime` · **Dépend de :** SF-253-01

## Objectif
Qu'une réponse du cerveau rapide allume à l'écran (2D et 3D) les nœuds qu'elle cite et la surbrillance du nom au moment
où il est dit, et que le sujet choisi soit connu de la scène, de la 3D et du cockpit dès l'ouverture.

## Comportement attendu
1. `converse()` (`jarvis.component.ts`) ouvre un tour de scène (`scenes.turnStarted()`) **avant** toute phrase ou tout
   ordre d'écran, si aucun tour Opus ou délégation ne tient déjà la scène ; un « Montrer » local du cockpit s'efface,
   comme au début d'un tour Opus.
2. Chaque phrase reçue est lue par la scène (`scenes.text('🎙 phrase\n')`, priorité « dite ») : les nœuds cités
   s'allument ; la voix de la phrase porte `sayingHooks` composé avec `cardHooks` (pancartes F-248 inchangées) : le nom
   est surligné au moment où il est dit.
3. Fin de l'échange sans délégation : `scenes.done()` puis `jarvis.scenes(scenes.takeStats())` (mesure SF-234-11). Avec
   délégation ou repli vers Opus, le tour délégué / Opus reprend la scène (il appelle déjà `turnStarted`/`done`) : la
   réponse rapide ne le clôt pas.
4. Le sujet de l'écran est passé à la scène (`scenes.setSubject`) à l'ouverture (sujet demandé ou mémorisé) et à chaque
   `chooseSubject` ⇒ mise en avant 3D, constellation, « Journée du sujet » du cockpit actif.
5. Téléphone (< 768 px) : une étiquette de la constellation 2D qui sortirait de l'écran passe sous son point et se
   recentre dans le repère (aucune couleur ni police nouvelle).

### Cas d'erreur
- Une délégation ou un tour Opus tourne déjà : la réponse rapide ne réinitialise pas sa scène (seule la surbrillance
  au moment dit s'applique) et ne la clôt pas.
- Une parole plus récente arrive pendant l'échange : l'échange plus récent reprend la scène ; l'ancien ne la clôt pas.
- Repli (plafond, 503) : `send()` reprend la scène ; aucune double mesure.

## Critères d'acceptation
- [x] Une réponse rapide citant un sujet de l'univers l'allume dans la scène (`scenes.state().lit`), mesure envoyée.
- [x] La phrase jouée appelle `scenes.saying` et toujours les pancartes (`cardHooks`).
- [x] Un échange délégué ne clôt pas la scène de la délégation ; un « Montrer » reçu dans l'échange n'est pas effacé.
- [x] `scenes.subjectId()` vaut le sujet ouvert et suit `chooseSubject`.
- [x] Banc (vrai rendu, API simulée) : nœud cité allumé après une réponse rapide ; sujet propagé (cockpit « Journée du sujet » actif) ; aucune
  étiquette hors écran au téléphone.

## Plan de test
Unitaires (`jarvis.component.spec.ts`, `jarvis-constellation-layout`) : allumage, `saying`, clôture/non-clôture,
sujet propagé, étiquette contenue. Intégration : banc `frontend/scripts/banc-jarvis-montre/` (vraie page, API simulée).
Isolation utilisateur : sans objet (aucune lecture nouvelle ; l'univers est déjà filtré `user_id` côté serveur).

## Impact
`frontend/src/app/jarvis/jarvis.component.ts`, `scenes/jarvis-constellation.component.ts`,
`scenes/jarvis-constellation-layout.ts`, specs, banc. Aucune table, aucun endpoint.
Préoccupations transversales : aucune (ni auth, ni tenant, ni limites, ni routage).

## Hors périmètre
Scène propre au cerveau rapide (audit §5.1) ; mesure du rendu, délégation, frise (SF-253-08).

## Décisions non prévues
- `clearLocalShow()` à l'ouverture du tour rapide (comme un tour Opus, SF-234-13) : un « Montrer » local du cockpit
  s'efface à l'échange suivant, pas à la fin de la réponse.
- `scenes.done()` relit l'univers à chaque échange rapide (appel existant de fin de tour) : +1 GET léger par échange.
- Banc : `BANC_ONLY` (passages choisis) et passage `telephone-2d` (le téléphone du banc rend en 3D par défaut).

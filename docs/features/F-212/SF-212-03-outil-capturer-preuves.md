# Mini-spec — F-212 / SF-212-03 Backend : l'outil `capturer_preuves`

## Identifiant
`F-212 / SF-212-03` — Feature parente : F-212 « Les captures de preuve » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-212-03-capturer-preuves`

## Objectif
L'agent prouve un résultat en images : il rejoue ses commandes de vérification à la suite dans un même shell du poste ; chaque commande passe la même porte et la même invite que `bash` ; le relevé brut est filtré des secrets, rendu en images de terminal, rangé (S3 + `proof_captures`), affiché dans le fil (bloc `proof`) et résumé au modèle en texte court.

## Comportement attendu
### Cas nominal
1. Outil `capturer_preuves(titre, commandes[1..8], etape?)` déclaré sur tout terminal de poste (hors terminal Teams) — définition **courte et fixe** (préfixe caché).
2. **Porte du parcours** : l'appel est classé par sa commande la plus risquée (`JourneyRiskClassifier`, liste vide ⇒ modification).
3. **Pour CHAQUE commande**, comme un `bash` : point d'accroche de gouvernance (`applyCommandCheckpoint`), politique allow/ask/deny, **invite d'autorisation** (sauf « tout autoriser pour ce message »). Une seule refusée ⇒ rien n'est exécuté.
4. Une seule session runner `proof_run` (délai ≤ budget de tour, ≤ 10 min) ; journal du poste : une ligne `bash` par commande exécutée (code, durée).
5. `ProofCaptureService` : **filtre de secrets** (`McpSecretFilter`) sur commandes et sorties AVANT rendu ; rendu `/terminal-shot` (SF-212-02) ; images sous `preuves/{userId}/{workspaceId}/{id}.png` ; une ligne `proof_captures` par image (migration **168**).
6. Bloc riche `proof` dans le compte rendu du tour (`AtelierTurnReport.Block`, champ ajouté, constructeur d'avant conservé : les anciens `terminal_json` se relisent) + événement SSE `proof` au fil de l'eau.
7. Route `GET /workspaces/{id}/proofs/{captureId}` (PNG, `requireOwned` + filtre `(id, user_id, workspace_id)`, clé reconstruite).
8. Effacement avec le terminal (`WorkspaceService.delete` : préfixe de stockage ; lignes en cascade).
9. **Coût (exigence PO)** : aucune image ne revient au modèle (ni bloc image, ni read_file) ; résultat = texte court : identifiants, puis par commande code + durée + sortie bornée (≈ 2 000 caractères tête + fin, ≈ 6 000 au total). Définition d'outil < 250 tokens. Consigne : pas de preuve systématique — sur demande de preuve, ou pour un point annoncé validé quand des preuves ont été demandées dans le sujet ; une fois par point ; ne pas lancer les mêmes commandes en bash juste avant.
10. **Usage cible (précision PO)** : la consigne des encarts message (F-203, `MESSAGE_BLOCK_DOCTRINE`, texte fixe) fait référencer les captures dans la balise : ```` ```message preuves=<id1>,<id2> ```` (à côté de `attente=` de F-211). Le résultat rend `preuves=<ids>` prêt à copier.
11. **Étape** (amendement PO) : `etape` facultatif, porté par le bloc et la ligne ; le résultat invite à citer les identifiants dans la preuve de l'étape.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| terminal hébergé / Teams | refus explicite (« exigent un poste connecté ») |
| runner sans capacité `proof` | « mettez à jour le poste », rien n'est émis |
| 0 ou > 8 commandes | refus explicite |
| une commande refusée (invite, règle DENY, gouvernance, porte du parcours) | rien n'est exécuté, motif rendu |
| poste injoignable / délai | note « non concluant », comme bash |
| service de rendu indisponible | pas de bloc ; le modèle reçoit quand même les sorties du poste |
| capture d'un autre compte / identifiant invalide | 404 indiscernable |

## Critères d'acceptation
- [x] Chaque commande déclenche l'invite comme un bash (test : 2 commandes ⇒ 2 invites, puis 1 session).
- [x] Une commande refusée ⇒ aucune exécution.
- [x] Porte du parcours : la commande la plus risquée tranche (EXTERNE bloqué en Investigation).
- [x] Runner ancien ⇒ « mettez à jour le poste ».
- [x] Aucun bloc image au modèle ; texte borné ; identifiants et `preuves=` présents.
- [x] Bloc `proof` dans `terminal_json`.
- [x] Isolation : 404 pour un autre compte ; effacement avec le terminal ; secrets masqués avant rendu.
- [x] Contrat runner ↔ gateway : outil, capacité, borne.

## Plan de test
`AtelierChatServiceProofTest` (8), `ProofCaptureServiceTest` (5), `ProofCaptureApiIntegrationTest` (3, isolation `user_id`), `HttpTerminalShotRendererTest` (SF-02), `JourneyRiskClassifierTest` (+1), `ProofToolsContractTest` (contrat). Suite backend complète.

## Préoccupations transversales
- **Contexte tenant** : nouvelle table et route d'image ⇒ composants : `ProofCaptureRepository.findByIdAndUserIdAndWorkspaceId`, `ProofCaptureController` (`requireTerminalAccess` + `requireOwned`), `ProofCaptureService.image` (clé reconstruite), `WorkspaceService.delete` (préfixe). Test 404.
- **Sécurité du poste (porte/invite)** : composants : `AtelierChatService.applyProofCapture` (checkpoint, `resolveEffect`, `askPermission`, audit), `JourneyRiskClassifier.classify`, `RunnerCallDispatcher.capabilityFor` (capacité `proof`). Test dédié.
- Auth, plans/limites, navigation : non.

## Impacts
Table `proof_captures` (168) ; endpoint `GET /workspaces/{id}/proofs/{captureId}` ; événement SSE `proof` ; `AtelierTurnReport.Block.proof` ; outil `capturer_preuves` ; relais `RunnerToolGateway.proof`.

## Hors périmètre
Affichage (SF-212-04) ; overlay F-208 « Voir la preuve » (le texte de preuve de l'étape cite l'identifiant) ; invite personnalisée par poste (reste à faire).

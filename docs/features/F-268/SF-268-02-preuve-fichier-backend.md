# Mini-spec — F-268 / SF-268-02 Backend : `capturer_preuves` montre aussi des fichiers

## Identifiant
`F-268 / SF-268-02` — Feature parente : F-268 « La preuve fichier, jointe au message » — Statut : `done` — 2026-11-18
Branche : `feat/SF-268-02-preuve-fichier-backend` — Dépend de SF-268-01 (route `/file-shot`, mergée).

## Objectif
L'outil `capturer_preuves` accepte `fichiers` : chaque extrait est lu sur le poste au moment de la capture, secrets masqués, rendu par `/file-shot` et rangé comme une capture terminal (même table, même route, même `preuves=<id>`), et la porte du parcours guidé le traite en LECTURE.

## Comportement attendu
### Cas nominal
- Schéma : `titre` requis ; `commandes` (0..8) et/ou `fichiers` (0..4) `[{chemin, de?, a?, surligner?: int[], note?}]` — au moins un des deux.
- Pour chaque fichier : politique de permission de `read_file` (refus ⇒ rien n'est fait ; invite ⇒ comme un `read_file`, « tout autoriser » respecté), puis `RunnerToolGateway.readFile` (chemin relatif au projet), tracé au journal du poste (`read_file`).
- Fenêtre : plage `de..a` (≤ 80 lignes), sinon lignes surlignées ± 6, sinon lignes 1..40.
- Secrets masqués LIGNE À LIGNE (numéros justes) : filtre MCP + bloc PEM entier + valeur littérale d'une clé sensible (`password`, `secret`, `token`, `api_key`, `access_key`, `private_key`, `credentials`) ; une référence (`var.x`, `${…}`) reste visible.
- Une image par fichier (`TerminalShotRenderer.shootFiles` → `/file-shot`), rangée sous `preuves/{userId}/{workspaceId}/{id}.png` + ligne `proof_captures` (`commands_json` = `{file, firstLine, lastLine}`).
- Bloc `proof` : captures terminal puis fichiers ; nouveau champ `files[{captureId, path, firstLine, lastLine, totalLines, highlights}]` (absent des blocs antérieurs).
- Texte au modèle : par fichier « lignes a–b sur N — identifiant X » + le texte des lignes surlignées (5 au plus, 200 car.) pour qu'il vérifie qu'il montre la bonne ligne ; `preuves=` de tous les identifiants. Jamais d'image, jamais le contenu entier.
- Porte du parcours : `fichiers` seuls (sans commande) ⇒ `LECTURE` ; avec commandes ⇒ la plus risquée des commandes (inchangé). Les écritures (`write_file` hors notes `.md`) restent bloquées en Investigation.
- Session de commandes : la capacité runner `proof` n'est exigée que s'il y a des commandes (un fichier se lit avec tout runner).
- Consigne des encarts : « captures terminal et fichiers se mélangent ».

### Cas d'erreur
| Situation | Comportement |
|---|---|
| ni commande ni fichier (ou fichier sans `chemin`) | erreur outil explicite, rien n'est émis |
| > 8 commandes ou > 4 fichiers | erreur outil explicite |
| permission `read_file` refusée / invite refusée | erreur, rien n'est exécuté ni lu |
| fichier introuvable / poste muet | « Fichier « x » non lu : … » (non concluant), les autres fichiers sont rendus |
| renderer indisponible | « Les images des fichiers n'ont pas pu être produites » ; rien de rangé |
| terminal hébergé | refus inchangé (« poste connecté ») |

## Critères d'acceptation
- [x] Fichier seul : `readFile` appelé, `proof_run` jamais, journal `read_file`, bloc `files` dans le compte rendu.
- [x] Phase Investigation : la preuve fichier passe la porte ; fichier + `kubectl apply` reste bloqué ; `write_file notes/rendu.png` reste REVERSIBLE.
- [x] Fenêtre conforme aux trois règles ; 80 lignes au plus.
- [x] Secrets masqués, numéros de ligne conservés, référence visible.
- [x] Terminal + fichiers dans un même bloc, identifiants ordonnés, `preuves=` les contient tous.
- [x] Isolation : image servie au seul `(user_id, workspace_id)` propriétaire (route existante, test).

## Plan de test
- Unitaires : `ProofCaptureServiceTest` (nominal, masquage, fenêtre, mélange, erreurs, isolation), `JourneyRiskClassifierTest` (fichiers = lecture, pas de blanchiment, écriture inchangée).
- Intégration (boucle) : `AtelierChatServiceProofTest` (lecture sur le poste, porte Investigation, blocage mixte, refus « rien à prouver ») ; `ProofCaptureApiIntegrationTest` inchangé (route isolée commune).
- Isolation `user_id` : `fileIsolation` + `anotherAccountGets404` (même route, même table).

## Impact
- `ProofToolCatalog` (schéma + description, préfixe caché changé une fois), `ProofCaptureService`, `ProofBlock` (+`files`), `TerminalShotRenderer` (+`shootFiles`), `HttpTerminalShotRenderer` (`/file-shot`), `AtelierChatService.applyProofCapture`, `JourneyRiskClassifier`, consigne des encarts.
- Aucune migration, aucun endpoint nouveau. Runner inchangé (`read_file` existant).
- Préoccupations transversales : aucune (auth/tenant/plans/navigation non touchés).

## Hors périmètre
- Fichiers hors du projet (chemin absolu) : refusés par `read_file`.
- Terminal hébergé (bac à sable) : refus inchangé.
- Affichage front (SF-268-03).

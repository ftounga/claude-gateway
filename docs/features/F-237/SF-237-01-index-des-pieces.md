# Mini-spec — F-237 / SF-237-01 — L'index des pièces

## Identifiant
`F-237 / SF-237-01` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-01-index-pieces`
Cadrage : `CADRAGE-F-237-jarvis-montre-les-pieces.md` §3, §4, §11, §12.

## Objectif
> Jarvis sait ce qu'il a : `GET /api/jarvis/pieces` rend, pour le seul compte courant, la liste des pièces
> montrables (entrées, preuves, productions) d'un sujet ou du compte, rattachées au chantier quand c'est possible,
> avec la description **déjà en base** qui les accompagne.

## Comportement attendu
- `GET /api/jarvis/pieces?subjectId=&chantier=&category=&kind=&since=&until=&series=&limit=` (JWT, accès Atelier).
  - `subjectId` fourni : `WorkspaceService.requireOwned` (404 si autre compte ou inconnu).
  - Sans `subjectId` : tous les sujets du compte (40 plus récents au plus), réponse bornée à 200.
  - `limit` défaut 50, borné à 200. Réponse `{ pieces: Piece[], total, truncated }`.
- **Agrégat sans table**, calculé à la demande, mis en cache 60 s par (utilisateur, sujet) ; les filtres
  s'appliquent après le cache.
- Sources (filtre `user_id` + `workspace_id` à chaque requête) :
  | Source | id | catégorie | type | description (`captionSource`) |
  |---|---|---|---|---|
  | E2 besoin initial (1er message `USER` de chaque chantier) | `need:<msgId>` | ENTREE | TEXT | le message ≤ 600 (MESSAGE) |
  | E1 dépôts `atelier_deposited_files` | `dep:<uuid>` | ENTREE | par extension (IMAGE, VIDEO, AUDIO, PDF, FILE) | phrase du message autour du k-ième marqueur `[Image #n]`… ≤ 300, sinon message ≤ 300 (MESSAGE), sinon nom + date (TITLE) |
  | E3 besoins `app_requests` | `req:<uuid>` | ENTREE | TEXT | reformulation (REFORMULATION) › description (DESCRIPTION) › titre (TITLE) |
  | P1 captures `proof_captures` | `proof:<hex>` | PREUVE | IMAGE | titre + étape + « n commandes, toutes réussies / k en échec » (STEP) |
  | P2 envois marqués `message_block_sends` (non annulés) | `send:<uuid>` | PREUVE | TEXT | objet + destinataire + extrait (DESCRIPTION) |
  | P3 courriels `client_emails` envoyés | `mail:<uuid>` | PREUVE | TEXT | objet + destinataire + début du corps texte (DESCRIPTION) |
  | R1 pages | `page:<uuid>@v<n>` | PRODUCTION | PAGE | description (DESCRIPTION) › titre (TITLE) |
  | R4 images IA prêtes | `img:<uuid>` | PRODUCTION | IMAGE | prompt ≤ 200 (PROMPT) |
  | R5 présentations | `slides:<uuid>` | PRODUCTION | SLIDES | description › titre + n diapos |
  | R6/R7 vidéos et audio | `video:<uuid>@v<n>` | PRODUCTION | VIDEO / AUDIO | description (DESCRIPTION) › série + épisode + titre (TITLE) ; chapitres en `meta` |
- **Rattachement au chantier** (`chantierLink`, cadrage §4.2) :
  1. `MESSAGE` : le message lié (`message_id`) porte `chantier_number`, ou l'identifiant de la pièce apparaît dans
     `tool_trace` / `terminal_json` d'un message de chantier ;
  2. `WINDOW` : sinon, la date de la pièce tombe dans la fenêtre d'**un seul** chantier (premier → dernier message
     du chantier ; le chantier courant de `subject_journeys` reste ouvert jusqu'à maintenant) ;
  3. `NONE` : sinon (aucune fenêtre ou plusieurs).
- **Disponibilité** : dépôt d'un sujet exécuté sur poste ⇒ `ONLINE` si le poste est connecté
  (`RunnerRegistry.isConnected`), sinon `HOST_OFFLINE` ; tout le reste `ONLINE`.
- `thumb` / `open` : **chemins passerelle existants** seulement (aucune URL S3) ; vignettes réduites = SF-237-02.
- Ordre : catégorie (ENTREE, PREUVE, PRODUCTION), besoin initial en tête de ses entrées, puis date croissante.

### Cas d'erreur
- Sujet d'un autre compte ou inconnu ⇒ **404** (indiscernable).
- `category` / `kind` inconnus ⇒ 400 ; `since > until` ⇒ liste vide (pas d'erreur).
- Sans jeton ⇒ 401 ; compte sans accès Atelier ⇒ 403 (`AtelierAccessService`).
- Source illisible (JSON de commandes ou de chapitres corrompu) ⇒ la pièce reste, sans le détail (jamais 500).

## Critères d'acceptation
- [ ] Les 10 sources ci-dessus apparaissent pour leur propriétaire avec catégorie, type, description et source.
- [ ] Un second compte ne voit **aucune** pièce du premier (liste vide) et reçoit 404 sur son sujet.
- [ ] Rattachement : MESSAGE, WINDOW et NONE (chantiers concurrents) vérifiés.
- [ ] Priorité des descriptions vérifiée (dépôt : marqueur › message › nom).
- [ ] Filtres `chantier`, `category`, `kind`, `limit` appliqués ; `truncated` vrai au-delà de la limite.
- [ ] Dépôt sur poste déconnecté ⇒ `HOST_OFFLINE`.

## Contraintes de validation
`limit` 1..200 (défaut 50) ; `title` ≤ 120 ; `caption` ≤ 600 ; cache 60 s ; ≤ 40 sujets parcourus sans `subjectId`.

## Technique
Paquet `fr.claudegateway.jarvis.pieces` : `Piece` (+ énumérations), `PieceChantierIndex` (pur : rattachement),
`PieceCaptions` (pur : descriptions), `JarvisPieceService` (agrégat + cache), `JarvisPieceController`.
Méthodes de dépôt ajoutées (lecture, filtrées `user_id`) : `ProofCaptureRepository`, `GeneratedImageRepository`,
`PresentationRepository`, `ClientEmailRepository`, `AppRequestRepository`, `AtelierMessageRepository`.
Aucune migration, aucune table.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | JWT existant, `CurrentUser` |
| Contexte tenant | **Oui** | `JarvisPieceService` : chaque source filtrée `user_id` + `workspace_id` ; `WorkspaceService.requireOwned` ; test A/B |
| Plans / limites | Non | aucune |
| Navigation / routing | Non | aucune route front |

## Sécurité
Masquage des secrets des captures de preuve : **vérifié existant** (`ProofCaptureService.masked`, test
`ProofCaptureApiIntegrationTest` « secrets masqués AVANT le rendu ») — rien à ajouter.

## Plan de test
- Unitaires : `PieceChantierIndexTest` (MESSAGE par id et par trace, WINDOW, NONE concurrents, chantier courant
  ouvert), `PieceCaptionsTest` (marqueurs, message, nom ; preuves réussies / en échec ; tronquage).
- Intégration : `JarvisPieceApiIntegrationTest` — sources réelles en H2, filtres, isolation A/B (liste vide + 404),
  401 sans jeton.

## Hors périmètre
- Moments Teams (P4) : différés (identifiants enfouis dans `tool_trace` d'un terminal Teams ; 3 messages en prod) —
  arbitrage tracé, à reprendre en SF-237-02 si l'usage le demande.
- Schémas (R3) : SF-237-03. Vignettes réduites, corps de courriel, pièces de besoin : SF-237-02.
- Aucun écran (SF-237-04+).

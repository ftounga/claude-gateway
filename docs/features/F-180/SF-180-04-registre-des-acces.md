# Mini-spec — F-180 / SF-180-04 — Le registre des accès

## Identifiant
`F-180 / SF-180-04` — feature parente `F-180` *Les accès et les interlocuteurs* (décision **D4**).
Dépend de SF-180-01→03 (mergées). Branche : `feat/SF-180-04-registre-des-acces`. Statut : `in-progress`.
Date : 2026-10-16.

## Objectif
Garder la trace des **accès obtenus** — système, compte, quand, par qui, jusqu'à quand — pour que l'agent
ne les redemande plus, **sans jamais stocker un secret**.

## Comportement attendu

### Cas nominal
1. **Table `acces_obtenus`** (migration `161`) : `id`, `user_id`, `host_id` (le poste ; nul pour un
   terminal hébergé, alors la portée est le terminal), `workspace_id` (terminal d'origine), `system`,
   `normalized_system`, `account` (compte / profil), `obtained_at` (date), `granted_by` (texte),
   `interlocuteur_id`, `expires_at` (date, facultative), `subject_id`, `action_id` (l'attente d'origine),
   `note`, `created_at`, `updated_at`. `terminal_actions.registry_dismissed_at` (« pas besoin »).
2. **Aucun secret** (`AccesSecretGuard`, appliqué à `system`, `account`, `granted_by`, `note`) : refus
   400 si la valeur contient un mot-clé de secret suivi d'une valeur (`mot de passe:`, `password=`,
   `token:`, `jeton =`, `mdp`, `apikey`…), un jeton reconnaissable (JWT `eyJ…`, `ghp_`, `github_pat_`,
   `xox?-`, `AKIA…`, `sk-…`, `glpat-`, `AIza…`, `-----BEGIN`), ou une chaîne sans espace de ≥ 32
   caractères mêlant majuscules, minuscules et chiffres sans `/ : . @` (forme d'un secret).
   Message : « Le registre ne garde aucun secret… ». Rien n'est écrit, rien n'est journalisé de la valeur.
3. **« Ajouter au registre ? »** : une attente `ACCES` passée **Fait** depuis moins de 30 jours, sans
   entrée de registre liée et non écartée, est proposée — `GET /api/workspaces/{id}/acces/proposals` →
   `[{ action, system, grantedBy, obtainedAt }]` (système déduit de l'énoncé, « par qui » =
   interlocuteur, à défaut à qui / personne, date = fermeture). `POST …/acces` avec `actionId` l'ajoute ;
   `POST …/acces/proposals/{actionId}/dismiss` = « Pas besoin ».
4. **Registre** : `GET/POST /api/workspaces/{id}/acces`, `PATCH/DELETE /api/workspaces/{id}/acces/{aid}`
   — portée poste du terminal possédé. « Par qui » est résolu en interlocuteur (SF-180-03) ; une fusion
   d'interlocuteurs reporte le registre.
5. **Joint au tour** : le registre **compact** de la portée (20 lignes au plus : système · compte · obtenu
   le · par qui · expire le / EXPIRÉ) rejoint la liste des attentes dans le **message** du tour (jamais
   dans la consigne système). Il est joint même sans attente ouverte.
6. **Dédoublonnage étendu** (à l'inscription par l'agent, nature `ACCES` ou non dite) :
   - un accès **non expiré** du registre dont tous les mots du système figurent dans l'énoncé →
     issue `ALREADY_OBTAINED` : « DÉJÀ OBTENU le 02/10 (expire le 31/12) … » — **pas de nouvelle attente** ;
   - la même clé, **Fait** depuis moins de 30 jours, ailleurs sur le poste → `ALREADY_DONE` « déjà
     obtenu le … » ; idem par le sens (pgvector) quand la clé d'embeddings est là.
7. **Panneau** : vue « Accès » (registre : liste, ajout, édition, suppression confirmée par dialogue,
   pastille « expire dans N j » / « expiré ») et section « Ajouter au registre ? » [Ajouter] [Pas besoin].

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Secret dans un champ | 400, rien d'écrit |
| `system` vide / > 120, `account`/`granted_by` > 120, `note` > 300 | 400 |
| `expires_at` antérieure à `obtained_at` | 400 |
| `actionId` d'autrui / hors portée | 404 |
| Entrée d'un autre compte ou poste | 404 |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `system` | 1–120, sans secret |
| `account`, `grantedBy` | 0–120, sans secret |
| `note` | 0–300, sans secret |
| `obtainedAt` | date, défaut aujourd'hui |
| `expiresAt` | date ≥ `obtainedAt`, facultative |

## Critères d'acceptation
- [ ] Demander à l'agent un accès déjà au registre → « déjà obtenu le … (expire le …) », pas de nouvelle attente.
- [ ] Aucun secret (jeton, mot de passe) n'est accepté dans le registre (filtre + test).
- [ ] Une attente ACCES passée Fait propose « Ajouter au registre ? » ; « Pas besoin » ne la repropose plus.
- [ ] Le registre est joint au message du tour, compact, même sans attente ouverte.
- [ ] Bob ne lit ni ne modifie le registre d'Alice (404).

## Plan de test
- **Unitaires** : `AccesSecretGuardTest` (mots-clés, jetons connus, forme d'un secret, faux positifs
  évités : ARN, URL, e-mail, nom de compte) ; `AccesRegistryServiceTest` (CRUD, bornes, dates,
  proposition, pas-besoin, correspondance du système, expiration, fusion d'interlocuteurs) ;
  `TerminalActionRegistryDedupTest` (`ALREADY_OBTAINED`, expiré = inscrit, nature autre = inscrit,
  Fait récent ailleurs sur le poste) ; ligne du tour.
- **Intégration** : `AccesRegistryApiIntegrationTest` — proposition après Fait, ajout, secret refusé,
  **isolation Bob**.
- **Front** : vue Accès, proposition, suppression confirmée, erreurs.

## Impacts
- **Tables** : `acces_obtenus` (nouvelle) ; `terminal_actions.registry_dismissed_at`. Migration `161`.
- **Endpoints** : 7 sous `/api/workspaces/{id}/acces`.
- **Composants** : `AccesObtenu`, `AccesObtenuRepository`, `AccesRegistryService`, `AccesSecretGuard`,
  `AccesRegistryController` (nouveaux) ; `TerminalActionService` (dédoublonnage), `TerminalActionTurnNote`
  (registre joint), `TerminalActionToolExecutor` (message), `TerminalActionSemanticDedup` /
  `TerminalActionEmbeddingStore` (Fait récents), `InterlocuteurService` (fusion), `AttentesAccountPurge`,
  `WorkspaceService` (purge d'un terminal hébergé) ; front modèles, service, panneau, vue Accès.

### Préoccupations transversales
- **Contexte tenant** : oui — `AccesRegistryService` (portée = poste du terminal **possédé**, ou ce terminal
  s'il est hébergé ; `user_id` sur chaque lecture), `TerminalActionTurnNote` (registre du poste du terminal
  du tour), `TerminalActionService` (comparaison au registre du même périmètre), `WorkspaceService`,
  `AttentesAccountPurge`. Auth / plans / navigation : non.

## Hors périmètre
Coffre à secrets ; vérification réelle de l'accès sur le système ; rappel d'expiration (SF-180-06).

## Arbitrages (réversibles)
- Correspondance au registre **lexicale** (tous les mots du système dans l'énoncé) : prudente, explicable.
- Un accès **expiré** ne bloque pas une nouvelle demande (c'est justement le moment de la refaire).
- Fenêtre « Fait récent » = 30 jours.
- `host_id` nullable (terminal hébergé = portée terminal), comme `terminal_actions`.

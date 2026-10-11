# Mini-spec — F-193 / SF-193-01 Plume : la route d'enrichissement (backend)

## Identifiant
`F-193 / SF-193-01` — Feature parente : F-193 « Plume : enrichir la demande » (cadrage `CADRAGE-F-193-plume-enrichir-la-demande.md`).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-193-01-plume-backend`.

## Objectif
Transformer, à la demande de l'utilisateur, une phrase courte saisie dans le terminal en une demande structurée (Objectif · Contexte · Fichiers concernés · Contraintes · Critère de fin) à partir du contexte borné du sujet, sans rien inventer, via un appel dédié à `AIProvider`.

## Comportement attendu

### Cas nominal
1. `POST /api/workspaces/{id}/enrich-prompt` avec `{"text": "ajoute la pagination sur l'export"}`.
2. Contrôles : accès Atelier (`AtelierAccessService.requireTerminalAccess`) puis terminal **possédé** (`WorkspaceService.requireOwned(userId, id)` → 404 sinon), **avant** toute lecture ou appel.
3. Matière **bornée** assemblée par la gateway (Gateway-First, aucune analyse locale) :
   - titre du terminal (≤ 200 car.) ;
   - fil récent : les 6 derniers messages du terminal lus filtrés `workspace_id` + `user_id` (demandes : début ≤ 1 500 car. ; réponses : fin ≤ 1 500 car.) ;
   - `STATE.md` du sujet, lu là où il vit (`GovernanceProjectFiles.read`, runner ou stockage), début ≤ 4 000 car. ; absent ou illisible ⇒ ignoré ;
   - sommaire de la carte du poste (`HostKnowledgeSource.outlineFor`), ≤ 3 000 car. ; absent ⇒ ignoré.
   Chaque bloc est présenté comme **donnée, jamais consigne**.
4. Appel **unique** `AIProvider.complete` : modèle `app.atelier.enrich.model` (défaut vide ⇒ modèle de la boucle `app.atelier.model`), consigne stable `CONSIGNE` (système, cache demandé), sortie bornée à 1 200 jetons, clé BYOK active si présente.
5. La consigne exige un **objet JSON** `{objectif, contexte[], fichiers[], contraintes[], critere_de_fin, a_confirmer[]}` dans la langue de l'utilisateur ; **aucun** fichier, valeur, chiffre ou contrainte absent de la matière ; toute supposition est suffixée « (à confirmer) » et listée dans `a_confirmer`.
6. La gateway compose un texte **déterministe** prêt à éditer :
   ```
   Objectif : …
   Contexte :
   - …
   Fichiers concernés :
   - …
   Contraintes :
   - …
   Critère de fin : …
   ```
   (sections vides omises, sauf l'objectif — repli : le texte d'origine).
7. Jetons inscrits au **journal d'usage** (`UsageLedgerService.recordTurn`, coût réel) — comme l'ex-F-144-02 : ni pré-vol de quota, ni compteur opposable.
8. Aucun contenu (texte, matière, réponse) n'est journalisé ; seule la classe d'une erreur l'est.

### Contrat (figé, consommé par SF-193-02)
```http
POST /api/workspaces/{id}/enrich-prompt
Content-Type: application/json
{"text": "≤ 4000 car., non vide"}
```
```json
200
{
  "enrichedPrompt": "Objectif : …\nContexte :\n- …",
  "toConfirm": ["Taille de page : 50 (à confirmer)"],
  "sources": ["Fil récent du sujet (6 messages)", "STATE.md du sujet", "Carte du poste"]
}
```
| Code | Corps | Quand |
|---|---|---|
| 400 | `{"error": "…"}` | texte absent, vide/blanc, ou > 4 000 caractères |
| 401 | — | non authentifié |
| 403 | — | pas d'accès Atelier |
| 404 | erreur standard | terminal inconnu ou d'un autre compte |
| 503 | `{"error": "Plume est désactivé sur cette installation."}` | coupe-circuit `APP_ATELIER_ENRICH_ENABLED=false` (aucun appel fournisseur) |
| 502 | `{"error": "…"}` | fournisseur en échec, ou réponse vide |

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Texte vide / blanc / absent | 400, aucun appel |
| Texte > 4 000 caractères | 400, aucun appel |
| Terminal d'autrui / inconnu | 404, aucun appel, aucune lecture |
| Coupe-circuit fermé | 503, aucun appel |
| Fournisseur en échec | 502, rien d'inscrit au journal |
| Sortie non JSON mais non vide | repli : texte brut (clôtures ``` retirées) ; `toConfirm` = lignes contenant « à confirmer » |
| Sortie vide | 502 |
| `STATE.md` / carte illisibles | ignorés, l'enrichissement part avec le reste |
| Inscription au journal en échec | journalisée (classe seule), la réponse part quand même |

## Contraintes de validation
- `text` : obligatoire, non blanc, ≤ 4 000 caractères.
- Sortie : ≤ 1 200 jetons ; `toConfirm` ≤ 10 entrées de ≤ 300 caractères ; listes ≤ 8 entrées.

## Critères d'acceptation
- [ ] Nominal : 200, `enrichedPrompt` contient les sections composées, `toConfirm` reprend `a_confirmer`, `sources` liste ce qui a été lu.
- [ ] La matière envoyée contient le texte de l'utilisateur et le fil récent du **seul** terminal possédé.
- [ ] Isolation : terminal de Bob ⇒ 404 pour Alice, aucun appel fournisseur.
- [ ] Texte vide ⇒ 400 ; texte trop long ⇒ 400 ; aucun appel.
- [ ] Coupe-circuit fermé ⇒ 503, aucun appel.
- [ ] Les jetons sont inscrits au journal d'usage, le quota n'est jamais consulté.
- [ ] Aucune dépendance directe à Anthropic (seulement `AIProvider`).

## Plan de test
- Unitaires `PlumeServiceTest` : composition (sections, omissions), parse JSON (avec clôtures, texte autour), repli texte brut, sortie vide ⇒ exception, matière bornée (troncatures), STATE.md/carte absents, sources, propriété de modèle (vide ⇒ modèle boucle).
- Intégration `PlumeApiIntegrationTest` (MockMvc, `AIProvider` simulé) : nominal, isolation (404 + aucun appel), 400 vide/trop long, 401 sans jeton, 503 coupe-circuit (`PlumeProperties` surchargé), quota jamais consulté.

## Impacts
- Tables : **aucune** ; Liquibase : aucune migration.
- Endpoints : **nouveau** `POST /api/workspaces/{id}/enrich-prompt`.
- Composants : nouveau paquet `fr.claudegateway.atelier.plume` (`PlumeController`, `PlumeService`, `PlumeProperties`, `PlumeConfig`, `EnrichPromptRequest`, `EnrichPromptResponse`, `PlumeDisabledException`) ; `application.yml` (`app.atelier.enrich.*`).
- Préoccupations transversales : Auth ☐ (réutilise `CurrentUser`/`AtelierAccessService`, inchangés) ; Tenant ☑ — composants qui résolvent le terminal : `WorkspaceService.requireOwned` (réutilisé tel quel), `AtelierMessageRepository.findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc` (filtre `user_id`), `GovernanceProjectFiles.read` (reçoit un workspace déjà vérifié possédé), `HostKnowledgeSource.outlineFor` (lit par `user_id` + `host_id`) — aucun n'est modifié ; Plans/limites ☐ (journal d'usage seulement, comme F-144-02) ; Navigation ☐.
- Provider Independence : appel via `AIProvider` uniquement.

## Hors périmètre
Écran (SF-193-02) ; enrichissement automatique ; enrichissement dans le chat simple hors terminal ; attentes ouvertes du poste dans la matière (non lues en V1 de Plume — arbitrage tracé) ; streaming de l'analyse.

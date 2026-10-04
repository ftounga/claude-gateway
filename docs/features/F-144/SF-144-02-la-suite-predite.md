# Mini-spec — F-144 / SF-144-02 — La suite prédite, comme Claude Code

## Identifiant
`F-144 / SF-144-02` — feature parente `F-144` (rouverte le 2026-10-04,
`CADRAGE-F-144-reouverture-suite-predite.md`, décisions D1-D7 validées par le PO).

## Statut
`in-progress`

## Date de création
2026-10-04

## Branche Git
`feat/SF-144-02-la-suite-predite`

## Objectif
Après chaque tour terminé du terminal, proposer **une** suite contextuelle prédite par le modèle
rapide, affichée en texte fantôme dans la zone de saisie vide, acceptée d'une touche, jamais
envoyée sans geste.

## Contrat API (figé)

`POST /api/workspaces/{id}/next-prompt` — sans corps.

| Cas | Code | Corps |
|---|---|---|
| Suite prédite | 200 | `{ "suggestion": "Lance les tests du module paiement.", "messageId": "<uuid du dernier message assistant>" }` |
| Rien à proposer (coupe-circuit, pas de tour terminé, sortie vide ou illisible, fournisseur en échec) | 200 | `{ "suggestion": null, "messageId": "<uuid>" \| null }` |
| Terminal d'un autre compte / inconnu | 404 | `ErrorResponse` |
| Pas de droit au terminal (Forge, ou Vigie pour son terminal Teams) | 403 | `ErrorResponse` |
| Non authentifié | 401 | — |

Arbitrage (réversible) : le cadrage écrivait `/api/atelier/terminals/{id}/next-prompt` ; la
ressource « terminal » du code est le **workspace**, et toutes ses routes vivent sous
`/api/workspaces/{id}/…`. On suit la convention existante.

## Comportement attendu

### Cas nominal
1. Le tour se termine (l'écran voit `submitting` passer de vrai à faux et le dernier message du fil
   est une réponse de l'agent).
2. L'écran appelle `POST /api/workspaces/{id}/next-prompt`.
3. Le serveur vérifie le droit au terminal, puis la propriété du workspace (`user_id`), lit les six
   derniers messages du fil **filtrés `workspace_id` + `user_id`**, retient le dernier message
   `ASSISTANT` et la dernière demande `USER` qui le précède.
4. Si une suite a déjà été prédite pour ce message assistant : elle est rendue **sans appel**.
5. Sinon, un appel **séparé** au modèle rapide (`ModelCatalog.fastModel()`, via `AIProvider`, clé
   BYOK du propriétaire si présente sinon plateforme), hors de la boucle de l'agent : consigne fixe
   (cachable), matière bornée = demande ≤ 2 000 car., fin de la réponse ≤ 4 000 car., titre du
   terminal, état du relevé (étape de plan ouverte, interruption, plafond). `max_tokens` = 150.
6. La sortie est **une** phrase ≤ 200 caractères, dans la langue de l'utilisateur, à sa première
   personne ; `AUCUNE` (ou vide, ou trop longue, ou multi-ligne non exploitable) ⇒ `null`.
7. Les jetons sont inscrits au **journal d'usage** (`usage_turns`, coût réel du modèle rapide) —
   **pas** au compteur de période opposable au quota (`usage_counters`), et aucun pré-vol de quota
   n'est fait : l'appel annexe ne consomme pas le quota et ne déclenche jamais le plafond.
8. L'écran affiche la suggestion en **texte fantôme** (le `placeholder` du champ vide) ;
   **Tab** ou **→** (champ vide) l'accepte dans le champ ; **Échap** l'efface ; toute frappe la
   masque ; **Entrée sur champ vide n'envoie rien** (inchangé : `submit()` ignore un brouillon vide).
9. Vue étroite (< 820 px, pas de Tab) : une puce unique « Suggestion : … » qu'un toucher place dans
   le champ.
10. Une seule suggestion visible : la prédite remplace les puces SF-144-01 ; à défaut de prédite
    (`null`, erreur, appel en vol), les puces SF-144-01 restent le **repli**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Workspace d'un autre compte | 404, aucun appel fournisseur | 404 |
| Workspace inconnu | 404 | 404 |
| Utilisateur sans droit Forge | 403 | 403 |
| Coupe-circuit `APP_ATELIER_NEXT_PROMPT_ENABLED=false` | `suggestion: null`, aucun appel | 200 |
| Dernier message = demande utilisateur (tour non fini) ou fil vide | `suggestion: null`, aucun appel | 200 |
| Fournisseur en échec / exception | `suggestion: null`, rien n'est mémorisé (un nouvel essai reste possible), `warn` sans contenu | 200 |
| Sortie `AUCUNE`, vide ou > 200 car. | `suggestion: null` (mémorisé) | 200 |
| Échec réseau côté écran | puces SF-144-01 (repli), aucun message d'erreur | — |

## Critères d'acceptation
- [ ] Après un tour, `POST /next-prompt` rend la phrase du modèle rapide, nettoyée (guillemets,
      préfixes retirés), ≤ 200 car.
- [ ] Deux appels pour le même dernier message assistant ⇒ **un seul** appel fournisseur.
- [ ] La requête fournisseur utilise `fastModel()`, la consigne fixe, `maxTokens = 150`, la clé
      BYOK si présente ; la matière est bornée (2 000 / 4 000) et contient l'état du relevé.
- [ ] Coupe-circuit off ⇒ aucun appel fournisseur, `suggestion: null`.
- [ ] Isolation : workspace d'autrui ⇒ 404 et aucun appel ; les messages lus sont filtrés `user_id`.
- [ ] Jetons inscrits au journal d'usage ; `QuotaService` (pré-vol et compteur) **jamais** appelé.
- [ ] Aucun contenu (demande, réponse, suggestion) n'est journalisé.
- [ ] Écran : texte fantôme dans le champ vide, Tab / → l'acceptent, Échap l'efface, une frappe le
      masque, Entrée sur champ vide n'envoie rien.
- [ ] Écran : vue étroite ⇒ puce « Suggestion : … » ; toucher = remplir, pas envoyer.
- [ ] Écran : prédite présente ⇒ puces SF-144-01 masquées ; absente ⇒ puces SF-144-01 visibles.
- [ ] Écran : appel déclenché à la fin d'un tour seulement (pas au chargement, pas en lecture seule).
- [ ] Les mentions `@`, le menu `/`, la dictée et le brouillon pré-rempli ne sont pas cassés (Tab
      reste aux menus quand ils sont ouverts ; la suggestion n'apparaît que sur champ vide).

## Plan de test
- **Unitaires backend** (`NextPromptServiceTest`) : nettoyage de sortie (`AUCUNE`, guillemets,
  préfixe « Suggestion : », > 200 car., multi-ligne) ; bornage de la matière (2 000 / 4 000, fin
  conservée) ; état du relevé lu dans `terminal_json` (plan ouvert, interruption, plafond, JSON
  invalide ignoré) ; mémorisation (un seul appel) ; échec fournisseur ⇒ `null` non mémorisé ;
  coupe-circuit ; dernier message USER ⇒ aucun appel ; journal d'usage appelé, `QuotaService` non.
- **Intégration** (`NextPromptApiIntegrationTest`, fournisseur simulé) : 200 nominal ; isolation
  (workspace de Bob ⇒ 404 pour Alice, aucun appel) ; 401 sans jeton ; deuxième appel servi du cache.
- **Frontend** : `next-prompt` dans `AtelierService` (URL, POST) ; terminal : appel à la fin du tour
  uniquement, placeholder fantôme, Tab / → / Échap, Entrée vide n'envoie pas, puce mobile, repli
  SF-144-01 ; `TurnSuggestionsComponent` masqué si `hidden`.

## Tables / endpoints / composants impactés
- **Aucune table, aucune migration** (mémorisation en mémoire, bornée LRU 5 000 entrées par pod).
- Endpoint nouveau : `POST /api/workspaces/{id}/next-prompt`.
- Backend : paquet `fr.claudegateway.atelier.nextprompt` (`NextPromptService`,
  `NextPromptController`, `NextPromptProperties`, `NextPromptConfig`) ; méthode dérivée
  `findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc` sur `AtelierMessageRepository` ;
  `application.yml` (`app.atelier.next-prompt.enabled`).
- Frontend : `AtelierService.nextPrompt`, `AtelierTerminalComponent` (déclencheur, placeholder,
  clavier, puce mobile), `TurnSuggestionsComponent` (entrée `hidden`).

## Préoccupations transversales
- [x] **Plans / limites** — composants vérifiés : `QuotaService.assertWithinQuota` (pré-vol, non
  appelé), `QuotaService.recordUsage` (compteur opposable, non appelé), `AtelierTurnBudget` (plafond
  de tour, non touché : appel hors boucle), `UsageLedgerService.recordTurn` (journal, appelé),
  `CostBudgetService` / alertes de coût (lisent le journal : la suite y apparaît à son coût réel,
  ≈ 0,3 centime). Test : `QuotaService` jamais invoqué.
- [x] **Navigation / composer** — composants vérifiés : menu `/` (SF-121-23, prioritaire sur Tab),
  @-mentions (SF-121-24, prioritaires), dictée (F-145, écrit dans le brouillon : suggestion masquée
  dès que le champ n'est plus vide), brouillon pré-rempli « Demander à la Forge » (F-173, champ non
  vide ⇒ pas de fantôme). Aucune route, aucun guard modifié.
- [ ] Auth / Principal — non touché (réutilise `requireTerminalAccess` + `CurrentUser`).
- [ ] Contexte tenant — non touché.

## Arbitrages (réversibles, tracés)
- Route `/workspaces/{id}/next-prompt` (convention existante) au lieu de `/atelier/terminals/…`.
- Mémorisation en mémoire (permise par D3) : pas de migration ; deux pods peuvent payer deux fois
  la même suite au pire.
- « Fichiers modifiés » n'entre pas dans la matière serveur : le relevé persistant ne porte pas les
  diffs ; la fin de la réponse les cite déjà.
- Repli mobile = vue étroite `isNarrow` (< 820 px) existante, pas une détection tactile.

## Hors périmètre
Plusieurs suggestions au choix ; historique des suggestions ; suggestion pendant la frappe ; chat
passerelle hors terminal ; persistance en base de la suggestion.

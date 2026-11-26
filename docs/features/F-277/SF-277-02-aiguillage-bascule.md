# Mini-spec — F-277 / SF-277-02 Aiguillage Anthropic / Bedrock, bascule automatique et traçabilité du canal

## Identifiant
`F-277 / SF-277-02`

## Feature parente
`F-277` — Second accès à Claude via AWS Bedrock, avec bascule automatique

## Statut
`ready`

## Date de création
2026-11-26

## Branche Git
`feat/SF-277-02-aiguillage-bascule`

---

## Objectif
Faire servir automatiquement par Bedrock tout appel IA hébergé qu'Anthropic ne peut pas servir (crédit, clé, panne), sans aucune erreur visible ni régression quand Anthropic répond, et ranger quel canal a servi chaque tour.

## Comportement attendu

### Cas nominal
1. `FailoverAgentProvider` (`AiAgentProvider`) et `FailoverAIProvider` (`AIProvider`) sont les beans injectés par interface. Les implémentations Anthropic sont marquées `@Fallback`, au lieu d'un `@Primary` sur l'aiguillage : un `@Primary` entrerait en conflit avec les bouchons `@Primary` des tests. Les tests qui portent `@MockitoBean` remplacent l'aiguillage.
2. `ProviderFailover` lit `APP_AI_BEDROCK_MODE`. Le défaut est `auto` dans `application.yml`, et `off` en tests.
   - `off` : Anthropic seul.
   - `bedrock` : tout appel hébergé passe par Bedrock.
   - `auto` : Anthropic d'abord, puis bascule.
3. En `auto`, un appel est **rejoué sur Bedrock** dans ces cas :
   - crédit épuisé ou facturation (400/403 avec « credit balance », « billing_error » ou « purchase credits », ou 402) ;
   - 401 ou 403 ;
   - clé plateforme absente ;
   - 5xx ou 529 encore en échec après les relances existantes.
4. Le disjoncteur s'ouvre pour `APP_AI_BEDROCK_OPEN_MINUTES` (15 min) sur crédit, clé refusée ou clé absente.
   - Tant qu'il est ouvert, les appels vont directement sur Bedrock.
   - À l'échéance, l'appel suivant retente Anthropic. Un succès referme le disjoncteur, une panne le rouvre.
   - Une surcharge (5xx) ne fait basculer que l'appel en cours.
5. Chaque bascule et chaque changement d'état du disjoncteur produit un WARN : `Bascule IA vers Bedrock…`, `Disjoncteur IA ouvert|rouvert|refermé…`.
6. `usage_turns.provider` (non nul, défaut `ANTHROPIC`) et `atelier_turn_measures.provider` (nullable) prennent le canal du dernier appel IA du fil d'exécution (`ServedChannel`). À défaut, ils prennent la route courante.

### Cas d'erreur / de non-bascule
| Situation | Comportement attendu |
|---|---|
| Client BYOK (requête portant `apiKey`) | Jamais basculé, quel que soit le mode. L'erreur Anthropic remonte telle quelle. |
| 400 ordinaire (requête invalide, « prompt too long »), 404, 413, 429 | Pas de bascule. L'exception d'origine remonte inchangée, ce qui préserve la compaction et le réassainissement existants. |
| Refus du modèle (`stop_reason=refusal`, réponse 200) | Ce n'est pas une panne : le tour est rendu tel quel, avec la relance de refus existante. |
| Échec de réseau ou délai dépassé | Pas de bascule (comportement d'avant). |
| Échec **après** l'envoi de texte à l'écran (flux) | Pas de rejeu, car il dupliquerait le texte déjà vu. L'échec reste visible (WARN `Bascule IA impossible…`). C'est le choix sûr. |
| Bedrock échoue aussi | L'erreur Bedrock remonte, avec l'erreur Anthropic en *suppressed*. Un WARN est journalisé. |
| `uploadFile` (Files API) | Toujours chez Anthropic : la Files API n'existe pas sur Bedrock. |

## Critères d'acceptation
- [ ] En mode `auto`, quand Anthropic répond, la requête reçue par un vrai serveur HTTP est identique **à l'octet près** (méthode, chemin, en-têtes, corps) à celle d'un appel direct. Le test couvre `nextTurn` sans flux, `nextTurn` avec flux (deltas identiques) et le chat (`complete`, `streamComplete`).
- [ ] Chaque cause de bascule est testée. L'absence de bascule est testée sur 400 ordinaire, 413, 429, 404, réseau et refus du modèle.
- [ ] Le disjoncteur est testé : ouverture, appels directs sur Bedrock, demi-ouverture avec succès, puis avec nouvelle panne.
- [ ] Le BYOK n'est jamais basculé, en `auto` comme en `bedrock` (test).
- [ ] Aucun rejeu n'a lieu après du texte affiché (test).
- [ ] Les colonnes `provider` sont écrites. La suite complète reste verte, et le contexte Spring démarre.
- [ ] En prod, avec le crédit Anthropic à zéro, un vrai appel aboutit par Bedrock. La ligne `usage_turns` porte `provider=BEDROCK` avec son coût, et le WARN apparaît dans CloudWatch.

## Périmètre

### Hors scope (explicite)
- Un écran : la lecture se fait en SQL et dans CloudWatch.
- Le rapprochement de la facture AWS.
- Une bascule des Managed Agents.

## Valeurs initiales
- `usage_turns.provider` vaut `ANTHROPIC` pour tout l'historique. `atelier_turn_measures.provider` vaut `NULL` pour l'historique.

## Contraintes de validation
- Vocabulaire fermé `AiChannel {ANTHROPIC, BEDROCK}`. Un mode inconnu vaut `off`.

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
- `usage_turns` (+ `provider varchar(16) not null default 'ANTHROPIC'`).
- `atelier_turn_measures` (+ `provider varchar(16)`).

### Migration Liquibase
- [x] Oui — `277-01-ai-provider-channel.xml` (réversible par `dropColumn`).

### Composants Angular (si applicable)
Aucun.

## Plan de test

### Tests unitaires
- [ ] `ProviderFailoverTest` : classement des pannes, modes, disjoncteur, BYOK, absence de rejeu après du texte, échec de Bedrock.
- [ ] `FailoverNonRegressionTest` : comparaison à l'octet près sur un serveur HTTP local (agent avec et sans flux, chat), 400 et 413 non rejoués, refus rendu tel quel, crédit épuisé rejoué, échec après du texte non rejoué.

### Tests d'intégration
- [ ] Suite complète `./mvnw verify`. Le démarrage du contexte prouve que le câblage `@Fallback` ne crée aucune ambiguïté d'injection.
- [ ] `TurnMeasureWriterIntegrationTest` : l'insertion porte la colonne `provider`.

### Isolation workspace
- [x] Non applicable aux données : l'aiguillage ne lit aucune donnée utilisateur. Seule règle d'isolation : la clé BYOK d'un client ne passe jamais par le compte AWS de la plateforme (testé).

## Préoccupations transversales
- **Plans / limites** : non modifiés. Le quota et le coût suivent le même chemin, et la grille de coût s'applique telle quelle (Bedrock rend le même identifiant de modèle, au prix « global » égal à celui de l'API).
- Auth / Principal, contexte tenant, navigation : non concernés.

## Dépendances
SF-277-01 (canal Bedrock).

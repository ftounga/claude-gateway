# Cadrage — F-172 Fable 5.1 dans la Forge

> Cadrage PO le 2026-10-02. Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.

## 1. D'où vient la demande

Le PO demande si l'application peut utiliser **Claude Mythos**, le modèle le plus puissant
d'Anthropic. Diagnostic (documentation officielle Anthropic, 2026-10-02) :

- **Mythos 5** (`claude-mythos-5`) n'est ouvert qu'aux clients **approuvés** du programme
  *Project Glasswing* (cybersécurité). Aucun changement d'infra ni de code n'y donne accès : c'est
  une **approbation commerciale**. Hors sujet pour ce cadrage.
- **Fable 5 / 5.1** ont **les mêmes capacités** que Mythos, sans demande d'accès, sur l'API
  Claude. Seule différence : Fable porte des **classifieurs de sécurité** qui peuvent refuser une
  demande. L'interdiction américaine du 12 juin 2026 (non-ressortissants US) a été **levée**.

La cible réelle est donc **Fable 5.1 (`claude-fable-5-1`)**, pour la **Forge** (boucle maison
`AtelierChatService`) et son **terminal**, c'est-à-dire là où se joue le travail long et où le PO a
constaté ses deux défauts majeurs : la parité de raisonnement avec Claude Code et la recherche
superficielle au premier passage.

## 2. Ce que Fable change, et ce que le code ne sait pas encore faire

Constats faits en lecture de code sur `origin/main` :

| # | Comportement de l'API | État du code | Conséquence |
|---|---|---|---|
| A | Un refus arrive en **HTTP 200** avec `stop_reason: "refusal"` et `stop_details.category` | `AnthropicAgentProvider` : `finished = !"tool_use".equals(stopReason)` — un refus passe pour un tour **terminé** | Réponse **vide ou tronquée**, sans message ni erreur. **Bug latent dès aujourd'hui** : Opus 5, modèle actuel de la Forge, porte lui aussi ces classifieurs |
| B | Repli serveur : `fallbacks: "default"` + en-tête `server-side-fallback-2026-07-01` (bêta, API Claude directe uniquement — c'est notre cas) | Absent | Un refus n'est jamais rattrapé |
| C | Après un repli, la réponse porte un bloc `{"type":"fallback","from":…,"to":…}` qu'il faut **rejouer à sa place**, et les blocs `thinking` / `tool_use` situés avant lui doivent être **retirés** | Le parseur **ignore** les types de bloc inconnus | Le tour suivant serait **rejeté** par l'API (validation des blocs de raisonnement autour de la frontière) |
| D | `usage.iterations` détaille **chaque tentative**, chacune facturée **au tarif de son modèle** ; le champ `model` donne le modèle **réellement servi** | Coût calculé sur un seul modèle, celui demandé | Coût faux en cas de repli ; le modèle enregistré dans `usage_turns.model` ment |
| E | Raisonnement **toujours actif** (impossible à couper) ; texte brut du raisonnement jamais renvoyé | La Forge envoie déjà `thinking: adaptive` + `output_config.effort` (SF-39-10) | **Compatible tel quel** ; rien à faire |
| F | Fenêtre de **1 M tokens** par défaut, 128 k en sortie | Seuils de compaction réglés pour Opus | Aucun changement dans F-172 (voir §6) |
| G | Données conservées **30 jours**, pas de « zéro conservation » sauf accord | — | Question contractuelle avant toute ouverture aux clients → **OQ-23** |

La grille tarifaire contient **déjà** `claude-fable-5-1` (`ProviderPricingProperties`) :
10 $ / 50 $ par million (entrée / sortie), **lecture de cache 0,25 $** (moitié d'Opus 5), écriture
de cache 20 $ (double d'Opus 5).

## 3. Le coût attendu — à mesurer, pas à supposer

La Forge relit 90 à 95 % de ses tokens depuis le cache. Sur Fable, la **lecture** coûte **deux fois
moins** et l'**écriture** **deux fois plus** qu'Opus 5. Depuis F-171 (préfixe stable), l'écriture
baisse : le surcoût réel se situe donc probablement **entre ×1,3 et ×2**, pas à ×2 net. Seule la
mesure tranchera (protocole §7).

Règle PO « justesse avant coût » : F-172 **ajoute** une option de raisonnement plus fort, elle ne
retire rien. Le coût est un critère de **généralisation**, pas d'existence.

## 4. Décisions proposées (à valider par le PO)

- **D1 — Repli côté serveur (`fallbacks: "default"`).** Règle Provider-First : Anthropic fournit le
  repli, on le **relaie**, on ne le réimplémente pas. Activé par un réglage
  (`APP_ATELIER_FALLBACKS`, défaut `true`) : coupe-circuit si la bêta change. Vaut pour **tous**
  les modèles de la Forge, Opus 5 compris.
- **D2 — Choix du modèle par espace de travail, réservé à l'ADMIN dans F-172.** Liste fermée de
  modèles sélectionnables en configuration (`APP_ATELIER_SELECTABLE_MODELS`, défaut
  `claude-opus-5,claude-fable-5-1`). Une valeur absente fait suivre le défaut de configuration
  (comportement actuel, à l'octet près). L'ouverture aux clients est **hors périmètre**.
- **D3 — La sous-boucle d'exploration ne change pas** (Sonnet, F-149) : seule la boucle
  principale passe en Fable. C'est elle qui raisonne ; l'exploration lit.
- **D4 — Changer de modèle en cours de conversation est permis, mais annoncé.** Le prochain tour
  relit tout l'historique **sans cache** (le cache est propre au modèle). L'écran l'affiche avant
  de valider.

## 5. Découpage

| SF | Titre | Couche | Estimation |
|---|---|---|---|
| **SF-172-01** | Refus visible : reconnaître `stop_reason: "refusal"`, jeter la sortie partielle, afficher un message clair dans le terminal (avec la catégorie) au lieu d'une réponse vide | Backend + terminal (message) | 0,5 j |
| **SF-172-02** | Repli serveur : `fallbacks: "default"` + en-tête bêta, nouveau bloc `Fallback` (lu, conservé, rejoué à sa place), retrait des `thinking`/`tool_use` antérieurs au dernier repli | Backend (`AnthropicAgentProvider`) | 1,5 j |
| **SF-172-03** | Coût par tentative : tarifer chaque entrée de `usage.iterations` au tarif de son modèle, enregistrer le modèle **réellement servi** | Backend (quota) | 1 j |
| **SF-172-04** | Modèle de la Forge par espace de travail : colonne `model` (nullable) sur l'espace, résolution par tour dans `AtelierChatService`, endpoint de choix réservé ADMIN, liste fermée | Backend (+ migration) | 1,5 j |
| **SF-172-05** | Sélecteur dans le terminal de la Forge (ADMIN) : choix Opus 5 / Fable 5.1, avertissement « relecture sans cache » (D4), badge du modèle servi par tour, mention « repli → <modèle> » | Frontend | 1 j |

Ordre : **01 → 02 → 03** d'abord. Ces trois-là corrigent un défaut **actuel** (Opus 5 refuse déjà
parfois) et sont la condition pour basculer sans risque. Puis **04 → 05**.

Chaque SF a sa mini-spec (`subfeature-template.md`) au démarrage, selon le cycle de `CLAUDE.md`.

## 6. Hors périmètre (explicite)

- **Mythos 5** : approbation commerciale Glasswing, pas un sujet de code.
- **Ouvrir Fable aux clients** : demande une pondération du quota (le quota est en tokens, un
  token Fable coûte plus cher) et la réponse à **OQ-23**. Feature séparée, après la mesure.
- **Le chat passerelle** (`ChatService` / `AnthropicProvider`) : gain faible, surcoût certain.
- **Relever les seuils de compaction** pour profiter du million de tokens : toucher à la
  compaction est sous surveillance (réglage agressif en prod) ; à décider sur mesure, plus tard.
- **Le repli côté client (middleware SDK)** : inutile tant qu'on appelle l'API Claude directement
  (D1). À reconsidérer seulement si l'on passe par Bedrock ou Vertex.
- V3 (F-17 / F-18), multi-LLM runtime.

## 7. Protocole de mesure (condition de généralisation)

Après SF-172-05, l'ADMIN travaille sur Fable 5.1 une semaine sur des sujets réels. Lecture :

- **Coût par demande** : `usage_turns` par `model` (déjà enregistré, rendu exact par SF-172-03).
- **Justesse** : nombre d'auto-corrections (« je me suis trompé ») et de relances humaines par
  sujet, comparés à une semaine Opus 5 de même nature.
- **Refus** : nombre de refus et de replis, par catégorie.

Décision du PO à l'issue : généraliser, réserver à certains postes, ou abandonner.

## 8. Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | Oui (SF-172-04) | Nouvel endpoint de choix du modèle : contrôle de rôle ADMIN ; lecture de l'espace filtrée par `user_id` comme les autres endpoints de `AtelierController` |
| **Contexte tenant** | Non | — |
| **Plans / limites** | Oui (SF-172-03) | `UsageTurnWriter`, `ProviderPricingProperties`, lecture de coût F-133 : le coût change de calcul quand il y a repli ; le quota en tokens est inchangé |
| **Navigation / routing** | Non | Aucun écran ni route nouvelle ; le sélecteur vit dans l'en-tête du terminal existant |

## 9. Garde-fous architecturaux

- **Provider-First** : le repli est celui d'Anthropic (D1), relayé.
- **Provider Independence** : le modèle voyage comme une chaîne via `AiAgentProvider` ; le bloc
  `Fallback` et la lecture de `usage.iterations` restent dans l'implémentation Anthropic, le
  contrat neutre ne gagne qu'un état « refusé » et un « modèle servi ».
- **Gateway-First** : on choisit et on relaie un modèle ; aucune logique d'IA ajoutée.
- **Isolation** : le modèle choisi est une propriété de l'espace, lue sous filtre `user_id`.

## Sources

- https://platform.claude.com/docs/en/models/fable-5/introducing-claude-fable-5-and-claude-mythos-5
- https://platform.claude.com/docs/en/build-with-claude/refusals-and-fallback

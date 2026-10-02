# Cadrage — F-172 Opus 5.5 dans la Forge

> Cadrage PO le 2026-10-02, **recadré le jour même** : la cible initiale (Fable 5.1) est abandonnée
> au vu de l'audit `AUDIT-F-172-apport-modele-plus-fort.md`. Source de vérité produit :
> `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.

## 1. Pourquoi ce recadrage

La question de départ était « peut-on utiliser Mythos ? ». Mythos est réservé aux clients
approuvés de *Project Glasswing*. Fable 5.1, son équivalent accessible, a été évalué puis
**écarté**, car l'audit de nos 1 335 messages montre :

- **Opus 5.5** (`claude-opus-5-5`, sorti le 22/09/2026) dépasse Fable 5.1 sur tous les benchmarks
  publiés. Sur Terminal-Bench 4.0 : 66,4 % pour Opus 5.5, 55,8 % pour Fable 5.1 et 52,3 % pour
  Opus 5, notre modèle actuel.
- Sur nos 412 tours réels, à volume égal : Opus 5 = 661,78 $, **Opus 5.5 ≈ 471 $ (−29 %)**,
  Fable 5.1 ≈ 1 106 $ (+67 %).
- Gain de qualité attendu, estimé : ≈ 9 à 19 % des incidents de la Forge. Il est **modéré** :
  119 incidents sur 194 viennent du harnais et de la discipline de vérification, pas du modèle.

F-172 est donc une **montée de version du modèle de la Forge**. Le gain de coût est certain, le gain
de qualité modéré, et rien n'est retiré au raisonnement (règle « justesse avant coût »).
Opus 5.5 n'est pas un « Covered Model » : pas d'obligation de conservation des données à 30 jours.

## 2. Pourquoi ce n'est pas qu'un changement de variable

Le modèle de la Forge se règle par `APP_ATELIER_MODEL` (défaut `claude-opus-5`). Basculer sans
préparation casserait **silencieusement** la Forge. Constats en lecture de code sur `origin/main` :

| # | Changement Opus 5 → Opus 5.5 | État du code | Conséquence si on bascule tel quel |
|---|---|---|---|
| A | **Refus** : HTTP 200, `stop_reason: "refusal"`, `stop_details.category`. Classifieurs **plus larges** qu'Opus 5 (cyber + bio + `reasoning_extraction`) | `AnthropicAgentProvider` : `finished = !"tool_use".equals(stopReason)`, donc un refus passe pour un tour fini | **Réponse vide sans explication.** Le travail infra et sécurité du PO est le plus exposé. Bug déjà latent sur Opus 5 |
| B | **Repli serveur** `fallbacks: "default"` + en-tête `server-side-fallback-2026-07-01`. Un bloc `fallback` doit être rejoué à sa place, et les `thinking` / `tool_use` qui le précèdent doivent être retirés | Absent ; le parseur ignore les types de bloc inconnus | Refus jamais rattrapé ; après un repli, tour suivant **rejeté** |
| C | **Coût par tentative** : `usage.iterations`, chaque tentative au tarif de son modèle ; `model` = modèle servi | Un seul modèle tarifé. **`claude-opus-5-5` absent** de `ProviderPricingProperties` | Coût faux : tarif de repli au lieu de 4 / 20 / 0,20 / 8 $ |
| D | **Le texte entre deux appels d'outils** arrive en blocs `thinking` « mises à jour de progression », **vides** par défaut. `display: "updates"` + en-tête `thinking-display-updates-2026-08-18` les rend lisibles | Le terminal diffuse les `text_delta`. La **détection d'auto-contradiction** (F-119 / SF-119-01) et le tri de la narration (`hasEssential`, `backgroundText`) lisent `turn.text()` | **Terminal muet entre deux outils** ; **ré-escalade d'effort F-119 aveugle**, sans aucune erreur |
| E | **Plus de raisonnement par tour** à effort égal. `max_tokens` couvre raisonnement **et** texte. Défaut d'effort `medium` (Opus 5 : `high`) | `agent-max-tokens` = 16 384. L'effort est posé explicitement (`high` / `medium`) | Plus de tours **coupés au plafond de sortie** (`truncated`), surtout en escalade |
| F | **Blocs de raisonnement liés à la conversation** : un préfixe modifié invalide les blocs suivants. Le contrôle n'est appliqué par défaut qu'aux comptes créés depuis le 31/08/2026 | Historique réécrit par la compaction F-117 et par la porte de fin de tour (`blockedBlocks`) | Risque de 400 `bound to a different conversation`, ou de raisonnement perdu sans bruit. Ampleur **inconnue** |
| G | `thinking` désactivé, `tool_choice` `any` / `tool`, `computer_20251124` refusés | Aucun usage (vérifié) | — |

## 3. Décisions proposées (à valider par le PO)

- **D1 — Repli côté serveur** (`fallbacks: "default"`). Règle Provider-First : Anthropic fournit le
  repli, on le relaie. Coupe-circuit `APP_ATELIER_FALLBACKS` (défaut `true`). Il vaut aussi pour
  Opus 5 dès sa livraison. Les refus `reasoning_extraction` ne sont pas rejoués par l'API : on les
  affiche.
- **D2 — Bascule globale par configuration, sans sélecteur.** La Forge n'a qu'un utilisateur réel
  aujourd'hui ; un sélecteur par espace serait de la complexité sans usage. Bascule =
  `APP_ATELIER_MODEL=claude-opus-5-5`.
- **D3 — Retour arrière en une commande** : `APP_ATELIER_MODEL=claude-opus-5`, sans livraison de
  code. Ce retour **redémarre les pods** et tue les tours en cours : vérifier `runner_audit` avant,
  comme pour tout déploiement.
- **D4 — La sous-boucle d'exploration reste sur Sonnet 5** (F-149). Passer à Sonnet 5.5 est une
  décision séparée, à mesurer à part.
- **D5 — Raisonnement lié à la conversation : mesurer avant de décider.** On pose
  `thinking.block_binding.prefix_mismatch_behavior: "drop_block"` avec l'en-tête
  `thinking-binding-controls-2026-08-01`, et on journalise les `input_transformations`. Effet :
  jamais de 400, et chaque bloc perdu est **compté**. Si le compte est significatif, une feature
  séparée rendra l'historique « append-only » (compaction serveur, messages système en cours de
  conversation).

## 4. Découpage

| SF | Titre | Couche | Estimation |
|---|---|---|---|
| **SF-172-01** | **Refus visible** : reconnaître `stop_reason: "refusal"`, jeter la sortie partielle, afficher dans le terminal un message clair avec la catégorie, au lieu d'une réponse vide. Journaliser `stop_reason` | Backend + message terminal | 0,5 j |
| **SF-172-02** | **Repli serveur** : `fallbacks: "default"` + en-tête ; nouveau bloc neutre `Fallback` lu, conservé et rejoué à sa place ; retrait des `thinking` / `tool_use` antérieurs au dernier repli ; modèle servi remonté dans le tour | Backend (`AnthropicAgentProvider`) | 1,5 j |
| **SF-172-03** | **Coût juste** : tarif `claude-opus-5-5` (4 / 20 / 0,20 / 8 $) dans la grille ; coût calculé par entrée de `usage.iterations` ; `usage_turns.model` = modèle **servi** | Backend (quota) | 1 j |
| **SF-172-04** | **Narration entre outils** : `display: "updates"` + en-tête, envoyés **seulement** aux modèles qui produisent ces mises à jour (liste en configuration). Les blocs de progression non vides vont au terminal **et** au texte intermédiaire lu par F-119 et par le tri de narration. Blocs rejoués inchangés | Backend (+ vérification du rendu terminal) | 1,5 j |
| **SF-172-05** | **Garde-fous de requête** : `agent-max-tokens` recalibré (cible 32 k, 64 k en escalade `xhigh` / `max`), après vérification que tous les appels concernés sont en flux ; effort explicite vérifié sur **tous** les appels (boucle, étapes, escalade, exploration, synthèse, compaction) ; D5 (`drop_block` + journal des `input_transformations`) | Backend (config + provider) | 1 j |
| **SF-172-06** | **Bascule et mesure** : passage de `APP_ATELIER_MODEL` à `claude-opus-5-5` en prod (après le contrôle `runner_audit`), puis une semaine de mesure (§6). Décision : garder ou revenir | Exploitation + rapport | 0,5 j |

Ordre : **01 → 02 → 03** d'abord, car elles corrigent un défaut **déjà présent sur Opus 5**. Puis
**04 → 05**, prérequis de la bascule. Enfin **06**. Total ≈ 6 jours.

La SF-172-05 est **bloquante** pour la 06 : on ne bascule pas sans savoir combien de blocs de
raisonnement l'historique actuel invalide.

Chaque SF a sa mini-spec (`subfeature-template.md`) au démarrage, selon le cycle de `CLAUDE.md`.

## 5. Hors périmètre (explicite)

- **Fable 5.1 / Mythos** : écartés par l'audit (coût +67 %, sous Opus 5.5 ; Mythos sur approbation).
- **Sélecteur de modèle par espace** : sans usage aujourd'hui (D2).
- **Chat passerelle** (`ChatService`, défaut `claude-opus-4-8`) : montée de version à cadrer à part.
- **Sous-boucle d'exploration vers Sonnet 5.5** (D4).
- **Historique « append-only »** (compaction serveur, messages système en cours de conversation) :
  seulement si la mesure de D5 le justifie.
- **Les leviers majeurs révélés par l'audit** : harnais (plafonds, limite d'étapes, porte de fin de
  tour qui remplace la réponse, réponses vides) et doctrine « vérifier avant de conclure ». Ils
  pèsent plus que le modèle et méritent leurs propres features.
- V3 (F-17 / F-18), multi-LLM runtime.

## 6. Mesure (SF-172-06) et critères de retour arrière

Une semaine après la bascule, comparée aux deux semaines Opus 5 précédentes :

| Mesure | Source | Attendu | Retour arrière si |
|---|---|---|---|
| Coût par tour | `usage_turns` par `model` | −20 à −30 % | Hausse |
| Tours coupés au plafond de sortie | journal / `truncated` | ≤ Opus 5 | Hausse nette |
| Refus et replis, par catégorie | journal SF-172-01 / 02 | Rares, tous rattrapés ou affichés | Refus non rattrapés récurrents sur le travail infra |
| Blocs de raisonnement perdus | `input_transformations` (SF-172-05) | Faible | Perte systématique à chaque tour |
| Relances « creuse / vérifie / c'est faux » du PO | `atelier_messages` | Baisse modérée | Hausse |

## 7. Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | Non | Aucun endpoint nouveau |
| **Contexte tenant** | Non | — |
| **Plans / limites** | Oui (SF-172-03, SF-172-05) | `ProviderPricingProperties` (nouvelle ligne de grille), `UsageTurnWriter` (coût par tentative), lecture de coût F-133 ; budget de tour `AtelierTurnBudget` (le plafond de sortie relevé consomme le même quota en tokens) |
| **Navigation / routing** | Non | — |

## 8. Garde-fous architecturaux

- **Provider-First** : repli, narration et contrôle de liaison du raisonnement sont fournis par
  l'API ; on les relaie (D1, D5).
- **Provider Independence** : le modèle reste une chaîne de configuration. Le contrat neutre
  `AgentTurn` gagne « refusé », « modèle servi » et la narration ; les détails Anthropic (`fallback`,
  `iterations`, `display`, en-têtes bêta) restent dans `AnthropicAgentProvider`.
- **Gateway-First** : aucune logique d'IA ajoutée.
- **Justesse avant coût** : on garde l'effort explicite (pas le défaut `medium` du modèle) et on
  **relève** le plafond de sortie. Rien n'est retiré au raisonnement.

## Sources

- https://www.anthropic.com/claude-opus-5-5
- https://platform.claude.com/docs/en/models/opus-5-5/whats-new-opus-5-5
- https://platform.claude.com/docs/en/models/opus-5-5/migration-guide
- https://platform.claude.com/docs/en/build-with-claude/refusals-and-fallback
- https://platform.claude.com/docs/en/build-with-claude/thinking (progress updates, `display`)
- https://platform.claude.com/docs/en/manage-claude/api-and-data-retention

# Cadrage — F-171 Préfixe stable (effondrer le cache_write)

> Cadrage PO le 2026-10-01. Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.

## 1. Le problème (diagnostic prouvé en lecture de code + mesuré en prod)

Sur **opus-5**, le **cache_WRITE représente 71 % de la facture**. Ce n'est pas le cache_read
(bon marché, ÷20 vs écriture) qui coûte : c'est le fait de **réécrire** le cache à chaque tour.

**Cause racine.** La consigne système est un **bloc caché unique placé AVANT les messages**
(`AnthropicAgentProvider`, ordre tools → system → messages ; marqueur `cache_control` système ;
TTL 1 h ; quatre marqueurs au total ; marqueur intermédiaire + dernier message). Le cache
fonctionne par **préfixe** : dès qu'un octet du bloc système change, **tout ce qui suit dans le
préfixe est invalidé** et **réécrit** au lieu d'être relu — soit ~90 k tokens d'historique rejoués
**réécrits** à chaque tour.

Or ce bloc système contient aujourd'hui des **éléments volatils par travail**, que l'agent
**réécrit à chaque tour** et qui sont **re-lus après chaque tour** (`refreshAfterTurn`) :

- **`STATE.md` + `PLAN-ACTION.md`** du sujet courant (contenu borné à 6000 c./fichier, injecté
  dans `buildSystemPrompt` sous l'en-tête « État courant du sujet ») ;
- **l'arborescence / tree** du projet (et le **catalogue de skills** qui en est dérivé), relue à
  chaque tour.

À chaque tour, STATE/PLAN ou l'arborescence changent → le bloc système change → le cache casse au
**niveau système** → **tout l'historique** du préfixe est RÉÉCRIT. Au tarif opus-5, **écrire ≈ 20×
lire**.

## 2. Le correctif — Fix A : déplacer, pas retirer

Sortir **`STATE.md` + `PLAN-ACTION.md` + l'arborescence/tree (→ catalogue de skills)** du **bloc
système** (`buildSystemPrompt`) et les injecter **dans le MESSAGE du tour**, **exactement** comme le
font déjà :

- les **faits datés** (`hostKnowledge.factsFor`, préfixés à la `consigne`) ;
- la **conclusion rappelée** (`resolutionMemory.recall`, préfixée à la `consigne`) ;
- le **plan reporté** (`carriedPlanNote`, préfixé à la `consigne`).

C.-à-d. **préfixés à la `consigne`** (le message), donc **SOUS le dernier breakpoint** de cache.
Le bloc système redevient alors **byte-stable** d'un tour à l'autre → les ~90 k d'historique
**repassent en cache-READ** (÷20).

### Règle absolue PO — déplacer ≠ retirer

Le modèle doit voir **EXACTEMENT le même contenu** : mêmes octets de STATE / PLAN / arborescence /
catalogue de skills, **juste à un autre endroit** (message au lieu de système). **Zéro perte de
contexte, zéro impact raisonnement.** Tout en-tête / cadrage de ces sections est **préservé** (juste
relocalisé). Attention **F-148** (contenu de la consigne servi depuis la base via le cache de
sources, cible RUNNER amorcée) : l'**octet-identité du contenu servi** est préservée — on relit
exactement ce qui était injecté, on change seulement sa destination.

## 3. Fix C (gratuit, séparé) — TTL 1 h sur le relais passerelle

Le relais **chat-passerelle** (`ai/AnthropicProvider.systemField`) marque le système cacheable en
`cache_control: {type: ephemeral}` **sans `ttl`** → TTL **5 min** par défaut. L'Atelier, lui, pose
déjà `{type: ephemeral, ttl: 1h}` (`AnthropicAgentProvider.CACHE_CONTROL`). Aligner le relais sur
**`ttl: 1h`** = **gain gratuit** pour le chemin F-101 (chat passerelle). **Une ligne.**

## 4. Périmètre

- **SF-171-01** — le déplacement **STATE/PLAN + arborescence/tree (catalogue de skills)**
  système → message (le gros). Backend seul, cœur `AtelierChatService`.
- **SF-171-02** — Fix C : **TTL 1 h** sur le relais passerelle (`AnthropicProvider.systemField`).
  Backend seul, minuscule.

### Hors périmètre (explicite)

- **NE PAS toucher** le **sommaire de carte** (`hostOutline`, plafonné 4 k, déjà **stable** par
  construction F-136 : titres seulement, aucune date ni compte) — ce **n'est PAS** le driver.
- **NE PAS toucher** les **faits** (`factsFor`) — déjà dans le message.
- **NE PAS inclure** « carte par pertinence » / filtrage de l'outline — **écarté** : filtrer
  l'outline retirerait du contexte utile (violerait « justesse avant coût »).
- V3 (F-17 / F-18), multi-LLM runtime.

## 5. Garde-fous architecturaux

- **Gateway-First / Provider-First** respectés : on ne réimplémente aucune capacité IA, on
  réorganise l'assemblage du prompt.
- **Provider Independence** : aucune dépendance directe à Anthropic ajoutée ; Fix C reste dans
  l'implémentation `AnthropicProvider` (relais).
- **Isolation `user_id` / `workspace_id`** inchangée : les lectures STATE/PLAN/arborescence restent
  scoppées au couple (utilisateur, projet) du tour (mêmes helpers `promptFile` / `safeTree` /
  `promptSource`).
- **Préoccupation transversale « assemblage du prompt + cache »** : traitée explicitement dans
  chaque mini-spec (composants impactés recensés).

## 6. Preuve attendue

Le test qui **prouve le gain** : le bloc **système est byte-stable** entre deux tours **même si
STATE/PLAN/arborescence ont changé** entre les deux. Et : le contenu déplacé apparaît bien **dans le
message**, **sous le dernier breakpoint**, à l'**octet près**.

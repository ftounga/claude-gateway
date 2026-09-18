# Cadrage F-130 — Maîtrise du coût jetons (parité Claude Code)

> Cadrage produit. Subordonné à `docs/PROJECT.md`. Aucune capacité IA n'est réimplémentée :
> réglages de la boucle maison (`AtelierChatService`) et du relais fournisseur
> (`AnthropicAgentProvider`) uniquement — **Gateway-First / Provider-First** intacts.

## Date

2026-09-18

## Contexte chiffré (mesuré en prod, 2026-09-18 — établi, non ré-enquêté)

Consommation d'entrée énorme sur la boucle de l'Atelier :

- **~580 000 jetons d'ENTRÉE en moyenne PAR TOUR** ; ~200 tours/mois ⇒ **116 M jetons
  d'entrée/mois** ; pics observés à **5,6 M**.
- Sortie ~5 400 jetons/tour (négligeable devant l'entrée).
- **Le coût, c'est le contexte renvoyé à chaque tour.**

Le socle d'optimisation existe déjà (cache de prompt SF-39-01, écartement de contexte SF-39-12,
plafond de message SF-39-15, décompte au coût réel F-63) mais un point de **calibrage** est
sous-optimal pour le profil d'usage réel du PO.

## La contrainte non négociable du PO (2026-09-18)

> **Certitude d'avoir TOUJOURS le meilleur résultat.**

Cette contrainte prime sur toute optimisation de coût. Elle **exclut** toute optimisation qui
pourrait, même marginalement, dégrader ce que le modèle voit ou produit :

- on ne change **pas** le modèle (ce serait le lot B, voir plus bas) ;
- on ne **résume** pas le contexte (on écarte, décision existante D-L6-7) ;
- on ne rend **pas** l'écartement de contexte plus agressif ;
- on ne baisse **pas** le plafond de consommation par message ;
- on n'introduit **aucun** nouveau plafond de troncature sur ce que le modèle peut lire.

## Périmètre F-130

### Lot A — le présent correctif (SF-130-01) : cache de prompt en TTL 1 HEURE

**Le seul levier de gain PUR, à impact qualité STRICTEMENT NUL.**

Le cache de prompt (SF-39-01) marque le préfixe stable de la requête (outils + système +
préfixe d'historique) avec `cache_control: {"type":"ephemeral"}` — **TTL 5 minutes**. Or les
tours du PO sont **espacés dans la journée** : le cache expire entre deux tours, et l'on
**re-paie la CRÉATION** du cache (écriture, 1,25×/2× le tarif d'entrée) au lieu de la **LECTURE**
(0,1× le tarif d'entrée) — un écart de ~12,5× sur le plus gros poste de dépense.

Le TTL 1 heure (`cache_control: {"type":"ephemeral","ttl":"1h"}`) garde les entrées vivantes
à travers les pauses d'un usage étalé. **Il ne change RIEN à ce que le modèle voit** — mêmes
octets, même contexte, même préfixe — il change **seulement la facturation** (cache-read au lieu
de cache-creation). C'est exactement une des techniques de Claude Code. **Zéro risque qualité.**

Livré par **SF-130-01**.

### Lot A — écartés au titre de la contrainte « certitude du meilleur résultat » (décision PO 2026-09-18)

Le brief initial du lot A comportait trois autres réglages. Ils sont **écartés** :

| # | Réglage initial | Décision | Motif |
|---|-----------------|----------|-------|
| 2 | Baisser `triggerInputTokens` (écartement plus précoce) | **Écarté** | Écarterait davantage de contexte que le comportement actuel → risque d'ôter une info utile au modèle. Le seuil actuel (200 000) reste inchangé. |
| 3 | Plafonner la taille des sorties d'outils re-injectées | **Écarté** | Tronquerait ce que le modèle peut lire → risque qualité. Les plafonds existants (bash 128 Ko, lectures paginées) restent tels quels, aucun nouveau. |
| 4 | Baisser le plafond par message (4 M → 1,5–2 M) | **Écarté** | Risquerait de couper un tour AVANT le meilleur résultat. Le plafond reste **4 M**. |

Ces trois réglages restent des leviers de coût connus mais **ne seront pas actionnés** tant que la
contrainte « certitude du meilleur résultat » tient : ils touchent tous, de près ou de loin, ce
que le modèle voit ou jusqu'où il peut aller.

### Lot B — routage intelligent des modèles : **ABANDONNÉ**

Le lot B envisagé (routage/cascade de modèles selon la difficulté du tour) est **abandonné**
(décision PO 2026-09-18) : une cascade force à choisir un modèle moins capable sur certains tours,
ce qui contredit la contrainte « certitude du meilleur résultat » (et forfait la réutilisation du
cache, model-scoped). Il n'est pas reprogrammé.

## Ce que F-130 ne touche pas

- Le modèle (inchangé), le raisonnement (F-119), la parité (F-121), la discipline d'outils.
- Le cache existant (structure, points de marquage) — seul le **TTL** change.
- Le retry 429/529 (SF-39-11), le streaming (F-116), l'édition de contexte (SF-39-12).
- Le décompte de **volume** de jetons (inchangé et correct quel que soit le TTL).
- Le **tarif** d'écriture de cache (F-63) : laissé à 6,25 $/M. Il est partagé par le décompte de la
  boucle maison et celui des Managed Agents (même préfixe `app.atelier.agent.cost`, hors périmètre) ;
  le TTL 1 h rend les créations de cache rares, donc le résidu de sous-compte est négligeable et au
  bénéfice du client (jamais de sur-facturation). Le provider Managed Agents n'est pas touché.

## Références

- `docs/PROJECT.md` §3.2 (Gateway-First), §3.3 (Provider-First)
- SF-39-01 (cache de prompt), SF-39-12 (écartement de contexte), SF-39-15 (plafond de message)
- F-63 (décompte du quota au coût réel)
- Skill `claude-api` — `shared/prompt-caching.md` (TTL 1 h, économie, décompte `cache_creation`/`cache_read`)

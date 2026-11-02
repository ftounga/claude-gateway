# Mini-spec — F-239 / SF-239-11 — Des coûts sans escalade (garde-fou et cache du cerveau rapide)

## Identifiant
`F-239 / SF-239-11` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-11-couts-sans-escalade`

## Objectif
> Le bavardage ne bascule jamais sur Opus pour une raison de budget ou de quota, et le cerveau rapide relit son
> préfixe (consigne, univers, historique) au lieu de réécrire 6 600 jetons à chaque échange.

## Constat (prod, 2026-11-02)
- Cerveau rapide ≈ 0,028 $ par échange (`usage_turns`, `ANNEXE`, `claude-sonnet-5-5`) : `cache_read` 3 152 (consigne +
  outil) mais `cache_write` ≈ 6 600 **à chaque appel**. Cause : `AnthropicAgentProvider` marque toujours le **dernier**
  message ; chez le cerveau rapide, c'est celui qui porte tout le contexte volatil (heure, univers, attentes, fil…) —
  écrit, jamais relu.
- Plafond mensuel du cerveau rapide atteint ⇒ `fallback(REASON_BUDGET)` ⇒ l'écran renvoie **tout** à Opus (≈ 1,06 $
  le tour en moyenne, 10 à 50 fois plus cher). Quota du compte atteint ⇒ même chemin.

## Comportement attendu
### Cas nominal
- **Préfixe relu** : les messages du cerveau rapide deviennent `[univers de référence] → [« Noté. »] → échanges de la
  session → [contexte volatil + parole]`. L'univers ne porte plus d'heure (l'heure de l'état passe dans la partie
  volatile). La forme d'appel `compact` demande au fournisseur de **ne pas** marquer le dernier message : le marqueur
  va sur l'avant-dernier (fin de l'historique). Le volatil (heure, attentes, à envoyer, délégations, fil, alertes,
  coupure, parole) est lu au prix normal, sans écriture ; seul le dernier échange s'écrit.
- **Plafond du cerveau rapide** : au-delà du budget mensuel, le cerveau rapide **continue** (il reste 10 à 50 fois
  moins cher qu'Opus) ; l'écran en est averti une fois (`done.budget = "soft"`). Au-delà de **2 ×** le budget
  (plafond dur), **aucun appel** : une réponse fixe « Monsieur, le plafond mensuel de la conversation est atteint. »
  (`done.budget = "hard"`), et **jamais** de bascule vers Opus.
- **Quota du compte atteint** : réponse fixe « Le quota de votre compte est atteint, Monsieur. », aucun tour Opus
  (qui échouerait ou dépasserait).
- Les autres délégations d'office (erreur, délai, refus) restent vers Opus (justesse) — sauf pour une salutation
  (SF-239-02).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Univers illisible | Message de référence omis, le volatil porte le reste (comme avant) |
| Historique vide | `[univers] → [« Noté. »] → [volatil + parole]` : le marqueur reste sur « Noté. » |
| Budget à 0 € | Plafond dur immédiat (aucun appel), jamais Opus |

## Critères d'acceptation
- [ ] Deux appels successifs : messages 1..n-1 identiques octet pour octet au préfixe de l'appel suivant ; le dernier
      message n'est pas marqué ; l'avant-dernier l'est (test fournisseur).
- [ ] Le tour du terminal (forme `standard`) garde exactement son marquage (test existant inchangé).
- [ ] Budget dépassé (< 2 ×) ⇒ appel au cerveau rapide, `done.budget = soft` ; ≥ 2 × ⇒ réponse fixe, aucun appel,
      jamais FALLBACK.
- [ ] Quota atteint ⇒ réponse fixe, jamais FALLBACK.
- [ ] Mesure avant / après en € documentée (estimation, puis lecture `usage_turns` après déploiement).

## Périmètre — hors scope
Compteur visible (SF-239-12) ; budget par scénario du banc (SF-239-07) ; tour Opus du terminal (inchangé).

## Contraintes de validation
| Élément | Règle |
|---|---|
| Plafond dur | 2 × `app.jarvis.fast-brain.monthly-budget-eur` |
| Univers de référence | ≤ 9 000 caractères (borne existante) |

## Technique
- `AgentCallShape` : composante `cacheLastMessage` (vrai pour `standard`, faux pour `compact`).
- `AnthropicAgentProvider.toApiMessages` : marqueur sur l'avant-dernier message si `cacheLastMessage` est faux.
- `JarvisFastContextBuilder` : `reference(userId)` (univers sans heure) et `build(...)` sans l'univers.
- `JarvisFastBrainService` : messages, plafond doux / dur, quota ; `DoneEvent.budget`.
- Front : `jarvis-fast-brain.ts` lit `budget` ; l'écran affiche une fois « Budget de conversation dépassé ».
- Aucune table, aucune migration.

## Plan de test
- `AnthropicAgentProviderTest` : marquage `compact` vs `standard`.
- `JarvisFastBrainServiceTest` : préfixe stable sur deux appels ; plafond doux ; plafond dur sans appel ; quota sans
  FALLBACK.
- `JarvisFastContextBuilderTest` : univers hors du volatil, sans heure.
- Front : notice une fois au premier `budget: soft`.
- Isolation : non modifiée (univers lu sous `user_id`, test existant).

## Mesure du gain (vrais appels Sonnet 5.5, 2026-11-02, même forme qu'en prod)
Consigne + outil ≈ 3 000 jetons, univers ≈ 4 700, contexte volatil ≈ 1 000, quatre échanges successifs :

| Forme | Écrit en cache / appel | Relu / appel | Coût moyen / échange (hors 1er) |
|---|---|---|---|
| Avant (marqueur sur le dernier message, univers dedans) | ≈ 5 750 | 9 293 | **0,0376 $** |
| Après (univers en tête + « Noté. », marqueur sur l'avant-dernier) | ≈ 25 | ≈ 14 050 | **0,0077 $** |

**−80 %** par échange (le premier appel d'une session écrit le préfixe une fois). Pour 3 000 échanges / mois :
≈ 113 $ ⇒ ≈ 23 $ (≈ 104 € ⇒ ≈ 21 €). Le premier jeton arrive aussi plus tôt (préfixe relu). Lecture prod après
déploiement : `usage_turns` (`kind = 'ANNEXE'`, `cache_write_tokens` doit tomber de ≈ 6 600 à quelques dizaines).

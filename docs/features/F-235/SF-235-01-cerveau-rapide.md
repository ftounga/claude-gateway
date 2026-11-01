# Mini-spec — F-235 / SF-235-01 — Le cerveau rapide derrière `AiAgentProvider` (D1-D4, D15, D17)

## Identifiant
`F-235 / SF-235-01` · Feature parente `F-235` (Jarvis à deux cerveaux) · Statut `in-review` · 2026-11-01 ·
Branche `feat/SF-235-01-cerveau-rapide`

## Objectif
> Un modèle rapide (Claude Haiku 5.5, raisonnement éteint) répond à la parole du PO en flux ou la délègue à Opus,
> derrière `AiAgentProvider`, borné par un plafond propre et éteint par défaut (coupe-circuit).

## Comportement attendu
### Cas nominal
- `AgentReasoning.off(effort)` (nouvel état « éteint ») ; `AnthropicAgentProvider` le traduit en
  `thinking: {type: "disabled"}` (Haiku 5.5) ou `{type: "between_tools"}` (Sonnet 5.5, seul champ) ; aucune capacité
  qui suppose un raisonnement actif n'est posée (repli serveur, notes de progression, liaison du raisonnement,
  effort implicite). Rien ne change pour les appels existants (raisonnement non éteint).
- `AgentCallShape` (nouvelle forme d'appel, défaut `standard()` = comportement d'avant) : `compact(600)` ⇒
  `max_tokens` 600 (flux compris), **aucun outil serveur** (web_search/web_fetch), outils `strict: true`.
- Tarif `claude-haiku-5-5` : 0,10 / 0,50 / lecture 0,01 / écriture 0,20 $ par M jetons.
- `JarvisFastBrainProperties` (`app.jarvis.fast-brain.*`) : `enabled=false`, `model=claude-haiku-5-5`,
  `effort=low`, `max-tokens=600`, `monthly-budget-eur=20`, `first-token-timeout=PT3S`.
- `JarvisFastBrainPrompt` : consigne système **fixe** versionnée (identité, route D7, ancrage D9, style D11, formules
  interdites, exemples) + l'outil unique `deleguer` (strict : `demande`, `label`, `annonce`, `sujet`, `chantier`).
- `JarvisFastBrainService.converse` : plafond du mois (`jarvis_usage_days.fast_brain_cost_usd` × usd→eur) puis quota du
  compte, appel en flux, décision : **répondre** (texte) ou **déléguer** (outil) ; coût au journal d'usage
  (`recordAnnex`, appel annexe) et à la journée Jarvis.
- `POST /api/jarvis/converse` (SSE) `{subjectId, text, chantier?, heardBeforeCut?, turnSeq?}` ⇒ événements `text`
  (`{delta}`), `delegated` (`{delegationId, subjectId, chantier, label, announce, demande, automatic, reason}`),
  `fallback` (`{reason}`), `done` (`{brain: FAST|FAST_DELEGATED|OPUS, spokenText, firstTokenMs, model, turnSeq}`),
  `error`.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Coupe-circuit éteint (défaut) | rien ne part ; l'écran garde le mode d'avant | 503 |
| Sujet d'un autre compte / inexistant | introuvable, aucun appel modèle | 404 |
| `text` vide, > 4 000 car., `heardBeforeCut` > 2 000 car., `subjectId` absent | refus | 400 |
| Refus du modèle, erreur, réponse vide ou coupée | **délégation d'office** de la parole d'origine (`automatic=true`, raison), annonce « Je vérifie, Monsieur. » | 200 (flux) |
| Pas de premier jeton en 3 s | délégation d'office (`timeout`), le reste du flux est ignoré | 200 (flux) |
| Plafond du mois atteint / quota du compte épuisé | `fallback` (`budget`/`quota`) : mode d'avant, aucune erreur orale | 200 (flux) |
| Sujet cible nommé par le modèle mais d'un autre compte | ignoré : sujet courant | — |

## Critères d'acceptation
- [x] Une requête existante (raisonnement adaptatif, forme standard) est **strictement inchangée** (outils serveur, pas de `strict`, repli).
- [x] Haiku 5.5 éteint ⇒ `thinking.type=disabled` seul, `output_config.effort=low`, pas de `fallbacks`.
- [x] Sonnet 5.5 éteint ⇒ `thinking` = `{type: between_tools}` seul.
- [x] Appel compact ⇒ `max_tokens=600` (aussi en flux), un seul outil `deleguer`, `strict: true`, marqueur de cache sur l'outil.
- [x] La consigne système ne contient rien de volatil et est identique d'un appel à l'autre ; heure/sujet/chantier/parole sont dans le message.
- [x] Réponse ⇒ flux `text` puis `done.brain=FAST` ; délégation ⇒ `delegated` + `done.brain=FAST_DELEGATED`.
- [x] Refus / erreur / délai / vide ⇒ délégation d'office ; plafond / quota ⇒ `fallback`.
- [x] Isolation : sujet d'un autre compte ⇒ 404 sans appel fournisseur ; sujet cible du modèle vérifié sous `user_id`.
- [x] Coupe-circuit : 503 par défaut.

## Contraintes de validation
`text` 1..4 000 caractères ; `heardBeforeCut` ≤ 2 000 ; `subjectId` obligatoire ; plafond ≥ 0 € (0 = toujours en repli).

## Plan de test
- Unitaires `AnthropicAgentProviderTest` : mapping éteint Haiku/Sonnet, forme compacte (non streamée et streamée), requête standard inchangée.
- Unitaires `JarvisFastBrainServiceTest` : répondre, déléguer, sujet d'un autre compte ignoré, autre sujet du compte retenu, refus / erreur / délai / vide ⇒ délégation d'office, plafond ⇒ repli, quota ⇒ repli, consigne identique.
- Intégration `JarvisFastBrainApiIntegrationTest` (allumé) : flux `text` + `done`, 404 cross-compte sans appel, 400 ; `JarvisFastBrainDormantApiIntegrationTest` : 503.

## Tables / endpoints / composants
- Table `jarvis_usage_days` + `fast_brain_calls`, `fast_brain_cost_usd` (migration **235**, réversible).
- Endpoint `POST /api/jarvis/converse`.
- `agent/AgentReasoning`, `agent/AgentCallShape` (nouveau), `agent/AgentTurnRequest`, `agent/AnthropicAgentProvider`,
  `jarvis/fastbrain/*` (nouveau), `JarvisUsageService`, `application.yml`.

## Préoccupations transversales
- **Plans / limites** : `QuotaService.assertWithinQuota` (garde, inchangé), `UsageLedgerService.recordAnnex` (inchangé),
  grille de tarifs (+ `claude-haiku-5-5`), `JarvisFastBrainProperties.monthlyBudgetEur` (nouveau),
  `JarvisUsageService.monthFastBrainCostUsd` (nouveau) ; plafond voix `JarvisVoiceProperties` **inchangé** (somme
  séparée).
- **Contexte tenant** : `JarvisFastBrainController` (sujet par `findByIdAndUserId`), `JarvisFastBrainService.delegation`
  (sujet cible par `findByIdAndUserId`).
- **Auth** : nouvel endpoint authentifié (JWT, règle par défaut) ; Principal inchangé.

## Hors périmètre
Contexte complet (SF-235-02), garde-fous phrase par phrase (SF-235-03), personnalité (SF-235-09), délégation dans le
fil (SF-235-04), écran (SF-235-05).

## Arbitrages (pris par défaut, réversibles)
- Migration numérotée **235** (numéro de la feature) plutôt que le suivant libre : aucune collision possible avec les
  vagues parallèles ; ordre d'application sans dépendance (colonnes additives).
- Plafond porté par `jarvis_usage_days` (colonnes dédiées) plutôt que par une somme du journal d'usage : lecture
  O(1) par compte, même patron que la voix.
- La forme « éteint » de Sonnet 5.5 (`between_tools`) est une liste confinée au fournisseur (pas un réglage
  `@ConfigurationProperties` : un second constructeur casserait le contexte).

# Mini-spec — [F-172 / SF-172-08] Refus cyber non replié : diagnostiquer, puis relancer une fois sur un modèle permis

## Identifiant

`F-172 / SF-172-08`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (capacités API relayées), **rouverte** le 2026-11-07 pour ce correctif.

## Statut

`done`

## Date de création

2026-11-07

## Branche Git

`feat/SF-172-08-relance-refus-cyber`

---

## Constat (prod, 2026-11-06)

Sujet « waf » (EDENRED, configuration défensive d'un pare-feu applicatif) : 22 tours sur 58 refusés,
`stop_reason=refusal`, catégorie `cyber`, journal `modèle=claude-opus-5-5`. Le repli côté serveur
(`fallbacks: "default"`, SF-172-02) aurait dû rejouer sur `claude-opus-4-8`.

**Vérifications faites dans le code (preuves) :**

- `prepare()` pose `fallbacks: "default"` + l'en-tête `server-side-fallback-2026-07-01` dans `Prepared`
  (corps + `extraBetas`). Le chemin **en flux** de l'Atelier copie `prepared.body()` (`streamBody`) et
  passe par `requestSpec(prepared)` (lecture simple **et** surveillée SF-172-07) : le repli **est
  envoyé** en flux comme en appel complet.
- Config prod : `APP_ATELIER_MODEL=claude-opus-5-5`, ni `APP_ATELIER_FALLBACKS` ni
  `APP_AGENT_FALLBACK_MODELS` posés (configmap et secret vérifiés) → défauts de `application.yml` →
  `fallbacksFor("claude-opus-5-5")` est **vrai**.
- Le journal existant n'est **pas concluant** : il imprime `response.model`, qui en flux vient de
  `message_start` (modèle de départ), et ne dit rien de `usage.iterations` ni d'un bloc `fallback`.

Conclusion : la requête est correcte ; d'après la doc fournisseur, le repli « default » n'est pas tenté
quand le modèle cible est limité/surchargé (le refus revient alors avec `stop_details.recommended_model`).
Ce SF rend la cause **observable** et pose le plancher documenté : une relance client.

## Objectif

Savoir pourquoi le repli serveur n'a pas servi, et, quand il n'a pas servi sur un refus `cyber` /
`frontier_llm`, relancer une fois le même tour sur un modèle permis.

---

## Comportement attendu

### Cas nominal

1. **Diagnostic** (`refusedTurn`, WARN, sans contenu) : catégorie, modèle de la réponse, modèle servi,
   `recommended_model` (ou `aucun`), nombre d'entrées `usage.iterations`, présence d'un bloc `fallback`,
   présence d'une entrée `fallback_message`.
2. **Relance client** — conditions cumulatives :
   - coupe-circuit `app.agent.api-features.refusal-retry` (`APP_AGENT_REFUSAL_RETRY`, défaut `true`) ;
   - modèle demandé **listé** dans `fallback-models` (modèles à classifieurs ; les autres ne sont jamais
     relancés — comportement d'avant) ;
   - catégorie `cyber` ou `frontier_llm` ;
   - le serveur n'a **pas** replié : ni bloc `fallback`, ni entrée `fallback_message` ;
   - cible ≠ modèle demandé.
   Cible = `stop_details.recommended_model` s'il est fourni, sinon `refusal-retry-model`
   (`APP_AGENT_REFUSAL_RETRY_MODEL`, défaut `claude-opus-4-8`).
3. La relance **reconstruit** la requête pour la cible (même `prepare()`, modèle remplacé) : les
   capacités par modèle s'appliquent (pas de `fallbacks`, pas de `display: updates`, pas de
   liaison du raisonnement si la cible n'est pas listée). Deux ajustements de la requête domaine :
   - consignes d'effort par message retirées si la cible ne les connaît pas (Opus 4.8) — l'effort
     effectif (dernière consigne, sinon celui de la requête) passe à la racine ;
   - raisonnement adaptatif explicite (Opus 4.8 sans `thinking` raisonne à zéro : interdit par la règle
     « justesse avant coût »), sauf requête explicitement sans raisonnement.
   Les blocs de raisonnement d'Opus 5.5 restent dans l'historique : l'API les écarte, non facturés.
4. **Usage** : le tour relancé porte la part refusée (au modèle demandé) **plus** la sienne (au modèle
   servi) ; totaux = somme ; `servedModel` = modèle de la relance.
5. Journal INFO : relance (de → vers, origine de la cible) et issue (servie / refusée / échec).

### Cas d'erreur

- Relance refusée → le refus **d'origine** remonte inchangé.
- Relance en échec (400, 5xx épuisé, délai…) → WARN avec le type d'erreur, refus d'origine remonté.
- Catégorie `bio`, `reasoning_extraction`, nulle… → aucune relance.

---

## Critères d'acceptation

- [x] Refus cyber sans bloc fallback, `recommended_model` fourni → 2e appel sur ce modèle, tour servi.
- [x] Sans `recommended_model` → 2e appel sur `claude-opus-4-8` (configuré).
- [x] Relance refusée → refus d'origine (catégorie, usage) inchangé.
- [x] Catégorie `bio` → un seul appel.
- [x] Coupe-circuit off → un seul appel.
- [x] Bloc `fallback` présent → un seul appel.
- [x] Requête de relance sans `fallbacks` ni `display: updates`, effort par message replié à la racine.
- [x] Le contexte Spring démarre (suite complète).

## Plan de test

- Unitaires `AnthropicAgentProviderTest` (serveur simulé) : les 6 cas ci-dessus + forme de la requête
  de relance + usage ventilé ; flux SSE pour le cas nominal.
- Intégration : suite complète (démarrage du contexte, record `@ConfigurationProperties` à un seul
  constructeur).
- Isolation utilisateur : sans objet (aucun accès aux données, la clé du tour est réutilisée telle quelle).

## Impacts

- `AnthropicAgentProvider` (diagnostic, relance), `AgentApiFeaturesProperties` (+2 champs),
  `application.yml`. Aucune table, aucun endpoint, aucun écran.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans/limites — l'usage remonte par le
  chemin existant `usageByModel`, ni navigation).

## Hors périmètre

- Changer le modèle par défaut, ajuster des quotas fournisseur, toute réécriture du message refusé.
- Effacer à l'écran le texte partiel d'un refus survenu en cours de flux (comportement inchangé).

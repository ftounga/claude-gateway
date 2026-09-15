# Mini-spec — [F-119 / SF-119-01] Effort de raisonnement soutenu là où on investigue

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-119/CADRAGE-F-119-justesse-de-l-agent.md` (Cause 1).

---

## Identifiant

`F-119 / SF-119-01`

## Feature parente

`F-119` — La justesse de l'agent : se tromper moins, se corriger moins

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-119-01-effort-soutenu`

---

## Objectif

> En une phrase : ré-escalader l'effort de raisonnement de la boucle maison **sur signal de
> difficulté** (résultat d'outil en erreur, `bash` en code de sortie ≠ 0, `edit_file` raté,
> timeout/indispo runner, auto-contradiction du modèle) au lieu de rester bloqué à l'effort réduit
> `stepEffort` sur toutes les continuations — tout en **préservant le gain de vitesse F-118** sur une
> trajectoire qui roule sans incident.

---

## Comportement attendu

### Cas nominal

1. **Premier tour d'une demande** (`iteration == 0`) : effort **normal** (`app.atelier.effort`,
   défaut `high`) — inchangé (F-118).
2. **Étape de continuation qui roule sans incident** : le tour précédent n'a produit **aucun** signal
   de difficulté → effort **réduit** (`app.atelier.step-effort`, défaut `low`) — inchangé (F-118), le
   gain de vitesse/coût est préservé.
3. **Étape de continuation après un signal de difficulté** : le tour précédent a produit au moins un
   signal → le tour suivant repasse à l'effort **normal** (`effort`) au lieu de `stepEffort`.
4. **Sous-boucle `explore`** (`AtelierExploration.run`) : ne part plus avec `AgentReasoning.none()`
   (zéro raisonnement) mais avec un raisonnement adaptatif à effort configurable non nul
   (`app.atelier.explore-effort`, défaut `low`).

### Signaux de difficulté (déclenchent la ré-escalade du tour suivant)

- un `tool_result` marqué en erreur (`outcome.isError()`), ce qui couvre déjà : `edit_file` en échec
  (« texte introuvable »), timeout/indisponibilité runner, refus, argument manquant ;
- un `bash` dont le **code de sortie** est ≠ 0 (l'appel réussit mais la commande échoue — ce n'est
  pas `isError`) ;
- une **auto-contradiction détectable** dans le texte du tour (marqueurs sobres : « je me suis
  trompé », « erreur de ma part », « au temps pour moi », « c'était faux », « je me corrige »…).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Valeur d'effort inconnue en config (`explore-effort`, faute de frappe) | Retombe sur le défaut (`low`), n'arrête pas le démarrage — même repli que `effort`/`step-effort` |
| Coupe-circuit `app.atelier.escalate-on-signal=false` | Aucune ré-escalade : comportement F-118 strict (réduit sur toutes les continuations) |
| Coupe-circuit `app.atelier.adaptive-effort=false` | Effort normal à chaque étape (comportement d'avant F-118) — la ré-escalade devient sans objet |
| Contenu `bash` sans marqueur de code de sortie exploitable / code « inconnu » | Pas de signal (on n'invente pas un échec) — conservateur |

---

## Critères d'acceptation

- [ ] Un tour de continuation dont le tour précédent a produit un `tool_result` en erreur part avec
      l'effort **normal** (`high`), pas `stepEffort`.
- [ ] Un tour de continuation dont le tour précédent a produit un `bash` à code de sortie ≠ 0 part
      avec l'effort **normal**.
- [ ] Un tour de continuation dont le tour précédent a produit un `edit_file` raté part avec l'effort
      **normal** (couvert par `isError`).
- [ ] Un tour de continuation dont le tour précédent a roulé **sans incident** reste à l'effort
      **réduit** (`low`) — non-régression F-118.
- [ ] La sous-boucle `explore` reçoit un `AgentReasoning` adaptatif à effort non nul (plus jamais
      `none()`).
- [ ] Le coupe-circuit `escalate-on-signal=false` restaure le comportement F-118 strict.
- [ ] `xhigh`/`max` restent des valeurs d'effort acceptées et configurables pour `effort` (le bridage
      « xhigh après le lot 6 » de la doc yaml est levé : le streaming F-116 est livré).
- [ ] Tout est réversible par variable d'environnement, aucun défaut ne change le comportement livré
      sur une trajectoire sans incident (sauf `explore` qui passe de `none` à `low`, décision assumée).

---

## Périmètre

### Hors scope (explicite)

- Une heuristique de « niveau » d'escalade graduée (medium→high→xhigh selon la gravité) : on
  ré-escalade en une fois vers l'effort normal.
- Changer le **défaut** d'`effort` (reste `high`) ou de `stepEffort` (reste `low`).
- La discipline de prompt (SF-119-02), la mémoire des preuves (SF-119-03), la sortie partielle des
  bash (SF-119-04), le suivi d'état de fichier (SF-119-05).

---

## Valeurs initiales / réglages

| Propriété | Clé env | Défaut | Règle |
|-----------|---------|--------|-------|
| `escalateOnSignal` | `APP_ATELIER_ESCALATE_ON_SIGNAL` | `true` | Absent ⇒ actif. `false` ⇒ comportement F-118 strict |
| `exploreEffort` | `APP_ATELIER_EXPLORE_EFFORT` | `low` | Même repli que `effort` : valeur inconnue ⇒ défaut |

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierProperties` | Ajout `escalateOnSignal`, `exploreEffort` (fin du record) + constructeurs de compatibilité | Repli des valeurs inconnues comme `effort` |
| `AtelierChatService` | `reasoningForIteration(iteration, escalate)` ; détection de signal par tour ; `exploreReasoning` ; passe le raisonnement à `explore` | Cœur de la SF |
| `AtelierExploration` | `run(...)` reçoit un `AgentReasoning` et le met dans `AgentTurnRequest` | Plus de `none()` |
| `application.yml` | Clés `explore-effort`, `escalate-on-signal` ; commentaire `xhigh` corrigé | Réglable sans livraison |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, aucune migration, aucun frontend.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPropertiesTest` — défauts (`escalateOnSignal=true`, `exploreEffort=low`), repli valeur
      inconnue d'`exploreEffort`, valeurs honorées, coupe-circuit `escalateOnSignal=false`.
- [ ] `AtelierChatServiceReasoningTest` — après un `tool_result` en erreur : effort normal ; après un
      succès propre : effort réduit (non-régression) ; auto-contradiction dans le texte → effort
      normal ; coupe-circuit `escalate-on-signal=false` → réduit malgré l'erreur.
- [ ] `AtelierChatServiceRunnerTargetTest` (ou dédié) — `bash` à code de sortie ≠ 0 → tour suivant à
      l'effort normal ; `bash` à code de sortie 0 → tour suivant réduit.
- [ ] `explore` — vérifier que `AtelierExploration.run` est appelée avec un raisonnement non nul
      (assertion sur le `reasoning` du `AgentTurnRequest` vu par le stub dans la sous-boucle).

### Tests d'intégration

- [ ] Contexte Spring : le démarrage lie les nouvelles propriétés sans erreur (défauts).

### Isolation utilisateur

- [ ] Non applicable — aucun nouvel accès aux données ; l'isolation `user_id`/`workspace_id` de la
      boucle est inchangée (aucun nouveau chemin de lecture/écriture).

---

## Préoccupations transversales

- **Auth / Principal / tenant / navigation** : non — boucle d'agent interne, aucun endpoint ni route.
- **Plans / limites** : la ré-escalade **augmente** la consommation de jetons sur les tours après un
  incident (effort plus élevé). C'est l'arbitrage explicite du cadrage §1/§3 : plein *seulement* quand
  c'est utile. Composants de limites concernés (vérifiés inchangés) : `QuotaService.recordUsage`
  (compte les jetons réellement consommés, quel que soit l'effort), plafond par message
  `maxTurnTokens` et budget de temps `turnBudget` (bornes inchangées, la ré-escalade reste sous
  elles). Aucun nouveau *gate*, aucun quota nouveau.

---

## Notes et décisions

- **Décision par défaut** : `escalateOnSignal=true`. Justesse > vitesse sur les tours d'investigation,
  et le gain F-118 reste sur les trajectoires sans incident (majorité des étapes triviales).
- **Décision par défaut** : `exploreEffort=low` — non nul (correctif du `none()`), mais sobre pour ne
  pas alourdir chaque sous-boucle de lecture.
- **Auto-contradiction** : détectée par une liste fermée de marqueurs français sobres sur le texte du
  tour ; conservateur (faux négatifs préférés aux faux positifs), sous le coupe-circuit
  `escalateOnSignal`.
- **xhigh** : aucune borne de code ne l'interdisait (`ALLOWED_EFFORTS` le contient déjà) ; seul le
  commentaire yaml « attend le lot 6 » était périmé (streaming F-116 livré) — corrigé.

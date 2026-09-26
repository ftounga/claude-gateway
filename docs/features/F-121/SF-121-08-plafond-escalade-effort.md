# Mini-spec — F-121 / SF-121-08 — Plafond d'escalade d'effort configurable (`high` → `xhigh` / `max`)

## Identifiant

`F-121 / SF-121-08`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart de parité **Lot 2** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §4 :

> **F-121-08** — **Escalade d'effort plafonnée à `high`.** `escalateEffort` configurable (défaut
> `high`, jusqu'à `xhigh`/`max`, déjà autorisés `AtelierProperties.java:140`) consommé par
> `reasoningForIteration` ; nettoyer le commentaire périmé (`:29-32`, streaming F-116 livré).

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-08-plafond-escalade-effort`

---

## Objectif

Rendre **configurable** le niveau d'effort auquel la boucle maison remonte quand elle ré-escalade sur
signal de difficulté (F-119 / SF-119-01), aujourd'hui plafonné à l'effort **normal** (`high`), pour
qu'un incident puisse déclencher un vrai `xhigh`/`max` sans livraison.

---

## Contexte — pourquoi c'est un écart de parité

F-118 baisse l'effort dès la deuxième étape (`step-effort`, `medium`) ; F-119 le **remonte** quand le
tour précédent a produit un signal de difficulté (résultat d'outil en erreur, `bash` en code ≠ 0,
`edit_file` raté, timeout runner, auto-contradiction). Mais « remonter » signifie aujourd'hui
*revenir à l'effort normal* : `reasoningForIteration` rend `reasoning`, construit sur
`app.atelier.effort` (défaut `high`).

Conséquence : **le moment où il faudrait réfléchir le plus est plafonné au niveau ordinaire.** Monter
`app.atelier.effort` à `xhigh` pour y remédier monterait l'effort de *tous* les premiers tours — y
compris les demandes triviales — et paierait la profondeur en latence et en coût sur 100 % du trafic
au lieu des seuls tours d'incident.

Le vocabulaire `xhigh`/`max` est **déjà** accepté (`ALLOWED_EFFORTS`) et déjà transmis correctement au
fournisseur (`output_config.effort`, testé). Il ne manque qu'un levier dédié.

Au passage : le javadoc de `effort` (`AtelierProperties.java:29-32`) affirme encore que « `xhigh`
attend le lot 6 : la boucle appelle en **non-streamé** ». Le streaming (F-116 / SF-116-01, avec
timeout et retry) est **livré** ; `application.yml` le dit déjà, le javadoc non. Commentaire périmé à
nettoyer.

---

## Comportement attendu

### Cas nominal

Nouveau réglage `app.atelier.escalate-effort` (env `APP_ATELIER_ESCALATE_EFFORT`), vocabulaire
`low | medium | high | xhigh | max`, **absent/inconnu ⇒ repli sur `app.atelier.effort`** — donc
strictement le comportement d'aujourd'hui tant que personne ne l'exprime.

| Tour | `escalate-effort` absent (défaut) | `escalate-effort: xhigh` |
|------|-----------------------------------|--------------------------|
| Itération 0 (premier tour) | `effort` (`high`) — inchangé | `effort` (`high`) — **inchangé** |
| Continuation **sans** signal | `step-effort` (`medium`) — inchangé | `step-effort` (`medium`) — **inchangé** |
| Continuation **avec** signal de difficulté | `effort` (`high`) | **`xhigh`** |
| `adaptive-effort: false` (coupe-circuit F-118) | `effort` à chaque étape | `effort` à chaque étape — **inchangé** |
| `escalate-on-signal: false` (coupe-circuit F-119) | `step-effort` en continuation | `step-effort` en continuation — **inchangé** |

Le plafond ne s'applique donc **qu'au** chemin de ré-escalade : il n'ouvre aucun autre tour, et les
deux coupe-circuits existants le neutralisent tels quels.

Inchangé également : l'effort voyage **dans** la conversation (`per-message-effort`, F-134 /
SF-134-05) — la racine de la requête garde `effort` constant, donc **le cache de prompt n'est pas
invalidé** par ce réglage ; le décompte d'usage, le budget de tour et le plafond d'itérations sont
intacts.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `escalate-effort` absent / vide | Repli sur `effort` — comportement d'avant SF-121-08, aucun tour perturbé |
| `escalate-effort` inconnu (`tres-fort`, `HIGH`, `xxl`…) | Repli sur `effort` (même règle que `effort`/`step-effort` : une faute de frappe ne fait **pas** échouer le démarrage ni muter les tours) |
| `escalate-effort` valide mais **inférieur** à `step-effort` (ex. `low`) | Honoré tel quel — c'est un réglage d'exploitation assumé, pas une incohérence à corriger en silence |
| `adaptive-effort: false` | Réglage **sans effet** (comme `escalate-on-signal`) : l'effort normal s'applique à chaque étape |
| `escalate-on-signal: false` | Réglage **sans effet** : aucune ré-escalade n'a lieu |

---

## Critères d'acceptation

- [x] CA1 — `app.atelier.escalate-effort` absent : l'effort d'un tour de continuation **après signal**
      vaut exactement `effort` (`high`) — non-régression stricte de F-119.
- [x] CA2 — `escalate-effort: xhigh` : le tour de continuation **après signal** part à `xhigh`.
- [x] CA3 — `escalate-effort: xhigh` : le **premier** tour reste à `effort` (`high`) et une
      continuation **sans** signal reste à `step-effort` (`medium`).
- [x] CA4 — `escalate-effort` inconnu ou vide retombe sur `effort` (aucune exception au démarrage).
- [x] CA5 — `escalate-effort` suit `effort` quand il n'est pas exprimé : avec `effort: max` et
      `escalate-effort` absent, l'escalade vaut `max`.
- [x] CA6 — Coupe-circuits intacts : `adaptive-effort: false` et `escalate-on-signal: false` rendent
      le réglage sans effet.
- [x] CA7 — Les constructeurs de compatibilité d'`AtelierProperties` (formes antérieures) restent
      appelables et appliquent le repli.
- [x] CA8 — Le javadoc périmé de `effort` (« `xhigh` attend le lot 6 / appel non-streamé ») est
      corrigé ; `application.yml` documente le nouveau réglage.
- [x] CA9 — `mvn -pl backend test` vert ; aucune migration, aucun endpoint, aucun frontend touché.

---

## Périmètre

### Hors scope (explicite)

- **Changer le défaut** d'effort en production : le réglage est livré au niveau actuel (`high` via le
  repli sur `effort`). Monter à `xhigh`/`max` est une **décision d'exploitation**, pas de livraison.
- Un `budget_tokens` de thinking fixe (explicitement écarté par le cadrage §« non retenus » : il
  régresserait le sens adaptatif de l'effort).
- L'escalade **progressive** (un cran par incident successif) : un seul plafond, atteint au premier
  signal.
- Le chemin **Managed Agents** (`app.atelier.agent.effort`, SF-28-17) : réglage distinct, non touché.
- La sous-boucle d'exploration (`explore-effort`) et la sous-boucle `task` : non touchées.
- Tout écran / réglage utilisateur : le levier est un réglage d'exploitation (variable
  d'environnement), pas une préférence de compte.

---

## Valeurs initiales

Sans objet — aucune entité, aucune table, aucun état persisté. Le seul « état » ajouté est un réglage
de configuration.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.atelier.escalate-effort` | Non | — | `low` \| `medium` \| `high` \| `xhigh` \| `max` (sensible à la casse, comme `effort`) | — | Absent / vide / inconnu ⇒ repli sur `app.atelier.effort` |

Notes :
- Même ensemble `ALLOWED_EFFORTS` que `effort`, `step-effort` et `explore-effort` — une seule vérité
  de vocabulaire.
- Le repli vise `effort` (et non la constante `high`) pour qu'une exploitation qui a déjà relevé
  `effort` ne se retrouve pas avec une escalade **plus basse** que son régime ordinaire.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — réglage de configuration, aucun schéma touché.

### Composants Angular (si applicable)

Aucun — aucune UI. La feature parente F-121 a une UI (le terminal), mais cette subfeature est un
réglage d'exploitation **invisible** : pas de subfeature frontend à planifier.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `backend/src/main/java/fr/claudegateway/atelier/AtelierProperties.java` | composant `escalateEffort` (26ᵉ), repli dans le constructeur compact, constructeur de compatibilité (forme 25 composants), javadoc périmé corrigé |
| `backend/src/main/java/fr/claudegateway/atelier/AtelierChatService.java` | champ `escalateReasoning`, consommé par `reasoningForIteration(int, boolean)` |
| `backend/src/main/resources/application.yml` | `escalate-effort: ${APP_ATELIER_ESCALATE_EFFORT:}` + commentaire |
| `backend/src/test/java/fr/claudegateway/atelier/AtelierPropertiesTest.java` | tests de repli / valeur honorée / compat |
| `backend/src/test/java/fr/claudegateway/atelier/AtelierChatServiceReasoningTest.java` | tests de bout en bout sur `effectiveEfforts` |

---

## Plan de test

### Tests unitaires

- [x] `AtelierPropertiesTest` — `escalate-effort` absent ⇒ `escalateEffort() == effort()` (`high`).
- [x] `AtelierPropertiesTest` — `escalate-effort` inconnu / vide ⇒ repli sur `effort()`.
- [x] `AtelierPropertiesTest` — `escalate-effort: xhigh` ⇒ honoré ; `max` ⇒ honoré.
- [x] `AtelierPropertiesTest` — `effort: max` + `escalate-effort` absent ⇒ escalade `max` (le repli
      suit `effort`, pas la constante).
- [x] `AtelierPropertiesTest` — constructeur de compatibilité (forme 25 composants) ⇒ repli appliqué.

### Tests d'intégration

Pas d'endpoint : l'« intégration » est la boucle d'agent complète, couverte via `StubAiAgentProvider`
et le journal `effectiveEfforts` (le niveau réellement transmis au fournisseur, tour par tour).

- [x] `AtelierChatServiceReasoningTest` — erreur d'outil + `escalate-effort: xhigh` ⇒
      `effectiveEfforts == [high, xhigh]`.
- [x] `AtelierChatServiceReasoningTest` — erreur d'outil, réglage absent ⇒ `[high, high]`
      (non-régression F-119).
- [x] `AtelierChatServiceReasoningTest` — continuation **propre** + `escalate-effort: xhigh` ⇒
      `[high, medium]` (le gain F-118 est préservé).
- [x] `AtelierChatServiceReasoningTest` — `escalate-on-signal: false` + `escalate-effort: xhigh` ⇒
      `[high, medium]`.

### Isolation workspace / `user_id`

- [ ] Non applicable — raison : la subfeature ne lit et n'écrit **aucune donnée** ; c'est un réglage
      global de la boucle, appliqué au tour de l'utilisateur déjà authentifié et déjà isolé par
      `requireOwned(userId, workspaceId)` en amont (chemin inchangé).

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse d'impact |
|--------------|-----------|------------------|
| Auth / Principal | Non | Aucun changement d'authentification, de Principal ni de session. |
| Contexte tenant | Non | Aucune résolution de tenant touchée ; `requireOwned` inchangé. |
| **Plans / limites** | **Oui (indirect)** | L'effort pèse sur le **coût** d'un tour. Composants de limites concernés, tous **inchangés** et vérifiés : `QuotaService.currentUsage` (plafond de tour dérivé du quota), `maxTurnTokens` (`app.atelier.max-turn-tokens`, 4 M), `turnBudget` (budget de temps), `maxIterations`. Aucun n'est contourné : un tour à `xhigh` consomme plus vite **les mêmes** plafonds, qui s'appliquent tels quels. Et le défaut livré ne change **aucun** niveau d'effort → aucune variation de coût sans geste d'exploitation explicite. |
| Navigation / routing | Non | Aucune route, aucun guard, aucun écran. |
| **Cache de prompt (F-134)** | Non | `per-message-effort` reste vrai : l'effort voyage dans la conversation, la racine garde `effort` constant. Aucun marqueur `cache_control` déplacé, aucun préfixe système touché. |
| **Provider Independence** | Non | Le niveau reste une **chaîne** portée par `AgentReasoning` via `AiAgentProvider` ; aucun couplage Anthropic ajouté, aucun modèle en dur. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-119-01` (ré-escalade sur signal) — statut : **done**. C'est le chemin étendu.
- `SF-118-01` (effort adaptatif à l'étape) — statut : **done**.
- `SF-134-05` (effort par message) — statut : **done**.
- `SF-116-01` (streaming) — statut : **done** — lève la réserve qui plafonnait `xhigh`.

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est impactée.

---

## Notes et décisions

- **D1 — Repli sur `effort`, pas sur la constante `high`.** Un déploiement qui a déjà relevé
  `app.atelier.effort` ne doit pas se retrouver avec une escalade **en dessous** de son régime
  ordinaire. Corollaire : sans configuration, le comportement est byte-identique à aujourd'hui.
- **D2 — Un seul plafond, pas d'escalade progressive.** Le signal de difficulté est binaire dans
  F-119 ; y greffer des crans demanderait un compteur d'incidents par tour, hors périmètre.
- **D3 — Le réglage n'a d'effet que sous les deux coupe-circuits existants** (`adaptive-effort`,
  `escalate-on-signal`), au lieu d'un troisième interrupteur : moins de combinaisons, une seule
  sémantique à retenir.
- **D4 — Défaut livré inchangé.** Livrer le levier ≠ tirer le levier. Monter à `xhigh` en production
  est un arbitrage profondeur / latence / coût qui se fait par variable d'environnement, observable
  et réversible sans livraison.
- **Gateway-First / Provider-First** : aucune capacité IA réimplémentée — on règle ce qu'on demande au
  fournisseur, c'est lui qui raisonne.

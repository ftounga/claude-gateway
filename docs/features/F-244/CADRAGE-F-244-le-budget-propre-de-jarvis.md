# CADRAGE F-244 — Le budget propre de Jarvis

**Statut :** cadrage du 2026-11-02 (demande PO du même jour). Aucun code. Famille « Jarvis » : F-230 → F-243, **F-244**.
**Estimation :** 4 SF, ≈ 3,75 j. **Ordre recommandé : en premier** des quatre features cadrées le 2026-11-02 (F-242
à F-245), pour que tout nouveau coût Jarvis, le courrier en particulier, naisse étiqueté.

## 1. Demande PO (2026-11-02)

Jarvis a **sa propre ventilation** et **son propre plafond**, **séparés** des plafonds de Claude Gateway.

Cible :

- **Tout** coût d'origine Jarvis est **étiqueté** et **compté au budget Jarvis** : le cerveau rapide, la voix, la
  transcription, les délégations à Opus, et demain le courrier et l'agenda.
- Le budget Jarvis a un **plafond propre**.
- Il **ne consomme pas** le quota Gateway.
- Il s'affiche **en €**.
- Il n'y a **jamais d'escalade vers Opus** pour une raison de coût (règle F-239).

## 2. Constat précis : où va chaque coût aujourd'hui (code vérifié le 2026-11-02, `origin/main` `2ca051d1`)

### 2.1 Tableau de ventilation

| Coût d'origine Jarvis | `jarvis_usage_days` | `usage_turns` | `usage_counters` (le quota Gateway) | Contrôlé par |
|---|---|---|---|---|
| **Cerveau rapide** (`JarvisFastBrainService.account`, l. 1293-1310, et le pré-chauffage l. 369) | `fast_brain_calls`, `fast_brain_cost_usd` | **oui**, `kind = ANNEXE` | **non** (« ne touche PAS au quota ») | Le plafond rapide de 20 €/mois (souple, dur à 2×) **et** `QuotaService.assertWithinQuota` (l. 468-473) : **un quota Gateway épuisé coupe le cerveau rapide** (« Le quota de votre compte est atteint ») |
| **Délégation ou tour Opus vocal** (`via = 'JARVIS'`, `VoiceMark`) | `opus_turns`, `opus_cost_usd` (l. 4722-4729) | **oui**, `kind = TOUR`, **sans marque d'origine** | **oui**, en Hosted | **Le quota Gateway** (`assertWithinQuota` l. 3282, et le plafond par message `AtelierTurnBudget.hosted(maxTurnTokens, remainingTokens)` l. 3283). **Aucun plafond Jarvis.** |
| **Tour Opus Jarvis coupé** (`recordCutTurn`, l. 6760) | **absent** (pas d'`addOpus`) | oui, `TOUR` | oui | Le quota Gateway |
| **Voix (TTS)** (`JarvisVoiceService.account`, l. 226) | `chars_spoken`, `speech_cost_usd`, au tarif réel (OpenAI ou ElevenLabs) | **oui, `kind = TOUR`** (`SpeechUsage.record`), au **tarif OpenAI même pour ElevenLabs**, jetons estimés à caractères ÷ 4 | non | Le plafond voix de 100 €/mois (TTS + STT), qui renvoie 429 `jarvis_voice_budget` |
| **Transcription en flux et retranscription** (`JarvisSttSessionService`, `JarvisUtteranceService`) | `stt_stream_seconds`, `stt_stream_cost_usd` (**confondues** : les deux sources partagent ces colonnes) | non | non | Le plafond voix |
| **Dictée de repli** (`/api/atelier/transcription`, `DictationService`, appelée par `jarvis.component.ts` l. 921) | **absent** | **absent** | **absent** | Aucun |

**Réponse à la question du PO :** **oui**. Les délégations à Opus sont aujourd'hui **comptées dans les quotas
Gateway** : elles sont contrôlées avant le tour par `assertWithinQuota`, elles consomment `usage_counters` et elles
écrivent `usage_turns` en `TOUR`. **En même temps**, elles sont ajoutées à `jarvis_usage_days.opus_cost_usd`. C'est
un **double comptage**. Rien dans `usage_turns` ne les distingue d'un tour tapé : la seule trace persistée est
`atelier_messages.via = 'JARVIS'`, un champ d'affichage.

### 2.2 Autres constats

| # | Constat | Où |
|---|---|---|
| 1 | Le compteur en € (SF-239-12) additionne les quatre colonnes en USD (voix, STT, cerveau rapide, Opus) pour le jour et le mois (Europe/Paris), puis multiplie par `app.cost.provider.usd-to-eur` (0,92). `JarvisUsageDay.totalCostUsd()` **n'additionne que la voix et la STT**. | `jarvis/JarvisCostService.java` l. 60-79, `JarvisUsageDay.java` l. 149 |
| 2 | Il existe **deux** plafonds Jarvis partiels : la voix (100 €/mois, `APP_JARVIS_VOICE_MONTHLY_BUDGET_EUR`) et le cerveau rapide (20 €/mois, `APP_JARVIS_FAST_MONTHLY_BUDGET_EUR`, souple à 1×, dur à 2×). Il n'y a **pas de plafond global**, **pas de plafond sur Opus via Jarvis** et **pas de plafond quotidien**. Les plafonds sont des réglages de déploiement, pas des réglages utilisateur. | `application.yml` l. 631 et 648 |
| 3 | Le contrôle de quota du tour (l. 3282) a lieu **avant** la lecture de la `VoiceMark` (l. 3306). Pour qu'un tour Jarvis échappe au quota Gateway, il faut lire la marque **avant** ce contrôle. | `AtelierChatService.runLoopOnLane` |
| 4 | Les tableaux de coût Gateway (`/api/admin/cost/*`, `/api/usage*`) agrègent `kind = TOUR`. La **voix Jarvis y figure donc déjà** (TTS écrit en `TOUR`), au mauvais tarif. | `UsageTurnRepository` l. 52-56, 120, 143, 163, 209 |
| 5 | Les budgets par client F-133 sont hebdomadaires et **ne bloquent rien** (alerte seulement). | `quota/CostBudgetService.java` |
| 6 | La règle « jamais d'escalade de coût » est écrite dans `SF-239-11-couts-sans-escalade.md` (l. 7-8, 15-31) et rappelée dans le code (`JarvisFastBrainService` l. 462-463) et dans F-240 (l. 70). | — |
| 7 | Affichage : `GET /api/jarvis/costs` (la ligne de coût de `/jarvis`), `GET /api/jarvis/voice` (la dépense voix et son plafond), `GET /api/admin/jarvis/measure` (voix en € seulement). Aucune vue Jarvis dans les tableaux Gateway. | `JarvisCostController`, `JarvisAdoptionController` l. 54 |

## 3. Principes

- **Justesse avant coût** : le budget Jarvis **ne raccourcit jamais un tour en cours** et ne change **jamais** de
  modèle. Il décide seulement si une délégation **démarre**, et il prévient avant. Le plafond par message reste la
  borne de configuration (`maxTurnTokens`), la même qu'un tour tapé.
- **Jamais d'escalade vers Opus** pour une raison de coût. Au-delà du plafond, le cerveau rapide continue (F-239), et
  c'est **Opus** qui demande confirmation.
- **Une seule vérité** : le journal `usage_turns`, avec une **origine**. `jarvis_usage_days` reste l'agrégat
  quotidien rapide, alimenté au même endroit.
- **Le total reste vrai** : séparer ne veut pas dire cacher. Les tableaux Gateway montrent une ligne « dont Jarvis ».
- **Coûts en €**, à l'écran comme dans les messages. Isolation `user_id`.

## 4. Décisions tranchées par défaut

| # | Sujet | Défaut | Pourquoi |
|---|---|---|---|
| D1 | L'étiquette | Une colonne `usage_turns.origin` (`GATEWAY` par défaut, `JARVIS`), et une colonne `usage_turns.jarvis_part` (`FAST`, `VOICE`, `STT_STREAM`, `STT_UTTERANCE`, `DICTATION`, `OPUS`, `MAIL`, `AGENDA`). Tout écrivain Jarvis la renseigne. | Une seule table de vérité. Ventilation fine. |
| D2 | La voix dans le journal | `SpeechUsage.record`, appelé pour Jarvis, écrit **`ANNEXE` + `origin = JARVIS`** au **tarif réel** (ElevenLabs ou OpenAI), et plus `TOUR` au tarif OpenAI. | Corrige une erreur de tarif et une fuite de la voix dans les agrégats « tours » de la Gateway. |
| D3 | Les trous | Les tours Opus Jarvis **coupés** sont comptés (`addOpus` dans `recordCutTurn`). La **dictée de repli** est comptée quand elle est appelée depuis `/jarvis` (en-tête `via=JARVIS`). La STT est **séparée** en flux et en retranscription (deux colonnes de plus). `totalCostUsd()` couvre tout. | Le budget doit être complet pour être juste. |
| D4 | Le quota Gateway n'est plus consommé | Pour un tour `origin = JARVIS` : la `VoiceMark` est lue **avant** le contrôle de quota ; `assertWithinQuota` est **remplacé** par le contrôle du budget Jarvis ; `recordUsage` écrit `usage_turns` (origine `JARVIS`) mais **n'incrémente pas** `usage_counters`. Le plafond par message vaut `AtelierTurnBudget.byok(maxTurnTokens)`, c'est-à-dire la borne de configuration seule. Le cerveau rapide n'appelle plus `assertWithinQuota` : son seul garde est le budget Jarvis. | Demande du PO : séparé. Un quota Gateway épuisé ne rend plus Jarvis muet. |
| D5 | Le plafond Jarvis | **Un plafond mensuel global en €, par utilisateur**, réglable dans `/jarvis` (table `jarvis_budgets`). Défaut : **150 €/mois**, qui couvre la voix (100 €), le cerveau rapide (20 €) et une marge pour Opus et le courrier ; à revoir après une mesure de 30 jours. Les plafonds **voix** et **cerveau rapide** existants restent des **sous-bornes** de protection, inchangées. | Un seul chiffre à retenir pour le PO, et les gardes existantes sont gardées. |
| D6 | Le comportement au plafond | **En dessous de 80 %** : rien. **À 80 %** : une notification (F-185) unique dans le mois. **À 100 % (souple)** : le cerveau rapide continue ; une **délégation à Opus** est précédée d'une phrase « Budget Jarvis du mois atteint ; cette recherche coûte environ 1 €. Je la lance ? », et elle ne démarre que sur un « oui ». La voix continue jusqu'à son propre plafond. **À 2× (dur)** : une phrase fixe, aucun appel modèle ni voix, comme le dur du cerveau rapide aujourd'hui. Le PO peut relever le plafond depuis l'écran à tout moment. | Jamais d'escalade, jamais de tour tronqué. Le « oui » laisse la main au PO. Le dur protège d'un emballement. |
| D7 | BYOK | En BYOK, le coût Opus reste **compté** au budget Jarvis (c'est une dépense réelle, sur la clé du client), mais le plafond ne bloque qu'en Hosted, **sauf** si l'utilisateur coche « appliquer aussi en BYOK ». | Le budget est une vue de la dépense. Le blocage suit qui paie. |
| D8 | Les tableaux Gateway | Les agrégats `TOUR` et `/by-client` **excluent** `origin = JARVIS` du **quota** mais l'**affichent** dans une ligne « dont Jarvis » (total, et par client quand une délégation a eu lieu dans un sujet client). Les budgets F-133 par client **comptent** les délégations Jarvis faites dans leurs sujets, marquées « via Jarvis ». | Un client reste chiffré au réel, et le PO voit ce qui vient de Jarvis. |
| D9 | Le tarif | Les tarifs restent ceux de la grille (`ProviderCostCalculator`) et des réglages voix et STT. Le stockage est en USD et l'affichage en € par `usd-to-eur`. | Rien de nouveau à maintenir. |
| D10 | L'affichage | Le détail de coût de `/jarvis` passe à **une ligne par poste** (Conversation rapide, Voix, Transcription, Recherches Opus, Courrier, Agenda), plus le total du jour et du mois, et une **jauge** par rapport au plafond, en €. La mesure admin (`/api/admin/jarvis/measure`) reprend la même ventilation. | Demande du PO : la ventilation. |

**Question au PO (goût, non bloquante) :** le **montant** du plafond. Le défaut est 150 €/mois, et il se règle à
l'écran.

## 5. Données et points d'accès

- `usage_turns` : ajout de `origin` (non nul, défaut `GATEWAY`) et de `jarvis_part` (nullable). Un index
  (`user_id`, `origin`, `created_at`).
- `jarvis_usage_days` : ajout de `stt_utterance_seconds`, `stt_utterance_cost_usd`, `dictation_seconds`,
  `dictation_cost_usd`, `mail_cost_usd`, `agenda_cost_usd`.
- **`jarvis_budgets`** : `user_id` (clé), `monthly_cap_eur`, `apply_on_byok`, `alert_80_sent_month`, `updated_at`.
- Points d'accès :
  - `GET /api/jarvis/costs`, enrichi de la ventilation, du plafond et de l'état (`NONE`, `ALERT`, `SOFT`, `HARD`) ;
  - `PUT /api/jarvis/budget` (le plafond et l'option BYOK) ;
  - tous filtrés sur le `user_id` du principal.
- Migrations : les **prochaines libres** au moment du dev.

## 6. Découpage — SF-244-01 → SF-244-04 (≈ 3,75 j)

| SF | Titre | Estimation | Dépend de |
|---|---|---|---|
| SF-244-01 | Étiqueter tout ce que coûte Jarvis | 1 j | — |
| SF-244-02 | Jarvis ne consomme plus le quota Gateway | 1 j | SF-244-01 |
| SF-244-03 | Le plafond de Jarvis | 1 j | SF-244-02 |
| SF-244-04 | La ventilation en € à l'écran | 0,75 j | SF-244-01 (et SF-244-03 pour la jauge) |

**Préoccupation transversale « Plans / limites »** (SF-244-02 et SF-244-03). Composants impactés :

- `QuotaService.assertWithinQuota` et `recordUsage` ;
- `AtelierChatService.runLoopOnLane` (contrôle l. 3282, marque l. 3306), `recordCutTurn` (l. 6760) ;
- `AtelierTurnBudget` ;
- `JarvisFastBrainService.budgetState` et son garde de quota (l. 464-479) ;
- `JarvisVoiceService`, `JarvisSttSessionService`, `JarvisUtteranceService` (plafond voix) ;
- `DictationService` ;
- `SpeechUsage` ;
- `UsageLedgerService`, `UsageTurnWriter` ;
- `UsageTurnRepository` (agrégats) ;
- `CostBudgetService` (F-133) ;
- `RoutineTurnRunner` (tours de routine : origine `GATEWAY`, inchangés).

### SF-244-01 — Étiqueter tout ce que coûte Jarvis (1 j)

**Objectif :** chaque euro dépensé par Jarvis porte l'origine `JARVIS` et son poste. Aucun n'est perdu.

- Migration `origin` et `jarvis_part` (D1). Tous les écrivains : cerveau rapide (et pré-chauffage), TTS (D2, `ANNEXE`
  au tarif réel), STT flux et retranscription (séparées), dictée de repli quand elle vient de `/jarvis`, tours Opus
  Jarvis normaux **et coupés** (D3).
- `totalCostUsd()` complet.

**Critères d'acceptation :**
1. Un tour Opus délégué écrit `usage_turns` avec `origin = JARVIS` et `jarvis_part = OPUS`.
2. Un tour coupé est compté dans `jarvis_usage_days`.
3. La voix ElevenLabs est comptée au tarif ElevenLabs, en `ANNEXE`.
4. Une dictée de repli depuis `/jarvis` est comptée.
5. Les tours tapés et les routines restent en `GATEWAY`.
6. Isolation : une ligne ne porte que le `user_id` qui l'a causée.

**Plan de test :**
- Unitaires par écrivain.
- Intégration : un tour Jarvis complet, un tour coupé, la TTS, la STT et la dictée.
- **Non-régression** : un tour tapé est identique.
- **Isolation** A/B.
- Liquibase (valeur par défaut sur l'historique).

**Hors périmètre :** changer le quota.

### SF-244-02 — Jarvis ne consomme plus le quota Gateway (1 j)

**Objectif :** un tour d'origine Jarvis n'est ni bloqué par le quota Gateway, ni décompté de ce quota.

- La `VoiceMark` est lue avant le contrôle. Les branches D4 : `assertWithinQuota` est remplacé par un **contrôle de
  budget Jarvis provisoire** (souple seulement, en attendant SF-244-03), et `recordUsage` n'incrémente pas
  `usage_counters`.
- Le cerveau rapide n'appelle plus `assertWithinQuota`.
- Les agrégats Gateway sont séparés (D8).

**Critères d'acceptation :**
1. Après un tour Jarvis, `usage_counters` n'a pas bougé.
2. Un utilisateur dont le quota Gateway est épuisé peut encore parler à Jarvis et déléguer.
3. Un tour tapé dans le même sujet, dans le même état, reste bloqué par le quota (**non-régression**).
4. Le plafond par message d'un tour Jarvis est `maxTurnTokens`.
5. `/api/usage` et `/api/admin/cost/*` montrent « dont Jarvis » et excluent Jarvis du quota.
6. Isolation A/B.

**Plan de test :**
- Unitaires des branches.
- Intégration : quota épuisé + tour Jarvis qui passe, quota épuisé + tour tapé qui est refusé.
- Agrégats.
- **Non-régression** sur tous les composants de la liste transversale.
- **Isolation**.

**Hors périmètre :** le plafond Jarvis réglable.

### SF-244-03 — Le plafond de Jarvis (1 j)

**Objectif :** Jarvis a son propre plafond mensuel en €, qui prévient, demande avant de déléguer et ne s'emballe
jamais.

- La table `jarvis_budgets` et `JarvisBudgetService` (état calculé sur le total du mois).
- D6 : l'alerte à 80 % (notification F-185), la confirmation d'une délégation au-delà de 100 % (phrase fixe dans le
  message, « oui » vérifié), le dur à 2×.
- D7 (BYOK).
- Le réglage dans `/jarvis`.

**Critères d'acceptation :**
1. À 80 %, une seule notification par mois.
2. À 100 %, le cerveau rapide répond toujours ; une délégation demande « je la lance ? » et ne part que sur un
   « oui ».
3. À 2×, une phrase fixe, et aucun appel.
4. **Aucun tour en cours n'est coupé** par le plafond.
5. **Jamais** de délégation déclenchée par le dépassement (test : le motif `budget` ne mène jamais à Opus).
6. Le plafond est modifiable, ce qui change l'état immédiatement.
7. L'utilisateur B ne lit ni ne modifie le budget de A.

**Plan de test :**
- Unitaires des seuils (79, 80, 100, 199, 200 %).
- Intégration : délégation au-delà du plafond, avec et sans « oui ».
- Notification unique.
- **Isolation**.
- Banc CONVERSATION : 3 scénarios (80 %, souple, dur).

**Hors périmètre :** un plafond quotidien.

### SF-244-04 — La ventilation en € à l'écran (0,75 j)

**Objectif :** le PO voit en € ce que Jarvis a coûté, poste par poste, et où il en est de son plafond.

- Le détail de coût de `/jarvis` (D10), la jauge, la mesure admin, la ligne « dont Jarvis » des écrans de coût
  Gateway.
- DESIGN_SYSTEM.md, §22 : une ligne pour la jauge (couleurs existantes, orange au-delà de 80 %).
- Masqué en présentation.

**Critères d'acceptation :**
1. Les six postes et le total du jour et du mois sont affichés en € à 2 décimales, et le total égale la somme.
2. La jauge passe à l'orange à 80 %.
3. En présentation, rien n'est affiché.
4. Pas de débordement à 360 × 740.
5. Aucun montant en $.

**Plan de test :**
- Specs du composant.
- Intégration de `/api/jarvis/costs` (somme et **isolation**).
- Contrôle visuel headless.

**Hors périmètre :** un export.

## 7. Mesure de réussite

- **Zéro** tour d'origine Jarvis dans `usage_counters` (requête de contrôle à J+1).
- La somme des postes Jarvis égale la somme des `usage_turns` `origin = JARVIS` du mois, à 1 % près.
- Le total Gateway affiché reste égal au réel, Jarvis compris dans la ligne « dont Jarvis ».
- Ressenti du PO : « je sais ce que me coûte Jarvis, et Jarvis ne bloque plus mon travail ».

## 8. Points de contact

| Avec | Point | Règle |
|---|---|---|
| **F-240** SF-240-01 | Les colonnes de `jarvis_turn_timings`. | Rien de commun : le coût va dans `usage_turns` et `jarvis_usage_days`, pas dans les temps. Migrations distinctes, numéros pris au moment du dev. |
| **F-240** SF-240-04 | « Dis-m'en plus » sans appel Opus (≈ 0 €). | Compté à 0 €, rien à ajouter. |
| **F-241** | Le surcoût du portrait (≈ quelques € par mois). | Il est porté par le cerveau rapide et Opus en mode Jarvis, donc compté **automatiquement** au budget Jarvis. |
| **F-239** SF-239-11 | Les plafonds souple et dur du cerveau rapide, et « jamais d'escalade ». | Ils restent des sous-bornes. La règle est reprise dans le plafond global (D6). Le garde `assertWithinQuota` du cerveau rapide est **retiré** (D4), ce qui amende SF-239-11, explicitement. |
| **F-242 / F-243** | Les coûts du courrier et de l'agenda. | Postes `MAIL` et `AGENDA`, prévus dès SF-244-01. |
| **F-245** | Les tours du fil Jarvis. | Ils sont `origin = JARVIS` par construction (l'espace de travail `jarvis_thread`), même sans `VoiceMark`, par exemple un message tapé dans le fil Jarvis. |
| **F-133** | Les budgets par client (alerte). | Ils comptent les délégations Jarvis faites dans leurs sujets (D8). |
| **F-224** | Les coûts en €. | Reconduit. |

## 9. Hors périmètre

Un plafond quotidien ; un plafond par client pour Jarvis ; une facturation de Jarvis au client final (le paiement
est hors périmètre) ; la modification des tarifs de la grille ; les routines et les veilles (origine `GATEWAY`).

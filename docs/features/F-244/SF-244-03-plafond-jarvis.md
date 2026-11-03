# Mini-spec — F-244 / SF-244-03 — Le plafond de Jarvis

## Identifiant
`F-244 / SF-244-03` · Feature parente `F-244` « Le budget propre de Jarvis » · Statut `in-review` · 2026-11-03 ·
Branche `feat/SF-244-03-plafond-jarvis` · Estimation 1 j · Dépend de SF-244-02

## Objectif
> Jarvis a son propre plafond mensuel en €, qui prévient (80 %), demande avant de déléguer (100 %) et ne s'emballe
> jamais (2 ×) — sans jamais couper un tour en cours ni escalader vers Opus.

## Comportement attendu

### Cas nominal
- Table `jarvis_budgets` (clé `user_id`) : `monthly_cap_eur`, `apply_on_byok`, `alert_80_sent_month`, `updated_at`.
  Sans ligne : défaut `app.jarvis.budget.monthly-cap-eur` = **150 €**.
- `JarvisBudgetService` : dépense du mois = total du compteur Jarvis (`JarvisCostService`, en €, Europe/Paris) ;
  état `NONE` (< 80 %), `ALERT` (≥ 80 %), `SOFT` (≥ 100 %), `HARD` (≥ 200 %, ou plafond 0).
- **80 %** : notification F-185 `JARVIS_BUDGET_ALERT` (lien `/jarvis`), **une seule par mois** (`alert_80_sent_month`).
- **100 % (souple)** : le cerveau rapide continue ; un tour Opus né de Jarvis (`via=JARVIS`) est refusé **avant tout
  appel** avec `jarvis_budget_confirm` et la phrase « Budget Jarvis du mois atteint ; cette recherche coûte environ N €.
  Je la lance ? » (N = coût moyen d'une recherche Opus de Jarvis ce mois, arrondi, ≥ 1 €). `/jarvis` la dit, écoute ;
  un « oui » net (`classifyConfirmation`) appelle `POST /api/jarvis/budget/confirm` puis relance la même demande ; le
  « oui » vaut pour **une** délégation, 2 minutes. « non » : « Entendu, je ne lance rien. ».
- **2 × (dur)** : tour Opus refusé (`jarvis_budget_hard`) ; le cerveau rapide rend la phrase fixe « Monsieur, le budget
  de Jarvis de ce mois est épuisé. » **sans appel au modèle**.
- **BYOK (D7)** : le coût reste compté ; le plafond ne bloque qu'en Hosted, sauf `apply_on_byok`.
- Points d'accès : `GET /api/jarvis/budget`, `PUT /api/jarvis/budget` {`monthlyCapEur`?, `applyOnByok`?} (effet
  immédiat), `POST /api/jarvis/budget/confirm` — tous sous le `user_id` du principal.
- Le plafond voix (100 €) et le plafond du cerveau rapide (20 €) restent des **sous-bornes**, inchangés.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Plafond < 0 ou > 10 000 € | Refus explicite | 400 |
| Sans jeton | Refus | 401 |
| Délégation au-delà du plafond sans « oui » | Refus nommé avant tout appel (flux : `error` `jarvis_budget_confirm`) | 429 hors flux |
| Délégation à 2 × | Refus nommé (`jarvis_budget_hard`) | 429 hors flux |
| Budget illisible (base) | Rien ne bloque (sous-bornes en place) | — |
| Réponse ambiguë à « Je la lance ? » | Rien n'est lancé ; la parole repart au cerveau | — |

## Critères d'acceptation
- [x] À 80 %, une seule notification par mois.
- [x] À 100 %, le cerveau rapide répond toujours ; une délégation demande « je la lance ? » et ne part que sur un « oui ».
- [x] À 2 ×, une phrase fixe, et aucun appel.
- [x] Aucun tour en cours n'est coupé par le plafond (la porte n'existe qu'avant le premier appel).
- [x] Jamais de délégation déclenchée par le dépassement (le cerveau rapide au-delà du plafond répond, sans délégation).
- [x] Le plafond est modifiable, ce qui change l'état immédiatement.
- [x] L'utilisateur B ne lit ni ne modifie le budget de A.

## Plan de test
- Unitaires : `JarvisBudgetServiceTest` (seuils 79,99 / 80 / 100 / 199,99 / 200 %, défaut, alerte unique, porte
  avec et sans « oui » — usage unique —, dur malgré un « oui », BYOK avec/sans option, relèvement immédiat, bornes,
  lecture en panne), `AtelierChatServiceBudgetTest` (porte avant tout appel, tour tapé jamais gardé),
  `JarvisFastBrainServiceTest` (dur : phrase fixe sans appel ; souple : répond sans déléguer).
- Intégration : `JarvisBudgetApiIntegrationTest` (défaut 150 €, réglage ⇒ SOFT, isolation A/B, 400, 204, 401).
- Front : spec `jarvis.component` (« Je la lance ? » dit, « oui » ⇒ confirm + relance ; « non » ⇒ rien ; dur ⇒ phrase).
- Banc CONVERSATION (3 scénarios 80 % / souple / dur) : **non joué** ici (banc live sur la prod, après déploiement) —
  les trois cas sont couverts par les tests ci-dessus.

## Impacts
- Table `jarvis_budgets` (migration `244-jarvis-budgets.xml`) ; `PushEvent.JARVIS_BUDGET_ALERT`.
- Endpoints nouveaux : `GET|PUT /api/jarvis/budget`, `POST /api/jarvis/budget/confirm`. Codes d'erreur de flux
  `jarvis_budget_confirm`, `jarvis_budget_hard`.
- Composants : `JarvisBudget*` (nouveaux), `AtelierChatService`, `AtelierChatController`, `JarvisFastBrainService`,
  `PushEvent` ; front `JarvisService`, `jarvis.models`, `JarvisComponent`.

## Préoccupations transversales
- **Plans / limites** — composants impactés : `AtelierChatService.runLoopOnLane` (porte Jarvis, tours Jarvis
  seulement), `JarvisFastBrainService.decideRaw` (dur global), `JarvisVoiceService` / STT (plafond voix inchangé),
  `QuotaService` (inchangé depuis SF-244-02), `RoutineTurnRunner` (aucune marque Jarvis : jamais gardé).
- Navigation : aucune route nouvelle.

## Arbitrages
- Le **réglage à l'écran** (montant, option BYOK) est livré avec la jauge en SF-244-04 (même panneau) ; l'API est ici.
- Au plafond dur, la **voix** n'est pas coupée : la phrase fixe est dite (quelques centimes) pour que le PO sache
  pourquoi ; le plafond voix reste sa propre borne. Réversible.
- Estimation « environ N € » = moyenne Opus Jarvis du mois (≥ 1 €), pas une prévision du tour.

## Hors périmètre
Un plafond quotidien ; un plafond par client pour Jarvis.

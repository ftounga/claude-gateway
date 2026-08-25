# Mini-spec — [F-36 / SF-01] Budget de session dérivé du quota restant (backend)

---

## Identifiant

`F-36 / SF-01`

## Feature parente

`F-36` — Plafond de dépense &amp; facturation au coût réel

## Statut

`done` — livrée le 2026-08-26 (PR #164)

## Date de création

2026-08-26

## Branche Git

`feat/SF-36-01-budget-session`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Poser à la **création** de chaque session d'Atelier un **plafond de dépense dur** (`budget.max_list_cost`),
égal au minimum entre le **quota restant converti en dollars** et un **plafond par run** configurable
(défaut 2,00 $), pour que le pire cas d'un run cesse d'être illimité.

---

## Contexte

Le contrôle de quota est **post-run** : `assertWithinQuota` bloque le run *suivant*. Un seul run peut
donc dépasser le quota mensuel entier, et le dépassement n'est constaté qu'après — quand la dépense
est déjà engagée et irrécupérable (cadrage F-36).

Provider-First : la plateforme expose déjà un **verrou pré-requête** (budget de session). Avant chaque
appel au modèle, elle vérifie le cumul et met le thread en pause. On le **relaie** — aucune
comptabilité de dépense n'est réimplémentée dans la Gateway.

Deux contraintes du fournisseur structurent la SF :

- le budget est **création-seule** : impossible de l'ajouter à une session déjà ouverte ;
- le montant est en **unités mineures, en chaîne** (`"200"` = 2,00 $) ; une forme décimale est rejetée.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur lance une exécution ; `requireOwned` d'abord (isolation `user_id`), puis les pré-vols
   quota/sandbox existants.
2. **À l'ouverture d'une session** (archive ou Git), le service calcule le budget :
   `min(quota restant × coût de référence, plafond par run)`, borné en bas par un **plancher**
   (défaut 0,10 $) pour ne jamais créer une session au budget nul, que le fournisseur refuserait.
3. La session est créée avec `budget: {type: "limit", max_list_cost: {amount: "<mineures>", currency: "USD"}}`.
4. Le run se déroule normalement. Quand la dépense atteint le plafond, la plateforme met le thread en
   pause et rapporte `stop_reason: budget_reached`.
5. Le tour est **conservé** : fichiers resynchronisés, consommation décomptée, tour historisé — il a
   réellement eu lieu. Le résultat porte un drapeau `budgetReached`, relayé dans l'événement SSE
   `done` (l'écran dédié est SF-36-04).
6. Une session **déjà ouverte** (réutilisée) ne reçoit aucun budget : le fournisseur refuse d'en
   ajouter un après coup. Le quota post-run continue de s'y appliquer.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Workspace inexistant / d'un autre utilisateur | 404, aucun appel fournisseur | `workspace_not_found` |
| Quota mensuel déjà atteint | Refus **avant** création de session (inchangé) | `quota_exceeded` |
| Quota restant très faible | Budget ramené au **plancher** (0,10 $), session créée | — |
| Le fournisseur refuse le budget (modèle sans tarif public) | Erreur fournisseur remontée telle quelle | `provider_error` |
| Plafond du run atteint pendant le run | Tour conservé + drapeau `budgetReached` dans `done` | — |

---

## Critères d'acceptation

- [x] Toute session **nouvellement ouverte** porte un `budget.max_list_cost` non nul, en unités
      mineures et en chaîne, devise `USD`.
- [x] Le montant vaut `min(restant converti, plafond par run)` et n'est jamais inférieur au plancher.
- [x] Le plafond par run, le plancher et le coût de référence sont **configurables** (aucune valeur
      commerciale en dur dans le code).
- [x] Une session **réutilisée** n'envoie aucun budget (le corps de reprise est inchangé).
- [x] `stop_reason: budget_reached` ⇒ `AtelierSessionResult.budgetReached() == true` et l'événement
      SSE `done` porte `budgetReached: true`, sans perdre la réponse ni les fichiers réécrits.
- [x] Le calcul du budget lit le quota **de l'utilisateur du contexte de sécurité** (isolation).
- [x] Aucune clé ni aucun secret journalisé.

---

## Périmètre

### Hors scope (explicite)

- Le décompte au **coût réel** et le markup (SF-36-02).
- Les tarifs de référence du rapport d'usage (SF-36-03).
- Le message d'écran « plafond du run atteint » et le lien de rachat (SF-36-04).
- Le plafond majoré en cas de **délégation** : la délégation (F-35) n'existe pas encore ; la propriété
  est posée et documentée, mais elle n'est activée par aucun appelant (voir Notes, arbitrage A-1).
- Les plafonds par utilisateur pilotés depuis la console d'administration.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Le flux `POST /api/v1/workspaces/{id}/agent/stream` (SSE) gagne le champ
`budgetReached` dans son événement `done`.

### Tables impactées

Aucune. Le budget est calculé à l'ouverture et n'est pas persisté.

### Migration Liquibase

- [x] Non applicable

### Configuration

| Clé | Défaut | Rôle |
|-----|--------|------|
| `app.atelier.agent.cost.max-run-cost` | `2.00` | Plafond de dépense d'un run (USD) |
| `app.atelier.agent.cost.max-run-cost-delegated` | `5.00` | Plafond quand la délégation est active (F-35, dormant) |
| `app.atelier.agent.cost.min-run-cost` | `0.10` | Plancher : jamais de session au budget nul |
| `app.atelier.agent.cost.cost-per-million-tokens` | `9.00` | Coût de référence (USD/M tokens) servant à convertir le quota restant |

---

## Plan de test

### Tests unitaires

- [x] `AtelierCostPropertiesTest` — valeurs par défaut appliquées si absentes/négatives.
- [x] `SessionBudgetTest` — montant sérialisé en unités mineures, en chaîne ; arrondi ; refus des valeurs ≤ 0.
- [x] `AtelierSessionServiceTest` — une session neuve est créée **avec** un budget = plafond par run
      quand le quota restant est large.
- [x] `AtelierSessionServiceTest` — quota restant faible ⇒ budget = conversion du restant.
- [x] `AtelierSessionServiceTest` — quota restant ~nul ⇒ budget = plancher.
- [x] `AtelierSessionServiceTest` — session réutilisée ⇒ aucun appel de création, donc aucun budget.
- [x] `AtelierSessionServiceTest` — `stop_reason: budget_reached` ⇒ résultat `budgetReached`, réponse
      et fichiers préservés.
- [x] `AnthropicManagedAgentProviderTest` — le corps de création porte `budget.max_list_cost`
      (`amount` en chaîne, `currency` USD) ; corps **inchangé** quand le budget est `null`.

### Tests d'intégration

- [x] `AtelierAgentControllerTest` — l'événement `done` porte `budgetReached`.

### Isolation utilisateur

- [x] Applicable — le budget dérive du quota de l'utilisateur **du contexte de sécurité** ; le test
      d'isolation existant (workspace d'un autre utilisateur ⇒ 404 avant tout appel fournisseur) reste vert.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. F-30 SF-30-04 (session persistante) et F-28 SF-28-09 (sessions) sont livrées.

### Questions ouvertes impactées

- [x] OQ-08 (overage monétisé) — **non tranchée**, hors scope : le budget **empêche**, il ne facture pas.

---

## Notes et décisions

- **A-1 — plafond « délégation » dormant.** D2 du cadrage prévoit 5 $ quand la délégation est active.
  F-35 n'étant pas livrée, la propriété existe et est documentée mais aucun appelant ne la demande :
  tous les runs utilisent le plafond de base. Réversible (une ligne à l'ouverture de session quand
  F-35 arrivera).
- **A-2 — plancher de budget.** Le cadrage ne dit pas quoi faire d'un quota restant quasi nul. Créer
  une session à 0 $ serait refusé par le fournisseur (ou mise en pause immédiate) avec une erreur
  incompréhensible. Décision : plancher configurable à 0,10 $ — le dépassement possible du quota est
  borné à dix centimes, contre « illimité » aujourd'hui.
- **A-3 — conversion tokens → dollars.** Le cadrage donne un coût blended Opus ≈ 8,3 €/M. La devise du
  budget est le dollar : la propriété est donc exprimée en **USD/M** avec un défaut de 9,00 $
  (conservateur : un coût de référence plus élevé donne un budget plus serré). Ajustable par
  configuration, sans redéploiement.
- **A-4 — budget atteint : drapeau, pas erreur.** Traiter `budget_reached` comme une exception
  jetterait la réponse partielle, les fichiers produits et le décompte. On reprend le patron
  `interrupted` (F-32) : le tour est conservé, décompté, et **dit**.

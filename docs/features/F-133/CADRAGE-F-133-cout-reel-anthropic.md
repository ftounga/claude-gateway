# F-133 — Le coût réel Anthropic : par message, par client, par semaine

> Cadrage du 2026-09-20, à la demande du PO : *« si je charge 100 € dans ma clé et que j'en vois 99
> chez Anthropic, je dois voir dans l'application que la réponse m'a coûté 1 €. Un budget par semaine
> et par client/poste. Des alertes proche du quota et en dépassement. Pour l'admin seulement
> (ntounga@gmail.com). Savoir vite ce que je dépense par client à la fin de la semaine, du mois, et
> même par message. »*
>
> **Cadrage seul.** Aucune ligne de code avant le go du PO, l'ajout au `PRODUCT_SPEC.md` et les
> mini-specs par subfeature (CLAUDE.md §Séquence obligatoire).

---

## 0. Ce qui existe déjà — et qu'il ne faut pas réécrire

L'essentiel de la plomberie est **déjà là**. Le cadrage ne crée pas une chaîne de mesure : il rend
durable et vraie une mesure qui est aujourd'hui calculée puis jetée.

| Brique existante | Où | Ce qu'elle fait déjà |
|---|---|---|
| Journal par tour | `UsageTurn.java` (table `usage_turns`) | une ligne par tour facturé : `user_id`, `workspace_id`, `host_id` (**instantané** du poste), entrée, sortie, `occurred_at`. Append-only, sans aucun texte |
| Coût fournisseur en dollars | `BilledTokensCalculator.costUsd()` | entrée, sortie, **lecture** et **écriture** de cache, chacune à son tarif |
| Quatre natures de tokens | `TurnTokens.java` (F-63) | `input` / `output` / `cacheRead` / `cacheWrite` |
| Coût déjà rapporté par le fournisseur | `QuotaService.recordUsage(..., providerCostUsd, ...)` `:183` | quand le fournisseur donne le coût du tour, **il fait foi** |
| Consommation par client (= poste) et projet | `UsageByClient.java`, `UsageByClientService`, écran `reports/` | déjà ventilée par poste et par projet, avec parts et « hors client » |
| Console d'administration | `AdminUsage.java`, `admin/usage` (F-61 / SF-61-03) | consommation par compte, réservée ADMIN |
| Super-admin par e-mail | `application.yml:89` → `SUPER_ADMIN_EMAIL:ntounga@gmail.com` | **le mécanisme demandé existe déjà** (`AdminService:87`) |
| Ligne sous chaque réponse | `atelier-terminal.component.html:689` → `costLabel()` | affiche déjà « durée · tokens » — **l'emplacement du coût par message existe** |
| Alerte de quota | `QuotaAlertService`, `QuotaAlert`, bandeau `quota-alert-banner` | seuil à 80 %, une seule fois, avec pack de recharge |

**Conclusion** : il n'y a **rien à réimplémenter**. Il y a une mesure à **garder**, une vérité à
**réconcilier**, une fenêtre **hebdomadaire** à ajouter et un écran d'admin à compléter.

---

## 1. Les cinq constats qui changent la forme de la feature

### C1 — Le coût réel est calculé à chaque tour, puis **jeté**

`QuotaService.recordUsage()` connaît les quatre natures de tokens **et** le coût fournisseur. Trois
lignes plus bas :

```java
usageLedgerService.recordTurn(userId, workspaceId, hostId, input, output);  // QuotaService.java:204
```

Le cache et le coût ne franchissent pas cette ligne. `usage_turns` ne porte ni `cache_read`, ni
`cache_write`, ni coût, ni **modèle servi**. Conséquence directe : **l'historique actuel ne permet pas
de reconstituer le coût réel**, et aucune migration ne le réparera rétroactivement.

C'est aussi la bonne nouvelle : le point d'insertion est **unique** et il est déjà traversé par tous
les chemins (chat, ask, Atelier, Vigie, Radar, CRA, juge — 12 appelants).

### C2 — Le coût affiché aujourd'hui est faux pour ce que le PO veut en faire

`UsageCostEstimator.estimate(input, output)` ne connaît que deux natures et applique 5 / 25 par
million. Or en usage agentique, **l'écrasante majorité de l'entrée est de la lecture de cache**,
facturée un dixième. Le rapport F-16 et l'écran par client **surestiment** donc le coût, parfois d'un
ordre de grandeur. Par ailleurs les montants sont libellés `EUR` (`application.yml:751`) alors que
les tarifs saisis sont les **tarifs fournisseur en dollars**.

### C3 — Le tarif d'écriture de cache est **volontairement** sous-évalué

`application.yml:305-311` le dit noir sur blanc : depuis F-130 la boucle pose un cache **TTL 1 h**,
dont l'écriture coûte **2×** l'entrée (10 $/M sur Opus 5), mais le tarif reste à `6,25` — choix
assumé, favorable au client, pour le **décompte de quota**.

Pour un suivi de **vérité de coût**, ce choix devient un mensonge. Il faut donc **deux jeux de
tarifs**, explicitement séparés :

- `app.atelier.agent.cost.*` — **tarif commercial** : ce qu'on décompte au client. Inchangé.
- `app.cost.provider.*` — **tarif de vérité** (nouveau) : ce que le fournisseur nous facture
  réellement, par **modèle**, cache 1 h compris.

### C4 — Anthropic ne facture pas au message. Jamais.

L'API Usage & Cost d'Anthropic donne :

| Endpoint | Grain | Ventilation | Devise |
|---|---|---|---|
| `GET /v1/organizations/usage_report/messages` | `1m` / `1h` / `1d` | `api_key_id`, `workspace_id`, **`model`**, `service_tier`, `context_window` | tokens |
| `GET /v1/organizations/cost_report` | **`1d` uniquement** | `workspace_id`, `description` | **USD**, en cents |

Il n'existe **aucun** identifiant de message dans ces rapports. « Cette réponse m'a coûté 1 € » ne
peut donc être qu'un montant **calculé** chez nous. Ce que la facture permet, c'est de **recaler** ce
calcul : au jour et au modèle, on compare notre somme calculée au montant facturé et on en tire un
**écart**. C'est la forme honnête de la demande — et elle est meilleure que ce qui était demandé,
parce qu'elle rend l'erreur visible au lieu de la masquer.

Deux autres contraintes de cette API : les données arrivent **sous ~5 minutes** (donc pas de
temps réel), le `cost_report` **exclut le Priority Tier**, et le code execution n'apparaît que côté
coût, jamais côté usage.

### C5 — L'Admin API exige une **organisation**, pas un compte individuel

La documentation est explicite : *« The Admin API is unavailable for individual accounts. »* Il faut
une organisation Console et une clé `sk-ant-admin01-…` (une clé API normale est rejetée ; une clé de
workspace aussi). **C'est la seule inconnue bloquante de ce cadrage** — elle ne bloque que la
réconciliation (SF-05), pas le reste.

---

## 2. Périmètre

### Dans le périmètre

1. Persister, par tour, le **coût fournisseur réel** avec les quatre natures de tokens et le **modèle**.
2. Afficher le coût **sous chaque réponse**, à l'emplacement qui existe déjà.
3. Un **budget hebdomadaire par client/poste**, et un budget global.
4. Des **alertes** : approche du budget, puis dépassement.
5. Un **écran d'admin** : par client, par semaine, par mois, avec l'écart entre calculé et facturé.
6. La **réconciliation** avec la facture Anthropic réelle (conditionnée à C5).

### Hors périmètre (explicitement)

- **Bloquer** un tour au dépassement du budget. Les budgets de F-133 **informent** ; le blocage reste
  l'affaire du quota commercial (F-10 / F-36), qui a déjà ses plafonds et ses exceptions. Mélanger les
  deux ferait d'un outil de pilotage interne un mécanisme de refus de service.
- Refacturer, éditer une facture, exporter en comptabilité.
- Rendre le coût visible **aux utilisateurs**. F-133 est une fonction d'administration : un
  consultant ne voit pas ce que sa mission coûte à la plateforme.
- Reconstituer l'historique antérieur à la livraison (C1 : les données n'existent pas).
- Le multi-fournisseur. La table portera un `provider`, mais seul Anthropic est tarifé.

---

## 3. Découpage en subfeatures

> **Note de gouvernance.** La demande brute couvre quatre comportements séparables (mesurer,
> budgéter, alerter, rapporter). Ils sont réunis en **une** feature parce qu'ils partagent un unique
> objet métier — le coût réel d'un tour — et un seul acteur, l'admin. Les séparer en features
> distinctes ferait livrer un budget avant d'avoir une mesure juste à lui opposer. Le découpage en
> subfeatures ci-dessous rend chacune livrable seule et en moins de deux jours.

| # | Subfeature | Livre | Dépend de |
|---|---|---|---|
| **SF-133-01** | **Le coût réel survit au tour** | 4 colonnes de nature + `provider_cost_usd` + `model` + `provider` sur `usage_turns` ; `recordTurn` reçoit `TurnTokens` et le coût ; tarif de vérité par modèle (`app.cost.provider.*`) | — |
| **SF-133-02** | **Le coût sous la réponse** | `AtelierTurnCost` porte le montant ; `costLabel()` affiche « 1 min 12 s · 34 210 tokens · 0,42 € » — **uniquement si l'appelant est admin** | 01 |
| **SF-133-03** | **La semaine comme fenêtre** | `UsageWindow` apprend le grain semaine (ISO, lundi UTC) à côté du mois ; agrégats par semaine | 01 |
| **SF-133-04** | **Le budget par client et par semaine** | table `host_budgets` (poste, période, montant, seuil) ; budget global par défaut ; écran de réglage admin | 03 |
| **SF-133-05** | **La facture fait foi** | client Admin API (`cost_report` + `usage_report`), tâche planifiée quotidienne, table `provider_cost_days`, **écart** calculé et affiché | 01, **C5** |
| **SF-133-06** | **Les deux alertes** | « 80 % du budget de la semaine » puis « budget dépassé » ; par poste et global ; une fois par franchissement et par période | 04 |
| **SF-133-07** | **L'écran du PO** | sous `admin/`, une vue : cette semaine / ce mois, par client, coût réel, écart vs facture, budgets et leur état | 01→06 |

**Ordre de livraison** : 01 → 02 → 03 → 04 → 06 → 07, avec **05 en parallèle** dès que la question
C5 est tranchée. Si C5 est négative, 05 est reportée et l'écran affiche « coût calculé » sans écart —
le reste de la feature tient debout sans elle.

---

## 4. Décisions prises par défaut (réversibles, à confirmer)

| # | Décision | Pourquoi |
|---|---|---|
| **D1** | Le « client » est le **poste** (`runner_hosts`), comme en F-61 | **Confirmé par le PO le 2026-09-20.** Ne pas inventer une deuxième notion de client ; le poste est déjà l'instantané figé dans `usage_turns` |
| **D2** | La devise de **vérité** est le **dollar**. L'euro est un affichage, avec un taux configurable (`app.cost.usd-to-eur`) et la date du taux | Anthropic facture en USD ; convertir en dur ferait diverger l'application et la facture d'un écart de change invisible |
| **D3** | Le coût d'un **message** = somme de **tous** les appels du tour, sous-agents et outils compris | un tour d'Atelier peut lancer plusieurs appels ; n'en montrer qu'un sous-estimerait massivement |
| **D4** | La semaine est **ISO, du lundi 00:00 UTC** | cohérent avec le reste des fenêtres (UTC) ; évite un débat de fuseau sur une frontière de budget |
| **D5** | Le dépassement **alerte**, ne bloque pas | **Confirmé par le PO le 2026-09-20.** Le refus de service reste l'affaire du quota commercial (F-10/F-36), qui a déjà ses plafonds et ses exceptions |
| **D6** | Visible pour `ROLE_ADMIN` **ou** le super-admin configuré, via `AdminService.requireAdmin()` | le mécanisme existe (`application.yml:89`) et porte déjà ntounga@gmail.com |
| **D7** | Les tarifs de vérité sont **par modèle**, en configuration | Opus 5 à 5/25, Sonnet 5 à 2/10, Haiku 4.5 à 1/5 : un tarif unique se tromperait dès qu'un modèle change |
| **D8** | Aucun texte n'entre dans la nouvelle table, comme aujourd'hui | garantie tenue par la structure, pas par la prudence des requêtes (`UsageTurn` javadoc) |

---

## 5. Ce que ça donne à l'écran

**Sous une réponse** (admin uniquement) :
> `1 min 12 s · 34 210 tokens · 0,42 €`

**Écran admin, onglet « Cette semaine »** :
> Semaine 38 (15 → 21 sept.) — **47,80 €** sur 120 € de budget (40 %)
> | Client | Coût réel | Budget | État |
> |---|---|---|---|
> | CAGIP | 31,20 € | 60 € | 52 % |
> | Edenred | 14,10 € | 40 € | 35 % |
> | *hors client* | 2,50 € | — | — |
>
> *Facturé par Anthropic sur la période : 49,05 € — écart +2,6 % (arrondis, Priority Tier exclu).*

**Alerte** : un bandeau dans la console d'admin, et rien ailleurs.

---

## 6. Préoccupations transversales (CLAUDE.md §Anti-régression)

| Préoccupation | Cochée | Composants impactés à vérifier |
|---|---|---|
| **Auth / Principal** | oui (SF-02, 07) | `AdminService.requireAdmin()`, `AtelierAgentController`, le flux SSE du terminal — le coût ne doit **jamais** partir vers un non-admin, y compris dans le flux |
| **Contexte tenant** | oui (SF-01) | `UsageLedgerService`, `UsageTurnWriter`, `UsageByClientService`, `AdminUsageService`, `UsageReportService` — le filtre `user_id` reste la racine |
| **Plans / limites** | oui (SF-04, 06) | `QuotaService`, `QuotaAlertService`, `EntitlementService`, `AtelierCostProperties` — **ne pas** toucher au décompte commercial (C3) |
| **Navigation / routing** | oui (SF-07) | routes `admin/*` et leur guard |

**Les 12 appelants de `recordUsage`** sont à vérifier en SF-133-01, mais la nouvelle est bonne :
`ChatService:168,243`, `AskService:107`, `CraService:106`, `RadarNewsService:334`,
`RadarDraftService:238`, `RadarManagerAnswerService:145`, `JugeIndependantService:236`,
`MeetingCardPromotionService:246`, `MeetingExploitationService:175`, `AtelierChatService:1540`,
`AtelierSessionService:1061` — **tous** passent déjà des `TurnTokens` à quatre natures
(`ChatCompletionResult.turnTokens()` les recompose, `:30-33`). Aucun appelant à corriger : il suffit
que `QuotaService` cesse de les tronquer en descendant vers le journal.

En revanche, **un seul** fournit le coût réel du fournisseur : `AtelierSessionService:1062`
(`usdOf(costDelta)`, les Managed Agents, qui rapportent leur propre coût). Les onze autres passent
`null` et leur coût sera donc **calculé** au tarif de vérité. C'est l'écart que SF-133-05 mesure — et
c'est précisément là qu'il sera le plus instructif.

---

## 7. Questions ouvertes (à trancher avant SF-133-05)

| # | Question | Impact si non tranchée |
|---|---|---|
| **OQ-A** | Le compte Anthropic est-il une **organisation** Console avec une clé `sk-ant-admin01-…` ? | **En cours de vérification par le PO (2026-09-20).** Sans elle, **SF-133-05 est impossible** : pas de réconciliation, uniquement du calculé. Vérification : une requête `cost_report` qui répond `401`/`403` avec un message d'authentification admin tranche la question |
| ~~OQ-B~~ | ~~Un client peut-il avoir plusieurs postes ?~~ | **Tranchée le 2026-09-20 : un client = un poste.** D1 confirmé, aucune entité intermédiaire à créer (OQ-20 close) |
| **OQ-C** | Taux EUR/USD : figé en configuration, ou relevé automatiquement ? | figé par défaut (D2) ; l'automatiser ajoute une dépendance externe |
| **OQ-D** | Veut-on, à terme, **une clé API Anthropic par client** ? | ce serait la seule façon d'obtenir une ventilation par client **certifiée par la facture** (`group_by[]=api_key_id`) au lieu de calculée. Gros changement d'exploitation — à cadrer séparément si le sujet compte |

---

## 8. Ce que ça ne réglera pas

La question *« j'ai chargé 100 €, j'en vois 99 »* suppose un solde. L'API Anthropic expose la
**consommation** et le **coût**, pas le **crédit restant** de la clé. F-133 saura dire « on a dépensé
1,00 $ ce jour-là, et la facture le confirme » ; elle ne saura pas lire le solde du compte. Si le
solde compte, il se suit dans la Console — ou se déduit d'un montant de départ saisi une fois, ce qui
est un choix à faire, pas une évidence.

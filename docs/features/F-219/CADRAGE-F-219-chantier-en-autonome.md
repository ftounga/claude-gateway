# CADRAGE F-219 — Exécuter un chantier en autonome

**Statut :** livré le 2026-10-27 (4/4, PR #1310 → #1313, non déployé) — complété par l'agent de livraison (vague autonome) — découpage, contrat d'API et arbitrages ci-dessous
**Demande PO :** 2026-10-27
**Dépend de :** F-218 (mode offensif), à livrer avant

## Le besoin, dans les mots du PO
« Une fois le plan fait, l'outil doit valider que tout est faisable : pas de point de blocage, rien à arbitrer. Si c'est le cas, je dois pouvoir dire "exécute-moi tel chantier en autonome" : il lance toutes les actions et part d'un trait. On devient beaucoup plus puissants : on pourra lancer plusieurs chantiers en même temps en autonome. »

## Périmètre
1. **Contrôle avant départ**, calculé par le serveur et non deviné. Il vérifie que :
   - le plan est validé ;
   - aucune étape n'est BLOQUÉE ;
   - aucune attente ouverte vers un tiers (F-175) ne conditionne une étape ;
   - aucune question n'est en suspens ;
   - aucune information manquante n'est marquée dans le plan ;
   - les dépendances `depends_on` sont satisfiables.

   Résultat : « Prêt à partir », ou la liste précise de ce qui empêche le départ.
2. **Lancement** : une commande (outil, bouton « Lancer en autonome » dans le tableau des chantiers F-208, commande slash si c'est simple) qui exécute toutes les étapes restantes d'un trait, sans question, jusqu'à la clôture proposée.
   - Sur un vrai blocage (échec non récupérable, information manquante découverte en route), l'agent s'arrête proprement et marque l'étape BLOQUÉE avec la raison.
   - Le PO est alors notifié par les notifications existantes (F-185 et F-217).
3. **Plusieurs chantiers en parallèle**, dans ces limites :
   - un chantier autonome par sujet à la fois (le focus F-206 reste) ;
   - plusieurs sujets en même temps, dans la limite des 4 terminaux vivants (F-70, refus explicite au-delà) ;
   - le tour vit côté serveur (F-84) : le PO peut quitter l'écran.
4. **Longueur des chantiers** : vérifier que le plafond d'étapes d'un tour (100) et la compaction tiennent un chantier entier. Sinon, enchaîner les tours : relance automatique tant que des étapes restent et qu'aucun blocage n'est apparu, de façon bornée et visible.
5. **Écran**, conforme à `DESIGN_SYSTEM.md`. Sur le tableau des chantiers :
   - le badge « Prêt à partir », ou la raison du refus ;
   - le bouton « Lancer en autonome » ;
   - l'état « En autonome — étape 3/7 » en direct ;
   - le bouton « Reprendre la main ».
6. **API** de préflight et de lancement, par chantier, avec isolation `user_id`. Elle est **réutilisée par F-220** (tableau par client, multi-sélection) et doit être documentée ici.
7. **Coût** : une estimation simple est affichée au lancement si les mesures existent (`atelier_turn_measures`). Pas de confirmation demandée (décision F-218).

## Règles
- Justesse avant coût : l'agent autonome vérifie chaque étape (preuve dans les sous-étapes, F-208), il ne coche pas à l'aveugle.
- Rien de volatil dans le préfixe stable.

---

## Complément de l'agent de livraison (2026-10-27)

### Existant vérifié avant de cadrer
- **Le tour sans écran existe déjà** : `RoutineTurnRunner` (F-183) lance un tour dans le terminal d'un sujet par le
  mécanisme du tour vivant (F-84), `AtelierChatService.chatStreaming` — même modèle, même raisonnement, mêmes outils
  qu'au clavier. F-219 le **réutilise** (même schéma : ouvrir le tour si le sujet est libre, boucle en tâche de fond,
  journal en base, notification de fin) au lieu d'écrire une nouvelle boucle.
- **Le plan** (F-176/F-206/F-208) porte déjà tout ce que le préflight lit : version validée, phase, statut des étapes,
  `depends_on`, `waits_on` relié aux attentes F-175 (`TerminalActionService.statusOfKey`).
- **Les questions en suspens** sont un état du tour vivant (`LiveTurn.pendingQuestion()` / `pendingApproval()`, F-164).
- **Les notifications** passent par le catalogue `PushEvent` (F-185) ; F-217 en fait sonner celles qui « attendent une
  action » (`requiresAction()`).
- **Plafond d'étapes d'un tour** : 100 appels d'outils ; un plan peut compter 40 étapes, chacune exécutée **et**
  vérifiée (2 gestes `update_plan_step` + les commandes) : **un tour ne tient pas un chantier long**. D'où
  l'enchaînement borné de tours (SF-219-02).

### Découpage
| SF | Contenu | Couche |
|----|---------|--------|
| SF-219-01 | **Le contrôle avant départ** : statut d'étape `BLOQUE` (raison obligatoire), préflight calculé par le serveur, API de lecture | backend |
| SF-219-02 | **Le départ en autonome** : journal `chantier_autonomy_runs` (migration 175), lancement, boucle de tours bornée, arrêt propre sur blocage, « Reprendre la main », notifications, estimation de coût, API | backend |
| SF-219-03 | **L'écran** : badge « Prêt à partir » / raisons, « Lancer en autonome », « En autonome — étape k/n », « Reprendre la main », estimation ; commande `/autonome N` | frontend |
| SF-219-04 | **Le dire à l'agent** : outil `lancer_chantier_autonome` — l'agent vérifie le préflight et le départ est pris en file, il part dès que le tour courant est fini | backend |

### Contrat d'API (réutilisé par F-220)
Toutes les routes partent du JWT (`CurrentUser`) ; `requireOwned(userId, workspaceId)` d'abord — 404 sur le terminal
d'autrui ; aucune donnée d'un autre compte n'est lue.

| Méthode | Route | Rôle |
|---------|-------|------|
| GET | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy/preflight` | Préflight d'un chantier ouvert : `{ number, ready, reasons:[{code,label,step}] }` |
| GET | `/api/workspaces/{ws}/journey/autonomy` | Tous les chantiers ouverts du sujet : `{ chantiers:[{ number, title, preflight, run, estimate }], running, maxParallel }` |
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy` | Lancer en autonome → `201` + la course ; `409` `autonomy_not_ready` (raisons), `autonomy_subject_busy`, `autonomy_limit_reached`, `autonomy_host_offline` |
| POST | `/api/workspaces/{ws}/journey/autonomy/stop` | « Reprendre la main » → la course arrêtée (`STOPPED`, par l'utilisateur) |
| GET | `/api/atelier/autonomy/runs` | Les courses de l'utilisateur (en cours d'abord, puis les 20 dernières) — tous sujets, pour F-220 |

Codes de raison du préflight : `NOT_GUIDED`, `PLAN_NOT_VALIDATED`, `NOT_EXECUTING`, `NOTHING_LEFT`, `STEP_BLOCKED`,
`STEP_FAILED`, `WAIT_OPEN`, `MISSING_INFO`, `DEPENDENCY_BLOCKED`, `QUESTION_PENDING`.

### Arbitrages pris (décider par défaut, réversibles)
1. **« Étape BLOQUÉE »** devient un **statut d'étape** `BLOQUE` posé par l'agent avec sa raison (preuve obligatoire),
   et non un état seulement dérivé : c'est la seule façon que l'arrêt propre d'un chantier autonome se lise dans le plan.
   Débloquer : l'agent repasse l'étape `A_FAIRE` (ou `FAIT`). *Écarté* : réutiliser `ECHEC` (confond « ça a raté » et
   « il me manque quelque chose »).
2. **Information manquante** = une étape restante dont `waits_on` cite une clé **sans attente enregistrée** : l'input est
   attendu mais personne ne le suit. Une attente `FAIT` ou `ANNULE` ne bloque plus.
3. **Question en suspens** = question structurée ou demande d'autorisation en attente sur le tour vivant du sujet.
4. **Limite de parallélisme** = **4 courses autonomes en cours par compte** (le chiffre de F-70, même raison : garde-fou
   de dépense), refus explicite au-delà. Les courses ne prennent pas de place dans `live_terminals` (aucun onglet ouvert).
5. **Un chantier autonome par sujet** : une course `RUNNING` sur le sujet, ou un tour vivant, refuse le départ
   (`autonomy_subject_busy`).
6. **Enchaînement de tours** : au plus `app.autonomy.max-turns` (défaut **8**) tours par course ; un tour sans
   aucune avancée du plan arrête la course (`BLOCKED`, « aucune avancée ») — jamais de boucle à vide.
7. **Estimation de coût** : coût moyen d'un tour de l'utilisateur sur 30 jours × tours estimés — lu dans
   `usage_turns.provider_cost_usd` (`atelier_turn_measures` ne porte aucun coût, seulement des jetons)
   (une étape restante ≈ un demi-tour, au moins un tour) ; absente sans mesure. Aucune confirmation (F-218).
8. **Consigne d'autonomie** dans le **message** du tour, jamais dans la consigne système (préfixe stable, F-171).

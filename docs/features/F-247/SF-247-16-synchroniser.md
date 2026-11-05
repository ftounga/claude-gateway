# Mini-spec — F-247 / SF-247-16 « Synchroniser » : remplir le registre tout de suite, par client

## Identifiant
`F-247 / SF-247-16`

## Feature parente
`F-247` — Le registre d'état (amendement PO du 2026-11-05, version 3 : le rattrapage entre dans le périmètre —
cadrage §10 amendé)

## Statut
`in-review` (2026-11-05)

## Date de création
2026-11-05

## Branche Git
`feat/SF-247-16-synchroniser` (une PR backend puis une PR écran, même branche de base)

**Dépend de :** SF-247-02 (registre), 03 (file), 04/05 (extracteurs), 07/08 (relevé), 12 (lecture des captures),
15 (écran). **Estimation :** 2 j.

---

## Objectif
Un bouton « Synchroniser » par client, dans « Ce que je sais », remplit le registre **tout de suite** (historique des
tours, captures déjà déposées, relevé du jour) en tâche de fond, avec une barre de progression, sans rien bloquer.

---

## Comportement attendu

### Cas nominal
1. **Lancer** — `POST /api/jarvis/state/clients/{hostId}/sync` (sous le `user_id` du principal) crée une
   synchronisation (`jarvis_state_syncs`) et met **un** travail `STATE_SYNC` dans la file durable `async_jobs`
   (priorité basse : 200). Si une synchronisation de ce client est déjà active, elle est rendue telle quelle
   (**idempotent** : un seul passage actif par client, garanti par la contrainte unique `(user_id, active_host)`).
2. **Le travail avance par tranches** : chaque tranche (≤ 25 réponses, ≤ 2 captures ou 1 sonde, ≤ 5 s) persiste son
   curseur et sa progression, puis remet la tranche suivante dans la file (chaîne gardée par un compteur d'étape :
   un travail rejoué par le faucheur ne dédouble jamais la chaîne). Un redémarrage de pod reprend au curseur.
3. **Phase 1 — Historique** (sans IA, 0 €, poste éteint possible) : les réponses du client (`atelier_messages`
   portant `terminal_json`, sujets du client, fenêtre bornée) passent, **des plus anciennes aux plus récentes**, dans
   les extracteurs SF-247-04/05 (`StateExtractJobHandler.extract`, sans compter la couverture ni déclencher de
   constat payant). Chaque fait garde **l'heure de sa sortie d'outil** (journal du runner, sinon l'heure de la
   réponse) — jamais l'heure du rattrapage. L'upsert existant ignore toute observation plus ancienne que la ligne en
   base et le filtre « aucun secret » s'applique à l'enregistrement. Fenêtre : `min(90 j, rétention des faits du
   client)` — au-delà, la purge nocturne effacerait ce qu'on vient d'écrire (défaut 30 j, réglable à l'écran).
4. **Phase 2 — Captures déjà déposées** (runner allumé) : les dépôts d'images (`atelier_deposited_files`, png/jpeg/
   webp, ≤ 5 Mo, fenêtre `min(90 j, rétention des captures)`) **jamais lus** (absents de
   `jarvis_conversation_captures`) et **non déjà en file** sont relus sur le poste (`read_file_bytes`, par tranches,
   journal `releve_sonde:capture`) puis confiés à la lecture SF-247-12 (`CAPTURE_READ`, priorité 200 < 100 des
   captures du jour, `depositedAt` = **l'heure du dépôt d'origine**) : même plafond € (10 €/mois), même plafond
   quotidien (60), même coût compté au budget Jarvis. Au plus 10 lectures en file à la fois pour cette
   synchronisation. La phase se termine quand toutes ses lectures sont finies.
5. **Phase 3 — Relevé du jour** (runner allumé, à jour `low_priority`, relevé mis en place) : chaque sonde de relevé
   active passe une fois (`JarvisWatchPassage.run`, déclencheur `MANUAL`, `bashLow` qui cède et se fait préempter).
6. **Céder aux tours (Z4)** : avant chaque tranche, phase 1 → aucun tour vivant sur les sujets du client ; phases
   2/3 → `JarvisReleveService.quiet` (aucun tour vivant **et** aucune commande hors relevés depuis 2 min). Sinon la
   tranche est remise à 60 s (« en pause : tour en cours »). Un relevé `YIELDED` (poste occupé) est repris de même.
7. **Poste éteint** : phases 2/3 en `WAITING` (« poste éteint — en attente »), nouvel essai toutes les 2 min ;
   au-delà de 7 j d'attente, la phase est `EXPIRED`.
8. **Suivre** — `GET /api/jarvis/state/syncs` : la dernière synchronisation (≤ 7 j) de chaque client du compte, avec
   par phase `status`, `total`, `done`, `facts`, `durationMs`, `costEur`, `detail`. L'écran la relit toutes les 3 s
   tant qu'une synchronisation est active et **que le volet est ouvert** ; quitter l'écran n'arrête rien ; au retour,
   la barre reprend l'état persisté.
9. **Annuler** — `POST /api/jarvis/state/clients/{hostId}/sync/cancel` : la synchronisation passe `CANCELLED`, la
   tranche en vol s'arrête à sa prochaine écriture, les lectures de captures **en attente** de cette synchronisation
   sont abandonnées (`DROPPED`, image effacée). Les faits déjà écrits restent (ils sont vrais et datés).
10. **Mesure** (dans la ligne `jarvis_state_syncs`, lue par l'API et journalisée à la fin) : faits écrits par phase
    (phase 1 : lignes créées ou modifiées ; phase 2 : messages de conversation transcrits et captures lues ; phase 3 :
    faits `RELEVE` touchés), durée de travail par phase, € dépensés (phase 2 ; phases 1 et 3 : 0 €).
11. **Écran** (`jarvis-state-panel`) : par client, bouton « Synchroniser » (désactivé pendant un passage actif,
    « Annuler » à la place), une **barre orange** (`mat-progress-bar`, couleur `--cg-orange`) et trois lignes
    d'étape (« Historique », « Captures », « Relevé du jour ») avec leur état en clair (« 120/340 réponses · 46
    faits », « poste éteint — en attente », « en pause : tour en cours », « relevé non mis en place »…) et, à la fin,
    le bilan (faits, durée, €).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Client d'un autre compte (lancer, annuler) | refus, rien n'est créé | 404 |
| Registre coupé (global, client) ou coupe-circuit `APP_JARVIS_STATE_SYNC_ENABLED=false` | refus dit | 409 `{code, message}` |
| Annuler sans passage actif | refus | 404 |
| Source coupée pour le client (Tours, Captures, Relevé) | la phase est `SKIPPED` (« source coupée ») | — |
| Relevé non mis en place / runner trop ancien | phase 3 `SKIPPED` avec la raison | — |
| Fichier de capture introuvable sur le poste | compté « introuvable », on continue | — |
| Plafond € ou quotidien des captures atteint | lectures `DROPPED` (comme SF-247-12), dites dans le détail ; relancer plus tard les reprend | — |
| Travail de synchronisation rejoué (pod mort) | la tranche reprend au curseur ; chaîne non dédoublée | — |
| Échec réseau à l'écran | `MatSnackBar`, état d'avant | — |

---

## Critères d'acceptation
- [ ] Lancer crée **un** passage par client ; relancer pendant qu'il est actif rend le même ; relancer après la fin
      ne double aucun fait (upsert) ni aucune capture (déjà lue / déjà en file).
- [ ] Phase 1 : un fait historique porte l'heure de sa sortie d'origine ; un fait plus récent en base n'est jamais
      remplacé ; un secret n'entre jamais ; aucun appel modèle, aucun constat `STATE_NOTES` mis en file, couverture
      inchangée.
- [ ] Phase 2 : une capture déposée et jamais lue part en `CAPTURE_READ` priorité 200 avec `depositedAt` = heure du
      dépôt ; une capture déjà lue ou déjà en file n'est pas renvoyée ; poste éteint ⇒ `WAITING` « poste éteint ».
- [ ] Phase 3 : chaque sonde active passe une fois ; relevé non mis en place ⇒ `SKIPPED`.
- [ ] Z4 : tour vivant ⇒ aucune tranche ne travaille, la synchronisation est « en pause » puis reprend.
- [ ] Annuler ⇒ `CANCELLED`, lectures en attente abandonnées, la chaîne s'arrête.
- [ ] Isolation : deux comptes, deux clients — aucun passage, aucune lecture, aucun fait hors `user_id` + `host_id`.
- [ ] Écran : bouton, barre orange, étapes en clair, annuler ; l'état revient après fermeture/réouverture du volet ;
      le polling s'arrête à la fermeture ; masqué en présentation (hérité du volet).
- [ ] Portes Z1→Z13 inchangées : rien dans le chemin d'un tour ni d'une réplique ; consignes du cerveau rapide et du
      terminal **inchangées** (aucun banc live requis).

---

## Plan de test
- **Intégration backend** (`JarvisStateSyncIntegrationTest`, H2) :
  1. phase 1 date d'origine + fait plus récent non écrasé + secret filtré + idempotence (relance) + pas de
     `STATE_NOTES` ;
  2. phase 2 poste éteint ⇒ `WAITING` ; poste « vivant » (liveness simulée) ⇒ `CAPTURE_READ` priorité 200, payload
     `depositedAt` d'origine et `syncId` ; capture déjà lue ignorée ;
  3. tour vivant ⇒ pause, rien d'écrit ;
  4. annuler ⇒ `CANCELLED`, lectures en attente `DROPPED` ;
  5. isolation à deux comptes (404, rien lu) ; API (lancer 202/200, liste, 409 registre coupé) ;
  6. chaîne : un travail d'étape périmée est abandonné (`stale`).
- **Unitaire** : `AsyncJobQueue.enqueue` avec délai (`available_at` futur non pris).
- **Frontend** (`jarvis-state-panel.component.spec.ts`) : lancer ⇒ barre et étapes ; poste éteint dit ; annuler ;
  état relu à l'ouverture ; polling arrêté à la destruction.
- Suites complètes backend et frontend.

---

## Impact
- **Tables** : `jarvis_state_syncs` (nouvelle, migration `247-16`). `async_jobs` : nouveau `kind` `STATE_SYNC` (liste
  ouverte, sans migration).
- **Endpoints** : `POST /api/jarvis/state/clients/{hostId}/sync`, `POST …/sync/cancel`, `GET /api/jarvis/state/syncs`.
- **Backend** : `jarvis/state/sync/*` (nouveau) ; `AsyncJobQueue` (mise en file différée) ;
  `StateExtractJobHandler.extract` (variante sans couverture) ; `CaptureReadEnqueuer` (remise d'une capture existante) ;
  `CaptureReadJobHandler.payload` (variante avec `syncId`) ; `JarvisReleveService.quiet/disarmed` et
  `JarvisReleveTriggers.probesOf` rendus publics.
- **Frontend** : `jarvis/jarvis-state.ts`, `jarvis/jarvis-state-panel.component.{ts,spec.ts}`.
- **Coupe-circuit** : `APP_JARVIS_STATE_SYNC_ENABLED` (défaut `true`) ; fenêtre `APP_JARVIS_STATE_SYNC_WINDOW_DAYS` (90).

### Préoccupations transversales
| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | Routes sous `CurrentUser.requireId()`. **Pas** ajoutées à `JarvisCompanionScope` : le volet « Ce que je sais » (dont les routes `/jarvis/state/clients…`) n'est pas dans la portée compagnon — ses réglages engagent hors de la conversation ; n'ouvrir que la synchro serait inutile (le volet ne se charge pas). |
| Contexte tenant | **Oui** | `JarvisStateSyncService` (`RunnerHostRepository.findByIdAndUserId`), `JarvisStateSyncStore` (toute requête `user_id` + `host_id`/`id`), `StateSyncJobHandler` (relit le passage sous le `user_id` du travail ; messages par `findByIdAndUserId` ; sujets `user_id` + `host_id` ; dépôts `user_id` + sujets du client ; captures `user_id`), `CaptureReadEnqueuer.enqueueExisting` (sujet possédé), annulation (`async_jobs` sous `user_id`). |
| Plans / limites | **Oui** | Phase 2 passe par `CaptureReadJobHandler` inchangé : `JarvisStateSpend.maySpend/account` (plafond 10 €/mois, budget Jarvis F-244, part `CAPTURE`), plafond quotidien 60. Phases 1/3 : aucun appel modèle. |
| Navigation / routing | Non | Aucune route Angular ; le volet existe. |
| Protocole runner | Oui (lecture) | `RunnerToolGateway.readFileBytes` (existant) et `bashLow` via `JarvisWatchPassage` (existant) ; vieux runner : `unsupported_tool` ⇒ phase 2 `SKIPPED`, pas de `low_priority` ⇒ phase 3 `SKIPPED`. |

### Portes « zéro ralentissement » (inchangées, bloquantes)
Z1/Z12 : rien dans le chemin d'un tour ou d'une réplique (tout en file, fils démons priorité minimale, tranches ≤ 5 s).
Z3/Z13 : aucune consigne ni bloc du cerveau rapide modifiés. Z4 : gardes ci-dessus. Z5 : une sonde à la fois (bashLow,
20 s). Z6 : requêtes indexées (`workspaces(user_id, host_id)`, `atelier_messages(workspace_id)`, `runner_audit(user_id,
workspace_id, created_at)`, `atelier_deposited_files(user_id, workspace_id)`, `async_jobs(user_id, kind, created_at)`),
tranches `LIMIT`. Z7 : préfixe intact. Z8 : coupe-circuit. Z9/Z10/Z11 : le registre reste borné (upsert en place,
10 000 faits par client).

---

## Hors périmètre
- Les **constats rédigés** (SF-247-14) sur l'historique : payants, non demandés (phase 1 = 0 €).
- `tool_trace` : `terminal_json` porte déjà commande **et** sortie de chaque outil (F-30, antérieur à F-39) ; c'est la
  seule source relue.
- Une synchronisation « tous clients » d'un geste ; une planification automatique.
- La route compagnon (voir ci-dessus).
- Le déploiement.

## Arbitrages (réversibles)
- Fenêtre phase 1 bornée par la rétention des faits (sinon purge nocturne) ; le détail de la phase le dit.
- Polling léger (3 s, volet ouvert) plutôt que SSE : aucun flux SSE existant ne porte l'état du volet.
- La chaîne de tranches (un travail par tranche) plutôt qu'un long travail : le bail de 2 min et le faucheur restent
  valides, et chaque tranche peut céder.

# Mini-spec — F-247 / SF-247-17 « Constats sur l'historique » : le registre maximal, à partir de ce qui a déjà été vu

## Identifiant
`F-247 / SF-247-17`

## Feature parente
`F-247` — Le registre d'état (amendement PO du 2026-11-05, version 4 : cadrage §1 point 8)

## Statut
`done` (2026-11-05, PR #1629)

## Branche Git
`feat/SF-247-17-constats-historique`

**Dépend de :** SF-247-04/05 (extracteurs), SF-247-14 (constats rédigés, garde de citation), SF-247-16 (Synchroniser).
**Estimation :** 1,5 j.

---

## Objectif
Appliquer les constats rédigés (garde de citation mot pour mot) aux sorties d'outils **déjà gardées**, client par
client, dans « Synchroniser », dans une enveloppe ponctuelle distincte du plafond mensuel — et faire couvrir
gratuitement par les extracteurs les motifs réels les plus fréquents du PO.

## Constat de départ (prod, 2026-11-05)
Phase Historique : CAGIP 1 407 réponses → 3 faits ; EDENRED 454 → 0. Échantillon réel (90 j, lecture seule) :
CAGIP 7 402 sorties `bash` (12,2 M car.), EDENRED 1 613 (3,3 M car.). Usage : `python3` maison (690 / 223),
alias `A=~/aws-cli/aws`, `K=…/kubectl-devtools.sh`, `J="python3 …jira.py"`, `curl` à variables avec jeton capturé
(`T=$(… git credential fill …)`), scripts composés sur plusieurs lignes.

---

## Comportement attendu

### Cas nominal
1. **Ordre des phases** : Historique → **Constats** (phase 4, sans le poste) → Captures → Relevé du jour. L'écran les
   montre dans cet ordre (clé `NOTES`).
2. **Lancement** — `POST /api/jarvis/state/clients/{hostId}/sync` accepte un corps facultatif
   `{"notesEnvelopeEur": n}` (borné à [0 ; 100], 0 = pas de constats ; absent = défaut configuré
   `APP_JARVIS_STATE_SYNC_NOTES_ENVELOPE_EUR`, **20 €**). `GET /api/jarvis/state/syncs/envelope` rend l'enveloppe par
   défaut et le plafond mensuel des constats (affichés **avant** le lancement).
3. **Estimation** (sans IA) : pendant l'Historique, chaque réponse passe aussi dans le **tri** (`HistoryNotesSelector`) ;
   les sorties retenues et pas encore constatées (empreinte absente de `jarvis_state_notes_seen`) sont comptées
   (sorties, caractères, appels groupés par tour). L'estimation en € (prix du modèle rapide, ≈ 3 car./jeton +
   600 jetons d'habillage et 400 jetons de réponse par appel) est affichée dès l'Historique.
4. **Le tri** (déterministe) ne retient une sortie `bash` que si : code 0, entière, non vide ; **non couverte** par un
   extracteur ; **pas** un outil de fichiers ou de texte (`cat`, `sed`, `grep`, `ls`… toutes instructions confondues,
   corps des heredocs ignorés) ; **rien** qui touche un identifiant (commande `git credential`, trousseau,
   `get-login-password`, `get secret`, `.env`, `printenv`… sauf lecture **capturée** dans une variable, montrée au
   modèle comme `T=$(…)` ; sortie ou commande montrée qui ressemble à un secret) ; 24 à 8 000 car. Doublons exacts du
   tour écartés (empreinte SHA-256 du corps).
5. **Lecture** : une tranche = **un appel** au modèle rapide (`AiAgentProvider`, consigne **inchangée** `notes-v1`,
   1 500 jetons de sortie) sur un groupe de sorties d'un même tour (≤ 12 000 car.), des réponses les plus anciennes aux
   plus récentes. Garde de citation **inchangée** (`StateNotesGuard`) : un fait non prouvé mot pour mot est rejeté et
   compté. Faits : clé `NOTE:<objet>`, source `NOTE`, confiance `MOYENNE`, `observed_at` = **heure de la sortie
   d'origine** (journal du runner) ; l'upsert n'écrase jamais un fait plus récent ; filtre des secrets à
   l'enregistrement. Chaque sortie lue est **marquée** (empreinte) — jamais relue, ni par un autre passage ; les
   constats au fil de l'eau (SF-247-14) marquent aussi les leurs.
6. **Enveloppe** : avant chaque appel, `dépensé + pire coût de l'appel (réponse au plafond) > enveloppe` ⇒ la phase
   s'arrête (`envelope`) **sans** appeler. Budget Jarvis à l'arrêt dur ⇒ arrêt (`budget_hard`). Le coût est inscrit au
   journal JARVIS (part `ETAT`, hors quota) et au budget Jarvis (`jarvis_usage_days.notes_catchup_cost_usd`, ligne
   « État »), **hors** plafond mensuel des constats (`notes_cost_usd`, 15 €).
7. **Céder aux tours (Z4)** : tour vivant sur un sujet du client ⇒ « en pause : tour en cours », reprise dans 60 s.
8. **Annuler** : la phase passe `CANCELLED` ; les faits écrits et les marques restent.
9. **Mesure** (ligne `jarvis_state_syncs`, API, journal de fin) : sorties lues, appels, faits écrits, faits retenus et
   rejetés par la garde (part rejetée affichée), € dépensés, estimation, enveloppe, durée.
10. **Extracteurs gratuits** : (a) une commande composée est ramenée à son **unique** instruction utile (préfixes
    muets `cd`, `export`, affectations, `set -e`, `timeout N` retirés), l'alias de binaire en tête résolu
    (`aws`, `kubectl`, `oc`, `helm`, `glab`, `gh`, `az`, `terraform`, `argocd`, `*kubectl*.sh`) et les variables
    littérales substituées ; s'il reste plusieurs instructions (sorties mêlées), rien ne change ; (b) repli **JSON
    générique** : sortie entière JSON (objet ou ≤ 50 objets) dont chaque élément porte une identité (`key`, `name`,
    `arn`, `slug`, `id` avec une lettre) et `status`/`state`/`version`/`url` ⇒ `JSON:<identité>`, `OTHER`, `MOYENNE` ;
    jamais pour une commande de fichiers, jamais si un extracteur spécialisé a accepté la commande ; (c) l'écho entier
    d'une commande sur plusieurs lignes n'est plus pris pour la sortie.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Constats coupés (global, client) ou extraction des tours coupée | phase `SKIPPED` « source coupée » |
| Enveloppe 0 € | phase `SKIPPED` « enveloppe à 0 € » |
| Rien à lire (tout constaté, ou rien d'utile) | phase `DONE` « rien de nouveau à lire », 0 appel |
| L'appel suivant pourrait dépasser l'enveloppe | arrêt `envelope`, pas d'appel |
| Budget Jarvis à l'arrêt dur | arrêt `budget_hard` |
| Fournisseur en panne | rien n'est marqué, nouvel essai dans 60 s ; 5 échecs ⇒ arrêt `provider_error` |
| Réponse illisible, fait inventé, reformulé, secret | rejeté, compté |
| Passage antérieur (avant la migration) | `SKIPPED` « passage antérieur » |
| Client d'un autre compte | 404 (inchangé) |

---

## Critères d'acceptation
- [x] Seules les sorties utiles partent : ni fichiers, ni identifiant, ni couvertes, ni doublons ; regroupées par tour.
- [x] Garde de citation inchangée : un fait non cité mot pour mot est rejeté et compté ; consigne `notes-v1` inchangée.
- [x] Fait daté de la sortie d'origine ; plus récent jamais écrasé ; secrets filtrés.
- [x] Idempotent : relancer ne relit rien (empreintes), 0 appel.
- [x] Enveloppe jamais dépassée ; 0 € = sautée ; coût hors plafond mensuel, au budget Jarvis.
- [x] Z4 : tour vivant ⇒ pause, aucun appel.
- [x] Isolation : un autre compte ne lit ni n'hérite d'aucune sortie ni marque.
- [x] Écran : enveloppe avant, estimation, `sorties · faits · % rejetés · € / enveloppe` pendant, fin dite.
- [x] Gain des extracteurs gratuits mesuré sur l'échantillon réel.
- [x] Portes Z1→Z13 inchangées : rien dans le chemin d'un tour ni d'une réplique ; aucune consigne du cerveau rapide ni
      du terminal modifiée (aucun banc live requis).

---

## Plan de test
- **Unitaires** `StateExtractorsUsageTest` (5) : alias/préfixes/variables, JSON générique (et ses refus), écho
  multi-lignes, tri (chaque motif), regroupement.
- **Intégration** `JarvisStateSyncNotesIntegrationTest` (4, H2, fournisseur simulé) : sorties utiles seules + garde +
  date d'origine + non-écrasement + coût hors plafond + empreintes + relance sans appel ; enveloppe (arrêt avant
  l'appel, 0 €) ; pause sur tour vivant ; isolation et API (enveloppe demandée, enveloppe par défaut, 404).
- **Non-régression** : `JarvisStateSyncIntegrationTest` (4 phases), `StateExtractorsLot1/2Test`,
  `StateNotesJobIntegrationTest`, `StateNotesBenchTest`, `StateExtractJobIntegrationTest`.
- **Mesure** `StateSampleMeasureTest` (hors suite, `-Djarvis.sample=…`, échantillon jamais versionné).
- **Frontend** `jarvis-state-panel.component.spec.ts` : enveloppe avant, estimation et part rejetée, fin à l'enveloppe.
- Suites complètes backend et frontend.

## Mesure sur l'échantillon réel (90 j, sorties `bash`, lecture seule)
| | CAGIP | EDENRED |
|---|---|---|
| Sorties `bash` lues | 7 126 | 1 576 |
| Faits gratuits (clé·attribut distincts), avant → après | 3 → **12** (AWS 9, JSON 6) | 0 → 0 |
| Écartées : fichiers / inexploitables / identifiant / vides / trop courtes / doublons | 2 044 / 932 / 266 / 105 / 70 / 29 | 500 / 386 / 32 / 31 / 18 / 0 |
| Sorties retenues pour les constats | **3 674** (3,2 M car.) | **609** (0,64 M car.) |
| Appels (groupés par tour, ≤ 12 000 car.) | 902 | 215 |
| Coût estimé (modèle rapide de prod `claude-sonnet-5-5`, 2 $/10 $ par M) | **≈ 6,7 €** (pire cas 15,8 €) | **≈ 1,5 €** (pire cas 3,6 €) |

Les deux tiennent dans l'enveloppe de 20 € par passage. Le gain gratuit est **réel mais petit** : l'usage du PO est
fait de scripts composés (plusieurs instructions, sorties mêlées) et de sorties texte de scripts maison, que seul un
constat rédigé peut lire sans risque. La part rejetée par la garde sera mesurée au premier passage réel (ligne de la
synchronisation, journal de fin).

---

## Impact
- **Tables** (migration `247-17`) : `jarvis_state_notes_seen` (nouvelle : `user_id`, `host_id`, `fingerprint`,
  `created_at`, PK sur les trois premières) ; `jarvis_state_syncs` + colonnes `p4_*` ; `jarvis_usage_days` +
  `notes_catchup_cost_usd`.
- **Endpoints** : `POST /api/jarvis/state/clients/{hostId}/sync` (corps facultatif), `GET /api/jarvis/state/syncs/envelope`
  (nouveau) ; `GET /api/jarvis/state/syncs` (phase `NOTES` en plus).
- **Backend** : `jarvis/state/extract/{CommandShape, GenericJsonExtractor, HistoryNotesSelector, HistoryNotesReader}`
  (nouveaux) ; `StateExtractors`, `BashOutput`, `StateExtractJobHandler` (`historyCandidates`, faits JSON des
  commandes non reconnues), `StateNotesJobHandler` (marque ses sorties) ; `JarvisStateSpend` (`accountCatchup`,
  `priceUsd`, `jarvisHardStop`) ; `JarvisUsageDay/Service`, `JarvisCostService` (ligne « État ») ;
  `sync/{SyncRow, JarvisStateSyncStore, StateSyncJobHandler, JarvisStateSyncService, JarvisStateSyncController}`.
- **Frontend** : `jarvis/jarvis-state.ts`, `jarvis/jarvis-state-panel.component.{ts,spec.ts}`.
- **Config** : `APP_JARVIS_STATE_SYNC_NOTES_ENVELOPE_EUR` (20). Coupe-circuits existants : `APP_JARVIS_STATE_NOTES_ENABLED`,
  `APP_JARVIS_STATE_SYNC_ENABLED`.

### Préoccupations transversales
| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | Routes sous `CurrentUser.requireId()` ; nouvelle route `GET …/syncs/envelope` (lecture de configuration seule). Pas dans `JarvisCompanionScope` (même décision que SF-247-16). |
| Contexte tenant | **Oui** | `StateSyncJobHandler.notesPhase` (passage relu sous `user_id`, sujets `user_id`+`host_id`, messages `findByIdAndUserId`) ; `StateExtractJobHandler.historyCandidates` (journal du runner sous `user_id`) ; `HistoryNotesReader.seen/mark` (`user_id`+`host_id`) ; `JarvisStateRegistry.record` (client vérifié au compte) ; `StateNotesJobHandler` (marques sous `user_id`+`host_id`). |
| Plans / limites | **Oui** | `JarvisStateSpend.accountCatchup` (journal `JARVIS/ETAT`, hors quota), `jarvis_usage_days.notes_catchup_cost_usd` dans `JarvisCostService.sum` ⇒ `JarvisBudgetService.status` ; `jarvisHardStop` ; plafond mensuel des constats `maySpend(NOTES)` **non** touché par le rattrapage ; enveloppe par passage. |
| Navigation / routing | Non | Aucune route Angular. |

### Portes « zéro ralentissement » (inchangées, bloquantes)
Z1/Z12 : tout en file (`STATE_SYNC`, priorité 200), un appel par tranche, hors du chemin d'un tour et de Jarvis.
Z3/Z13 : aucune consigne ni bloc du cerveau rapide ou du terminal modifiés (`notes-v1` réutilisée telle quelle).
Z4 : pause sur tour vivant. Z6 : requêtes indexées (`atelier_messages`, `runner_audit`, PK des empreintes), lots
`LIMIT`. Z7 : préfixe intact. Z8 : coupe-circuits et enveloppe 0. Z9/Z10/Z11 : registre borné (upsert, 10 000 faits).

## Hors périmètre
- Étendre les constats **au fil de l'eau** aux scripts maison (aujourd'hui limités aux commandes d'état reconnues) :
  piste suivante, à décider sur la mesure de ce premier passage.
- Grouper plusieurs tours par appel (le PO a demandé « d'un même tour »).
- Un réglage de l'enveloppe à l'écran (l'API l'accepte ; défaut par configuration).
- Le déploiement.

## Arbitrages (réversibles)
- Phase numérotée 4 mais jouée en 2e : pas de renumérotation des phases d'un passage déjà en vol au déploiement.
- Estimation volontairement prudente (3 car./jeton) ; le pire cas sert de garde-fou avant chaque appel.
- Les empreintes portent sur le corps seul : deux sorties identiques ne coûtent qu'une lecture.

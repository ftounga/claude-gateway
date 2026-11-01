# Mini-spec — F-230 / SF-230-01 Poser une veille

## Identifiant
`F-230 / SF-230-01` — Feature parente : F-230 « Les veilles Jarvis » (cadrage `CADRAGE-F-230-les-veilles-jarvis.md`, décisions **D1, D2, D5**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-230-01-poser-une-veille`.

## Objectif
Permettre au PO de poser une veille Jarvis — sujet (+ chantier facultatif), plateforme et périmètre en clair, sonde en lecture seule écrite par l'agent sur le poste, heures données par le PO, lundi→vendredi imposés — **proposée par l'agent, testée devant le PO puis activée par lui**.

## Comportement attendu

### Cas nominal
1. **Table `jarvis_watches`** (migration **185**) : une ligne par veille, liée au `user_id` **et** au `workspace_id` (clé étrangère, cascade à la suppression du sujet ; purge nommée à la suppression du compte). Les colonnes de planification (`last_slot_at`, `missed_slot_at`, `consecutive_failures`, `broken`, `last_run_at`, `last_run_status`, `monthly_budget_usd`) sont posées dès cette migration (nullables ou à défaut) : SF-02/03 les remplissent, sans nouvelle `ALTER`.
2. **Outil `veille_poser`** (terminal d'un **sujet sur poste**, propriétaire seulement — même garde que `routine_proposer`). Entrée : `nom`, `plateforme`, `perimetre` (en clair), `commande` (la ligne lancée sur le poste), `script` (chemin relatif du script de sonde écrit par l'agent, s'il y en a un), `heures` (1 à 6 `HH:mm`), `chantier` (facultatif). La gateway :
   - valide les champs ;
   - **D5** : refuse une commande qui contient un littéral ressemblant à un jeton (formes de `ClientMailSecrets` + jeton Atlassian `ATATT…`), une méthode d'écriture HTTP (`POST/PUT/PATCH/DELETE`, sauf `POST` GraphQL **sans** `mutation`), ou une mutation GraphQL ; si la commande appelle un fichier de script (`.sh .py .js .mjs .cjs .ps1 .rb .ts .bash`), `script` est exigé, doit figurer dans la commande, est **relu sur le poste** (`read_file`, lecture seule) et passe le même contrôle ; son **empreinte SHA-256** est épinglée ;
   - range la veille **proposée** (`proposed=true, active=false, origin=AGENT`) — **elle ne tourne pas** ;
   - rend à l'agent : « Veille proposée … elle N'EST PAS active : une carte permet au PO de la tester puis de l'activer ».
   - Une veille du **même nom** dans le sujet est **remplacée** (sonde, heures, périmètre) et redevient une proposition non testée : c'est le chemin de réparation (D6, SF-02).
3. **La sonde rend une liste normalisée** (sortie standard, JSON) : `[{"id","titre","statut","assigne","mis_a_jour","dernier_commentaire"}]` (les clés accentuées `assigné`, `mis_à_jour` sont acceptées). 500 éléments au plus ; champs bornés.
4. **Carte de validation** en tête du fil (`app-terminal-veilles-band`) : nom, plateforme, périmètre, « Lundi → vendredi · 09:00, 14:00 », commande en clair (police mono), script éventuel. **[Tester la sonde]** lance la sonde **une fois** sur le poste, au clic du PO (consentement humain), et affiche « Je vois 12 éléments : 5 en cours, 2 bloqués… » + 5 titres d'exemple ; **[Activer]** n'est permis qu'après un test réussi ; **[Refuser]** supprime.
5. **Activation** : `proposed=false, active=true`, `last_slot_at` = créneau dû courant (une veille ne part jamais pour un créneau d'avant son activation).
6. **Commande `/veilles`** (panneau du fil, aucun tour modèle) : la liste des veilles du sujet (état, heures, plateforme), suppression.
7. Description de l'outil (littéral stable, cache F-134) : invite l'agent à **proposer en une phrase** une veille à l'ouverture d'un chantier sur un client suivi par une plateforme de tickets, et à ne l'écrire qu'après accord du PO (D1 — rien d'automatique).

### Contrat API (figé — consommé par le frontend de cette SF et par SF-02/03/04)
```json
// JarvisWatchView
{ "id": "uuid", "workspaceId": "uuid", "chantierNumber": 3, "name": "Agenor CAGIP",
  "platform": "Jira", "scope": "Tableau agenor : en cours, bloqués, ready to dev, nouveaux assignés à moi ; jamais les done",
  "command": "bash .atelier/veilles/agenor.sh", "scriptPath": ".atelier/veilles/agenor.sh",
  "runTimes": ["09:00", "14:00"], "timeZone": "Europe/Paris", "days": "MONDAY_TO_FRIDAY",
  "active": false, "proposed": true, "origin": "AGENT",
  "testedAt": null, "testItemCount": null, "testSummary": null,
  "nextRunAt": null, "createdAt": "…", "updatedAt": "…" }
// POST …/test → JarvisWatchTestView
{ "ok": true, "itemCount": 12, "summary": "12 éléments : 5 en cours, 2 bloqués, 5 ready to dev",
  "sample": ["PROJ-12 Alarme EDENRED", "…"], "errorMessage": null }
// Erreurs : {"error": "<code>", "message": "…"}
```
- `GET    /api/workspaces/{workspaceId}/watches` → `JarvisWatchView[]`
- `POST   /api/workspaces/{workspaceId}/watches/{watchId}/test` → `JarvisWatchTestView` (200 même en échec de sonde : `ok=false`, `errorMessage`)
- `POST   /api/workspaces/{workspaceId}/watches/{watchId}/accept` → `JarvisWatchView`
- `DELETE /api/workspaces/{workspaceId}/watches/{watchId}` → `204`

Champs ajoutés plus tard (additifs) : `lastRunAt`, `lastRunStatus`, `broken`, `consecutiveFailures` (SF-02) ; `monthlyBudgetEur`, `monthSpentEur` (SF-03).

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Sujet inexistant ou d'un autre compte | 404 indiscernable | 404 |
| Veille d'un autre sujet / compte | `watch_not_found` | 404 |
| Champ manquant ou hors bornes, heure illisible | `watch_invalid` | 400 |
| Littéral ressemblant à un jeton, écriture HTTP, mutation GraphQL, script non déclaré, script modifié depuis la proposition | `watch_unsafe` (la nature, jamais la valeur) | 400 |
| Sujet hébergé ou terminal du poste | `watch_unsupported` | 400 |
| Plus de 10 veilles sur le sujet | `watch_limit` | 409 |
| `accept` sans test réussi depuis la proposition | `watch_untested` | 409 |
| `accept` sur une veille déjà active | `watch_conflict` | 409 |
| `test` poste hors ligne | `watch_host_offline` | 409 |
| Sonde en échec (code ≠ 0, sortie illisible, délai) au test | 200 `ok=false` + message | 200 |
| Outil appelé hors garde / champ invalide / D5 | résultat d'outil en erreur, rien n'est créé | — |

## Contraintes de validation
`nom` 1..80 ; `plateforme` 1..40 ; `perimetre` 1..500 ; `commande` 1..2000 ; `script` ≤ 300, relatif, sans `..` ; `heures` 1..6 valeurs `HH:mm` distinctes (triées) ; `chantier` ≥ 1 ; fuseau `Europe/Paris` (défaut, comme les routines proposées) ; jours **lundi→vendredi imposés** (aucun champ) ; 10 veilles max par sujet ; sonde : délai 60 s, 500 éléments max, `titre` ≤ 200, `statut`/`assigne` ≤ 80, `dernier_commentaire` ≤ 300.

## Critères d'acceptation
- [ ] `veille_poser` dans un sujet sur poste range une veille proposée, inactive ; au terminal du poste ou d'un sujet hébergé, il est refusé et rien n'est créé.
- [ ] Une commande portant `Authorization: Bearer abcdef…`, un `ghp_…`, un `ATATT…`, `curl -X POST` (hors GraphQL) ou une `mutation` est refusée (`watch_unsafe`), avec la nature du problème, jamais la valeur.
- [ ] Une commande qui appelle un script sans le déclarer est refusée ; le script déclaré est relu et son empreinte épinglée ; un script modifié entre la proposition et le test est refusé.
- [ ] `test` exécute la sonde **une fois**, rend le décompte par statut et 5 exemples ; `accept` est refusé sans test réussi, permis après.
- [ ] Un autre compte reçoit 404 sur la liste, le test, l'acceptation et la suppression.
- [ ] La carte affiche la commande en clair ; [Activer] est désactivé tant que le test n'a pas réussi.

## Plan de test
- **Unitaires** : `JarvisWatchProbeGuardTest` (jetons, méthodes HTTP, GraphQL, script non déclaré), `JarvisWatchItemsTest` (parse JSON propre / bruité / clés accentuées / illisible, résumé par statut), `JarvisWatchServiceTest` (propose, remplacement par nom, limite, test OK/KO, accept sans test, empreinte de script), `JarvisWatchToolExecutorTest` (garde, entrée → service).
- **Intégration** : `JarvisWatchControllerIT` (MockMvc + H2 : liste, test avec passerelle runner simulée, accept, delete, **isolation : 404 pour un autre compte**).
- **Frontend** : `terminal-veilles-band.component.spec.ts` (carte, test, activer désactivé avant test, refuser), `atelier-slash-veilles.component.spec.ts` (liste, suppression), `veille.service.spec.ts`.

## Tables / endpoints / composants impactés
- Table **`jarvis_watches`** (nouvelle). Endpoints ci-dessus. `AtelierChatService` (déclaration + aiguillage de l'outil). `AccountService` (purge nommée). Frontend : `atelier-terminal` (bande + `/veilles`), `slash-panel-commands.ts`.

## Préoccupations transversales
- **Contexte tenant** : toute lecture/écriture filtre `user_id` + `workspace_id` (`requireOwned` puis `findByIdAndUserIdAndWorkspaceId`). Composants qui résolvent le tenant ici : `JarvisWatchService`, `JarvisWatchToolExecutor` (compte et terminal **du tour**), `JarvisWatchController` (`CurrentUser`).
- **Plans / limites** : aucune consommation IA dans cette SF (le test est une commande, pas un tour). Plafond de 10 veilles par sujet.
- **Navigation** : aucune route ajoutée (panneau du fil).

## Hors périmètre
Passages planifiés et diff (SF-02) ; appel IA et notification (SF-03) ; pause/reprise, tableau du client, compteurs (SF-04) ; écriture sur une plateforme ; week-end ; accès direct de la gateway aux plateformes ; la voix (F-231).

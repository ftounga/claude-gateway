# Mini-spec — [F-273 / SF-273-01] Erreurs de sonde honnêtes et gardées

## Identifiant

`F-273 / SF-273-01`

## Feature parente

`F-273` — Sondes de relevé : l'erreur réelle, lisible

## Statut

`in-progress`

## Date de création

2026-11-21

## Branche Git

`feat/SF-273-01-erreurs-sondes-lisibles`

---

## Diagnostic (preuves prod, lecture seule, 2026-11-21)

- `jarvis_watches` : les sondes `ab6764d6` (Relevé Jira, `python3 .atelier/releve/jira.py`) et `d4ec5e81`
  (Relevé git) du sujet secrets-azure-devops (poste EDENRED `82cc700e`) sont `proposed = true`, `active = false`,
  `tested_at = null`, `updated_at = created_at = 2026-11-05 11:21:09`. **Aucun passage** dans `jarvis_watch_runs`.
- `runner_audit` : pour ce poste, les seules lignes `veille_sonde:*` / `releve_sonde:bash` sont les deux
  `veille_sonde:read_file` OK de la proposition (05/11 11:21:09). Un test (bouton ou « Tout valider ») relit
  **toujours** le script d'abord, et cette relecture est **toujours** journalisée (OK ou ERROR) : aucun test n'a
  donc jamais atteint le poste. Le seul chemin d'échec en amont est « poste hors ligne » (liveness) — message
  rendu par l'API mais **jeté par l'écran** (« 0 sonde activée · 2 en échec », sans la raison) et **jamais gardé**
  (`test()` remet `tested_at` à nul et n'écrit aucune erreur).
- Autres postes (CAGIP) : 184 passages `FAILED probe_missing` (« Le script de sonde est introuvable ») alors que
  le journal du runner montre, pour les relectures `veille_sonde:read_file`, 50 `ERROR runner_unavailable` et
  134 `TIMEOUT runner_timeout` : **le runner ne répondait pas**, le script existait. `readScript` confond les deux.
- Une sortie en code ≠ 0 ne donne que « code N : jeton expiré, réseau… » : la vraie raison (dernière ligne
  écrite par le script) n'est jamais montrée.
- Une coupure du poste (runner muet) compte comme un échec de sonde ⇒ après 2, « en panne : demande à l'agent de
  la réparer » alors que la sonde n'y est pour rien.
- L'environnement d'exécution n'est **pas** en cause : la commande part dans le dossier du sujet (même cible que
  les sondes CAGIP qui passent : `python3 .atelier/releve/…`, 204 passages OK/CHANGED).

## Objectif

Qu'une sonde en échec dise **la vraie raison**, la **garde** sur la veille jusqu'au succès suivant, et ne soit
plus accusée à tort (script « introuvable », « en panne ») quand c'est le poste qui ne répond pas.

## Comportement attendu

### Cas nominal

1. **Relecture du script** : runner joignable et fichier lu ⇒ inchangé. Échec du runner :
   `runner_unavailable` ⇒ `host_offline` « Le poste ne répond pas (runner déconnecté) : … » ; `runner_timeout`,
   `runner_protocol_error`, `runner_not_on_this_node`, exception ⇒ `probe_transport` « Le poste n'a pas répondu
   à la relecture du script… ». Seul un refus **du runner lui-même** (`not_found`, chemin refusé) donne
   `probe_missing`, avec le message du runner.
2. **Code de sortie ≠ 0** : le message se termine par « Dernière ligne : « … » » — la dernière ligne non vide de
   la sortie, 200 caractères au plus, secrets masqués (motifs `token|password|secret|authorization|bearer|api key`
   suivis d'une valeur, et toute suite de 24+ caractères base64/hex ⇒ `***`).
3. **Sortie illisible** (`probe_output`) : même extrait (début de la sortie, 200 car.) ajouté au message.
   L'analyse tolère une ligne de bruit commençant par `[` (ex. `[WARN] …`) avant la liste JSON.
4. **Erreur gardée** : `jarvis_watches.last_error_code`, `last_error_message` (500), `last_error_at` sont écrits
   à chaque échec de **test** et de **passage** (hors « a cédé la place »), effacés au succès suivant et à une
   reproposition. Exposés dans `JarvisWatchView` (`lastErrorCode`, `lastErrorMessage`, `lastErrorAt`) et dans la
   vue « Relevé » du client (`ProbeView`).
5. **Poste muet ≠ sonde cassée** : un passage en `host_offline` ou `probe_transport` est journalisé `FAILED` avec
   son code et l'erreur gardée, **sans** incrémenter `consecutive_failures` ni passer la veille « en panne ».
6. **« Tout valider »** : poste hors ligne ⇒ **409** `watch_host_offline` « Le poste est hors ligne : … » une
   seule fois (au lieu de N échecs muets) ; sinon chaque résultat porte le message réel (déjà le cas, désormais
   aussi gardé sur la veille).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| « Tout valider », poste hors ligne | refus global, message lisible, aucune sonde touchée | 409 |
| Test, runner muet à la relecture | `ok=false`, message « Le poste n'a pas répondu… », erreur gardée | 200 |
| Test, script réellement absent | `ok=false`, `probe_missing` + message du runner, erreur gardée | 200 |
| Client d'un autre compte | 404 (inchangé) | 404 |

## Critères d'acceptation

- [ ] Relecture en `runner_unavailable` ⇒ `host_offline` ; `runner_timeout` ⇒ `probe_transport` ; `not_found` ⇒ `probe_missing`.
- [ ] Code de sortie ≠ 0 ⇒ message avec la dernière ligne de sortie, secrets masqués, 200 car. au plus.
- [ ] Un test en échec écrit `last_error_*` ; un test réussi les efface ; une reproposition les efface.
- [ ] Un passage en échec écrit `last_error_*` ; un passage réussi les efface ; un passage « cédé » n'y touche pas.
- [ ] Un passage `host_offline` / `probe_transport` n'incrémente pas `consecutive_failures` et ne met pas « en panne ».
- [ ] `POST /api/jarvis/state/clients/{hostId}/releve/validate-all`, poste hors ligne ⇒ 409 `watch_host_offline`.
- [ ] `GET …/releve` et `GET /api/workspaces/{id}/watches` exposent `lastErrorCode/Message/At`.
- [ ] Isolation : les écritures `last_error_*` filtrent `id + user_id` ; aucune route nouvelle.

## Périmètre

### Hors scope (explicite)

- L'affichage (SF-273-02) et l'outil de lecture de l'agent (SF-273-03).
- La contention « une seule commande basse priorité à la fois » côté runner (sondes d'un même créneau qui se
  cèdent la place : 499 `busy_deferred`) — sans erreur visible, non traitée ici.
- Aucune modification du runner ; aucun déploiement.

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| last_error_code | null | écrit à l'échec, effacé au succès / reproposition |
| last_error_message | null | idem, 500 car. au plus |
| last_error_at | null | idem |

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| last_error_code | Non | 32 | codes fermés `JarvisWatchProbeRunner.ERR_*` | Non | — |
| last_error_message | Non | 500 | texte de la passerelle + extrait masqué | Non | tronqué à 500 |

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/jarvis/state/clients/{hostId}/releve/validate-all` | Oui | USER (propriétaire) — 409 hors ligne |
| GET | `/api/jarvis/state/clients/{hostId}/releve` | Oui | USER — champs ajoutés |
| GET | `/api/workspaces/{id}/watches` | Oui | USER — champs ajoutés |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| jarvis_watches | ALTER + UPDATE | 3 colonnes nullables |

### Migration Liquibase

- [x] Oui — `273-01-jarvis-watches-last-error.xml` (rollback : colonnes retirées)

## Plan de test

### Tests unitaires

- [ ] `JarvisWatchProbeRunnerTest` — relecture `runner_unavailable`/`runner_timeout`/`not_found` ; exit ≠ 0 avec extrait masqué ; sortie illisible avec extrait.
- [ ] `JarvisWatchItemsTest` — bruit `[WARN] …` avant la liste accepté.
- [ ] `JarvisWatchPassageTest` — échec transport non compté, échec de sonde compté, erreur gardée puis effacée.

### Tests d'intégration

- [ ] `JarvisWatchApiIntegrationTest` — test en échec ⇒ `lastErrorMessage` lu par `GET watches` ; test réussi ⇒ effacé.
- [ ] `JarvisReleveIntegrationTest` — `validate-all` poste hors ligne ⇒ 409 ; vue relevé expose l'erreur.

### Isolation workspace

- [x] Applicable — `validate-all` d'un client d'un autre compte ⇒ 404 (test existant conservé) ; écritures filtrées `user_id`.

## Dépendances

- Aucune subfeature bloquante. Aucune question ouverte impactée.

## Notes et décisions

- **Extrait de sortie en base** : la règle historique « aucun contenu de sortie n'est journalisé » vise le
  journal du runner et les instantanés ; l'extrait gardé ici est **la seule ligne d'erreur**, bornée et masquée,
  parce que le PO doit lire la vraie raison (besoin `f2d66ba2`). Le journal du runner reste sans contenu.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni routing).

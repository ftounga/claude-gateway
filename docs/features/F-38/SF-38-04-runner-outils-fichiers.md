# Mini-spec — F-38 / SF-38-04 — Runner : outils fichiers

## Identifiant
`F-38 / SF-38-04`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`in-review`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-04-runner-outils-fichiers`

---

## Objectif

> Rendre le runner **capable d'exécuter les quatre outils fichiers** (`list_files`, `read_file`,
> `write_file`, `search_files`) sur la machine de l'utilisateur, strictement confinés à la racine
> `--workspace`, en recevant les trames `tool_call` et en répondant `tool_result` sur le canal
> WebSocket déjà ouvert (SF-38-02/03). **Aucune exécution de commande** (`bash` → SF-38-07), **aucun
> routage côté gateway** (SF-38-05).

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de la socket, le runner émet une trame `ready` :
   `{"type":"ready","protocol":1,"runnerVersion":"…","capabilities":["files"],"os":"linux"}`.
   (`bash` n'est **pas** annoncé : il arrive en SF-38-07.)
2. La gateway émet `{"type":"tool_call","id":"toolu_…","tool":"read_file","input":{…},"timeoutMs":30000}`.
   Le runner **parse le champ `type`** (Jackson) — plus aucune heuristique sur le texte de la trame.
3. L'appel est exécuté sur un **thread worker dédié** (jamais le thread heartbeat, jamais le thread
   de réception) :
   - `list_files` → chemins relatifs des fichiers réguliers sous la racine, triés, un par ligne ;
   - `read_file` → contenu texte UTF-8 du fichier ;
   - `write_file` → écrit (crée les dossiers parents manquants sous la racine) ;
   - `search_files` → lignes `chemin:ligne: texte`, bornées à 8 000 caractères avec le suffixe exact
     `… (résultats tronqués)` **ajouté par le runner**.
4. Le runner répond **exactement une** trame terminale par `id` :
   `{"type":"tool_result","id":…,"ok":true,"content":"…","truncated":false,"durationMs":12,"bytes":1234}`.
5. Toutes les émissions (heartbeat inclus) passent par **une file d'émission mono-thread** qui
   sérialise les `sendText` et chaîne les `CompletableFuture` — contrainte de `java.net.http.WebSocket`.
   Le heartbeat continue pendant un appel en cours (contrat §6).
6. `tool_cancel` sur un appel en vol : le runner interrompt le worker et émet le `tool_result`
   terminal `ok=false, error.code="cancelled"` (sauf s'il a déjà répondu → cancel ignoré).
7. Le runner arme son propre chronomètre sur `timeoutMs` : à l'échéance il interrompt le worker et
   répond `ok=false, error.code="timeout"`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `path` hors racine (`../`, chemin absolu, lettre de lecteur Windows) | `ok=false`, `error.code="path_outside_root"` — **rien n'est lu ni écrit** |
| `path` résolu **via un lien symbolique** qui sort de la racine | `ok=false`, `error.code="path_outside_root"` (résolution canonique `toRealPath`) |
| Fichier inexistant (`read_file`) | `ok=false`, `error.code="not_found"`, message avec chemin **relatif** |
| Chemin désignant un dossier alors qu'un fichier est attendu | `ok=false`, `error.code="is_directory"` |
| Cible non régulière (socket, device, lien cassé) | `ok=false`, `error.code="not_a_file"` |
| Fichier au-delà du plafond de lecture dure (8 Mio) | `ok=false`, `error.code="too_large"` |
| `path`/`query`/`content` manquant ou vide, ou `content` > 512 Kio | `ok=false`, `error.code="invalid_input"` |
| Permission refusée / erreur disque | `ok=false`, `error.code="io_error"` (message sans chemin absolu) |
| `tool` = `bash` ou outil inconnu | `ok=false`, `error.code="unsupported_tool"` |
| Trame JSON illisible | `{"type":"protocol_error","code":"unparsable",…}` — la socket **n'est pas fermée** |
| `tool_call` sans `id` exploitable ou sans `tool` | `{"type":"protocol_error","code":"invalid_envelope",…}` |
| Trame de `type` inconnu | **ignorée en silence** (compatibilité ascendante, contrat §0) |
| `tool_cancel` sur un `id` inconnu | ignoré en silence |
| Socket fermée avec des appels en vol | les workers sont interrompus, aucun résultat n'est émis (la gateway conclut `runner_unavailable`) |

---

## Critères d'acceptation

- [ ] Le runner exécute `list_files`, `read_file`, `write_file`, `search_files` reçus en `tool_call`
      et répond exactement une trame `tool_result` par `id`, avec `durationMs` renseigné.
- [ ] `read_file`/`write_file`/`search_files` refusent tout chemin qui sort de la racine
      `--workspace` : `..`, chemin absolu, `C:\…`, **et** lien symbolique pointant hors racine
      (vérification par résolution canonique `toRealPath`).
- [ ] `write_file` crée les dossiers parents manquants **sous la racine** et écrit en UTF-8 ; il
      refuse un contenu > 512 Kio (`invalid_input`) et une cible qui est un dossier (`is_directory`).
- [ ] `list_files` ne renvoie que des **chemins relatifs** de fichiers réguliers, triés, et ne suit
      pas les liens symboliques (pas de boucle, pas de sortie de racine).
- [ ] `search_files` produit le format `chemin:ligne: texte` et le suffixe exact
      `… (résultats tronqués)` au-delà de 8 000 caractères ; `Aucun résultat.` si rien ne matche.
- [ ] `content` d'un `tool_result` est borné à 512 Kio, coupé sur une frontière de caractère, avec
      `truncated=true`.
- [ ] **Aucun message d'erreur ne contient de chemin absolu de la machine** (règle anti-fuite).
- [ ] `bash` (et tout outil inconnu) répond `unsupported_tool` sans rien exécuter.
- [ ] Une trame illisible produit `protocol_error` et **ne ferme pas** la socket ; une trame de type
      inconnu est ignorée.
- [ ] `tool_cancel` termine l'appel en vol en `cancelled` ; le dépassement de `timeoutMs` le termine
      en `timeout`.
- [ ] Toutes les émissions (heartbeat compris) passent par la file mono-thread : aucun `sendText`
      concurrent, donc plus d'`IllegalStateException` intermittente.
- [ ] `cd runner && ./mvnw test` est vert ; `cd backend && ./mvnw test` reste vert (aucune modification backend).

---

## Périmètre

### Hors scope (explicite)

- **Outil `bash`** et le streaming `tool_stream` → SF-38-07.
- **Routage côté gateway** (`RunnerCallDispatcher`, `ConcurrentWebSocketSessionDecorator`, bean
  `ServletServerContainerFactoryBean` 1 Mio, cible d'exécution `RUNNER` du workspace) → SF-38-05.
- **Exclusions** `.runnerignore` / deny-list non désactivable → SF-38-10. En SF-38-04, `list_files`
  et `search_files` voient donc **tout** fichier régulier sous la racine (y compris `.git/`) ; c'est
  assumé et borné (voir « Notes et décisions »).
- **Audit** `runner_audit` → SF-38-08. **Repli long-polling** → SF-38-09. **Écrans** → SF-38-06.
- Aucune migration Liquibase, aucun endpoint HTTP, aucun composant Angular.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|--------------|-----------------------------|---------------|
| `id` (trame) | Oui | 64 caractères | chaîne opaque non vide | renvoyée verbatim |
| `tool` | Oui | — | `list_files` \| `read_file` \| `write_file` \| `search_files` (autres → `unsupported_tool`) | — |
| `timeoutMs` | Non | — | entier > 0 ; défaut **30 000** si absent/invalide | — |
| `input.path` | Oui (`read_file`, `write_file`) | 4 096 caractères | relatif, séparateur `/`, sans `..`, sans `/` initial, sans `\0`, sans lettre de lecteur | `\`→`/`, segments vides supprimés |
| `input.content` | Oui (`write_file`, peut être vide) | **524 288 octets UTF-8** | texte | — |
| `input.query` | Oui (`search_files`) | 1 024 caractères | non vide après `strip()` | `strip()`, comparaison insensible à la casse |

Bornes imposées (contrat de messages §5) :
- `content` d'un `tool_result` ≤ **524 288 octets** ; au-delà, coupe sur frontière de caractère + `truncated=true`.
- Plafond de lecture dure : **8 Mio** → `too_large` (au-delà, une tête tronquée n'a aucune valeur).
- `search_files` : résultat borné à 8 000 caractères, fichiers > 1 Mio et fichiers binaires ignorés.
- `list_files` : 20 000 entrées au plus (`truncated=true` au-delà).

---

## Technique

### Endpoint(s)
Aucun. Les messages transitent sur le canal WebSocket `/runner/ws` livré en SF-38-02.

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
Aucun.

### Classes (module `runner/`, package `fr.claudegateway.runner`)

| Classe | Rôle |
|--------|------|
| `PathGuard` | Résolution canonique + confinement à la racine (`toRealPath`, symlinks compris), relativisation des chemins pour les messages |
| `ToolException` | Erreur d'outil portant un code de la liste close du contrat |
| `ToolOutcome` | Résultat d'exécution (ok, content, truncated, bytes, code, message) |
| `FileTools` | Implémentation des 4 outils fichiers |
| `FrameSender` | File d'émission **mono-thread** sérialisant tous les `sendText` (heartbeat inclus) |
| `FrameTransport` | Interface d'émission (adaptée sur `java.net.http.WebSocket`), rend le tout testable |
| `ToolDispatcher` | Parse `tool_call`/`tool_cancel`, exécute sur un worker, arme le timeout, émet `tool_result`/`protocol_error` |
| `RunnerConnection` (modifiée) | Parsing réel du champ `type`, émission de `ready`, heartbeat via `FrameSender` |

---

## Plan de test

### Tests unitaires

- [ ] `PathGuard` — nominal : `src/a.ts` résolu sous la racine ; `./a`, `a//b` normalisés.
- [ ] `PathGuard` — `..`, `/etc/passwd`, `C:\Windows` → `path_outside_root`.
- [ ] `PathGuard` — lien symbolique vers `/etc` → `path_outside_root` (résolution canonique).
- [ ] `PathGuard` — chemin vide/`\0` → `invalid_input`.
- [ ] `FileTools.readFile` — nominal, `not_found`, `is_directory`, `too_large`, troncature à 512 Kio.
- [ ] `FileTools.writeFile` — nominal, création de dossiers parents, contenu > 512 Kio → `invalid_input`,
      cible dossier → `is_directory`, chemin hors racine → `path_outside_root`.
- [ ] `FileTools.listFiles` — chemins relatifs triés, ignore les dossiers et les liens symboliques.
- [ ] `FileTools.searchFiles` — format `chemin:ligne: texte`, `Aucun résultat.`, troncature 8 000 caractères.
- [ ] Anti-fuite : aucun message d'erreur ne contient la racine absolue.
- [ ] `FrameSender` — envois sérialisés, aucun `sendText` avant complétion du précédent ; envoi sans
      transport attaché ignoré sans exception.

### Tests d'intégration (bout en bout dans le module runner, sans réseau)

- [ ] `ToolDispatcher` + `FileTools` + `FrameSender` sur transport factice : `tool_call read_file`
      → une trame `tool_result` `ok=true` portant le contenu et le même `id`.
- [ ] `tool_call` `write_file` puis `read_file` → le fichier est bien écrit sur le disque.
- [ ] `tool_call` avec `path` hors racine → `tool_result ok=false path_outside_root`, fichier intact.
- [ ] `tool_call` `bash` → `unsupported_tool`.
- [ ] Trame illisible → `protocol_error unparsable` ; trame de type inconnu → aucune émission.
- [ ] `tool_cancel` → `tool_result ok=false cancelled` ; `timeoutMs` dépassé → `timeout`.
- [ ] Exactement une trame terminale par `id` (cancel après résultat = ignoré).

### Isolation workspace

- [x] Applicable — **l'isolation est ici l'isolation de la racine du système de fichiers** :
      les tests vérifient qu'aucun chemin hors `--workspace` n'est lisible ou inscriptible (`..`,
      absolu, symlink). L'isolation `user_id`/`workspace_id` du canal est portée par
      `RunnerHandshakeInterceptor` (SF-38-02) : le runner ne lit **jamais** d'identité dans un message.

---

## Dépendances

### Subfeatures bloquantes
- `SF-38-02` — canal WS + registre — statut : done
- `SF-38-03` — module runner, connexion WSS, heartbeat — statut : done

### Questions ouvertes impactées
- Aucune.

---

## Notes et décisions

- **D-04-1 (réversible)** — *Plafond de lecture dure à 8 Mio* : au-delà, `too_large` plutôt qu'une
  tête tronquée de 512 Kio, qui n'aurait aucune valeur pour le modèle et coûterait une lecture inutile.
- **D-04-2 (réversible)** — *`list_files`/`search_files` ne suivent pas les liens symboliques* :
  `Files.walkFileTree` sans `FOLLOW_LINKS`. Un lien reste invisible dans l'arborescence (il n'est pas
  un fichier régulier). Cela ferme d'un coup les boucles de liens et les sorties de racine par lien.
- **D-04-3 (réversible)** — *Bornes de balayage* : 20 000 entrées pour `list_files`, fichiers > 1 Mio
  et binaires (octet nul dans les 8 premiers Kio) ignorés par `search_files`. Sans exclusions
  (SF-38-10), c'est ce qui garde le balayage d'un dépôt réel utilisable.
- **D-04-4 (réversible)** — *`unsupported_tool` en `tool_result` plutôt qu'en `protocol_error`* pour
  `bash` et tout outil inconnu : la gateway obtient ainsi la trame terminale qu'elle attend pour cet
  `id`, au lieu d'un appel resté en vol jusqu'au timeout.
- **Piège corrigé** — l'ancien `payload.contains("heartbeat_ack")` est remplacé par un vrai parsing
  Jackson du champ `type` ; sans cela, aucun `tool_call` n'aurait jamais été exécuté.
- **Piège corrigé** — le `sendText` du heartbeat partait du thread `runner-heartbeat` ;
  toutes les émissions passent désormais par `FrameSender` (file mono-thread), sinon
  `java.net.http.WebSocket` lève des `IllegalStateException` intermittentes qui tuent la socket.
- **À flagger pour SF-38-05** — tant que le bean `ServletServerContainerFactoryBean`
  (`setMaxTextMessageBufferSize(1048576)`) n'est pas posé côté backend, un `tool_result` dépassant
  8 192 octets sera coupé par le conteneur. SF-38-04 ne pose pas ce bean (périmètre SF-38-05) ; en
  attendant, aucun `tool_call` n'est émis par la gateway, donc la situation n'est pas atteignable.

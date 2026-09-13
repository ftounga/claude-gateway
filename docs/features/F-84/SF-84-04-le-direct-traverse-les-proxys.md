# Mini-spec — F-84 / SF-84-04 — Le direct traverse les proxys qui retiennent le flux (correctif)

## Identifiant

`F-84 / SF-84-04`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`done` — PR #525, mergée le 2026-09-13

## Date de création

2026-09-13

## Branche Git

`feat/SF-84-04-direct-derriere-un-proxy`

---

## Objectif

Pendant un tour d'atelier, le terminal montre **au fil de l'eau** qu'il a pris la demande en main puis
chaque appel d'outil au moment où il commence — **y compris** quand un proxy d'entreprise retient le
flux SSE jusqu'à sa fin.

---

## Constat (production, image `staging-a2f5c00`, 2026-09-13)

Poste CAGIP, workspace `8210f678…`, cible RUNNER. Tour ouvert 11:54:32Z, terminé 12:02:44Z
(17 étapes, 29 appels d'outils). Le terminal est resté sur « démarrage… » tout du long.

### Cause racine — prouvée par les journaux, pas déduite du code

1. **Le navigateur n'a rien reçu avant la fin.** L'écran rapporte son aperçu toutes les 30 s
   (`POST /terminal/live`, corps dérivé des blocs du tour). Journal de l'ingress : corps de
   **1235 octets constants** de 11:54:32 à 12:02:20 (aperçu « démarrage », zéro bloc), puis **1917 à
   12:02:44 — la seconde exacte où `POST /chat/stream` se termine** (46 993 octets, 492,47 s).
   Même motif sur les deux tours précédents (11:45:41→11:46:06 et 11:51:08→11:53:38) : l'invite
   d'autorisation du tour de 11:51 n'a jamais été vue, d'où son refus par expiration — et le PO a
   désarmé la porte à 11:53:46, juste après.
2. **Notre chaîne ne retient rien.** Contrôleur ingress-nginx 1.9.4 : `proxy_buffering off` et
   `gzip off` générés pour `location /api/` (lu dans `nginx.conf` du pod) ; devant, un **NLB en TCP**.
   Côté Spring, chaque `SseEmitter.send` vide la sortie. La piste « tampon nginx » est **écartée**.
3. **Le client passe par Netskope.** Les requêtes du même onglet alternent entre `24.206.110.197` et
   `24.206.110.213` : `NETSKOPE-PAR2` (RDAP ARIN). Netskope, comme Zscaler, **retient le corps d'une
   réponse `text/event-stream` jusqu'à ce qu'elle soit complète** (inspection TLS) — comportement
   documenté par les éditeurs de produits en streaming. Aucun en-tête ni rembourrage ne le contourne.

### Second constat, même cause (12:28, « il s'était arrêté quand je suis revenu »)

Le PO lance un tour à 12:28:15Z (`chat-sse-13`), part sur `/chat` à 12:28:47, revient à 12:30:13,
voit un terminal au repos et renvoie la même demande à 12:30:31 (`chat-sse-15`). **Le tour de 12:28
ne s'était pas arrêté** : journal backend, `Point de contrôle bloquant` à 12:32:15 puis `Tour d'atelier
terminé : 18 étape(s), 291 s, 952044 tokens` à **12:33:07**, sur `chat-sse-13`. Journal de l'ingress :
le rebranchement du retour, `GET /chat/attach?cursor=0`, part à 12:30:13 et ne se termine qu'à
12:30:31 (17,594 s, 947 octets) — **retenu par le proxy** comme le flux d'émission ; l'aperçu rapporté
à 12:30:13 est celui d'un terminal au repos (1579 octets). L'écran n'a donc jamais su qu'un tour
tournait, a laissé renvoyer, et `LiveTurnRegistry.open` a **remplacé** le tour vivant : ses spectateurs
sont détachés, il devient introuvable, sa boucle continue à l'aveugle — deux boucles ont tourné en
parallèle sur le même projet de 12:30:31 à 12:31:46. Remède : la même sonde sur le rebranchement
(l'écran voit le tour en ≤ 4 s au retour, là où le PO a attendu 18 s), et deux lignes de journal qui
manquaient — le remplacement d'un tour encore vivant, et la clôture de **tout** flux de tour avec son
issue (y compris les erreurs de pré-vol, où la boucle n'écrit rien).

### Pistes écartées

| Piste | Verdict | Preuve |
|---|---|---|
| (a) abonnement resté sur le tour précédent refusé | écartée | chaque envoi ouvre son propre `POST /chat/stream` (journal) ; `LiveTurnRegistry.open` ferme le précédent ; le rapport d'aperçu reste à zéro bloc dès le 1er tour |
| (b) événements d'outil émis en fin d'étape seulement | écartée pour `bash`/`read`/`write`/`edit`/`list`/`search` (`listener.onAction` avant `executeTool`) ; **partielle** : `explore` et les outils `teams_*` n'ont **aucune** étape | `AtelierChatService.stepFor` |
| (c) rendu hors zone sans signal | écartée | le chronomètre (`setInterval` dans la zone) relance un cycle chaque seconde, et l'aperçu — calculé par un `effect` sur les signaux — n'a pas bougé : l'état lui-même n'a rien reçu |
| (d) tampon nginx | écartée | configuration générée lue sur le pod |
| (e) proxy d'entreprise côté client | **retenue** | points 1 et 3 |

---

## Comportement attendu

### Cas nominal

1. **Prise en main.** Dès l'ouverture du tour, la gateway publie `started` (`turnId`, `startedAt`)
   dans le tour vivant — premier événement, avant tout appel fournisseur. L'écran passe de
   « démarrage… » à « demande reçue — Claude réfléchit… ».
2. **Chaque appel d'outil au moment où il commence.** Tout appel d'outil publie son `action` avant
   son exécution — y compris `explore` (la question déléguée) et les outils `teams_*` (leur cible
   d'audit, jamais leur résultat). Seul `set_plan` n'en publie pas : il a son propre affichage.
3. **Sonde de flux retenu.** `started` part en quelques millisecondes sur un réseau direct. Si l'écran
   ne l'a pas reçu **4 s** après l'envoi, il suit le tour **par fenêtres** en plus du flux d'origine :
   `GET /workspaces/{id}/chat/attach?cursor=N&waitMs=W`.
4. **Attache par fenêtres.** Avec `waitMs`, la gateway rejoue depuis le curseur puis suit le direct,
   et **clôt la réponse** 250 ms après le premier événement de tour livré, ou au plus tard après
   `waitMs` (borné à [1 000 ; 25 000] ms). Une réponse complète est relâchée par le proxy : l'écran
   voit l'événement, puis se rebranche aussitôt avec son nouveau curseur. Sans `waitMs`, rien ne
   change.
5. **Ni doublon ni trou.** Les deux sources (flux d'origine, fenêtres) partagent le même
   numérotage `id:` ; l'écran ignore tout événement de tour dont le numéro est déjà vu. Quand le
   flux d'origine finit par être relâché d'un bloc, tout ce qu'il contient est déjà affiché et
   ignoré.
6. **Fin.** `done` ou `error` reçu par l'une ou l'autre source arrête les fenêtres. Un
   rebranchement (`reattachTurn`, ouverture d'un projet dont un tour tourne) applique la même sonde
   sur l'aparté `attached`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Fenêtre ouverte alors qu'aucun tour ne tourne (fini entre deux fenêtres) | `idle` puis clôture ; l'écran réessaie au plus 3 fois espacées de 2 s puis s'en remet au flux d'origine, qui porte `done` | 200 (SSE) |
| `waitMs` hors bornes ou négatif | ramené dans [1 000 ; 25 000] ; absent ⇒ comportement historique | 200 (SSE) |
| Réseau coupé pendant une fenêtre | traité comme `idle` : nouvel essai espacé | — |
| Accès Atelier refusé | `error: forbidden` dans le flux, comme aujourd'hui | 200 (SSE) |
| Tour d'un autre utilisateur | introuvable ⇒ `idle` (isolation par clef `(userId, workspaceId)`, inchangée) | 200 (SSE) |

---

## Critères d'acceptation

- [x] CA1 — `started` est le **premier** événement de tour publié par `POST /chat/stream`, avant tout appel à `chatStreaming`.
- [x] CA2 — `explore` et un outil `teams_*` publient une `action` avant leur exécution ; `set_plan` n'en publie pas ; les étapes existantes sont inchangées.
- [x] CA3 — `GET /chat/attach?waitMs=…` clôt la réponse après le premier événement de tour livré (rejeu ou direct), et au plus tard à l'échéance ; le spectateur est **détaché** du tour, qui continue.
- [x] CA4 — Sans `waitMs`, l'attache se comporte exactement comme avant (tests F-84 existants verts).
- [x] CA5 — **Reproduction du constat** : flux `POST /chat/stream` retenu (aucun octet), le terminal affiche l'étape `bash` reçue par fenêtre — test rouge avant correctif, vert après.
- [x] CA6 — Un événement reçu deux fois (fenêtre puis flux d'origine relâché) n'est appliqué qu'une fois.
- [x] CA7 — La ligne vivante dit « demande reçue — Claude réfléchit… » après `started` et avant la première étape ; « démarrage… » avant `started`.
- [x] CA8 — Isolation : une fenêtre ouverte par un autre utilisateur ne voit pas le tour (`idle`).
- [x] CA9 — Journal : toute clôture de flux de tour écrit `Flux de tour clos (…, issue=…)` (done, code d'erreur ou `echec_fatal`) ; remplacer un tour encore vivant écrit un avertissement ; aucune donnée de message.

---

## Périmètre

### Hors scope (explicite)

- **Texte du modèle jeton par jeton.** La boucle maison appelle le fournisseur sans streaming
  (`AiAgentProvider.nextTurn`, un aller-retour) : le commentaire d'une étape est relayé **avant ses
  outils**, mais pas jeton par jeton, et la réponse finale arrive avec `done`. Le streaming
  fournisseur (événements `content_block_delta`, blocs de raisonnement signés à reconstituer,
  réessais) est une subfeature à part entière, qui touche **chaque** tour de production — proposée
  en **SF-84-05**, non livrée ici. Elle n'aurait de toute façon rien changé au constat : derrière
  Netskope, un flux jeton par jeton est retenu comme le reste.
- Le parcours Managed Agents (`AtelierAgentController`, `/agent/stream`) : autre moteur, autre flux.
- La mosaïque (`LiveTurnView`, lecture seule) : même exposition, même remède applicable ; laissée en
  l'état pour ne pas élargir le lot.
- Toute modification Kubernetes / ingress : aucune n'est nécessaire (tampon nginx écarté).
- **Refuser côté gateway un envoi sur un projet dont le tour tourne encore** (au lieu de remplacer
  le tour vivant) : c'est un changement de contrat de `POST /chat/stream` — aujourd'hui l'écran
  transforme un envoi pendant un tour en précision (`steer`). Désormais journalisé ; la décision
  (refus, ou envoi converti en précision côté gateway) est proposée au PO, non tranchée ici.
- La configuration Netskope du client (exemption `text/event-stream` ou « Do Not Decrypt » sur
  `portal.ng-itconsulting.com`) : **recommandée** au client, elle rend les fenêtres inutiles, mais le
  produit ne peut pas en dépendre.

---

## Valeurs initiales

Non applicable — aucune entité créée.

## Contraintes de validation

| Champ | Obligatoire | Bornes | Règle |
|---|---|---|---|
| `waitMs` (query) | Non | [1 000 ; 25 000] ms | absent ⇒ attache historique ; hors bornes ⇒ ramené dans les bornes |
| délai de sonde (écran) | — | 4 000 ms | constante |
| délai de clôture après 1er événement | — | 250 ms | constante (regroupe une rafale) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| POST | `/api/workspaces/{id}/chat/stream` | Oui (JWT, droit Atelier) | nouvel événement `started` en tête |
| GET | `/api/workspaces/{id}/chat/attach?cursor=&waitMs=` | Oui (JWT, droit Atelier) | paramètre optionnel `waitMs` |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `AtelierChatController` (`started`, `waitMs`, journal de clôture),
  `live/WindowedTurnSubscriber` (nouveau), `live/LiveTurnRegistry` (journal de remplacement),
  `AtelierChatService.stepFor` (étapes manquantes).
- Frontend : `AtelierService` (`followTurnInWindows`, filtre de numéros déjà vus, événement
  `started`), `AtelierComponent` (sonde sur `send` et `reattachTurn`), `atelier-terminal` (libellé de
  prise en main), `chat-steps` (libellé `explore`).

### Préoccupations transversales

- Auth / Principal : **non** touché (gating inchangé, résolu sur le thread de requête).
- Contexte tenant : **non** — l'attache cherche toujours le tour par `(userId, workspaceId)`.
- Plans / limites : **non** — l'attache passe par `turnAttachExecutor` (lecteur), aucune place au
  registre F-70 n'est prise, aucun token consommé. Composants vérifiés : `LiveTerminalService`
  (aucun appel ajouté), `SseStreamDispatch` (inchangé).
- Navigation / routing : **non**.

---

## Plan de test

### Tests unitaires

- [ ] `WindowedTurnSubscriberTest` — clôture après le 1er événement de tour + linger ; les apartés (seq 0) ne déclenchent pas la clôture ; clôture à l'échéance ; détache du tour qui continue ; `finish` idempotent.
- [ ] `AtelierChatServiceTest` — `explore` et `teams_*` publient une étape ; `set_plan` non.
- [ ] `chat-steps.spec` — libellé `explore`.
- [ ] `atelier-terminal.component.spec` — libellé après `started`.
- [ ] `atelier.service.spec` — `started` routé ; numéros déjà vus ignorés ; fenêtres : URL avec `waitMs` et curseur, rebranchement après clôture, arrêt sur `done`, abandon après `idle` répétés.

### Tests d'intégration

- [ ] `AtelierChatControllerAttachTest` — `attach(…, waitMs)` clôt le flux après le rejeu ; clôt après le 1er événement direct ; sans `waitMs`, reste ouvert (non-régression).
- [ ] `AtelierChatControllerTurnSurvivalTest` / nouveau test — `started` publié en premier.
- [ ] `atelier.component.spec` — **CA5** : `streamChat` retenu, fenêtre livrant `action bash` ⇒ bloc visible ; CA6 doublon ignoré.

### Isolation workspace

- [x] Applicable — `AtelierChatControllerAttachTest` : la fenêtre ouverte par BOB sur le projet d'ALICE rend `idle`.

---

## Dépendances

### Subfeatures bloquantes

- SF-84-01, SF-84-02, SF-84-03 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Pas de nouveau transport.** Les fenêtres réutilisent l'attache par curseur de SF-84-02 :
  même endpoint, mêmes événements, même numérotage. Seule la **durée de vie** de la réponse change.
- **D2 — Sonde plutôt que fenêtres systématiques.** Un réseau direct ne paie rien : `started` arrive
  avant 4 s et aucune fenêtre n'est ouverte.
- **D3 — `X-Accel-Buffering` non ajouté.** L'en-tête ne sert qu'à nginx, dont le tampon est déjà
  désactivé en production (preuve ci-dessus) ; l'ajouter n'aurait rien corrigé.

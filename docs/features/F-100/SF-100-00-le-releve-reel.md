# Mini-spec — F-100 / SF-100-00 — Le relevé réel

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 et §12 bis (F-100, constats du 2026-09-13 et
> corrections du PO) ; `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §4.8 et §5.3.
> Le cadrage est validé par le PO : cette mini-spec l'applique, elle ne le rediscute pas.

## Identifiant

`F-100 / SF-100-00`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`done` — livrée le 2026-09-13 (PR #510) ; **le relevé lui-même reste à faire par le PO** sur un poste client

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-00-releve-reel`

---

## Objectif

Donner au PO **l'outil du relevé réel** : un mode diagnostic du runner, lancé à la main sur un vrai
poste d'entreprise, qui relève **hôtes, chemins et type de cible** (onglet Teams, autre onglet,
cadre intégré, worker) des réponses que Teams web reçoit — **jamais les corps, jamais les chaînes de
requête, jamais les en-têtes** — et l'écrit dans un rapport **local**.

---

## Contexte

Le cadrage (§12 bis, SF-100-00) fait du relevé réel un **préalable, fait une seule fois** : les
adresses sont les mêmes pour tous les clients, seules la session et les droits changent. Le relevé
lui-même **ne peut pas être fait par l'agent de livraison** : il n'a pas de poste client, pas de
tenant Microsoft 365. Cette sous-feature livre donc **l'instrument**, et trace que **le relevé reste à
faire par le PO** (commande et mode d'emploi ci-dessous). Les sous-features suivantes s'écrivent sur
les hypothèses actuelles de `TeamsUrls` et sur la reconnaissance par motif ; le relevé les confirmera
ou les corrigera **dans l'adaptateur unique**, sans rien réécrire ailleurs.

Les trois angles morts que le relevé doit trancher (cadrage §5) : **un autre onglet**, **un cadre
intégré d'un autre site** (lecteur Stream en `*.sharepoint.com`, isolé dans son processus), **un
worker**. Le rapport dit, pour chaque chemin, **d'où** il a été vu.

---

## Comportement attendu

### Mode d'emploi (ce que fait le PO)

```text
1. Lancer Chrome avec le port de débogage (la ligne exacte est celle du volet Teams, F-87).
2. Ouvrir Teams web et s'y connecter.
3. java -jar claude-runner.jar --releve-teams [--teams-port 9222] [--duree 15] [--sortie <dossier>]
4. Suivre les étapes affichées : taper 1, 2, 3, 4 puis Entrée au début de chaque étape
   (1 un fil · 2 une réunion passée · 3 son récapitulatif · 4 sa transcription), « fin » pour terminer.
5. Lire le rapport écrit dans le dossier de sortie : releve-teams-<horodatage>.md (+ .json).
```

Ni `--gateway`, ni `--root`, ni appairage : **aucune connexion à la gateway**, aucun jeton lu ou
écrit. Rien ne quitte la machine : le rapport est un fichier local que le PO relit avant de le
transmettre.

### Cas nominal

1. `RunnerMain` reconnaît `--releve-teams` **avant** toute résolution de configuration et délègue à
   `TeamsSurveyCommand` ; le runner ne s'appaire pas, ne se connecte pas, n'ouvre aucun transport.
2. Découverte du navigateur sur la boucle locale (`/json/version`, `/json/list`, comme F-87) ;
   l'**onglet Teams** est requis (sinon : le remède de F-87, code de sortie 2).
3. Sur l'onglet Teams : `Network.enable`, écoute de `Network.responseReceived`, puis
   `Target.setAutoAttach` (aplati) — **commandes déjà dans la liste blanche, aucune ajoutée**.
4. Chaque cible attachée (`Target.attachedToTarget`) est retenue **seulement si son adresse est sur
   un domaine Microsoft autorisé** (`MicrosoftDomains.isAllowed`, source unique F-108) ; l'écoute du
   réseau y est activée par `Network.enable` **sur la session de cette cible**. Une cible hors liste
   n'est jamais écoutée.
5. Les **autres onglets** dont l'adresse est sur un domaine Microsoft autorisé (SharePoint, Stream,
   OneDrive, Office) sont aussi écoutés, en lecture seule (`Network.enable` uniquement) ; la liste des
   onglets est relue toutes les 5 s pour capter un onglet ouvert pendant le relevé. **Aucun autre
   onglet de l'utilisateur n'est touché.**
6. Pour chaque réponse observée, le relevé retient **uniquement** :
   - l'**origine** : `TEAMS_TAB`, `OTHER_TAB`, `FRAME`, `WORKER`, `SERVICE_WORKER` ;
   - l'**hôte ramené à son motif** : `<tenant>.sharepoint.com` → `*.sharepoint.com`,
     `<tenant>-my.sharepoint.com` → `*-my.sharepoint.com` (le nom du tenant n'est jamais écrit) ;
   - le **chemin gabarisé** : tout segment qui ressemble à un identifiant (chiffres longs, `:`, `@`,
     UUID, hexadécimal, encodage `%`, base64) devient `{id}` ; **la chaîne de requête et l'ancre sont
     retirées à l'entrée** ;
   - le type de ressource (`XHR`, `Fetch`, `Document`, `Media`, `WebSocket`…), le type MIME, le
     statut HTTP ;
   - la **classification actuelle** de l'adaptateur (`TeamsUrls` : `CONVERSATION_MESSAGES`,
     `MEETING_TRANSCRIPT`, … ou `UNKNOWN`) — c'est ce qui fait apparaître **les écarts** ;
   - l'**étape** en cours (1 à 4) où le chemin a été vu la première fois ;
   - un **compteur**.
7. Sont écartés sans détail : scripts, feuilles de style, images, polices ; les réponses hors domaines
   Microsoft sont seulement **comptées** (« 312 réponses hors domaines Microsoft, non détaillées »).
8. Fin : sur « fin », fin de l'entrée standard, ou au terme de la durée (`--duree`, 10 min par défaut,
   1 à 60). Le rapport est écrit : un tableau Markdown par hôte-motif (chemin, origines, types,
   classification, étapes, nombre), la section **« Écarts avec l'adaptateur »** (chemins utiles
   classés `UNKNOWN`), la section **« Vu seulement hors de l'onglet Teams »** (les trois angles morts),
   et le même contenu en JSON.
9. Les sockets de débogage sont fermées ; le runner sort avec le code 0.

### Cas d'erreur

| Situation | Comportement attendu | Code de sortie |
|-----------|---------------------|----------------|
| Navigateur non détecté sur le port | Remède F-87 (ligne de commande à coller) | 2 |
| Pas d'onglet Teams / page d'identification | Remède F-87 (ouvrir Teams / se connecter) | 2 |
| `--duree` hors 1..60 ou non entier | Message d'usage | 2 |
| Dossier de sortie absent ou non inscriptible | Message clair, aucun relevé lancé | 2 |
| Liaison perdue pendant le relevé (fenêtre fermée) | Le relevé s'arrête, **le rapport partiel est écrit** et dit « interrompu » | 0 |
| Cible attachée hors domaines Microsoft | Non écoutée, sans bruit | — |
| Aucune réponse observée | Rapport écrit, qui le dit (« rien observé : Teams était-il actif ? ») | 0 |

---

## Critères d'acceptation

- [ ] `--releve-teams` ne demande ni `--gateway` ni `--root`, n'appaire pas, n'ouvre aucun transport.
- [ ] Le relevé n'émet que `Network.enable` et `Target.setAutoAttach` (et la découverte HTTP locale) :
      **jamais** `Network.getResponseBody`, jamais une commande cookies/stockage ; la liste blanche
      `CdpCommands` est **inchangée**.
- [ ] Un cadre ou un worker hors domaines Microsoft n'est jamais écouté (`Network.enable` non envoyé
      sur sa session) ; un onglet hors domaines Microsoft n'est jamais ouvert.
- [ ] Le rapport ne contient **aucune** chaîne de requête, **aucun** en-tête, **aucun** corps, **aucun**
      nom de tenant SharePoint, **aucun** identifiant (segments gabarisés en `{id}`).
- [ ] Chaque chemin porte ses origines (`TEAMS_TAB`, `OTHER_TAB`, `FRAME`, `WORKER`,
      `SERVICE_WORKER`) et sa classification `TeamsUrls` ; les chemins `UNKNOWN` sont listés en écarts,
      ceux vus seulement hors de l'onglet Teams sont listés à part.
- [ ] Les réponses de ressources statiques sont écartées ; les réponses hors domaines Microsoft sont
      comptées, non détaillées.
- [ ] Une liaison perdue écrit un rapport partiel marqué « interrompu ».

---

## Périmètre

### Hors scope (explicite)

- **Faire** le relevé : c'est un geste du PO sur un poste client (tracé en risque résiduel).
- Corriger `TeamsUrls` d'après le relevé : fait en SF-100-03 pour la reconnaissance par motif ; les
  écarts réels seront corrigés **après** le relevé, dans l'adaptateur unique.
- Tout geste dans la page (navigation, clic) : le relevé **observe** ce que le PO fait à la main.
- Toute remontée vers la gateway.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `--duree` | 10 minutes | entier 1 → 60 |
| `--teams-port` | 9222 (`CLAUDE_TEAMS_DEBUG_PORT`) | celui du volet Teams |
| `--sortie` | dossier courant | doit exister et être inscriptible |
| relecture des onglets | 5 s | fixe |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| `--duree` | Non | entier, 1 ≤ n ≤ 60 |
| `--sortie` | Non | dossier existant, inscriptible |
| chemin retenu | — | ≤ 300 caractères après gabarit (au-delà : tronqué, « … ») |
| lignes du rapport | — | 2 000 chemins distincts au plus (au-delà : compté, « N chemins non détaillés ») |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

| Composant | Rôle |
|-----------|------|
| `RunnerMain` | aiguille `--releve-teams` avant la configuration |
| `teams/TeamsSurveyCommand` (nouveau) | options, découverte, boucle d'entrée, écriture du rapport |
| `teams/NetworkSurvey` (nouveau) | écoute des réponses par cible, filtre de domaine, agrégat sans corps ni requête |
| `teams/SurveyPaths` (nouveau) | motif d'hôte, gabarit de chemin |
| `teams/SurveyReport` (nouveau) | rendu Markdown + JSON |
| `teams/CdpConnection` | **ajout additif** : envoi sur une session de cible et événements avec leur session (méthodes par défaut ; la garde `assertAllowed` reste au point d'émission) |
| `teams/WebSocketCdpConnection` | réalise l'envoi sur session et transmet la session des événements |

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `SurveyPathsTest` — motif `*.sharepoint.com` / `*-my.sharepoint.com` ; gabarit (`19:abc@thread.v2`,
      UUID, hexadécimal, nombres longs, `%`) ; requête et ancre retirées ; troncature.
- [ ] `NetworkSurveyTest` (faux navigateur) — origine par session (onglet, cadre, worker, service
      worker, autre onglet) ; cadre hors domaine jamais écouté ; statiques écartés ; hors domaines
      comptés ; étape de première vue ; **aucune commande hors `Network.enable` / `Target.setAutoAttach`**.
- [ ] `SurveyReportTest` — écarts (`UNKNOWN`), « vu seulement hors de l'onglet Teams », rapport partiel
      « interrompu », rapport vide qui le dit ; **aucun secret** : un faux navigateur qui émet en-têtes
      `Set-Cookie`/`Authorization`, requête `?token=`, tenant `contoso.sharepoint.com` → aucune de ces
      chaînes dans le Markdown ni dans le JSON.
- [ ] `TeamsSurveyCommandTest` — options (`--duree` invalide → 2, sortie absente → 2), ne demande pas
      `--gateway`, arrêt sur « fin ».
- [ ] `WebSocketCdpConnection` — la session d'un événement est transmise ; l'envoi sur session porte
      `sessionId` et passe par la liste blanche.
- [ ] `CdpCommandsTest` existant **inchangé et vert** (liste blanche non étendue).

### Tests d'intégration

- [ ] `RunnerMain` avec `--releve-teams` et un navigateur absent → code 2, aucune tentative de
      connexion à une gateway.

### Isolation utilisateur

- [x] Non applicable côté données (aucune donnée gateway) ; **isolation machine** testée : rien ne sort
      (aucun client HTTP vers autre chose que la boucle locale).

---

## Dépendances

### Subfeatures bloquantes

- F-108 / SF-108-01 (liste close des domaines, auto-attach) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Auth / Principal : non.** **Contexte tenant : non** (aucune donnée gateway). **Plans / limites :
  non.** **Navigation / routing : non** (aucun écran).
- **Sécurité (volet Teams)** : oui — composants impactés : `CdpConnection` (ajout additif, garde au
  point d'émission conservée), `WebSocketCdpConnection`, `MicrosoftDomains` (consulté, non modifié),
  `CdpCommands` (**non modifié**).

---

## Notes et décisions

- **Onglets Microsoft autres que Teams écoutés pendant le relevé** : le cadrage exige de savoir si une
  réponse n'est vue « que depuis un autre onglet ». L'écoute est bornée à la liste close F-108, en
  lecture seule (aucun corps), et **seulement dans ce mode lancé à la main**. Réversible.
- **Le nom du tenant n'est jamais écrit** : le motif suffit (correction du PO — « ces URL sont
  toujours les mêmes pour tous les clients »), et le rapport peut circuler sans révéler le client.
- **Pas de corps, même pour « la forme »** : le cadrage parlait de « formes » ; la consigne de la vague
  est plus stricte (« JAMAIS les corps ») et prévaut. La forme est approchée par le type MIME et le type
  de ressource ; la forme JSON exacte sera vérifiée, après le relevé, par la sonde de santé de F-87.

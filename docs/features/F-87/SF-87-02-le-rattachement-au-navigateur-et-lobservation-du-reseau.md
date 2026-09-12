# Mini-spec — F-87 / SF-87-02 — Le rattachement au navigateur et l'observation du réseau

## Identifiant

`F-87 / SF-87-02`

## Feature parente

`F-87` — Le volet Teams : la liaison

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-87-02-rattachement-navigateur`

---

## Objectif

Se rattacher au **navigateur déjà authentifié du poste** par son port de débogage, **observer les
réponses JSON** que la page Teams reçoit déjà, et **échouer clairement en disant comment lancer
Chrome** quand elle n'est pas joignable.

---

## Comportement attendu

### Cas nominal

1. Le runner résout le port de débogage : `--teams-port`, sinon `CLAUDE_TEAMS_DEBUG_PORT`, sinon
   **9222**. L'hôte est **toujours** `127.0.0.1` — jamais une autre machine.
2. Il interroge `GET http://127.0.0.1:<port>/json/version` puis `/json/list` : il découvre le
   navigateur et **ses onglets**.
3. Il retient l'onglet dont l'adresse est une page Teams. Il ouvre la socket de débogage de **cet
   onglet-là**, et de lui seul.
4. Il active l'observation du réseau, puis **fait défiler la page** — le seul geste demandé au DOM.
5. Chaque réponse observée est classée par l'**adaptateur** (SF-87-01). Les réponses intéressantes
   voient leur **corps** récupéré et rendu en objets à nous ; le bruit de la page est écarté sans
   bruit.
6. Chaque observation dit ce qu'elle vaut : un corps indisponible devient un manque
   `BODY_UNAVAILABLE`, un défilement sans effet un manque `SCROLL_EXHAUSTED`.

### Ce qu'on ne fait pas, et pourquoi

| | |
|---|---|
| Lire le **DOM** comme source de vérité | liste virtualisée, classes générées : un scraper se répare tous les deux mois. Le DOM ne sert **qu'au défilement**. |
| **Appeler** les services Microsoft nous-mêmes | on **observe** ce que le navigateur de l'utilisateur reçoit déjà. C'est ce qui distingue cette approche d'un client API déguisé. |
| **Lancer** Chrome à sa place | un navigateur lancé par nous ne porte pas sa session. On dit **comment** le lancer ; c'est lui qui le fait. |
| Lire les **cookies**, les **jetons**, les **en-têtes** | ils n'entrent jamais dans le produit (voir Sécurité). |

### Cas d'erreur — chacun nomme le remède ET le moyen (leçon F-80)

| Situation | Comportement attendu |
|---|---|
| Rien n'écoute sur le port | échec `browser_not_detected` : message qui **donne la ligne de commande exacte** pour ce système, avec le dossier de profil dédié et l'adresse de Teams |
| Le port répond, mais aucun onglet Teams | échec `teams_not_open` : « ouvrez `https://teams.microsoft.com` dans **cette** fenêtre » |
| Onglet Teams présent mais session non ouverte | échec `not_signed_in` : « connectez-vous à Teams dans cette fenêtre, une fois ; la session y reste » |
| Port de débogage exposé hors `127.0.0.1` | **refus** : on ne se rattache jamais à un navigateur distant |
| Socket perdue en cours d'observation | l'observation s'arrête, ce qui a été lu est rendu **avec** un manque qui dit que la liaison a été perdue |
| Corps de réponse non récupérable (déjà purgé par le navigateur) | manque `BODY_UNAVAILABLE`, la lecture continue |
| Commande de débogage hors liste blanche | **refus immédiat**, avant émission (garde de sécurité) |

---

## Sécurité — **le verrou du volet**

**Les cookies et les jetons Microsoft de la session ne sont JAMAIS rapatriés.** C'est l'avantage
propre à l'approche navigateur, et il se perdrait en une ligne de code inattentive.

Trois gardes, cumulatives :

1. **Liste blanche de commandes.** Le client de débogage n'accepte que :
   `Browser.getVersion`, `Page.enable`, `Network.enable`, `Network.getResponseBody`,
   `Runtime.evaluate`. Toute autre commande est **refusée avant émission** — en particulier
   `Network.getCookies`, `Network.getAllCookies`, `Storage.getCookies`, `Network.setCookie`.
2. **Aucun en-tête n'est lu.** L'observation ne retient que l'**URL** et le **corps** ; l'objet
   d'observation n'a pas de champ d'en-têtes, donc rien à oublier de vider.
3. **Seuls les champs déclarés sont recopiés** (acquis de SF-87-01) : même un corps porteur d'un
   jeton ne le laisse pas ressortir.

---

## Décision D3 — ce qui se télécharge, et ce qui se voit

Le **pilotage du navigateur** est **natif** : le protocole de débogage est du JSON sur une socket,
et la machine virtuelle Java sait déjà faire les deux. **Il n'y a donc rien à télécharger, et rien
d'embarqué non plus** — ce qui est l'intention de D3 (« jamais embarqué, le paquet ne grossit
pas »), atteinte à **zéro octet** plutôt qu'à cent mégaoctets.

Ce qui est livré ici, c'est **l'annonce elle-même** : au premier usage de Teams, le runner **dit ce
dont la liaison a besoin et où chaque chose en est**. `ffmpeg` et le modèle de transcription (F-90,
F-91) s'y déclareront au même endroit, et leur téléchargement s'y verra. Arbitrage A5.

---

## Critères d'acceptation

- [ ] Le port se résout dans l'ordre `--teams-port` → `CLAUDE_TEAMS_DEBUG_PORT` → `9222`, et l'hôte
      est toujours la boucle locale.
- [ ] Quand rien n'écoute, l'échec **contient la ligne de commande complète** du système courant :
      exécutable, `--remote-debugging-port`, `--user-data-dir` **dédié**, et l'adresse de Teams.
- [ ] L'échec explique **pourquoi** un dossier de profil dédié est nécessaire et qu'il faut s'y
      connecter **une fois**.
- [ ] Les trois systèmes (Windows, macOS, Linux) ont chacun leur commande, Chrome **et** Edge.
- [ ] Un port qui répond sans onglet Teams produit un échec **différent**, qui dit quoi faire.
- [ ] Une commande hors liste blanche est refusée **avant** émission ; `Network.getCookies` en fait
      partie.
- [ ] Une observation ne porte **ni en-tête, ni cookie** : vérifié sur un événement de débogage
      délibérément porteur de `Set-Cookie` et d'`Authorization`.
- [ ] Une réponse dont le corps est indisponible produit un manque, jamais un silence.
- [ ] Le défilement est le **seul** appel `Runtime.evaluate` du volet, et il est borné en nombre de
      gestes.
- [ ] L'annonce de premier usage est écrite une **seule** fois par exécution du runner.

---

## Périmètre

### Hors scope (explicite)

- La sonde de santé et l'indicateur de liaison (SF-87-03).
- Les outils donnés à l'agent (F-88).
- `ffmpeg`, la capture, la transcription (F-90, F-91) — seule l'annonce leur ouvre la place.
- Lancer le navigateur à la place de l'utilisateur.

---

## Contraintes de validation

| Champ | Obligatoire | Règle | Normalisation |
|---|---|---|---|
| port de débogage | Oui | entier `1024..65535` ; défaut `9222` | hors bornes → défaut + avertissement |
| hôte | Oui | `127.0.0.1` **uniquement** | non négociable |
| délai de découverte | Oui | 2 000 ms | — |
| délai d'une commande de débogage | Oui | 10 000 ms | — |
| gestes de défilement | Oui | 40 au plus par lecture | au-delà → manque `SCROLL_EXHAUSTED` |
| attente après un défilement | Oui | 600 ms | — |
| taille d'un corps observé | Oui | 4 Mio au plus | au-delà → manque `BODY_UNAVAILABLE` |

---

## Technique

### Fichiers (tous dans le paquet `teams` du runner)

`BrowserPort`, `BrowserLaunchAdvice`, `BrowserLinkException`, `CdpCommands`, `CdpConnection`
(interface), `WebSocketCdpConnection`, `BrowserTargets`, `BrowserLink`, `NetworkObserver`,
`ObservedResponse`, `TeamsFirstUseNotice`.

### Endpoints / tables / migration

Aucun. Le rattachement vit **sur la machine de l'utilisateur** : la gateway n'ouvre aucune socket
vers un navigateur, et n'en connaîtra jamais l'adresse.

---

## Plan de test

### Tests unitaires

- [ ] `BrowserPortTest` — ordre de résolution, bornes, refus d'un hôte distant.
- [ ] `BrowserLaunchAdviceTest` — les trois systèmes, Chrome et Edge, profil dédié, adresse Teams.
- [ ] `CdpCommandsTest` — liste blanche ; `Network.getCookies` et consorts refusés.
- [ ] `NetworkObserverTest` — classement, récupération de corps, corps indisponible, défilement
      épuisé.
- [ ] `AucunCookieNeRemonteTest` — un événement porteur d'en-têtes sensibles ne laisse rien passer.
- [ ] `TeamsFirstUseNoticeTest` — annoncé une fois, dit ce qui est nécessaire et son état.

### Tests d'intégration

- [ ] `BrowserLinkTest` — découverte contre un **faux navigateur** (serveur HTTP du JDK) : onglet
      Teams trouvé ; aucun onglet Teams → échec nommé ; port fermé → échec qui donne la commande.

### Isolation utilisateur

- [x] Non applicable — aucun accès aux données de la gateway. Le runner ne parle qu'à **son** poste
      appairé et au navigateur **de la boucle locale**.

---

## Dépendances

- `SF-87-01` — **done** (mergée).

---

## Préoccupations transversales

Aucune des quatre. Aucun endpoint, aucune route, aucun gate, aucun changement du Principal.
Composants impactés : le paquet `runner/.../teams` seul.

---

## Notes et décisions

**A5 — Le pilotage du navigateur est natif, donc zéro téléchargement.** D3 tranche que le pilotage
n'est **jamais embarqué** et se télécharge au premier usage. Le protocole de débogage étant du JSON
sur une socket, la JVM le parle nativement : l'intention de D3 (ne pas alourdir le paquet) est
atteinte **sans** télécharger cent mégaoctets de navigateur piloté. Ce qui est livré de D3, c'est
l'**annonce** de premier usage, qui dit ce dont la liaison a besoin et où chaque chose en est — et
c'est là que `ffmpeg` et le modèle de transcription viendront se déclarer. Alternative écartée :
embarquer une bibliothèque de pilotage, plus lourde, moins observable, et qui ouvrirait par défaut
des commandes de débogage que nous refusons. Réversible.

**A6 — On se rattache, on ne lance pas.** Lancer Chrome nous-mêmes donnerait un navigateur sans la
session de l'utilisateur — donc inutile — et transformerait un outil de lecture en automate
d'authentification. On **dit comment**, avec la commande exacte : c'est la leçon de F-80, où nommer
le remède sans donner le moyen s'était révélé inutilisable.

**A7 — Le profil dédié est écrit dans le remède, pas découvert par l'utilisateur.** Chrome refuse
d'ouvrir son port de débogage sur le profil par défaut. La commande donnée porte donc un
`--user-data-dir` dédié, et le message explique qu'il faut s'y connecter à Teams **une fois** — la
session y reste ensuite. Sans cette phrase, la commande « marche » et l'utilisateur tombe sur un
Teams déconnecté sans comprendre pourquoi.

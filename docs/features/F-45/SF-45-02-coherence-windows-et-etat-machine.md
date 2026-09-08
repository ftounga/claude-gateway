# Mini-spec — F-45 / SF-45-02 — Cohérence Windows de l'écran, et état de la machine

## Identifiant

`F-45 / SF-45-02`

## Feature parente

`F-45` — Mise en service guidée du runner sur poste d'entreprise

## Statut

`ready`

## Date de création

2026-09-08

## Branche Git

`feat/SF-45-02-coherence-windows-et-etat-machine`

---

## Objectif

> Que le **format choisi** pilote le chemin d'exemple et la commande affichée, et que l'écran dise
> **si la machine s'est connectée** au lieu de laisser l'utilisateur deviner.

---

## Déclencheur

Deux incohérences relevées le 2026-09-07 sur l'écran d'appairage :

1. Sous un bouton **« Télécharger le runner pour Windows »**, le champ « Racine du projet » propose
   `/chemin/vers/le/projet` — un chemin **Unix**. Sur le poste du client, l'utilisateur a recopié la
   forme proposée avant de la corriger : le troisième obstacle de l'installation (SF-38-23, le chemin
   Windows avalé par Git Bash) commence exactement là.
2. Une fois la commande lancée, **rien** à l'écran ne dit si la machine a été appairée. L'information
   existe pourtant : `GET /api/workspaces/{id}/runner/status` (SF-38-02) la sert déjà.

---

## Comportement attendu

### Cas nominal — le chemin suit le format

Le chemin d'exemple (placeholder du champ, et valeur utilisée dans la commande tant que rien n'est
saisi) est celui du **format retenu à l'étape 3** :

| Format retenu | Chemin d'exemple |
|---|---|
| Paquet Windows | `C:\Users\moi\projets\mon-projet` |
| Paquet macOS (Apple Silicon ou Intel) | `/Users/moi/projets/mon-projet` |
| `.jar`, page consultée depuis Windows | `C:\Users\moi\projets\mon-projet` |
| `.jar`, tout autre poste | `/chemin/vers/le/projet` |

La commande de lancement affichée reprend ce chemin, **entre guillemets** (déjà acquis, SF-38-23) et
derrière le lanceur du format (déjà acquis, SF-44-02/03). Changer de format met le chemin d'exemple à
jour **immédiatement**, tant que l'utilisateur n'a rien saisi ; une saisie de l'utilisateur n'est
**jamais** écrasée.

### Cas nominal — l'état de la machine

L'étape « Lancer le runner » porte un indicateur relevé toutes les **5 secondes** tant que le
dialogue est ouvert :

| État | Affichage |
|---|---|
| Aucun runner vu | « En attente de la machine… », avec une activité visible |
| Runner vu | « Machine connectée », avec l'heure du dernier signe de vie |

Le relevé s'arrête dès que la machine est vue, et à la fermeture du dialogue. L'écran dit que le
signal a une tolérance de 90 s côté serveur, plutôt que de laisser croire à une pastille instantanée.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le relevé d'état échoue (réseau, 5xx) | L'indicateur **reste** sur l'état connu et le prochain relevé retente ; aucune erreur rouge — un dialogue d'installation n'a pas à crier sur un aller-retour manqué |
| Le relevé répond 403/404 (projet d'un autre utilisateur, projet supprimé) | Le relevé **s'arrête** : réessayer toutes les 5 s ne changerait rien, et l'indicateur reste sur « en attente » |
| Le dialogue est fermé pendant un relevé en vol | Aucun `setInterval` ne survit : le minuteur est arrêté dans `ngOnDestroy` |
| L'utilisateur a saisi un chemin puis change de format | Sa saisie est conservée telle quelle |
| Le format retenu n'est servi par aucune gateway (repli jar) | Le chemin suit le poste consulté, jamais un chemin Windows sur un Mac |

---

## Critères d'acceptation

- [ ] Format Windows retenu → le chemin d'exemple est un chemin Windows (`C:\…`), dans le champ **et**
      dans la commande.
- [ ] Format macOS retenu → le chemin d'exemple est `/Users/…`.
- [ ] Format `.jar` → le chemin suit le **poste consulté** : Windows en donne un chemin Windows, tout
      autre poste le chemin générique.
- [ ] Changer de format met le chemin d'exemple à jour sans recharger le dialogue.
- [ ] Un chemin saisi par l'utilisateur n'est **jamais** remplacé par un changement de format.
- [ ] L'étape « Lancer le runner » affiche « En attente de la machine… » tant qu'aucun runner n'est vu.
- [ ] Elle affiche « Machine connectée » dès que le relevé le dit, avec l'heure du dernier signe de vie.
- [ ] Le relevé a lieu toutes les 5 s et **s'arrête** dès la première connexion vue.
- [ ] Un échec de relevé ne produit **aucun** message d'erreur et n'interrompt pas les suivants.
- [ ] Un relevé refusé (403) ou introuvable (404) arrête le minuteur.
- [ ] Le minuteur est arrêté à la destruction du composant (aucune fuite).
- [ ] L'écran mentionne la tolérance de 90 s plutôt que de promettre du temps réel.

---

## Périmètre

### Hors scope (explicite)

- **Un canal poussé** (WebSocket, SSE) pour l'état du runner : le backend calcule cet état avec une
  tolérance de 90 s sur le heartbeat ; un canal temps réel transporterait une information qui ne l'est
  pas.
- **Fermer le dialogue automatiquement** quand la machine se connecte : l'utilisateur peut vouloir
  relire l'étape suivante ; on informe, on ne décide pas à sa place.
- **Deviner le chemin réel du projet sur la machine** : le navigateur n'y a pas accès, et l'envoyer au
  backend divulguerait l'arborescence du poste pour rien (parti pris de SF-38-06, conservé).
- **La fiche DSI** : c'est SF-45-03.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `workspacePath` (saisie) | chaîne vide | Le chemin d'exemple n'est **pas** pré-rempli : il est affiché en `placeholder`, pour qu'une saisie vide reste distinguable d'une saisie recopiée |
| `runnerConnected` | `false` | Aucun relevé n'a encore répondu |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| `workspacePath` | Non | 200 (déjà en place) | texte libre | Non | `trim()` avant composition de la commande (déjà en place) |

Le chemin **n'est pas validé** contre une grammaire de chemin : Windows, macOS, Linux, WSL et les
partages réseau n'en partagent aucune, et un refus à tort coûterait plus que la faute qu'il éviterait.
C'est le runner qui tranche, et il le dit clairement depuis SF-38-23.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum | Statut |
|---------|-----|------|-------------|--------|
| GET | `/api/workspaces/{id}/runner/status` | Oui | propriétaire du projet | **Existant** (SF-38-02), consommé tel quel |

**Aucun endpoint créé ni modifié.** L'endpoint consommé est déjà filtré par `user_id` côté service
(`RunnerStatusService.status(userId, workspaceId)`), et cette PR ne touche pas ce chemin.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants Angular

- `RunnerPairingDialogComponent` — `examplePath()` (computed), `runnerStatus` / `runnerConnected` /
  `lastSeenAt` (signals), démarrage et arrêt du relevé.
- `runner-pairing-dialog.component.html` — placeholder et indicateur d'état.
- `runner-pairing-dialog.component.scss` — style de l'indicateur, jetons `--cg-*` uniquement.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun nouveau type d'appel : `getRunnerStatus` passe par le même `HttpClient` et le même intercepteur que tous les autres appels du dialogue |
| **Contexte tenant** | **Oui, en lecture** | Le relevé porte le `workspaceId` reçu par le dialogue. Composants vérifiés : `AtelierService.getRunnerStatus` (inchangé), `RunnerManagementController#status` → `RunnerStatusService.status(userId, workspaceId)` — filtre `user_id` **déjà** en place (SF-38-02), non modifié ici. Aucun nouveau chemin d'accès aux données n'est ouvert. |
| Plans / limites | Non | Aucun quota consommé |
| Navigation / routing | Non | Aucune route |
| **Minuteurs du composant** | **Oui** | Le dialogue portait déjà un `setInterval` (compte à rebours du code). Un second s'ajoute. Composants à vérifier : `startCountdown` / `stopCountdown` existants, et `ngOnDestroy`, qui doit désormais arrêter **les deux**. |

---

## Plan de test

### Tests unitaires

- [ ] Format Windows : `examplePath()` est un chemin `C:\…` et la commande le reprend entre guillemets.
- [ ] Format macOS : `examplePath()` est `/Users/…`.
- [ ] Format `.jar` sur un poste Windows : chemin Windows.
- [ ] Format `.jar` sur un poste « autre » : chemin générique.
- [ ] Changer de format change le chemin d'exemple sans autre effet.
- [ ] Un chemin saisi survit à un changement de format.
- [ ] Un relevé qui répond « déconnecté » laisse l'écran en attente et le minuteur actif.
- [ ] Un relevé qui répond « connecté » bascule l'écran et **arrête** le minuteur.
- [ ] Un relevé en échec (500) ne produit aucune snackbar et laisse le minuteur actif.
- [ ] Un relevé refusé (403) arrête le minuteur.
- [ ] `ngOnDestroy` arrête le relevé **et** le compte à rebours.
- [ ] Le gabarit rend « En attente de la machine » puis « Machine connectée ».

### Tests d'intégration

Sans objet — aucune route ajoutée ni modifiée. La couverture d'intégration de
`GET /api/workspaces/{id}/runner/status`, isolation `user_id` comprise, est celle de SF-38-02 et
reste inchangée.

### Isolation workspace

- [x] **Applicable, et déjà couverte en amont** : le relevé consommé filtre par `user_id`
      (`RunnerStatusService`), avec son test d'isolation existant (SF-38-02). Cette PR n'ouvre aucun
      accès aux données et ne modifie aucun filtre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-02` (registre de connexions + `GET .../runner/status`) — **done**
- `SF-38-23` (guillemets autour du chemin) — **done**, réutilisé tel quel
- `SF-44-02` / `SF-44-03` (formats et lanceurs) — **done**
- `SF-45-01` — **done** (même fichier ; mergée avant celle-ci)

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D1 — Le format pilote le chemin ; le `.jar` retombe sur le poste consulté.** Un paquet Windows ne
s'exécute que sur Windows : le format est alors une information **certaine**, plus forte que
l'`User-Agent`. Le `.jar`, lui, ne dit rien du système — c'est justement son intérêt. Dans ce seul
cas, le poste d'où la page est consultée décide. *Alternative écartée* : toujours suivre
l'`User-Agent`, qui afficherait un chemin Unix sous un bouton « pour Windows » — l'incohérence même
qu'on corrige. **Réversible.**

**D2 — Un `placeholder`, jamais une valeur pré-remplie.** Pré-remplir `C:\Users\moi\…` ferait partir
une commande vers un dossier inexistant chez la moitié des utilisateurs, et le runner refuserait tout
accès en dehors — un échec de plus, après le lancement. Le `placeholder` montre la **forme** sans
prétendre connaître le poste.

**D3 — Relevé toutes les 5 s, arrêté à la première connexion.** L'information a une tolérance de 90 s
côté serveur : interroger plus souvent ne la rendrait pas plus fraîche, seulement plus coûteuse. Et
une fois la machine vue, la question posée par ce dialogue — « l'appairage a-t-il marché ? » — a sa
réponse ; continuer à interroger serait de la surveillance, pas de l'installation. *Alternative
écartée* : un canal poussé, qui transporterait en temps réel une information qui ne l'est pas.

**D4 — Un échec de relevé est silencieux.** Ce dialogue est déjà celui où quelque chose ne marche
pas ; ajouter un rouge sur un aller-retour manqué ferait chercher au mauvais endroit. Un 403 ou un
404, en revanche, ne se répareront pas tout seuls : le minuteur s'arrête plutôt que de battre à vide.

**D5 — La tolérance de 90 s est dite.** Le backend annonce « connecté » jusqu'à 90 s après un
`Ctrl-C`. L'écrire évite le seul reproche qu'on puisse faire à cet indicateur : avoir menti.

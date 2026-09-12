# Mini-spec — F-72 / SF-72-02 — « Connecter un poste », le parcours qui part de la machine

## Identifiant

`F-72 / SF-72-02`

## Feature parente

`F-72` — Connecter un poste, puis y ajouter des projets

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-72-02-connecter-un-poste`

---

## Objectif

Offrir sur l'accueil de la Forge un **premier geste qui part de la machine** : nommer le client,
vérifier le réseau, appairer, lancer le runner — **sans qu'aucun projet soit créé**, et c'est
normal.

---

## Comportement attendu

### Cas nominal

1. L'accueil de la Forge (`/forge`) porte, dans son en-tête, un bouton d'action
   **« Connecter un poste »**.
2. Il ouvre le parcours de mise en service **en mode poste** : le même dialogue qu'aujourd'hui, avec
   les mêmes étapes (réseau → poste → code → binaire → lancement), mais **sans projet**.
3. **Étape « Le poste »** : un seul champ, **« Nom du client ou de la machine »**, et un bouton
   « Créer le poste ». Ni liste de postes existants, ni explorateur de dossiers, ni bouton
   « Rattacher ce projet » — on connecte une machine, on ne range pas un projet.
4. Le poste créé, le parcours enchaîne sur le **code d'appairage**, puis le binaire, puis la commande
   — inchangés.
5. La commande de lancement désigne la **racine du poste** (`--root`), exactement comme aujourd'hui :
   c'est le runner qui déclare cette racine à la gateway.
6. Quand la machine se signale, la **conclusion** remplace le parcours et dit la suite :
   *« Aucun projet n'existe encore, et c'est normal : ajoutez-en depuis la carte de ce poste, autant
   que vous voulez, sans jamais réappairer. »*
7. À la fermeture, l'accueil de la Forge **relit sa vue** : la carte du nouveau poste est là.

### Ce qui ne change pas

Le mode **projet** du même dialogue — celui qu'on ouvre depuis l'en-tête d'un terminal pour un
projet existant — reste **identique** : liste des postes, explorateur de dossiers (SF-71-03),
bouton « Rattacher ce projet ». F-72 ajoute un mode, il n'en retire aucun.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Nom vide ou blanc | « Créer le poste » inerte ; aucun appel ne part |
| Nom refusé par la gateway (400 `invalid_host_name`) | Le message du serveur, à côté du champ ; l'étape reste ouverte, le nom saisi est conservé |
| 403 (accès Atelier) | « La Forge est nécessaire pour connecter une machine. » ; le parcours reste ouvert |
| Réseau muet à la création | « Le poste n'a pas pu être créé. Veuillez réessayer. » ; rien n'est perdu |
| Code d'appairage : 404 (poste disparu) | Message existant du dialogue, inchangé |
| Le poste est créé mais l'utilisateur ferme avant d'appairer | **Le poste existe** et apparaît sur l'accueil, non connecté. Il se supprime (F-69) ou se reprend plus tard — aucun projet fantôme n'a été créé |

---

## Critères d'acceptation

- [ ] L'accueil de la Forge porte un bouton **« Connecter un poste »** dans son en-tête, et il est
      l'action principale de l'écran.
- [ ] En mode poste, le dialogue **ne demande qu'un nom** : aucun sélecteur de poste, aucun
      explorateur de dossiers, aucun bouton « Rattacher ce projet » dans le DOM.
- [ ] Le titre du dialogue est **« Connecter un poste »** ; en mode projet il reste
      « Connecter une machine ».
- [ ] Créer le poste enchaîne **automatiquement** sur l'étape du code d'appairage.
- [ ] L'état de la machine est relevé sur le **poste** (`GET /runner-hosts/{id}/status`), pas sur un
      projet — il n'y en a pas.
- [ ] La conclusion dit **qu'aucun projet n'existe encore et que c'est normal**, et renvoie vers
      « Ajouter un projet » sur la carte.
- [ ] À la fermeture du dialogue, la vue des postes est **relue**.
- [ ] Le mode **projet** du dialogue est inchangé : ses tests existants passent sans modification.
- [ ] Aucune couleur nouvelle : le bouton est `mat-flat-button color="primary"` (orange d'action,
      §2), le dialogue garde ses styles.

---

## Périmètre

### Hors scope (explicite)

- **Ajouter un projet** sous le poste : c'est SF-72-03.
- Retirer le bouton « Nouveau projet » de la racine : c'est SF-72-04.
- Renommer / couper / révoquer depuis la carte : restent où ils sont.
- Deviner le nom du client : le nom est **libre**, c'est une décision de F-48.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| Nom du poste | oui | 100 (`RunnerHostRequest`) | libre | `trim` côté écran ; la gateway fait foi |

---

## Technique

### Composants Angular

| Composant | Changement |
|---|---|
| `runner-pairing-dialog.component.ts/.html` | `workspaceId` devient **optionnel** dans `RunnerPairingDialogData` ; un mode `host` en découle : étape « poste » réduite au nom, création du poste, relevé d'état par poste, conclusion adaptée |
| `postes.component.ts/.html/.scss` | bouton « Connecter un poste » dans l'en-tête + dans l'encart « Aucun poste connecté » ; relecture à la fermeture |
| `core/services/atelier.service.ts` | `getHostRunnerStatus(hostId)` → `GET /runner-hosts/{hostId}/status` |

### Endpoints consommés

| Méthode | URL | Origine |
|---------|-----|---------|
| POST | `/api/runner-hosts` | F-48 / SF-48-01, existant |
| POST | `/api/runner-hosts/{id}/pairing-code` | existant |
| GET | `/api/runner-hosts/{id}/status` | existant, **pas encore appelé par l'écran** |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — frontend seul.

### Design system

Aucune couleur nouvelle, aucun registre nouveau. Le bouton d'en-tête est l'action de marque
(`color="primary"`, orange `#E07B39`, §2) ; la carte, ses pastilles et ses filets sont inchangés.

---

## Plan de test

### Unitaires (`runner-pairing-dialog.component.spec.ts`)

- [ ] Mode poste : le DOM ne contient **ni** sélecteur de poste, **ni** explorateur, **ni** bouton
      « Rattacher ce projet ».
- [ ] Mode poste : le titre est « Connecter un poste ».
- [ ] Nom vide → le bouton de création est inerte, aucun appel.
- [ ] Nom saisi → `createRunnerHost` appelé une fois, puis l'étape « code » s'ouvre.
- [ ] Échec de création (500) → message, nom conservé, étape toujours ouverte.
- [ ] Mode poste : l'état est relevé par `getHostRunnerStatus`, jamais par `getRunnerStatus`.
- [ ] Machine vue → conclusion affichée, et elle dit qu'aucun projet n'existe encore.
- [ ] Mode **projet** : les comportements existants (liste des postes, explorateur, rattachement)
      sont inchangés.

### Unitaires (`postes.component.spec.ts`)

- [ ] L'en-tête porte « Connecter un poste ».
- [ ] Le clic ouvre le dialogue **en mode poste** (aucun `workspaceId` passé).
- [ ] À la fermeture, la vue est relue.
- [ ] L'encart « Aucun poste connecté » porte le même geste.

### Isolation `user_id`

- [x] Applicable — garantie **côté gateway** : la création d'un poste part du JWT, et l'état lu
  passe par `requireOwned`. L'écran n'envoie aucun identifiant d'utilisateur.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | aucun identifiant construit côté écran ; l'`hostId` employé est celui que la gateway vient de rendre |
| Plans / limites | non | aucun quota touché. Connecter un poste crée une ligne `runner_hosts` — le **siège facturé** (F-65) — exactement comme la création de poste d'aujourd'hui (`POST /runner-hosts`, inchangé) : le chemin de facturation ne bouge pas, seul l'endroit d'où on appuie change |
| Navigation / routing | **oui** | aucune route ajoutée ni modifiée. Chemins vérifiés : `/forge` (accueil, inchangé), `/postes` (redirection F-68, inchangée), `/atelier` et `/atelier/:id` (inchangés). Le dialogue ne navigue pas : il se ferme et l'écran relit |

---

## Notes et décisions

- **Un mode de plus, pas un second dialogue** : le parcours de mise en service porte quatre années
  de cas réels — proxy d'entreprise, `407`, paquet autonome, chemin avalé par Git Bash, commande de
  reprise. Le dupliquer pour changer une étape ferait diverger les deux au premier correctif.
- **Le poste créé survit à l'abandon** : c'est voulu. Une ligne de poste non appairée est visible,
  nommée, et se supprime (F-69). C'est très exactement l'inverse du défaut d'avant, où l'abandon
  laissait un **projet** orphelin que personne ne reliait plus à rien.

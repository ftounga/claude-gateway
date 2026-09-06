# Mini-spec — F-38 / SF-38-16 — Créer un projet « sur ma machine »

## Identifiant

`F-38 / SF-38-16`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée` — PR #239, mergée le 2026-09-06

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-16-ecran-projet-local`

---

## Objectif

> Donner à l'écran de création de projet une **troisième option** — « sur ma machine » — et
> accompagner l'utilisateur jusqu'au premier message : nom, code d'appairage, commande à copier,
> état de la connexion.

---

## Comportement attendu

### Cas nominal

L'écran de création propose désormais trois choix, présentés par ce qu'ils font :

| Option | Sous-titre |
|---|---|
| **Sur ma machine** | « Le projet existe déjà sur votre poste ou votre serveur » |
| Depuis GitHub | « Cloner un dépôt » *(inchangé)* |
| Importer une archive | « Un `.zip` de votre projet » *(inchangé)* |

Choisir « sur ma machine » ouvre un parcours en **trois temps**, dans le même dialogue :

1. **Nommer** — un champ, rien d'autre. Aucun chemin n'est demandé : le dossier se désigne au
   lancement du runner, pas ici.
2. **Connecter** — la commande complète s'affiche, code d'appairage compris, avec un bouton
   *Copier* et un lien de téléchargement du jar :

   ```
   java -jar claude-runner.jar \
     --gateway https://portal.ng-itconsulting.com/api \
     --workspace /chemin/de/votre/projet \
     --code AB2C3D4E
   ```

   `--workspace` est laissé en clair, à remplacer par l'utilisateur : c'est **lui** qui sait où vit
   son projet, et c'est le seul endroit du parcours où ce chemin apparaît.
3. **Attendre** — l'écran indique « en attente de connexion… » et bascule tout seul dès que le runner
   s'annonce, sans rafraîchissement manuel (sondage déjà en place, SF-38-06).

Une fois connecté, l'en-tête du projet affiche **« runner-claude — sur poste-dev »**, et le terminal
s'ouvre directement : il n'y a pas d'autre mode sur un projet local.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Nom vide | Bouton désactivé, `mat-error` explicite |
| Le runner ne se connecte pas | Le dialogue reste ouvert et rappelle les trois causes fréquentes : jar non lancé, mauvais code, réseau sortant bloqué — puis propose de régénérer un code |
| Code d'appairage expiré | Message clair et bouton « générer un nouveau code » |
| L'utilisateur ferme le dialogue avant connexion | Le projet existe, l'écran l'affiche « en attente de machine » avec le moyen de reprendre l'appairage |
| Accès non Gold | Option visible mais désactivée, avec le badge Gold — comme le mode Terminal aujourd'hui |

---

## Critères d'acceptation

- [ ] L'écran de création propose trois sources, et « sur ma machine » est la première.
- [ ] Le parcours ne demande **jamais** de chemin à envoyer au serveur.
- [ ] La commande affichée contient le code réel et se copie en un clic.
- [ ] L'écran bascule automatiquement à la connexion du runner, sans action de l'utilisateur.
- [ ] Un projet local sans machine connectée affiche son état et le moyen de reprendre l'appairage.
- [ ] L'en-tête du projet nomme la machine (`label`) et le dossier (`rootName`) quand ils existent.
- [ ] Le sélecteur de mode n'apparaît pas sur un projet local : il n'y a que le terminal.
- [ ] Aucune régression sur la création `ARCHIVE` et `GIT`.
- [ ] **Design system** : couleurs et polices de `docs/DESIGN_SYSTEM.md`, espacements multiples de
      4 px, `MatDialog` pour le parcours, `MatSnackBar` pour les retours, aucun `window.confirm`.

---

## Périmètre

### Hors scope

- SF-38-15 (backend) — préalable.
- SF-38-17 (explorateur en mode runner).
- L'installation du runner en tant que service ou son démarrage automatique.

---

## Technique

### Endpoints consommés

| Méthode | URL | Origine |
|---------|-----|---------|
| POST | `/api/workspaces/local` | SF-38-15 |
| POST | `/api/runner/pairing-code` | existant (SF-38-01) |
| GET | `/api/runner/status` | existant (SF-38-02/06) |
| GET | `/api/runner/download` | existant (SF-38-03) |

### Composants Angular

- `LocalWorkspaceDialogComponent` — le parcours en trois temps (nouveau).
- `atelier.component` — troisième option de création, en-tête « projet — sur machine », masquage du
  sélecteur de mode sur un projet local.
- `atelier.service` — appel de création `LOCAL`.

### Migration Liquibase

- [x] Non applicable.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun identifiant d'utilisateur ne transite par le client |
| Plans / limites | **Oui** | Le gating Gold s'applique à la nouvelle option comme aux deux autres. Composants : garde d'accès de l'écran Atelier, désactivation de l'option, badge Gold. |
| **Navigation / routing** | **Oui** | Chemins à revérifier : création → projet, explorateur → terminal (SF-30-10), retour depuis l'appairage, ouverture directe par URL `?mode=terminal`. Chacun testé sur un projet local. |

---

## Plan de test

### Tests unitaires (frontend)

- [ ] Le dialogue n'envoie que le nom ; aucun chemin dans la requête.
- [ ] Le bouton de création est désactivé tant que le nom est vide.
- [ ] La commande affichée contient le code renvoyé par le serveur.
- [ ] Le sondage de statut bascule l'écran à la connexion, sans action.
- [ ] Un code expiré affiche le message et le bouton de régénération.
- [ ] Le sélecteur de mode est absent sur un projet `LOCAL`.
- [ ] L'en-tête affiche `label` et `rootName` quand ils sont fournis, dégrade proprement sinon.

### Tests d'intégration

- [ ] Couverts par les tests d'API de SF-38-15.

### Isolation workspace

- [x] Applicable côté backend (SF-38-15) ; l'écran ne porte aucune décision d'accès.

---

## Dépendances

- `SF-38-15` — à livrer d'abord.
- **Vague F-39, lot 4 (écran unique)** — elle refond l'écran de l'Atelier. Cette subfeature se
  développe **après**, sur l'écran refondu, sinon elle serait à réécrire immédiatement.

---

## Notes et décisions

**D1 — Le chemin n'apparaît qu'une fois, dans la commande à copier.** C'est l'utilisateur qui le
connaît et le saisit dans son terminal. Rien ne remonte au serveur : la gateway n'apprend que le
**nom** du dossier, et seulement parce que le runner le lui dit.

**D2 — Trois temps dans un seul dialogue, pas trois écrans.** Nommer, connecter, attendre : le
parcours doit tenir d'un seul tenant, parce qu'il est interrompu par une action hors du navigateur
(lancer une commande). Fragmenter en plusieurs écrans ferait perdre le fil à ce moment précis.

**D3 — Pas de sélecteur de mode sur un projet local.** Il n'y a rien à choisir : le bac à sable n'a
pas ce dossier. Afficher un choix impossible serait un piège.

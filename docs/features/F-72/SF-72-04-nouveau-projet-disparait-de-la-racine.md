# Mini-spec — F-72 / SF-72-04 — « Nouveau projet » disparaît de la racine

## Identifiant

`F-72 / SF-72-04`

## Feature parente

`F-72` — Connecter un poste, puis y ajouter des projets

## Statut

`done` — mergée le 2026-09-12 (PR #399)

## Date de création

2026-09-12

## Branche Git

`feat/SF-72-04-plus-de-nouveau-projet-a-la-racine`

---

## Objectif

Retirer le bouton **« Nouveau projet »** — la porte qui faisait partir du projet au lieu du poste —
et donner aux deux sources sans machine, **dépôt GitHub** et **archive `.zip`**, leur place sur la
carte du poste **« Hébergé »**.

---

## Comportement attendu

### Cas nominal

1. Dans la barre latérale des projets (`/atelier`), le bouton **« Nouveau projet »** et son menu à
   trois entrées **disparaissent**. À leur place, un lien **« Connecter un poste »** qui mène à
   l'accueil de la Forge (`/forge`).
2. La phrase de la liste vide change et **dit le nouvel ordre** : *« Aucun projet. Connectez un
   poste, puis ajoutez-lui des projets — ou ouvrez un dépôt GitHub depuis le poste Hébergé. »*
3. Sur l'accueil de la Forge, la carte **« Hébergé »** porte deux gestes :
   **« Ouvrir un dépôt GitHub »** et **« Importer une archive .zip »**. Ce sont les deux sources qui
   n'ont **pas de machine** — elles vivent chez la gateway, donc sous ce poste-là.
4. La carte « Hébergé » est désormais **toujours affichée** sur l'accueil, même vide : elle porte
   des gestes, et une carte de gestes qui disparaît quand elle est vide met ses gestes hors de
   portée (arbitrage A2).
5. Ces deux gestes se comportent **exactement** comme avant (dialogue GitHub, sélecteur de fichier,
   nom demandé pour l'archive, messages d'erreur inchangés) ; seul leur **emplacement** change.
6. Après création, l'accueil relit sa vue et le projet apparaît sous « Hébergé » ; l'utilisateur
   l'ouvre d'un clic sur « Terminal ».

### Ce qui reste

- La **liste** des projets dans la barre latérale, avec son menu « Supprimer le projet » (F-69) :
  c'est la seule vue qui montre **tous** les projets, y compris les essais de l'ancien parcours.
- Le dialogue de mise en service ouvert depuis l'en-tête d'un terminal (mode projet) : il sert un
  projet **existant**, et reste inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Archive > taille maximale | Le contrôle client existant, message inchangé |
| Archive refusée par la gateway | Message existant, inchangé |
| Dépôt GitHub : jeton manquant / refusé / dépôt introuvable | Messages existants, inchangés |
| 403 (accès Atelier) | Message existant |
| Réseau muet | Message existant ; rien n'est créé |

---

## Critères d'acceptation

- [ ] Le bouton « Nouveau projet » et son menu **n'existent plus** dans le DOM de `/atelier`.
- [ ] Un lien « Connecter un poste » mène à `/forge` depuis la barre latérale.
- [ ] La phrase de la liste vide décrit le **nouvel ordre** (poste, puis projets).
- [ ] La carte « Hébergé » porte « Ouvrir un dépôt GitHub » et « Importer une archive .zip ».
- [ ] La carte « Hébergé » est affichée **même quand elle ne porte aucun projet**.
- [ ] Ouvrir un dépôt GitHub depuis cette carte crée le projet et relit la vue.
- [ ] Importer une archive depuis cette carte demande le nom (comportement existant), crée le
      projet et relit la vue.
- [ ] Les messages d'erreur des deux gestes sont **identiques** à ceux d'avant.
- [ ] Aucune couleur nouvelle : la carte « Hébergé » garde le gris neutre du §5 et **aucun** ton
      d'identité (§9).
- [ ] Aucun chemin de création n'est perdu : machine → Forge ; GitHub et archive → carte
      « Hébergé ».

---

## Périmètre

### Hors scope (explicite)

- Supprimer `POST /workspaces`, `POST /workspaces/local` et `POST /workspaces/git` côté gateway :
  ces endpoints restent, et `local` sert toujours au mode projet du dialogue. On retire une
  **porte d'écran**, pas une capacité.
- Reprendre automatiquement les projets créés par l'ancien parcours (D10) : ce sont des essais,
  ils se suppriment (F-69).
- Refondre la barre latérale : elle garde sa liste, ses pastilles et son menu.

---

## Contraintes de validation

Aucun champ nouveau. Les contraintes des deux gestes déplacés sont inchangées (nom de projet
255 caractères, taille d'archive, forme de l'URL de dépôt).

---

## Technique

### Composants Angular

| Composant | Changement |
|---|---|
| `atelier.component.html` | retrait du bouton « Nouveau projet » et de son menu ; lien « Connecter un poste » ; phrase de liste vide |
| `atelier.component.ts` | `openGitRepoDialog` / `onZipPicked` / `openLocalProjectDialog` conservés tant qu'ils servent ailleurs ; le code devenu mort est retiré |
| `postes.component.ts/.html/.scss` | gestes « dépôt GitHub » et « archive » sur la carte « Hébergé » ; carte « Hébergé » rendue même vide |

### Endpoints consommés

Aucun nouveau. `POST /api/workspaces` (multipart) et `POST /api/workspaces/git`, tels quels.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — frontend seul.

### Design system

La carte « Hébergé » garde `--cg-divider` (filet) et `--cg-text-secondary` (pastille, texte) — le
gris « archivé / inactif » du §5. Ses deux boutons sont des `mat-stroked-button`, comme les autres
actions de carte. **Aucun ton d'identité (§9)** : ce n'est pas une machine.

---

## Plan de test

### Unitaires (`atelier.component.spec.ts`)

- [ ] Aucun élément portant « Nouveau projet » dans le DOM.
- [ ] Un lien vers `/forge` intitulé « Connecter un poste » est présent.
- [ ] La phrase de liste vide mentionne le poste avant le projet.

### Unitaires (`postes.component.spec.ts`)

- [ ] La carte « Hébergé » est rendue même quand la gateway ne renvoie aucune entrée virtuelle.
- [ ] Elle porte les deux gestes, et **aucun** geste de machine (appairage, suppression, mission).
- [ ] « Ouvrir un dépôt GitHub » ouvre le dialogue existant ; un dépôt choisi appelle
      `createGitWorkspace` puis relit la vue.
- [ ] « Importer une archive » demande le nom puis appelle `createWorkspace`, puis relit la vue.
- [ ] Une archive trop volumineuse est refusée **avant** l'appel.
- [ ] Les messages d'erreur GitHub (`git_token_missing`, `invalid_git_token`,
      `invalid_git_repository`) sont repris à l'identique.

### Isolation `user_id`

- [x] Applicable — garantie **côté gateway** : les deux créations partent du JWT et n'acceptent
  aucun identifiant d'utilisateur. Rien ne change de ce côté : seuls les boutons se déplacent.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | aucun identifiant construit côté écran |
| Plans / limites | non | aucun quota touché : ni siège de poste (F-65) — aucun poste créé —, ni terminal vivant (F-70) — aucun terminal ouvert. Le contrôle de taille d'archive est déplacé, pas modifié |
| Navigation / routing | **oui** | une **navigation nouvelle** : `/atelier` → `/forge` par le lien de la barre latérale. Chemins vérifiés : `/forge` (accueil, existant), `/postes` → `/forge` (redirection F-68, intacte), `/atelier` et `/atelier/:id` (inchangés), fil d'Ariane `app-forge-breadcrumb` (inchangé), ancre `#poste-<id>` (inchangée). Aucun guard touché |

---

## Notes et décisions

- **A2 (cadrage) — la carte « Hébergé » ne disparaît plus quand elle est vide.** SF-71-01 la
  masquait parce qu'elle ne portait **que** des projets ; elle porte maintenant les deux seules
  portes d'entrée sans machine. La gateway, elle, n'est pas touchée : c'est l'écran qui complète.
  Réversible — le jour où ces gestes vivent ailleurs, la carte peut redevenir conditionnelle.
- **Pourquoi retirer la porte plutôt que la renommer** : elle ne pose pas la mauvaise question, elle
  la pose dans le **mauvais ordre**. La renommer laisserait le parcours partir du projet, et le
  défaut du PO reviendrait sous un autre nom.
- **A4 (arbitrage pris au dev) — le guide d'accueil mène à `/forge`.** L'étape « projet » du guide
  (F-53) déclenchait `openLocalProjectDialog()`, c'est-à-dire **le parcours qui part du projet** :
  le laisser aurait gardé le défaut vivant dans l'onboarding, à l'endroit exact où un nouvel
  utilisateur le rencontre pour la première fois. Une ligne : le guide conduit là où les deux
  gestes sont. Réversible.
- **A5 (arbitrage pris au dev) — `gitErrorMessage` devient une fonction partagée**
  (`atelier/git/git-error.util.ts`). Le geste change d'écran ; deux copies de ces messages
  divergeraient au premier code d'erreur ajouté côté gateway. Aucun message n'est modifié.
- **Tests retirés avec leur raison écrite** : les quatorze tests qui couvraient la création depuis
  `/atelier` (archive, dépôt, « sur ma machine ») ont suivi les gestes vers
  `postes.component.spec.ts`. Là où un test se servait de l'ancien parcours seulement pour
  *atteindre un état* (un projet Git ouvert), il passe désormais par `selectWorkspace` — ce qui est
  d'ailleurs ce que l'écran fait réellement.

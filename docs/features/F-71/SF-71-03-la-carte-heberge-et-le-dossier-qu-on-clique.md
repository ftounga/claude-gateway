# Mini-spec — F-71 / SF-71-03 — La carte « Hébergé » et le dossier qu'on clique

## Identifiant

`F-71 / SF-71-03`

## Feature parente

`F-71` — Le poste « Hébergé », et le dossier qu'on désigne sans le taper

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-71-03-carte-heberge-et-explorateur`

---

## Objectif

Montrer le poste « Hébergé » sur l'accueil de la Forge, et remplacer le champ libre « Dossier du
projet » du dialogue d'appairage par un **explorateur où l'on clique**.

---

## Comportement attendu

### Cas nominal — la carte « Hébergé » (`/postes`)

1. La vue d'ensemble rend une entrée `virtual = true` (SF-71-01) ; l'écran l'affiche **en dernier**,
   après les machines.
2. La carte porte : une pastille neutre `cloud`, le titre **« Hébergé »**, la phrase
   *« Ces projets vivent chez la gateway, pas sur une de vos machines. »*, puis ses projets — même
   ligne de projet que partout, avec le bouton « Terminal ».
3. Ce qu'elle **ne porte pas** (D2) : aucun état de mission, aucune pastille « Connecté / Vu il y a »,
   aucun menu « Supprimer le poste », aucune mention de racine / système / interpréteur.
4. Elle n'apparaît **pas** quand elle est vide (D3) — la gateway ne la rend pas.
5. Ses terminaux vivants comptent dans « Terminaux vivants : n / 4 » de l'en-tête, qui devient donc
   exact : jusqu'ici, un terminal ouvert sur un projet hébergé n'était compté nulle part.

### Cas nominal — l'explorateur de dossiers (dialogue d'appairage)

1. Dans l'étape « Rattacher ce projet », l'utilisateur choisit un poste.
2. Si le poste est **connecté**, l'écran appelle `GET /runner-hosts/{id}/folders` et affiche :
   - un **fil** du chemin courant, cliquable pour remonter ;
   - la **racine elle-même** comme premier choix (« la racine du poste » — un poste peut n'héberger
     qu'un projet) ;
   - un bouton par sous-dossier ; un dossier **déjà ouvert** est marqué « déjà ouvert » et n'est
     pas sélectionnable ;
   - une flèche « entrer » par dossier, pour descendre d'un niveau ;
   - quand la liste est tronquée, la phrase qui le dit.
3. Le dossier choisi est **écrit en clair** à côté du bouton « Rattacher ce projet », qui n'est actif
   qu'après un choix.
4. **Aucun champ de saisie de chemin** ne subsiste.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste **non connecté** (`connected = false`, ou 409 au retour) | Un encart explicite : « Le runner de ce poste n'est pas connecté. Lancez-le sur la machine, puis rafraîchissez : les dossiers de la racine apparaîtront ici. » + bouton « Réessayer ». **Aucun champ vide offert** (D7) |
| Poste « Nouveau poste… » pas encore créé | Le même encart, formulé pour le cas : le dossier se choisit **après** l'appairage — il faut un runner pour lister |
| 403 (accès Atelier) | Message existant du dialogue, inchangé |
| 404 (poste disparu) | « Poste introuvable. » + relecture de la liste des postes |
| Réseau muet | « Les dossiers n'ont pas pu être lus. » + « Réessayer » ; le choix précédent est conservé |
| Racine sans sous-dossier | « Aucun sous-dossier ici. » — la racine reste choisissable |

---

## Critères d'acceptation

- [ ] La carte « Hébergé » apparaît **en dernier** et seulement si la gateway la rend.
- [ ] Elle ne porte **ni** mission, **ni** état de connexion, **ni** suppression, **ni** appairage.
- [ ] Elle n'emprunte **aucune** des dix couleurs d'identité (§9) : filet et pastille neutres.
- [ ] Les projets hébergés ouvrent leur terminal d'un clic, comme ceux d'une machine.
- [ ] Le compteur « Terminaux vivants » inclut les projets hébergés.
- [ ] Le dialogue d'appairage n'offre **plus aucun champ texte** pour le chemin.
- [ ] Poste connecté → les sous-dossiers de la racine sont listés et cliquables ; on peut descendre
      puis remonter.
- [ ] Poste non connecté → l'encart explicatif, et **rien à remplir**.
- [ ] Un dossier déjà ouvert est marqué et non sélectionnable.
- [ ] Liste tronquée → la phrase qui le dit est affichée.
- [ ] Le rattachement envoie exactement le chemin choisi (`""` pour la racine).

---

## Périmètre

### Hors scope (explicite)

- Créer un dossier depuis l'écran (D8).
- Refondre le parcours de création (« Connecter un poste » puis « Ajouter un projet ») — c'est
  **F-72**, qui s'appuiera sur cet explorateur.
- Afficher sur la carte d'un poste les dossiers **non encore ouverts** — F-72 également.
- Renommer, couper ou révoquer depuis la carte « Hébergé » : elle ne porte aucun geste de machine.

---

## Technique

### Composants Angular

| Composant | Changement |
|---|---|
| `postes.component.ts/.html/.scss` | rendu de l'entrée `virtual` : gabarit de carte partagé, gestes de machine masqués ; `track` par `host.id ?? 'hosted'` |
| `runner-pairing-dialog.component.ts/.html/.scss` | champ texte remplacé par l'explorateur ; état `folders`, `folderPath`, `foldersError` |
| `core/services/atelier.service.ts` | `runnerHostFolders(hostId, path?)` |
| `core/models/atelier.models.ts` | `virtual` sur `RunnerHostOverview` ; `HostFolder`, `HostFoldersResponse` |

### Design system

- Aucune couleur nouvelle. La carte « Hébergé » emploie `--cg-divider` (filet) et
  `--cg-text-secondary` (pastille et texte) — le gris « archivé / inactif » du §5. Le §9 réserve ses
  dix tons à l'**identification d'une machine** : le poste virtuel n'en est pas une, il n'en prend
  aucun.
- `app-live-badge` (§11) et les pastilles `badge--*` (§5) sont réutilisés tels quels, sans surcharge
  de couleur.

---

## Plan de test

### Unitaires (`postes.component.spec.ts`)

- [ ] Une entrée `virtual` est rendue en dernier, avec le titre « Hébergé ».
- [ ] Aucun bouton de suppression, aucun menu de mission, aucune pastille de connexion sur elle.
- [ ] `liveTerminalCount()` inclut les terminaux de l'entrée virtuelle.
- [ ] `deleteHost` / `setMission` ne sont **pas** appelables depuis la carte virtuelle.

### Unitaires (`runner-pairing-dialog.component.spec.ts`)

- [ ] Poste connecté → l'explorateur appelle `runnerHostFolders` et rend un bouton par dossier.
- [ ] Clic sur un dossier → chemin retenu ; « Rattacher » devient actif.
- [ ] Entrer dans un dossier → second appel avec `path` ; remonter → retour au parent.
- [ ] Poste non connecté → l'encart, **aucun** `input` de chemin dans le DOM.
- [ ] 409 → l'encart « pas connecté » ; réseau muet → « Réessayer ».
- [ ] Dossier `used` → non sélectionnable.
- [ ] Rattachement → `attachWorkspaceToHost(id, hostId, chemin)` avec le chemin exact.

### Isolation `user_id`

- [x] Applicable — garantie **côté gateway** : la vue d'ensemble ne prend aucun identifiant, et
  `folders` vérifie l'appartenance du poste. L'écran n'envoie que l'`id` d'un poste déjà listé pour
  lui.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | aucun identifiant construit côté écran |
| Plans / limites | **oui** | compteur « Terminaux vivants : n / 4 » de `postes.component.ts` : la source (`liveTerminals` par poste) est inchangée, le total inclut désormais l'entrée virtuelle. Le plafond, lui, reste **appliqué par la gateway** (F-70 / SF-70-01) — l'écran n'en est que le miroir. |
| Navigation / routing | non | aucune route ajoutée ; la carte virtuelle ne porte pas d'ancre `#poste-<id>` (elle n'a pas d'`id`) |

---

## Notes et décisions

- La carte « Hébergé » réutilise le **gabarit** de carte des postes plutôt qu'un composant à part :
  un second gabarit divergerait au premier changement, et les projets doivent se lire exactement
  pareil, où qu'ils vivent.
- **A6 (arbitrage pris au dev)** — *poste pas encore connecté : la racine, et on le dit.* Un poste
  qu'on vient de créer n'a **pas encore de runner** : personne ne peut lister ses dossiers, et
  exiger un choix rendrait la première mise en service **impossible**. Le projet prend alors la
  **racine** que l'utilisateur passera au runner (`--workspace`) — le seul dossier connaissable à
  cet instant — et l'écran l'écrit noir sur blanc. C'est bien D7 : on **dit** qu'on ne peut pas
  lister, au lieu d'offrir un champ vide. Réversible : F-72 inverse l'ordre (connecter le poste,
  *puis* ajouter des projets) et ce cas disparaîtra de lui-même.
- L'explorateur est **dans le dialogue existant** et non dans un dialogue de plus : le geste est le
  même — dire où vit ce projet.

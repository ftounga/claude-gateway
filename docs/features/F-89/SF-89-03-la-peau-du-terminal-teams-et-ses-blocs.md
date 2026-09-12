# Mini-spec — F-89 / SF-89-03 — La peau du terminal Teams, son entrée, et ses blocs à l'écran

## Identifiant

`F-89 / SF-89-03`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams

## Statut

`done` — mergée le 2026-09-13 (PR #469)

## Date de création

2026-09-12

## Branche Git

`feat/SF-89-03-la-peau-et-les-blocs`

---

## Objectif

Rendre le terminal Teams **visible et ouvrable** — son entrée sur la carte du poste, sa peau, son
en-tête — et **afficher les trois blocs** de SF-89-02, avec la densité d'un compte rendu : « qu'est-ce
qu'on attend de moi » saute aux yeux en trois secondes, sans faire défiler.

---

## Contexte

Le PO a tranché deux choses : **enrichir le fil** (livré en SF-89-02, côté modèle) et faire du
passage à Teams **un vrai basculement visuel**. Le second est ici.

**Toujours pas de boutons** (§5.2 du cadrage) : le basculement est **visuel, pas fonctionnel**. On
ouvre un terminal Teams comme on ouvre un terminal de poste, puis **on parle**. Ce qui change, c'est
qu'on **voit** qu'on a changé d'outil.

---

## Comportement attendu

### Cas nominal — l'entrée

1. Sur `/forge`, la carte d'un poste **réel** porte, à côté d'« Ajouter un projet » et de
   « Terminal du poste », un bouton **« Terminal Teams »**.
2. Il n'apparaît **que si le compte a le droit Teams** (`GET /api/teams/access`), lu **une fois** au
   chargement de l'écran. Sans le droit, il n'y a pas de bouton — pas un bouton grisé, pas un bouton
   qui mène à un refus : *le terminal existe, ou il n'existe pas*.
3. Un clic appelle `POST /api/runner-hosts/{id}/teams-terminal` et **navigue vers `/atelier/{id}`**
   au succès seulement.
4. La carte montre la **pastille de vie** (`app-live-badge`) quand un onglet y vit, et le compteur
   « Terminaux vivants » l'inclut — les deux viennent de SF-89-01, rien à écrire ici.

### Cas nominal — la peau

Le terminal ouvert **se nomme** et **se voit** :

| Ce qui change | Ce qui ne change pas |
|---|---|
| l'en-tête dit « Terminal Teams » et porte l'indicateur de liaison (F-87, déjà là) | la barre, l'invite, les réglages, les gestes |
| le flux est en **prose** (`--cg-font-sans`) et non en monospace | la **surface** : `--cg-primary`, celle de tout terminal |
| les blocs riches s'affichent | tout le reste du fil |
| l'icône de la liste latérale et l'absence de « supprimer le projet » | — |

**Le basculement passe par la typographie et le contenu, pas par une couleur nouvelle.** C'est la
décision qui tient la contrainte du PO — *la charte sans rien y ajouter, aucun registre de couleur
nouveau, quatre cohabitent déjà* — et elle a un motif propre : **un compte rendu est de la prose,
pas une sortie de shell.** Le monospace d'un terminal de projet dit « ceci est ce que la machine a
répondu » ; il dirait faux ici.

### Cas nominal — les trois blocs

**La carte de réunion.** Un titre, une seconde ligne (date, participants), puis les sections **dans
l'ordre où l'agent les a écrites** — la première étant, par consigne d'outil, ce qu'on attend du
lecteur. Chaque ligne porte **son auteur, son heure, et un lien** : cliquer ouvre le fil Teams à la
bonne position, dans un nouvel onglet.

**Le bloc moment.** Une image **à côté** de la phrase, jamais une galerie en bas de page. L'heure
ouvre la transcription à la seconde ; l'image **s'agrandit d'un clic**. Un moment **sans image**
reste un moment : la phrase et l'heure suffisent.

**Le bloc liste.** Une suite de lignes sourcées, chacune avec son niveau de certitude **écrit**.

**Ce qui est incertain se lit comme incertain**, et **par la typographie** : une ligne « à confirmer »
est en *italique*, avec la mention « à confirmer » écrite en toutes lettres. **Aucun pictogramme
d'avertissement, aucune couleur d'alerte** : un triangle jaune sur une ligne de compte rendu dit
« attention danger », alors que la ligne dit seulement « je l'ai déduit ».

**Ce que le bloc dit toujours, en pied** : la **fenêtre réellement lue**, et **ce qui n'a pas pu
l'être**. En texte secondaire, mais **jamais replié** : un manque qu'il faut déplier est un manque
qu'on ne voit pas.

### Cas nominal — l'agrandissement d'une image

**Le geste de la mosaïque, qu'on n'invente pas deux fois** (charte §13, F-83 / SF-83-03) : un clic
agrandit, un second rend l'image à sa place, **Échap** ferme. Le bouton porte un **libellé accessible
qui dit l'état** (« Agrandir l'image de 14:32 » / « Réduire… »), comme celui de la mosaïque.

L'agrandissement est un **état d'écran**, jamais une adresse : le porter dans l'URL rouvrirait la
page — et donc le fil — au moindre retour arrière.

### La règle non négociable, à l'écran aussi

Un bloc riche **ne s'affiche que dans un terminal Teams**. Le composant reçoit la marque du workspace
et, sans elle, **rend le bloc en texte** — il ne le masque pas : masquer ferait disparaître une
information sans le dire. **Un test le verrouille**, et c'est le troisième verrou de la même règle,
après les deux de SF-89-02.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `GET /teams/access` échoue | **aucun bouton** — fail-closed. Un bouton qui mène à un 403 n'est pas une porte, c'est un piège |
| `POST /teams-terminal` échoue | message court en `snackBar`, **aucune navigation**, et l'écran se relit |
| Poste « Hébergé » | le bouton n'existe pas : ce n'est pas une machine |
| Une image de moment ne charge pas | la phrase et l'heure restent, l'emplacement de l'image dit « image indisponible » — le moment n'est pas perdu avec son image |
| Un bloc arrive sans ligne (backend antérieur, JSON partiel) | le bloc est **ignoré** plutôt que rendu vide |

---

## Critères d'acceptation

- [ ] La carte d'un poste réel porte « Terminal Teams » **si et seulement si** le compte a le droit ;
      la carte « Hébergé » ne le porte jamais.
- [ ] Un clic ouvre `/atelier/{id}` ; un échec n'entraîne **aucune** navigation.
- [ ] Sur un terminal Teams, la vue porte la classe de peau et le flux est en **prose** ; sur un
      terminal de projet, **rien ne change** — un test compare les deux.
- [ ] Une carte de réunion affiche ses sections **dans l'ordre reçu**, la première en tête.
- [ ] Chaque ligne affiche **auteur, heure et lien** ; le lien ouvre un nouvel onglet
      (`rel="noopener"`).
- [ ] Une ligne « à confirmer » porte la mention **écrite** et l'italique ; **aucun** pictogramme
      d'avertissement, **aucune** couleur hors charte — un test vérifie l'absence des deux.
- [ ] **Aucun chiffre de certitude** n'est affiché nulle part.
- [ ] La fenêtre lue et les manques sont **toujours visibles**, jamais repliés.
- [ ] Une image s'agrandit d'un clic, se réduit d'un second, se ferme par **Échap**.
- [ ] Un moment sans image affiche quand même sa phrase et son heure.
- [ ] **Sur un terminal de projet, un bloc portant une carte est rendu en TEXTE** — jamais en carte.
- [ ] `ng build` passe, budgets de style compris.

---

## Périmètre

### Hors scope (explicite)

- Toute **action** dans un bloc (répondre, cocher un engagement, marquer comme fait) : on lit.
- L'**écran de facturation** de l'option Teams (montant à confirmer par le PO).
- La **production** des images (F-90) et la capture (F-91).
- Un écran Teams à boutons — décision fondatrice du cadrage.

---

## Contraintes de validation

| Élément | Règle |
|---|---|
| Couleurs | **aucune couleur nouvelle** : `--cg-*` existants uniquement, et aucun cinquième registre |
| Polices | Inter (prose) et JetBrains Mono (heures, identifiants) — les deux déjà à la charte |
| Espacements | multiples de 4 px, par les jetons `--cg-space-*` |
| Liens externes | `target="_blank"` **et** `rel="noopener noreferrer"` |
| Certitude | `EXPLICITE` / `A_CONFIRMER` rendus par leur **libellé français**, jamais par la constante |

---

## Technique

### Composants impactés

| Composant | Changement |
|---|---|
| `core/models/atelier.models.ts` | `teamsTerminal` sur le workspace, `teamsTerminalId`/`teamsTerminalLive` sur la vue d'ensemble, les types du bloc riche, `card?` sur `AtelierTerminalBlock` |
| `core/services/atelier.service.ts` | `openTeamsTerminal(hostId)`, `teamsAccess()`, événement SSE `card` |
| `postes/postes.component` | le bouton « Terminal Teams », gardé par le droit |
| `atelier/terminal/atelier-terminal.component` | entrée `teamsTerminal`, peau, rendu des trois blocs, agrandissement |
| `atelier/terminal/teams-block.ts` (nouveau) | fonctions pures : libellés, tri, heure lisible — testables seules |
| `atelier/terminal/atelier-terminal-teams.component.scss` (nouveau) | la peau, à part (budget de style par composant : 12 ko) |
| `atelier/atelier.component` | relais de `card` dans le flux vivant, passage de la marque au terminal |
| `docs/DESIGN_SYSTEM.md` | **§15 — Le compte rendu dans le fil** |

### Endpoints consommés

`POST /api/runner-hosts/{id}/teams-terminal`, `GET /api/teams/access`,
`GET /api/workspaces/{id}/teams/moments/{imageId}` — tous livrés par SF-89-01 et SF-89-02.

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [ ] `teams-block.ts` — libellé de certitude, heure lisible, bloc sans ligne ignoré.
- [ ] `atelier-terminal.component` — la carte s'affiche sur un terminal Teams ; **le même bloc est
      rendu en TEXTE sur un terminal de projet**.
- [ ] Une ligne « à confirmer » : mention écrite, italique, **aucun** `mat-icon` d'avertissement.
- [ ] Aucun pourcentage ni chiffre de certitude dans le rendu.
- [ ] La fenêtre lue et les manques sont dans le DOM sans interaction.
- [ ] L'agrandissement : un clic, un second, `Échap`.
- [ ] Un moment sans image garde sa phrase et son heure.
- [ ] La peau : classe présente sur un terminal Teams, absente sinon.
- [ ] `postes.component` — bouton présent avec le droit, absent sans, absent sur « Hébergé » ;
      erreur ⇒ pas de navigation.
- [ ] `atelier.service` — `card` relayé ; `openTeamsTerminal` tape la bonne URL.

### Isolation workspace

- [x] Non applicable côté écran : aucune donnée n'est lue autrement que par les endpoints gardés.

---

## Dépendances

### Subfeatures bloquantes

- **SF-89-01** et **SF-89-02** — livrées.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non (écran) | — |
| Plans / limites | **Oui** — un droit décide de l'existence d'un bouton | `postes.component` (lecture unique de `GET /teams/access`, fail-closed), `atelier.service` |
| **Navigation / routing** | **Oui** — un nouveau chemin vers `/atelier/{id}` | `postes.component.openTeamsTerminal` (navigation **au succès seulement**) ; `/atelier/:id` **inchangé** : le terminal Teams s'y ouvre comme tout terminal, aucune route nouvelle, aucun garde modifié |
| **Design system** | **Oui** | `DESIGN_SYSTEM.md` §15 ajoutée ; aucune couleur nouvelle ; un test vérifie l'absence de pictogramme d'alerte |

---

## Notes et décisions

- **D-89-12 — le basculement visuel passe par la typographie, pas par la couleur.** La charte
  compte déjà quatre registres de couleur ; un cinquième rendrait les quatre autres illisibles
  (§14). La prose contre le monospace dit la même chose, et le dit plus juste : un compte rendu
  n'est pas une sortie de shell.
- **D-89-13 — la surface reste celle d'un terminal.** On doit *reconnaître* un terminal (charte
  §13), pas découvrir un écran. Ce qui bascule est le **contenu** et la **façon de le lire**.
- **D-89-14 — l'incertitude se lit, elle ne s'annonce pas.** Italique + mention écrite. Un
  pictogramme d'avertissement dirait « danger » là où la ligne dit « je l'ai déduit ».
- **D-89-15 — sans le droit, pas de bouton.** Ni grisé, ni menant à un refus. Et fail-closed : si la
  lecture du droit échoue, le bouton n'apparaît pas.
- **D-89-16 — hors d'un terminal Teams, un bloc riche se rend en TEXTE, pas en rien.** Masquer
  ferait disparaître une information sans le dire ; la règle interdit la **carte**, pas le contenu.

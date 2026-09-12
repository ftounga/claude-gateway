# Mini-spec — F-82 / SF-82-05 — La connexion se fait par poste, pas par projet

## Identifiant

`F-82 / SF-82-05`

## Feature parente

`F-82` — Arrêter proprement, et savoir quand on n'y arrive pas

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-82-05-connexion-par-poste`

---

## Objectif

Retirer du terminal d'un projet le geste **« connecter une machine »** — remplacé par un **état** qui
dit chez qui le projet vit, dans quel état, et quoi faire — et faire partager au coupe-circuit du
terminal **la confirmation de SF-82-02**, celle qui dit tout ce que le geste fait.

---

## La décision, et d'où elle vient

Le PO, le 2026-09-12 : *« on est d'accord que la connexion se fait par poste uniquement, pas par
projet ? »*. Vérification faite (cadrage §5 quater, D7), **les deux gestes existaient aux deux
endroits** :

| Geste | Terminal d'un projet | Carte du poste |
|---|---|---|
| Connecter | `atelier.component.ts:1765` | oui (`/forge`, F-72) |
| Couper la liaison | `atelier-terminal.component.html:229` | oui (SF-82-02) |

SF-82-04 a supprimé l'**ambiguïté** (les deux modes du dialogue demandent désormais la racine du
**poste**), pas le **chemin**. D7 tranche le chemin, et **différemment pour chaque geste, parce
qu'ils ne servent pas le même moment**.

---

## Comportement attendu

### Partie 1 — « Connecter » quitte le terminal d'un projet

Le cas réel que ce bouton couvrait — *« je suis dans mon projet, le poste est éteint, je veux le
rallumer »* — n'est pas une **connexion**, c'est une **reprise**, et SF-82-04 vient de la mettre en
premier. Une machine **jamais appairée** se connecte depuis `/forge` : un clic de plus, **une fois
par machine**, contre un parcours qui cesse de laisser croire qu'on appaire un dossier.

**Points d'entrée retirés** (les trois, cherchés avant de conclure) :

1. l'en-tête du terminal — bouton « Connecter une machine » (`atelier-terminal.component.html:208`) ;
2. la proposition de runner F-39 / SF-39-09 — bouton « Connecter une machine » du bandeau
   (`…:253`) ;
3. le guide d'accueil F-53 — étape « poste » et bouton « Vérifier mon poste »
   (`atelier.component.ts:guideConnectHost()`).

Les deux derniers **mènent désormais à `/forge`**, exactement comme l'étape « projet » du guide le
fait déjà depuis F-72 / SF-72-04 (« le guide n'a rien à recopier : il conduit là où les gestes
sont »). Aucun n'ouvre plus `RunnerPairingDialogComponent` depuis le terminal.

**À la place, dans l'en-tête : un ÉTAT, pas un bouton d'action.** Il tient trois choses — **qui**
(le poste), **dans quel état**, et **quoi faire** :

| Situation | Ce que l'état dit | Geste offert |
|---|---|---|
| Poste connu, runner **connecté** | « Ce projet vit sur **EDENRED** — poste connecté. » | lien **Voir le poste** (`/forge#poste-<id>`) |
| Poste connu, **appairé** mais éteint (`paired === true`, `connected === false`) | « … — poste non connecté. Son jeton est resté sur son disque : aucun code n'est nécessaire, il suffit de relancer le runner. » + la **commande de reprise** (`java -jar claude-runner.jar`, copiable) | lien **Voir le poste** |
| Poste connu, **non appairé** (`paired` absent ou `false`) | « … — poste non connecté, et sa liaison est à refaire. » | lien **Voir le poste** (la remise en service vit sur sa carte) |
| Poste connu, état **pas encore relevé** (`runnerStatus === null`) | « … — état inconnu. » | lien **Voir le poste** |
| **Aucun poste rattaché** (projet hébergé F-71 compris) | « Ce projet n'est rattaché à aucun poste : il n'a ni machine, ni reprise. Les postes se connectent depuis la Forge. » | lien **Ouvrir la Forge** (`/forge`, **sans** ancre) |

**Poste virtuel « Hébergé » (F-71)** : il n'a pas de machine, donc **ni commande de reprise, ni lien
vers une carte de poste qui n'existe pas** — son `hostId` est `null`, l'ancre `#poste-<id>` n'est
jamais construite, et le texte le dit. Rien ne lui est retiré : il n'avait rien.

La commande de reprise est **le lanceur nu**, sans argument — le même arbitrage qu'à l'encart de
SF-82-04 : la passerelle et la racine sont mémorisées à côté du jeton (F-46 / SF-46-01), et préfixer
un `cd` vers un chemin qu'on ne connaît pas d'ici fabriquerait une commande faussement prête. La
racine déclarée est **nommée** quand la gateway la connaît (`rootName`), pour dire d'où la lancer.

### Partie 2 — « Couper la liaison » reste, avec une seule formulation

Le bouton du coupe-circuit de l'en-tête (`atelier-terminal.component.html:229`) **ne bouge pas** :
c'est le geste d'urgence, et quand ça se passe mal l'utilisateur est **dans le terminal en train de
le regarder** (SF-38-08 — « le bouton qu'on cherche quand ça se passe mal »). L'obliger à naviguer
ajouterait une étape au pire moment.

Mais sa confirmation promettait **moins** que ce que le geste fait : ni les projets ramenés en cible
`SANDBOX`, ni le processus qui **continue de tourner** sur la machine, ni comment l'arrêter
vraiment. Les deux endroits **partagent** donc désormais `KillHostDialogComponent` (SF-82-02) — la
confirmation qui **nomme les projets un par un** et marque ceux déjà au bac à sable.

**Factorisé, pas recopié** : le dialogue quitte `postes/` pour `shared/kill-host-dialog/`, puisqu'il
sert maintenant deux écrans. Deux copies divergeraient à la première retouche.

Le terminal ne connaît pas les projets du poste : il les relève avec
`GET /api/runner-hosts/overview` (lecture existante, isolée par le JWT) **avant** d'ouvrir la
confirmation. Le relevé échoue → la liste est **inconnue**, et le dialogue le **dit** au lieu de
laisser croire que le poste ne porte aucun projet.

Le message de succès est lui aussi partagé (`killHostSuccessMessage`) : il dit ce que la gateway a
**réellement** fait, et que le runner continue de tourner.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Relevé des projets du poste en échec (réseau, 5xx) | La confirmation s'ouvre quand même, projets **« liste indisponible »** — jamais « aucun projet » | — |
| Poste absent du relevé (vue en retard) | Idem : liste inconnue, la confirmation s'ouvre | — |
| Aucun poste rattaché au projet | Le coupe-circuit ne fait rien (garde existante, inchangée) | — |
| Poste d'un autre utilisateur | Refusé par la gateway, isolation `user_id` — **inchangé** | 403 / 404 |
| Poste inexistant au moment de couper | « Ce poste n'existe plus. » — inchangé | 404 |
| Presse-papiers indisponible (copie de la commande de reprise) | Message doux, rien ne casse | — |
| Un coupe-circuit déjà en vol | Le second clic ne fait rien (verrou `killingRunner`) | — |

---

## Critères d'acceptation

- [ ] Aucun point d'entrée du terminal n'ouvre plus `RunnerPairingDialogComponent` : ni l'en-tête,
      ni le bandeau F-39, ni le guide F-53.
- [ ] L'en-tête du terminal, en cible « ma machine », affiche un **état** : le **nom du poste**, son
      **état**, et le geste qui a du sens.
- [ ] Un poste **appairé mais éteint** affiche la **commande de reprise**, copiable.
- [ ] Un poste **connecté** n'affiche **aucune** commande de reprise.
- [ ] Un projet **sans poste** (hébergé F-71 compris) le dit, **sans** lien vers une carte de poste
      et **sans** commande de reprise.
- [ ] Le lien « Voir le poste » pointe `/forge` avec l'ancre `#poste-<hostId>`, et **seulement**
      quand `hostId` est connu.
- [ ] Le bouton « Couper la liaison » de l'en-tête du terminal **existe toujours**, au même endroit.
- [ ] Il ouvre **le même** `KillHostDialogComponent` que la carte du poste — un seul composant, une
      seule formulation.
- [ ] Cette confirmation, ouverte depuis le terminal, **nomme** les projets du poste un par un et
      marque ceux **déjà** au bac à sable.
- [ ] Quand la liste des projets n'a pas pu être relevée, le dialogue dit **« liste indisponible »**
      et non « aucun projet ».
- [ ] Annuler ne déclenche **aucun** appel ; confirmer appelle `killHost(hostId)` et lui seul.
- [ ] Le comportement du coupe-circuit côté gateway est **inchangé** : aucun fichier backend ni
      runner touché (`RunnerKillSwitchService`, endpoint `/kill`).
- [ ] La carte du poste dans `/forge` garde **tous** ses gestes, inchangés.
- [ ] Aucun registre de couleur nouveau : uniquement les jetons `--cg-*` de `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Changer ce que **fait** le coupe-circuit (endpoint, `RunnerKillSwitchService`) — hors périmètre
  F-82 lui-même.
- Toucher au parcours `/forge`, livré et déployé (SF-82-02 y compris).
- **Supprimer** le mode « projet » de `RunnerPairingDialogComponent` : voir « Notes et décisions ».
- Arrêter le processus du runner depuis l'application (hors périmètre F-82, §7 du cadrage).

---

## Technique

### Endpoints

Aucun nouvel endpoint. Deux lectures **existantes** sont appelées depuis un écran de plus :

| Méthode | URL | Usage nouveau |
|---|---|---|
| GET | `/api/runner-hosts/overview` | relever les projets du poste avant la confirmation du coupe-circuit |
| POST | `/api/runner-hosts/{hostId}/kill` | inchangé |

### Tables impactées

Aucune. Aucune migration.

### Composants Angular

- `shared/kill-host-dialog/` — **déplacé** depuis `postes/` (mêmes fichiers, import mis à jour dans
  `postes.component.ts`), plus l'état **« liste de projets inconnue »**.
- `shared/kill-host-dialog/kill-host-messages.ts` — message de succès **partagé** par les deux
  écrans.
- `atelier/terminal/atelier-terminal.component` — le bouton « Connecter une machine » cède la place
  à l'état du poste ; le bandeau F-39 mène à `/forge`.
- `atelier/atelier.component` — `openRunnerPairing()` retiré, `guideConnectHost()` mène à `/forge`,
  `killRunner()` relève les projets et ouvre la confirmation partagée.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `atelier-terminal` — l'en-tête ne porte **plus** de bouton « Connecter une machine ».
- [ ] `atelier-terminal` — poste connecté : nom du poste + « connecté », **aucune** commande de
      reprise.
- [ ] `atelier-terminal` — poste appairé et éteint : la **commande de reprise** est affichée.
- [ ] `atelier-terminal` — poste non appairé : pas de commande de reprise, le lien vers la carte
      reste.
- [ ] `atelier-terminal` — **aucun poste** : le texte le dit, aucun lien `#poste-`, aucune commande.
- [ ] `atelier-terminal` — le lien « Voir le poste » porte `href="/forge#poste-h1"`.
- [ ] `atelier-terminal` — le bouton du coupe-circuit est **toujours** là et émet `killRunner`.
- [ ] `atelier-terminal` — le bandeau F-39 mène à `/forge` et n'émet plus `pairRunner`.
- [ ] `atelier` — `killRunner()` relève l'aperçu des postes et ouvre `KillHostDialogComponent`
      avec les projets **du bon poste**.
- [ ] `atelier` — relevé en échec → dialogue ouvert avec `projects: null` (« liste indisponible »).
- [ ] `atelier` — annuler n'appelle pas `killHost`.
- [ ] `atelier` — confirmer appelle `killHost(hostId)`.
- [ ] `atelier` — `guideConnectHost()` navigue vers `/forge` et n'ouvre aucun dialogue.
- [ ] `kill-host-dialog` — `projects: null` rend « liste indisponible », jamais « aucun projet ».
- [ ] `postes` — la carte du poste ouvre toujours la même confirmation (non-régression SF-82-02).

### Tests d'intégration

Sans objet : aucun code backend n'est touché.

### Isolation utilisateur

- [ ] Non applicable côté nouveau code : `/api/runner-hosts/overview` et `/kill` partent du JWT et
      ne portent aucun identifiant d'utilisateur — l'écran ne peut pas viser la machine d'autrui.
      Comportement **inchangé**.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| **Navigation / routing** | **Oui** | Trois chemins du terminal mènent désormais à `/forge` : bandeau F-39 (`atelier-terminal.component.html`), guide F-53 (`atelier.component.ts:guideConnectHost`), lien « Voir le poste » de l'état (ancre `#poste-<id>`, la **même** que celle du fil d'Ariane de F-68, déjà honorée par `postes.component.ts:honourAnchor`). Aucune route ajoutée, aucun garde modifié, aucune redirection nouvelle. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-82-02` — statut : `done` (la confirmation partagée vient de là)
- `SF-82-04` — statut : `done` (le champ `paired` vient de là)

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D7-a — le mode « projet » du dialogue d'appairage devient du code mort.** Cherché avant de
  conclure : après ce retrait, le **seul** appelant de `RunnerPairingDialogComponent` est
  `postes.component.ts:connectHost()`, qui passe `{}` — le mode **poste**. Plus personne ne passe
  `workspaceId`. Le mode projet (rattachement d'un projet à un poste depuis le dialogue) n'est donc
  plus atteignable. **Il n'est pas supprimé ici** : c'est un retrait de plusieurs centaines de
  lignes dans le fichier le plus chargé de la Forge, sans rapport avec ce que D7 tranche, et le
  faire dans la même subfeature mêlerait deux revues. Il est **signalé dans la PR** — le code mort
  est un piège pour le prochain lecteur.
- **D7-b — la commande de reprise est le lanceur nu.** Même arbitrage qu'à SF-82-04 (D5-b) : depuis
  le terminal, on ne connaît pas le chemin d'installation du runner sur la machine ; un `cd`
  préfixé porterait un chemin d'exemple, c'est-à-dire une commande faussement prête. On dit d'où la
  lancer, avec la racine déclarée quand la gateway la connaît.
- **D7-c — le relevé des projets précède la confirmation.** Le terminal ne connaît que son projet.
  Ouvrir la confirmation partagée avec ce seul projet la rendrait **fausse** (elle promet de nommer
  **tous** les projets du poste). D'où un appel à l'aperçu existant, et un troisième état honnête —
  liste **inconnue** — quand il n'aboutit pas.

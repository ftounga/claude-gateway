# Mini-spec — F-165 / SF-165-01 — Le mécanisme des commandes slash (socle) + `/aide`

## Identifiant

`F-165 / SF-165-01`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-01-mecanisme-commandes-slash`

---

## Objectif

> En une phrase : poser dans le composer du terminal le **mécanisme** des commandes slash « à notre
> sauce » — interception du `/`, autocomplétion, dispatch **client-side sans aucun tour modèle**, et
> **cadre de rendu de panneau** réutilisable — avec une première commande `/aide`.

---

## Comportement attendu

### Cas nominal

1. Dans le composer (`atelier-terminal.component`), taper `/` en début de saisie **ouvre une
   autocomplétion** listant les commandes slash **de F-165** (nom + description), fusionnée avec les
   macros de prompt existantes (F-121). Filtrage au fur et à mesure de la frappe.
2. Navigation **clavier** (flèches ↑/↓, Entrée/Tab pour valider, Échap pour fermer) et **tactile**
   (clic/tap sur une entrée), cibles ≥ 44 px.
3. **Valider une commande slash F-165** :
   - si elle **ne prend pas d'argument** (`/aide`) → **dispatch immédiat** ;
   - si elle **prend un argument** (prévu SF-165-06, `/rappel <terme>`) → complète le brouillon en
     `/<nom> ` pour laisser saisir l'argument, puis Entrée dispatche.
4. Le dispatch d'une commande F-165 **n'émet JAMAIS `send`** (aucun message « /xxx » envoyé au modèle,
   aucun tour) : il **efface le brouillon** localement et **ajoute un panneau** rendu dans le fil du
   terminal, dans un **cadre réutilisable** (en-tête : icône + titre + jeton `/nom` + badge de famille
   + bouton fermer ; corps projeté ; jetons `--cg-*`).
5. `/aide` affiche un panneau listant **toutes les commandes slash F-165 disponibles** (nom, famille,
   description).
6. Un panneau est **fermable** individuellement (bouton fermer).

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| Saisie ordinaire (sans `/`) | Aucun menu ; envoi normal inchangé (aucune régression) |
| Macro de prompt F-121 connue (`/revue`) | Comportement **inchangé** : expansion en prompt imposé puis `send` (un tour) |
| Commande slash inconnue (`/inconnue`) | Non interceptée par F-165 ; suit le chemin existant (littéral → `send`) |
| `/aide` avec des arguments superflus (`/aide xyz`) | Dispatch `/aide` (arguments ignorés), aucun tour |
| Terminal en lecture seule (mosaïque, F-83) | Pas de composer → aucune commande possible |
| Plafond de terminaux vivants atteint (`liveLimitReached`) | Un message normal reste bloqué ; une **vue** locale gratuite reste possible (elle ne consomme rien) |

---

## Critères d'acceptation

- [ ] Taper `/` ouvre un menu contenant au moins `/aide` (commande F-165) en plus des macros F-121.
- [ ] Le menu se filtre au préfixe (`/ai` propose `/aide`).
- [ ] Le menu est navigable au clavier (↑/↓ surlignent, Entrée/Tab valident, Échap ferme) et au tap.
- [ ] Valider `/aide` (menu ou saisie complète + Entrée) **n'émet pas `send`** et **ajoute un panneau**
      dans le fil.
- [ ] Le panneau `/aide` liste toutes les commandes slash F-165 (au moins `/aide` à ce stade).
- [ ] Le panneau se ferme via son bouton fermer.
- [ ] Le brouillon est **vidé** après dispatch d'une commande F-165.
- [ ] **Garantie « aucun tour »** : un test prouve que dispatcher `/aide` n'émet jamais `send`.
- [ ] **Non-régression** : `/revue` expanse toujours son prompt imposé puis émet `send` (F-121).
- [ ] **Non-régression** : un texte sans `/` part inchangé et émet `send`.
- [ ] **Non-régression** : l'outil `demander` (SF-164), la porte d'autorisation, l'autocomplétion
      `@`-mentions, la dictée et l'envoi précisant (steer) restent intacts.
- [ ] **Design** : panneau et menu n'utilisent que des jetons `--cg-*` ; cibles ≥ 44 px ; aucun
      débordement horizontal à 390 px (SF-158) ; aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] **API de registre extensible** documentée : une nouvelle commande (SF 02→06) se branche en
      ajoutant une entrée de registre (nom, description, famille, prend-un-argument, `panelKind`),
      une branche de dispatch, et un `@case` de corps de panneau.

---

## Périmètre

### Hors scope (explicite)

- Les commandes concrètes `/cout`, `/contexte`, `/quota`, `/budget`, `/poste`, `/sujet`, `/compacter`,
  `/nouveau`, `/rappel` (SF-165-02 → 06).
- Tout **endpoint backend** ou agrégation de coût (SF-165-02+). SF-165-01 est **100 % frontend**.
- Toute persistance des panneaux (ils sont **locaux et éphémères** — voir Notes).
- Refonte du composer (hors périmètre F-165).

---

## Technique

### Endpoint(s)

Aucun. SF-165-01 est purement frontend (mécanisme + cadre + `/aide`).

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers Angular

| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (nouveau) | **Registre F-165** (pur TS, sans Angular) : type `SlashPanelCommand` (nom, titre, description, `kind` vue/action/meta, `takesArgument`, `argHint`, `icon`, `panelKind`), catalogue `SLASH_PANEL_COMMANDS` (= `/aide`), `findPanelCommand`, `panelCommandSuggestions`, `parsePanelCommand`, modèle `SlashPanel`, `buildPanel`, `buildHelpEntries`. **Point d'extension des SF 02→06.** |
| `atelier/terminal/atelier-slash-panel.component.ts` (+ html/scss, nouveau) | **Cadre de panneau réutilisable** (standalone) : en-tête (icône, titre, jeton `/nom`, badge famille, bouton fermer ≥ 44 px) + corps projeté (`<ng-content>`). Jetons `--cg-*`. |
| `atelier/terminal/atelier-slash-help.component.ts` (+ html/scss, nouveau) | **Corps `/aide`** (standalone) : la liste des commandes. Modèle d'extension : chaque commande a son composant de corps, sélectionné par `panelKind`. |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Fusion du menu (macros F-121 + commandes F-165), `acceptSlash` sur entrée unifiée, **interception dans `submit()` avant `send`**, `dispatchPanelCommand`, signal `slashPanels`, `dismissPanel`. |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | Menu unifié ; rendu des panneaux dans le fil (cadre + `@switch panelKind`). |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires (registre, `slash-panel-commands.spec.ts`)

- [ ] `panelCommandSuggestions('/')` contient `/aide`.
- [ ] `panelCommandSuggestions('/ai')` contient `/aide` ; `panelCommandSuggestions('lance')` = `[]`.
- [ ] `parsePanelCommand('/aide')` → `{ command: aide, arg: '' }`.
- [ ] `parsePanelCommand('/revue')` → `null` (macro, pas une commande F-165).
- [ ] `parsePanelCommand('texte libre')` → `null`.
- [ ] `buildHelpEntries()` liste `/aide`.
- [ ] `buildPanel(aide, '', id)` → panneau `panelKind: 'help'` avec entrées d'aide.

### Tests composant cadre (`atelier-slash-panel.component.spec.ts`)

- [ ] Rend le titre, le jeton `/nom` et le badge de famille.
- [ ] Le bouton fermer émet `dismiss`.

### Tests composant corps `/aide` (`atelier-slash-help.component.spec.ts`)

- [ ] Rend une ligne par entrée fournie.

### Tests composant terminal (`atelier-terminal.component.spec.ts`, ajouts)

- [ ] Taper `/` montre un item `/aide` dans `.slash-menu`.
- [ ] `submit()` avec `/aide` : `send` **non émis**, un `app-atelier-slash-panel` apparaît, brouillon vidé.
- [ ] Fermer le panneau le retire du fil.
- [ ] **Non-régression** : `/revue` → prompt expansé + `send` (test existant conservé, vérifié vert).

### Isolation workspace

- [x] Non applicable — SF-165-01 n'accède à **aucune donnée** (mécanisme + `/aide` statique). Les SF
  suivantes qui liront des données appliqueront l'isolation `user_id` + `workspace_id`.

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés |
|--------------|--------|---------------------|
| Auth / Principal | Aucun | — |
| Contexte tenant | Aucun (aucune donnée lue) | — |
| Plans / limites | Aucun nouveau gate ; on ne relâche pas `liveLimitReached` pour un message normal | `submit()` (interception avant le gate ne concerne QUE les commandes F-165 locales) |
| Navigation / routing | Aucune route ajoutée/modifiée | — |

---

## Dépendances

### Subfeatures bloquantes

- Aucune. SF-165-01 est le prérequis des SF 02→06.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Deux familles de slash coexistent** : les **macros de prompt** F-121 (`/revue`… → expansées et
  **envoyées au modèle**, un tour) restent inchangées ; F-165 ajoute des **commandes vue/action**
  (`/aide`, puis `/cout`… → dispatchées **localement, aucun tour**). Le menu les fusionne ; le
  comportement se distingue **à la validation** (`parsePanelCommand` dans `submit()` intercepte les
  commandes F-165 **avant** tout `send`).
- **Garantie structurelle « aucun tour »** : `dispatchPanelCommand` n'appelle jamais `send.emit()` et
  ne fait que muter un signal local `slashPanels`. *Vérifier son coût ne doit rien coûter.*
- **Panneaux = affichage LOCAL et éphémère** : ils vivent dans un signal du composant terminal, hors de
  `displayedMessages` (qui vient du parent/backend). Ils ne rejoignent donc **jamais** l'historique
  envoyé au modèle, ni le préfixe système stable, ni le cache de prompt (F-134). Ils ne survivent pas à
  un rechargement — acceptable pour des vues consultables à la demande, et voulu (aucune pollution).
- **Gateway-First / Provider-First** : SF-165-01 est un mécanisme d'UI ; aucune logique de moteur IA,
  aucune capacité fournie par Claude réimplémentée.

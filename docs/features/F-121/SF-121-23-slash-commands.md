# Mini-spec — [F-121 / SF-121-23] Slash-commands dans le composer

---

## Identifiant

`F-121 / SF-121-23`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-121-23-slash-commands`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter au composer du terminal un parseur de **slash-commands** `/xxx` qui, de façon
**déterministe**, expanse la saisie en un **prompt imposé** issu d'un catalogue connu — au lieu de
laisser le seul modèle deviner quel prompt/skill appliquer.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur tape `/` dans le champ du composer.
2. Un **menu** apparaît au-dessus du champ, listant les commandes du catalogue (nom + description
   courte), filtrées en préfixe au fur et à mesure de la frappe (`/rev` → `/revue`).
3. L'utilisateur navigue au clavier (↑/↓), **Tab/Entrée** complète la commande surlignée dans le
   champ (`/revue ␣`) et ferme le menu ; **Échap** ferme le menu ; un clic sur une entrée complète
   de même. La complétion **n'envoie pas** — elle écrit dans le brouillon.
4. À l'envoi (Entrée menu fermé, ou bouton Envoyer), si le brouillon est une commande **connue**
   (`/nom` éventuellement suivi d'arguments libres), il est **expansé de façon déterministe** en son
   prompt imposé (avec, s'il y en a, les arguments ajoutés en contexte) **avant** l'émission. Le
   texte expansé est écrit dans le brouillon (le parent en reste propriétaire), puis envoyé.
5. Le tour part avec le prompt imposé comme message utilisateur — aucun aller-retour modèle pour
   « découvrir » la commande.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Commande **inconnue** (`/xyz`) | Aucun menu (0 correspondance) ; à l'envoi, le texte part **littéralement** tel quel (aucune expansion silencieuse, aucune erreur). |
| Champ vide ou brouillon ne commençant pas par `/` | Comportement d'envoi inchangé (aucune régression). |
| `/` suivi d'un espace ou d'un texte ne matchant aucun préfixe | Menu fermé ; envoi normal (littéral). |
| Envoi pendant un tour sans précision possible | Inchangé : l'envoi reste refusé comme aujourd'hui (le parseur ne modifie pas cette garde). |
| Terminal en lecture seule | Inchangé : pas d'invite, pas de menu, pas d'envoi. |

---

## Critères d'acceptation

- [ ] Taper `/` (champ non lecture seule) ouvre un menu listant les commandes du catalogue.
- [ ] Le menu filtre en préfixe (`/rev` ne montre que les commandes dont le nom commence par `rev`).
- [ ] ↑/↓ déplacent le surlignage ; Tab et Entrée (menu ouvert) complètent la commande surlignée en
      `\/<nom> ` sans envoyer ; Échap ferme le menu.
- [ ] Un clic sur une entrée du menu complète la commande dans le brouillon sans envoyer.
- [ ] À l'envoi d'une commande connue, le brouillon est remplacé par le **prompt imposé** du
      catalogue, puis émis (assertion sur `draftChange` puis `send`).
- [ ] Les arguments libres tapés après le nom (`/explique le service X`) sont ajoutés au prompt
      imposé de façon déterministe.
- [ ] Une commande inconnue part littéralement, sans expansion et sans erreur.
- [ ] Aucune régression : un message sans `/` part inchangé ; l'expansion des textes collés
      (SF-146-01) et la garde de plafond/lecture seule/précision restent respectées.
- [ ] `ng build` vert ; charte `DESIGN_SYSTEM.md` respectée (jetons `--cg-*`, mono, navy/orange).

---

## Périmètre

### Hors scope (explicite)

- **Aucun backend** : le catalogue et l'expansion sont **frontend uniquement** (le prompt imposé
  devient le message utilisateur volatil — il ne touche NI le préfixe système stable, NI le cache de
  prompt F-134, NI la discipline F-119, NI `AIProvider`). Pas de table, pas de migration, pas
  d'endpoint, pas de mise à jour du runner.
- **Pas de commandes qui pilotent un mode** (plan/act — c'est SF-121-10) ni qui exécutent une action
  cluster : une slash-command n'est qu'un **prompt imposé** injecté dans le brouillon.
- **Pas de catalogue éditable par l'utilisateur / par workspace** (catalogue statique intégré) —
  personnalisation reportée si le besoin émerge.
- **@-mentions de fichiers** (P3-b de l'audit) : hors scope, subfeature distincte.

---

## Valeurs initiales

Sans objet (aucune entité persistée).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-----------------------------|---------------|
| jeton de commande | — | `^/[a-z0-9-]+$` (nom du catalogue, insensible à la casse) | `toLowerCase()`, `trim()` |
| arguments libres | Non | texte libre après le nom | `trim()` ; ajoutés au prompt seulement si non vides |

Notes :
- Le catalogue est **fermé** (constante frontend). Seul un nom présent au catalogue déclenche une
  expansion ; tout le reste part littéralement (déterminisme, pas de surprise).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `slash-commands.ts` (neuf) — module **pur** : interface `SlashCommand`, constante
  `SLASH_COMMANDS`, `slashSuggestions(draft)`, `expandSlashCommand(draft)`. Aucune dépendance
  Angular → testable en isolation.
- `atelier-terminal.component.ts` / `.html` / `.scss` — câblage du menu (signaux d'état, gestion
  clavier) et expansion à `submit()`, au même endroit que l'expansion des textes collés.

---

## Plan de test

### Tests unitaires (module pur `slash-commands.spec.ts`)

- [ ] `slashSuggestions('/')` renvoie tout le catalogue.
- [ ] `slashSuggestions('/rev')` filtre en préfixe.
- [ ] `slashSuggestions('bonjour')` et `slashSuggestions('/revue x')` (espace) renvoient `[]`.
- [ ] `expandSlashCommand('/revue')` renvoie le prompt imposé de `revue`.
- [ ] `expandSlashCommand('/explique le service X')` ajoute les arguments au prompt.
- [ ] `expandSlashCommand('/xyz')` et `expandSlashCommand('bonjour')` renvoient `null`.
- [ ] Insensibilité à la casse (`/REVUE`).

### Tests d'intégration (composant `atelier-terminal.component.spec.ts`)

- [ ] Taper `/` ouvre le menu (au moins une entrée rendue).
- [ ] Envoyer `/revue` émet `draftChange` avec le prompt imposé **puis** `send`.
- [ ] Envoyer un texte sans `/` émet `send` sans réécriture du brouillon par le parseur.

### Isolation workspace

- [x] Non applicable — aucune donnée serveur ni accès base (pur composer frontend).

---

## Dépendances

### Subfeatures bloquantes

Aucune (indépendante ; ne touche pas SF-121-10 plan mode ni le prompt système).

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales (analyse d'impact)

- **Navigation / routing** : la subfeature n'ajoute aucune route ni guard ; elle ajoute un menu et
  une gestion clavier **au sein du composer existant**. Composants concernés : uniquement
  `atelier-terminal.component` (l'unique composer). Les touches interceptées (↑/↓/Tab/Échap/Entrée)
  ne le sont **que** menu ouvert ; menu fermé, le comportement d'envoi est identique à l'actuel.
- **Auth / tenant / plans-limites** : non concernés (aucun appel serveur, aucune donnée tenant).

---

## Notes et décisions

- **Provider-First / Gateway-First** : une slash-command ne réimplémente aucune capacité IA ; elle
  ne fait qu'imposer un texte de prompt côté saisie. Le modèle reste seul moteur.
- **Cache de prompt (F-134) préservé** : l'expansion produit un **message utilisateur** (volatil),
  jamais un ajout au préfixe système stable.
- **Déterminisme** : catalogue fermé ; seule une commande connue est expansée ; inconnue = littéral.
- Réutilise le patron d'expansion déjà en place pour les textes collés (SF-146-01) dans `submit()`.

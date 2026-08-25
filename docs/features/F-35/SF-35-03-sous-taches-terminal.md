# Mini-spec — [F-35 / SF-03] Visibilité des sous-tâches dans la vue terminal (frontend)

---

## Identifiant

`F-35 / SF-03`

## Feature parente

`F-35` — Sous-agents

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-35-03-sous-taches-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre lisible un run délégué : la vue terminal **marque chaque bloc du fil dont il vient**, de sorte
que trois sous-tâches parallèles ne se lisent plus comme un flux unique entrelacé.

---

## Contexte

SF-35-01 a ouvert le roster `multiagent` (activé par défaut, plafond 3), SF-35-02 relaie le fil
d'exécution de chaque commande — `threadId` sur les événements SSE `action` / `action_result` et sur
chaque bloc de `terminal_json`. **Personne ne le lit encore.**

Sans cette SF, la délégation produit exactement le symptôme que le cadrage redoutait : le flux devient
illisible **au moment précis** où la délégation devrait rendre le travail plus rapide. Trois
sous-agents qui lancent chacun `npm test`, `grep`, `cat` produisent neuf blocs entrelacés dans l'ordre
d'arrivée, sans qu'aucun ne dise à quel fil il appartient.

Le backend fait déjà tout le travail : le contrat est **figé** dans SF-35-02 et importé tel quel ici.
Cette SF est **purement frontend** — aucun endpoint, aucune table, aucune migration.

---

## Comportement attendu

### Cas nominal

1. **Run sans délégation** (le cas courant) : aucun bloc ne porte de `threadId`. L'affichage est
   **strictement identique à avant F-35** — aucun marqueur, aucun libellé, aucune bordure nouvelle.
2. **Run délégué** : les fils sont numérotés **dans l'ordre de leur première apparition dans le tour**
   (`Sous-tâche 1`, `Sous-tâche 2`, …). Les blocs du fil principal (`threadId = null`) restent le
   « Fil principal ».
3. Un **libellé de fil** est inséré au-dessus d'un bloc **uniquement quand le fil change** par rapport
   au bloc précédent : une rafale de quatre commandes d'une même sous-tâche porte un seul libellé.
4. Les blocs d'une sous-tâche portent un **rail vertical d'accent** (orange de la charte) qui les
   distingue du fil principal d'un coup d'œil. Un bloc **en échec** garde le rouge d'erreur — l'échec
   prime sur la provenance.
5. Le tour affiche, sous la transcription, le **nombre de sous-tâches** qu'il a ouvertes
   (« 3 sous-tâches »), pour que le taux de délégation se constate sans relire tout le flux.
6. Le même rendu s'applique au **tour en cours** (flux SSE) et aux **tours relus** (`terminal_json`) :
   un rechargement de page montre les mêmes sous-tâches.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun bloc ne porte de fil (run séquentiel, tours d'avant F-35) | Aucun marqueur, rendu d'avant F-35 à l'identique |
| `threadId` absent / `null` / chaîne vide dans le flux SSE | Traité comme le fil principal, jamais d'exception |
| `threadId` absent d'un bloc de `terminal_json` (tour écrit avant SF-35-02) | Bloc rattaché au fil principal, tour lisible sans marquage |
| Sortie arrivant sans commande connue (bloc orphelin) | Bloc créé avec le fil de la **sortie**, comme le backend |
| Commande sans fil recevant une sortie avec fil | La commande **adopte** le fil de la sortie (même règle que `TerminalTranscript`) |
| Plus de fils que de couleurs disponibles | Sans objet : la distinction est **numérotée**, pas colorée (charte) |

---

## Critères d'acceptation

- [ ] Un bloc issu d'un fil délégué porte le libellé `Sous-tâche N`, numéroté dans l'ordre de première
      apparition dans le tour
- [ ] Deux blocs consécutifs du **même** fil ne produisent **qu'un** libellé
- [ ] Le retour au fil principal après une sous-tâche produit le libellé `Fil principal`
- [ ] Une transcription dont **aucun** bloc ne porte de fil produit **zéro** libellé et **zéro** rail
      (non-régression stricte du rendu d'avant F-35)
- [ ] Le nombre de sous-tâches du tour est affiché quand il est ≥ 1, jamais quand il vaut 0
- [ ] Le `threadId` du flux SSE (`action`, `action_result`) est repris dans le bloc affiché
- [ ] Le `threadId` de `terminal_json` est repris au rechargement de la page
- [ ] Une commande sans fil qui reçoit une sortie avec fil adopte ce fil (parité avec le backend)
- [ ] Un bloc en échec conserve son marquage d'erreur, même s'il vient d'une sous-tâche
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md`
- [ ] `npm run build` et `npm test` verts

---

## Périmètre

### Hors scope (explicite)

- Repli / filtrage par sous-tâche (n'afficher qu'un fil) — la lisibilité passe d'abord par le marquage
- Coût **par** sous-tâche : le tour affiche un coût agrégé (décision D5 du cadrage)
- Hiérarchie des fils (qui a délégué à qui) : `threadId` est une **chaîne opaque**, jamais interprétée
- Toute modification backend : le contrat de SF-35-02 est figé et importé tel quel
- Réglage utilisateur du plafond de sous-agents (configuration serveur, SF-35-01)

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-----------------------------|---------------|
| `threadId` (SSE et `terminal_json`) | Non | Chaîne opaque du fournisseur, ou `null` | `undefined` / `''` ⇒ `null` |
| Numéro de sous-tâche | — | Entier ≥ 1, attribué **par tour**, ordre de première apparition | — |
| Libellé | — | `Fil principal` / `Sous-tâche N` — texte, jamais une couleur seule | — |

Notes :
- Le `threadId` n'est **jamais affiché brut** : il identifie un fil chez le fournisseur, il n'a aucun
  sens pour l'utilisateur. Seul son **numéro d'ordre** est montré.
- La numérotation est **locale au tour** : deux tours successifs peuvent réutiliser `Sous-tâche 1`
  pour des fils différents. C'est voulu — l'unité de lecture est le tour.

---

## Technique

### Endpoint(s)

**Aucun.** Contrat consommé (figé par SF-35-02) :

| Source | Champ | Type |
|--------|-------|------|
| SSE `action` | `threadId` | `string \| null` |
| SSE `action_result` | `threadId` | `string \| null` |
| `GET /api/workspaces/{id}/chat` → `terminal_json.blocks[]` | `threadId` | `string \| null` |

### Tables impactées

**Aucune.**

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune donnée nouvelle, aucun schéma touché

### Composants Angular

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | `threadId` sur `AtelierAgentStreamAction`, `AtelierAgentStreamActionResult`, `AtelierTerminalBlock`, `AtelierPersistedTranscript.blocks[]` (champs **additifs**, facultatifs) |
| `core/services/atelier.service.ts` | Lecture défensive de `threadId` dans le parseur SSE |
| `atelier/atelier.component.ts` | `openBlock` / `attachOutput` / `toThreadItem` transportent le fil (règle d'adoption identique au backend) |
| `atelier/terminal/terminal-block.ts` | Fonctions **pures** : `terminalRows()` (libellé + rail par bloc) et `subtaskCount()` |
| `atelier/terminal/atelier-terminal.component.ts/.html/.scss` | Rendu du libellé, du rail et du décompte |

---

## Plan de test

### Tests unitaires

- [ ] `terminalRows` — aucun fil ⇒ aucun libellé, aucun rail (non-régression d'avant F-35)
- [ ] `terminalRows` — numérotation dans l'ordre de première apparition
- [ ] `terminalRows` — deux blocs consécutifs du même fil ⇒ un seul libellé
- [ ] `terminalRows` — retour au fil principal ⇒ libellé `Fil principal`
- [ ] `subtaskCount` — compte les fils **distincts**, ignore le fil principal
- [ ] `attachOutput` — une commande sans fil adopte le fil de sa sortie
- [ ] `attachOutput` — bloc orphelin créé avec le fil de la sortie
- [ ] `toThreadItem` — `threadId` de `terminal_json` repris ; absent ⇒ `null`
- [ ] Service SSE — `action` / `action_result` avec `threadId` ⇒ relayé ; sans ⇒ `null`

### Tests d'intégration (composant)

- [ ] Vue terminal — transcription à deux fils ⇒ libellés `Sous-tâche 1` / `Sous-tâche 2` rendus
- [ ] Vue terminal — transcription sans fil ⇒ aucun élément `.terminal-thread` dans le DOM
- [ ] Vue terminal — décompte « 2 sous-tâches » affiché ; absent quand aucun fil

### Isolation workspace / utilisateur

- [x] **Non applicable** — composant de **présentation** : aucun appel réseau, aucune donnée
  supplémentaire demandée. Les blocs affichés proviennent du run déjà autorisé (`requireOwned`) et de
  l'historique déjà filtré par `user_id` côté backend. Aucun identifiant n'est envoyé au serveur.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ni de Principal. |
| Contexte tenant | **Non** | Aucun nouveau chemin de résolution du tenant : rien n'est demandé au serveur. |
| Plans / limites | **Non** | Aucun décompte, aucun gate touché : le coût du tour reste agrégé et inchangé (D5). |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucune redirection : le rendu se fait dans la vue terminal existante. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-35-01` — statut : **done** (PR #168)
- `SF-35-02` — statut : **done** (PR #170), contrat API figé

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**Numéroter plutôt que colorer (arbitrage).** L'usage habituel — une couleur par fil — est **interdit
par la charte** : `docs/DESIGN_SYSTEM.md` ne définit ni palette catégorielle ni couleurs libres, et
« couleurs hors design system » est un blocage automatique de `CLAUDE.md`. La distinction repose donc
sur un **numéro** (lisible aussi en niveaux de gris, et par un lecteur d'écran), l'orange de la charte
servant uniquement à séparer « délégué » de « fil principal » — une distinction binaire, elle, tenable
avec les jetons existants.

**Libellé au changement de fil, pas sur chaque bloc.** Répéter le marqueur sur chaque bloc double le
bruit dans le cas le plus fréquent (une sous-tâche qui enchaîne plusieurs commandes). Le libellé ne
paraît qu'à la **frontière**, comme un changement de locuteur dans une transcription.

**Numérotation locale au tour.** Le `threadId` du fournisseur n'a pas de continuité garantie entre
tours, et l'unité de lecture est le tour. Numéroter globalement donnerait des sauts inexpliqués
(`Sous-tâche 7` sur un tour qui n'en a ouvert qu'une).

**Parité d'appariement avec le backend.** `attachOutput` reprend exactement la règle de
`TerminalTranscript.addOutput` : le fil du bloc cible s'il en a un, sinon celui de la sortie. Sans
cela, le même run se lirait différemment en direct et après rechargement.

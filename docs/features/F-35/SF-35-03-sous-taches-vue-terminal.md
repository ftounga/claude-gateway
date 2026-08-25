# Mini-spec — [F-35 / SF-03] Sous-tâches visibles dans la vue terminal (frontend)

---

## Identifiant

`F-35 / SF-03`

## Feature parente

`F-35` — Sous-agents (sessions parallèles)

## Statut

`done` — livrée le 2026-08-26 (PR #172)

## Date de création

2026-08-26

## Branche Git

`feat/SF-35-03-sous-taches-vue-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Marquer dans la vue terminal les commandes qui viennent d'une **sous-tâche** plutôt que du travail
principal, pour qu'un run délégué reste lisible au lieu d'entrelacer trois flux sans signalisation.

---

## Contexte

SF-35-01 permet à la session de déléguer, SF-35-02 relaie le fil d'exécution de chaque commande
(`threadId`, champ additif du flux SSE et de la transcription persistée). Il reste à le **montrer**.

Sans marqueur, un run à trois sous-agents produit un flux où les commandes s'entrelacent : on y voit
un `npm test` suivi d'un `grep` suivi d'un autre `npm test`, sans comprendre qu'il s'agit de trois
travaux séparés. Le flux devient illisible exactement au moment où la délégation devrait le rendre
plus rapide.

Le marqueur reste **discret** : la vue terminal de F-30 est un flux continu, pas un tableau de bord.
Un badge court en tête de bloc suffit à rendre la lecture possible, sans réorganiser l'écran ni
introduire de colonnes parallèles — ce qui serait une autre feature, et un autre écran.

---

## Comportement attendu

### Cas nominal

1. Le flux SSE porte `threadId` sur `action` et `action_result` ; le service l'expose dans les
   modèles, le composant Atelier le pose sur le bloc terminal.
2. Le **premier** fil rencontré dans un tour est le **coordinateur** : ses blocs n'ont aucun badge —
   c'est le cas de tous les runs non délégués, où l'écran reste strictement identique à aujourd'hui.
3. Chaque autre fil reçoit un numéro d'ordre d'apparition (1, 2, 3…) et ses blocs portent un badge
   `sous-tâche N`.
4. La numérotation est **locale au tour** : un même fil ne garde pas son numéro d'un tour à l'autre,
   et c'est voulu — le numéro dit « la deuxième sous-tâche de ce tour », pas un identifiant.
5. Au rechargement de la page, la transcription persistée porte les mêmes `threadId` : les badges
   sont reconstruits à l'identique.
6. Un bloc sans `threadId` (backend antérieur, event sans fil) est traité comme appartenant au
   coordinateur : aucun badge.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Backend antérieur (aucun `threadId` dans le flux) | Aucun badge, écran identique à avant F-35 |
| Transcription relue sans `threadId` | Aucun badge, tour lisible |
| `threadId` présent mais vide ou non textuel | Traité comme absent (aucun badge) |
| Plus de fils que prévu dans un tour | Numérotation qui continue (aucune borne à l'affichage) |
| Un fil n'apparaît que via une sortie orpheline | Le bloc orphelin porte le badge de son fil |

---

## Critères d'acceptation

- [ ] `AtelierAgentStreamAction` et `AtelierAgentStreamActionResult` portent `threadId?: string | null`
- [ ] `AtelierPersistedTranscript.blocks[]` porte `threadId?: string | null`
- [ ] `AtelierTerminalBlock` porte `threadId: string | null`
- [ ] Fonction pure `subtaskIndexes(blocks)` : le premier fil non nul est le coordinateur (absent de
      la table), les suivants sont numérotés `1, 2, 3…` dans l'ordre d'apparition
- [ ] Blocs du coordinateur ⇒ **aucun** badge ; run non délégué ⇒ écran **inchangé**
- [ ] Blocs d'un autre fil ⇒ badge `sous-tâche N`, en direct **et** après rechargement
- [ ] Couleurs, polices et espacements pris dans les tokens du `DESIGN_SYSTEM.md` — aucune valeur
      littérale nouvelle
- [ ] Le badge est accessible : porté par un élément textuel, jamais par la seule couleur
- [ ] Aucun appel réseau nouveau : le composant terminal reste un composant de présentation
- [ ] `npm run build` et `npm test` verts

---

## Périmètre

### Hors scope

- Repli/dépliage par sous-tâche, filtre par fil, colonnes parallèles (autre écran, autre feature)
- Coût par sous-tâche (le tour affiche un coût agrégé — SF-35-02)
- Réglage utilisateur de la délégation (le flag est serveur — SF-35-01)
- Toute reprise de la marque « Claude Code » dans l'interface (hors périmètre F-30)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `threadId` | Chaîne opaque, jamais affichée telle quelle (elle n'a aucun sens pour l'utilisateur) |
| Numérotation | Entier `>= 1`, ordre d'apparition, **local au tour** |
| Coordinateur | Premier fil non nul du tour ; les blocs sans fil lui sont rattachés |
| Libellé | `sous-tâche N` — minuscules, comme le reste de la vue terminal |
| Non-régression | Aucun `threadId` dans un tour ⇒ zéro badge, zéro changement de rendu |

---

## Technique

### Contrat API (importé de SF-35-02, figé)

| Événement SSE | Champ | Type |
|---------------|-------|------|
| `action` | `threadId` | `string \| null` |
| `action_result` | `threadId` | `string \| null` |

`GET /api/workspaces/{id}/chat` → `terminal.blocks[].threadId` (`string | null`).

Aucun appel nouveau, aucun endpoint nouveau.

### Tables impactées / Migration

**Aucune** — subfeature 100 % frontend.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | Champs additifs `threadId` (flux, transcription, bloc) |
| `core/services/atelier.service.ts` | Lecture de `threadId` dans le parsing SSE |
| `atelier/atelier.component.ts` | `openBlock` / `attachOutput` / `toThreadItem` portent le fil |
| `atelier/terminal/terminal-block.ts` | `subtaskIndexes` + `subtaskLabel` (fonctions pures) |
| `atelier/terminal/atelier-terminal.component.ts` | Exposition du libellé au gabarit |
| `atelier/terminal/atelier-terminal.component.html` | Badge en tête de bloc (tour en cours + historique) |
| `atelier/terminal/atelier-terminal.component.scss` | Style du badge (tokens du design system) |

---

## Plan de test

### Tests unitaires

- [ ] `subtaskIndexes` : aucun fil ⇒ table vide
- [ ] `subtaskIndexes` : un seul fil ⇒ table vide (c'est le coordinateur)
- [ ] `subtaskIndexes` : trois fils ⇒ le premier absent, les deux autres numérotés `1` et `2`
- [ ] `subtaskIndexes` : blocs sans fil mélangés ⇒ ignorés, numérotation inchangée
- [ ] `subtaskLabel` : bloc du coordinateur ⇒ `null` ; bloc d'un autre fil ⇒ `sous-tâche 1`
- [ ] Service : `action` avec `threadId` ⇒ relayé ; sans ⇒ `null`
- [ ] `openBlock` / `attachOutput` : le fil est posé sur le bloc et conservé à l'appariement
- [ ] `toThreadItem` : transcription avec `threadId` ⇒ blocs marqués ; sans ⇒ blocs sans fil
- [ ] Composant terminal : un tour à deux fils affiche exactement un badge `sous-tâche 1`
- [ ] Composant terminal : un tour mono-fil n'affiche **aucun** badge (non-régression)

### Tests d'intégration

- [ ] Non applicable (aucun appel réseau nouveau) — la couverture passe par les specs de composant et
      de service, sur un flux SSE simulé

### Isolation utilisateur

- [x] **Applicable** — garantie côté backend : le composant n'affiche que ce que le flux et
  l'historique du workspace **possédé** lui renvoient (JWT porté par l'`authInterceptor`). Aucun
  identifiant d'utilisateur, de workspace ou de session n'est construit côté client.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ; les appels existants passent par l'`authInterceptor` inchangé. |
| Contexte tenant | **Non** | Aucun identifiant n'est résolu côté client : le workspace vient de la route déjà en place, le reste du backend. |
| Plans / limites | **Non** | Aucun gate, aucun quota touché : le badge est un rendu. |
| Navigation / routing | **Non** | Aucune route, aucun guard, aucune redirection. Le badge s'insère dans la vue terminal existante (`AtelierTerminalComponent`), qui reste atteinte par le même chemin. |

---

## Notes

**Pourquoi le premier fil est le coordinateur.** Il n'existe aucun marqueur explicite disant « ce fil
est le principal ». L'ordre d'apparition est le seul signal disponible et il est fiable : c'est le
coordinateur qui reçoit le message de l'utilisateur et qui délègue, donc lui qui agit en premier. À
défaut de fil du tout, il n'y a rien à distinguer — et c'est le cas de tous les runs actuels.

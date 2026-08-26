# Mini-spec — [F-37 / SF-02] Affichage des modifications dans la vue terminal (frontend)

---

## Identifiant

`F-37 / SF-02`

## Feature parente

`F-37` — Voir les modifications (diff unifié du tour d'exécution)

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-37-02-diff-vue-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher dans la vue terminal, **replié par fichier**, le diff unifié du tour — ajouts et retraits
lisibles au même endroit que le reste du fil, sans aller-retour vers l'explorateur de fichiers.

---

## Contexte

Le backend (**SF-37-01**) relaie désormais les modifications du tour sur l'événement SSE `done`, et les
persiste dans le document `terminal_json` du tour. Rien ne les affiche.

La vue terminal (F-30 SF-30-07) n'affiche même pas `changedFiles` : le fil s'arrête au commentaire de
l'agent et au coût. Le diff est ce qu'on lit **avant d'accepter le travail** ; il a sa place là,
juste après le commentaire, et **replié** — le fil ne doit pas être noyé par des centaines de lignes
qu'on ne regardera pas toutes (D7).

Le contrat API est **importé tel quel de SF-37-01** ; les tests de cette SF s'appuient sur un **mock**
du service, sans dépendre du backend mergé.

---

## Comportement attendu

### Cas nominal

1. À la fin d'un run, le tour ajouté au fil porte les modifications reçues sur l'événement `done`
   (`diffs`).
2. Sous le commentaire de l'agent, une section **« Modifications »** liste **une ligne par fichier** :
   - le **chemin** du fichier ;
   - un compteur `+N` / `−M` (lignes ajoutées / retirées) ;
   - la mention **« nouveau »** quand le fichier n'existait pas avant ce tour ;
   - un chevron indiquant que la ligne est dépliable.
3. La ligne est **repliée par défaut**. Un clic déplie le diff de **ce fichier seulement** ; un second
   le replie. Chaque fichier a son propre état.
4. Le diff déplié est rendu en monospace, une ligne par ligne du diff :
   - ligne commençant par `+` ⇒ style **ajout** ;
   - ligne commençant par `-` ⇒ style **retrait** ;
   - ligne commençant par `@@` ⇒ style **section** ;
   - toute autre ligne ⇒ style **contexte**.
5. Quand le backend a **borné** le diff (`omittedLines > 0`), une mention finale dit le nombre de
   lignes omises.
6. Quand le fichier est **illisible** (`unreadable`), la ligne dépliée affiche « fichier binaire ou
   illisible » au lieu d'un diff vide.
7. Au **rechargement de la page**, les modifications sont relues depuis le document persisté du tour,
   dans le même état replié.
8. Un tour **sans** modification n'affiche aucune section — l'écran est strictement celui d'avant
   F-37.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `diffs` absent de l'événement `done` (backend antérieur) | Aucune section ; le tour s'affiche comme avant F-37 |
| `diffs` présent mais vide | Aucune section |
| Entrée sans `path` | Entrée ignorée (rien à désigner à l'écran) |
| `diff` absent ou `null` | Traité comme chaîne vide ; la ligne reste dépliable et n'affiche rien |
| Compteurs absents ou non numériques | Traités comme `0` ; le compteur correspondant n'est pas affiché |
| Document `terminal_json` illisible | Inchangé : tour sans transcription **et** sans modifications, jamais d'écran cassé |
| Tour relu écrit avant cette SF | Aucune section — lisible tel quel |

---

## Critères d'acceptation

- [ ] Un tour portant des modifications affiche une ligne **par fichier**, repliée
- [ ] La ligne porte le chemin, `+N`, `−M`, et « nouveau » pour un fichier créé
- [ ] Un clic déplie le diff de ce fichier ; un autre fichier reste replié
- [ ] Les lignes `+`, `-`, `@@` et de contexte ont des styles distincts
- [ ] `omittedLines > 0` ⇒ mention du nombre de lignes omises sous le diff
- [ ] `unreadable` ⇒ mention « fichier binaire ou illisible », aucun diff vide affiché
- [ ] Après rechargement, les modifications persistées réapparaissent, repliées
- [ ] Un tour sans modification n'affiche aucune section
- [ ] Les couleurs et polices proviennent du `DESIGN_SYSTEM.md` (variables existantes de la vue
      terminal) — aucune valeur littérale nouvelle hors palette
- [ ] Les tests frontend passent sur un **mock** du service, sans backend

---

## Périmètre

### Hors scope

- Calcul du diff → **SF-37-01** (backend)
- Coloration syntaxique du code à l'intérieur du diff
- Annulation d'une modification depuis le diff (retour en arrière)
- Affichage des modifications dans le mode **Assistant** (la vue conversationnelle garde sa liste de
  fichiers `changedFiles` inchangée)
- Diff entre deux tours quelconques

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Contrat consommé | **Importé de SF-37-01** — aucune renégociation |
| État de repli | Local au composant, **par fichier** ; non persisté |
| Rendu | Aucun HTML injecté : les lignes du diff sont du texte, rendues par interpolation |
| Palette | Variables du design system déjà utilisées par la vue terminal |
| Champs facultatifs | Tout champ absent est traité par sa valeur neutre, jamais par une erreur |

---

## Technique

### Contrat API consommé (importé de SF-37-01, non renégocié)

Événement SSE `done` de `POST /api/workspaces/{id}/agent/stream`, champ additif `diffs` :

```ts
interface AtelierFileDiff {
  path: string;
  added: boolean;
  diff: string;
  addedLines: number;
  removedLines: number;
  omittedLines: number;
  unreadable: boolean;
}
```

Même forme sous la clé `diffs` du document `terminal_json` renvoyé par
`GET /api/workspaces/{id}/chat`.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | `AtelierFileDiff` ; `diffs` sur `AtelierAgentStreamDone` et `AtelierPersistedTranscript` |
| `atelier/atelier.types.ts` | `AtelierThreadItem.diffs` (tour du fil) |
| `atelier/terminal/terminal-diff.ts` | **Nouveau** — découpage d'un diff en lignes typées, libellés |
| `atelier/terminal/terminal-diff.spec.ts` | **Nouveau** — tests unitaires du découpage |
| `atelier/terminal/atelier-terminal.component.*` | Section « Modifications », repli par fichier |
| `atelier/atelier.component.ts` | `onDone` porte les diffs ; `toThreadItem` les relit du document |
| `core/services/atelier.service.ts` | Relais de `diffs` depuis l'événement `done` (et de `budgetReached`, qui n'était pas relayé) |

Aucun endpoint appelé nouveau, aucune route, aucun guard.

---

## Plan de test

### Tests unitaires (`terminal-diff`)

- [ ] Découpage d'un diff en lignes typées : `add`, `remove`, `hunk`, `context`
- [ ] Diff vide ⇒ aucune ligne
- [ ] Ligne vide dans le diff ⇒ ligne de contexte (jamais une exception)
- [ ] Libellé de compteur : `+3 −1` ; compteur à zéro omis
- [ ] Libellé d'un fichier nouveau ⇒ mention « nouveau »
- [ ] Libellé de lignes omises ⇒ singulier/pluriel corrects

### Tests unitaires (composants, sur **mock** du service)

- [ ] `done` portant `diffs` ⇒ le tour du fil les porte
- [ ] `done` **sans** `diffs` ⇒ tour sans modification, aucun rendu
- [ ] `toThreadItem` relit `diffs` du document persisté
- [ ] `toThreadItem` sur un document sans `diffs` ⇒ tour sans modification
- [ ] Entrée sans `path` ⇒ ignorée
- [ ] Rendu : une ligne par fichier, repliée ; un clic déplie ce fichier seulement
- [ ] Rendu : `unreadable` ⇒ mention « fichier binaire ou illisible »
- [ ] Rendu : `omittedLines > 0` ⇒ mention du volume omis

### Isolation utilisateur

- [x] **Applicable** — aucune donnée n'est demandée par cette SF : les modifications arrivent dans la
  réponse d'un endpoint déjà filtré par `user_id` côté backend (`requireOwned`). Aucun identifiant de
  workspace ni chemin n'est fabriqué côté client.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun appel authentifié nouveau, aucun changement d'intercepteur. |
| Contexte tenant | **Non** | Aucun nouveau chemin de résolution du tenant côté client. |
| Plans / limites | **Non** | Aucun gate, aucun quota consulté ou affiché. |
| Navigation / routing | **Non** | Aucune route ajoutée ou modifiée, aucun guard, aucune redirection : la section vit dans la vue terminal existante. |

---

## Notes

**Pourquoi replié par défaut.** Le diff complète le fil, il ne le remplace pas. Déplié d'office, un
tour qui touche dix fichiers pousserait le commentaire de l'agent et le coût hors de l'écran — et le
terminal défile déjà tout seul vers le bas. Replié, la liste dit **ce qui a changé** en une ligne par
fichier, et le détail est à un clic (D7 du cadrage).

**Correctif adjacent (F-36 SF-36-04).** Le relais du flux ne recopiait pas `budgetReached` de
l'événement `done` : la mention « plafond de dépense de ce run atteint » n'apparaissait qu'**après
rechargement de la page**, relue du tour persisté — jamais à l'instant où le run s'arrête, c'est-à-dire
au seul moment où elle sert. Une ligne, dans le même mappeur que `diffs`, corrigée ici.

**Pourquoi ne pas colorer la syntaxe.** Hors scope assumé : il faudrait embarquer un moteur de
coloration pour chaque langage, alors que la distinction utile — ce qui est **entré** et ce qui est
**sorti** — tient dans deux couleurs.

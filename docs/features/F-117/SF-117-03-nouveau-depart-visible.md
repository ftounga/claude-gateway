# Mini-spec — F-117 / SF-117-03 Le nouveau départ visible, et suggéré

## Identifiant

`F-117 / SF-117-03`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-117-03-nouveau-depart`

---

## Objectif

> En une phrase : rendre le « nouveau départ » **visible** (une action claire dans l'en-tête du
> terminal) et le **suggérer** de façon non bloquante au-delà d'un seuil de taille de fil — il était
> invisible et jamais utilisé (0/18 terminaux en prod).

---

## Comportement attendu

### Cas nominal

1. L'en-tête du terminal (mode non lecture-seule) porte une action **« Nouveau départ »** toujours
   disponible, qui émet un événement `restart` — câblé au `restartThread()` existant du parent (F-39 /
   SF-39-04) : Claude repart sans le contexte des tours précédents, **la conversation reste affichée**.
2. Au-delà d'un seuil de taille de fil (nombre de tours rejouables, constante `LONG_THREAD_TURNS`), un
   **bandeau de suggestion non bloquant** apparaît : « Cette conversation est longue — repartir
   propre ? », avec un bouton « Nouveau départ » et un bouton « Plus tard » qui **ferme** le bandeau
   sans rien changer. Le bandeau **ne bloque jamais** l'envoi ni la lecture.
3. La suggestion est **complémentaire** de la compaction (SF-117-01, qui, elle, ne demande rien) :
   elle s'adresse au confort et au coût d'un fil devenu long, pas au débordement (déjà couvert).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Terminal en lecture seule | Ni l'action d'en-tête ni le bandeau (ce sont des gestes) |
| Fil sous le seuil | Aucun bandeau de suggestion |
| Bandeau fermé (« Plus tard ») | Il ne réapparaît pas pour ce fil tant qu'on ne le rouvre pas |
| Échec du `restartThread` (réseau) | Message d'erreur `MatSnackBar` (comportement existant du parent) |

---

## Critères d'acceptation

- [ ] L'en-tête du terminal (non lecture-seule) affiche une action « Nouveau départ » qui émet
      `restart`.
- [ ] Le parent câble `restart` sur `restartThread()` (comportement SF-39-04 réutilisé).
- [ ] Au-delà du seuil de tours, le bandeau de suggestion apparaît ; sous le seuil, non.
- [ ] Le bandeau est **non bloquant** (l'envoi et la lecture restent possibles) et fermable
      (« Plus tard »).
- [ ] En lecture seule, ni l'action ni le bandeau ne sont rendus.
- [ ] Couleurs/polices/espacements conformes au `DESIGN_SYSTEM.md` (réutilise les registres de bandeau
      existants du terminal ; aucune couleur nouvelle).

---

## Périmètre

### Hors scope (explicite)

- La compaction (SF-117-01) et le repli sur 400 (SF-117-02), déjà livrés.
- Le reaper et les fuites (SF-117-04).
- Toute nouvelle route ou tout changement backend (le `restart` et le `resume` existent déjà).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `threadTurns` (Input) | entier ≥ 0 ; le seuil `LONG_THREAD_TURNS` déclenche la suggestion |
| Suggestion | non bloquante ; fermable ; absente en lecture seule |

---

## Technique

### Endpoint(s)

Aucun (réutilise `POST /api/workspaces/{id}/chat/restart` et `GET .../resume` existants).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable.

### Composants Angular

- `AtelierTerminalComponent` — action d'en-tête « Nouveau départ » (`@Output() restart`), bandeau de
  suggestion piloté par `@Input() threadTurns` + signal de fermeture local.
- `AtelierComponent` — câble `(restart)="restartThread()"` et `[threadTurns]="resumeTurns()"`.

---

## Plan de test

### Tests unitaires (specs Angular)

- [ ] `atelier-terminal.component.spec` — l'action d'en-tête émet `restart`.
- [ ] `atelier-terminal.component.spec` — le bandeau apparaît au-dessus du seuil, pas en dessous, pas
      en lecture seule ; « Plus tard » le ferme.
- [ ] `atelier.component.spec` — `restart` déclenche `restartThread()` (réutilise l'existant).

### Tests d'intégration

- [ ] `npm run build` vert (budget de style respecté).

### Isolation workspace

- [x] Non applicable côté UI — l'isolation est garantie par les endpoints backend existants
      (`requireOwned`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-01` / `SF-117-02` — statut : **done** (défenses automatiques ; celle-ci est le geste
  humain complémentaire).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Taille de fil = nombre de tours rejouables.** `resumeTurns` (déjà exposé par
  `GET .../resume`) est un proxy simple et suffisant ; inutile de coupler la suggestion à l'estimation
  de tokens de SF-117-01 (qui, elle, sert la compaction automatique).
- **D2 — Non bloquant.** La suggestion informe, elle ne conditionne rien — contrairement à la
  bannière IDLE (SF-39-04) qui posait déjà un choix à l'ouverture d'un fil inactif ; ici, on ne coupe
  jamais le travail en cours.
- **D3 — Réutilise `restartThread()`.** Aucun nouveau backend : le geste et l'état de reprise
  existent depuis F-39.

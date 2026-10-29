# Mini-spec — [F-221 / SF-221-04] L'overlay « en parallèle » et le départ direct

## Identifiant
SF-221-04

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-29

## Branche Git
`feat/SF-221-04-overlay-en-parallele`

---

## Objectif
Depuis un terminal, lancer **un autre** chantier ouvre un overlay « Lancer le chantier N en parallèle ? ». S'il est validé, le split s'ouvre et le chantier démarre à droite, sans phrase dans le chat.

## Comportement attendu

### Cas nominal
- **Gestes interceptés** (dans un terminal, pour un chantier **autre** que celui du volet) :
  - [Lancer en autonome] et [Avancer jusqu'au blocage] du **tableau des chantiers du sujet** ;
  - **« Basculer sur le chantier N »** d'une suite (F-206/F-209).

  Le chantier du volet lui-même part comme avant, sans overlay. La bande du parcours (onglets de chantiers) reste une simple bascule de focus.
- **L'overlay** (`MatDialog`, `ParallelLaunchDialogComponent`) lit le préflight du chantier N (`GET /journey/autonomy`). Il dit :
  - **« Lancer le chantier N en parallèle ? »**, avec le titre du chantier et le mode retenu :
    - *Avancer jusqu'au blocage* si `advance.possible` (préféré, SF-219-05) ;
    - sinon *Exécution autonome* si le préflight est prêt ;
    - le mode demandé par le bouton du tableau est respecté quand il est possible ;
  - sinon : **pourquoi il ne peut pas partir** (raisons du préflight), avec [Ouvrir à côté] : le volet s'ouvre sur le chantier et ce qui l'empêche ;
  - pour une suite : en plus, [Basculer ici] (l'ancien geste) ;
  - toujours [Annuler].
- **[Lancer en parallèle]** : `POST …/chantiers/{n}/autonomy?mode=…&keepFocus=true`. **Aucun message** n'est envoyé dans le chat. Le split s'ouvre aussitôt, chantier N à droite (sur téléphone : l'onglet du chantier N). Le volet se rebranche sur la course.
- Refus serveur au départ (`409`) : la raison s'affiche (snackbar) et le volet s'ouvre quand même sur le chantier, pour qu'on voie ce qui bloque.
- **[Reprendre la main]** du tableau vise désormais **son** chantier (`stop?chantier=N`, SF-221-02).
- Un volet ouvert à la main (« Ouvrir à côté ») reste ouvert même si son chantier n'est pas actif, jusqu'à ce qu'on le ferme.

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| Préflight illisible | overlay avec [Ouvrir à côté] et [Annuler] seulement |
| `409` au lancement (limite, poste hors ligne, chantier déjà en cours) | message du serveur, volet ouvert sur le chantier |
| Annuler | rien ne change |
| Overlay depuis le volet droit pour un 3ᵉ chantier | le volet droit passe sur ce chantier (deux volets au plus) |

## Critères d'acceptation
- [ ] Dans le terminal du chantier 7, [Avancer jusqu'au blocage] sur le chantier 8 du tableau ouvre l'overlay, pas un départ direct.
- [ ] Validé : `launch(w, 8, 'UNTIL_BLOCKED', keepFocus=true)`, aucun `streamChat`, split ouvert avec 8 à droite.
- [ ] Chantier 8 qui ne peut pas avancer mais qui est prêt en strict : l'overlay propose l'exécution autonome. S'il n'est ni l'un ni l'autre : les raisons s'affichent, et [Ouvrir à côté] ouvre le volet sans rien lancer.
- [ ] Sur le chantier 7 lui-même, [Lancer en autonome] part sans overlay (inchangé).
- [ ] « Basculer sur le chantier 8 » d'une suite ouvre l'overlay avec [Basculer ici]. Ce choix bascule le focus comme avant.
- [ ] [Reprendre la main] du tableau appelle `stop(w, N)`.

## Périmètre

### Hors scope (explicite)
- Les suites en split (SF-221-05) et le worktree (SF-221-06).
- Le tableau du client (F-220) : il lance hors terminal, sans overlay (arbitrage PO 3).

## Valeurs initiales
- Mode préféré : `UNTIL_BLOCKED` si possible.

## Contraintes de validation
- Deux volets au plus.

## Technique

### Endpoint(s)
- Utilisés : `GET /journey/autonomy`, `POST /journey/chantiers/{n}/autonomy?mode=&keepFocus=true`, `POST /journey/autonomy/stop?chantier=`.

### Tables impactées
- Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `AutonomyService` : `launch(…, keepFocus)` et `stop(…, chantier)`.
- `TerminalChantiersBoardComponent` :
  - entrée `shownChantier` (le chantier du volet), sortie `parallel` ;
  - lancement intercepté pour un autre chantier ;
  - `takeBack` par chantier.
- `AtelierTerminalComponent` : sortie `parallelLaunch`. Les suites « Basculer » passent par elle.
- `ParallelLaunchDialogComponent` (nouveau) : l'overlay.
- `AtelierComponent` : ouvre l'overlay, lance, ouvre le split et épingle un volet ouvert à la main.

## Plan de test

### Tests unitaires (Karma)
- [ ] `parallelChoice(entry, requested)` (pur) choisit le mode : `UNTIL_BLOCKED` si possible, sinon `STRICT` si prêt, sinon `null` avec les raisons. Le mode demandé est respecté quand il est possible.
- [ ] `TerminalChantiersBoardComponent` :
  - un autre chantier émet `parallel` sans appeler `launch` ;
  - le chantier du volet appelle `launch` ;
  - `takeBack` appelle `stop(w, N)`.
- [ ] `AtelierComponent` :
  - overlay validé → `launch(…, keepFocus=true)`, aucun `streamChat`, split sur N ;
  - « Ouvrir à côté » → split sans `launch`.
- [ ] `ParallelLaunchDialogComponent` : boutons selon le cas.

### Tests d'intégration
- [ ] Backend déjà couvert (SF-221-02 : `keepFocus`, `stop?chantier`).

### Isolation utilisateur
- [x] Côté serveur, inchangé (routes de SF-219/SF-221-02).

## Préoccupations transversales
- **Plans / limites** : la limite de 4 courses est appliquée par le serveur. L'overlay rend son refus.
- **Navigation** : aucune route.

## Dépendances
- SF-221-02, SF-221-03 (mergées).

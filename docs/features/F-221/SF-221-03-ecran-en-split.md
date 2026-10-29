# Mini-spec — [F-221 / SF-221-03] L'écran en split

## Identifiant
SF-221-03

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-29

## Branche Git
`feat/SF-221-03-ecran-en-split`

---

## Objectif
Quand deux chantiers d'un sujet sont actifs, le terminal se partage verticalement : un volet par chantier, chacun avec son fil, son tour vivant et sa saisie.

## Comportement attendu

### Cas nominal
- **Le fil de l'écran normal** (cadrage A1).
  - Sujet guidé avec un chantier au focus : l'écran suit le fil de ce chantier. L'historique vient de `GET /chat?chantier=F`. L'envoi, le rebranchement, l'état du tour, la précision, l'interruption et l'autorisation portent `chantier=F`.
  - Sujet libre ou sans focus : fil commun, comme aujourd'hui.
  - Changer de focus (« Basculer sur le chantier N ») recharge le fil et se rebranche sur le tour du nouveau fil.
- **Ouverture en split.** À l'ouverture d'un sujet, et à chaque changement de focus, l'écran lit `GET /chat/lanes` (SF-221-02).
  - Si un **autre chantier** que celui affiché est actif (tour vivant ou course autonome), l'écran passe en split.
  - **Gauche** = le chantier affiché (le focus). **Droite** = l'autre chantier actif, le plus ancien.
  - Plus de deux chantiers actifs : la droite prend le plus ancien. Un lien « +N autre(s) actif(s) » ouvre le tableau des chantiers.
  - Un seul chantier actif : écran normal.
- **Un volet = un terminal complet sur un fil.** Le volet droit est un `app-atelier-terminal` piloté par `TerminalLanePaneComponent`. Il a :
  - son historique (`GET /chat?chantier=N`) et son direct (rebranchement au tour du fil N au chargement, tour suivi jusqu'à la fin) ;
  - sa saisie : l'envoi part dans le fil N, et la précision dans son tour s'il tourne ;
  - son « Interrompre », qui n'arrête que le fil N ;
  - ses autorisations et ses questions, tranchées dans ce volet ;
  - sa bande du parcours sur **le chantier N** (`GET /journey?chantier=N`, vue du fil ; gestes par chantier : `/journey/chantiers/{n}/…`).
- **En-tête de volet** (les deux volets en split) : « Chantier N · titre », pastille d'activité (autonome / tour en cours), bouton **Fermer ce volet**.
  - Fermer un volet ne tue rien : le chantier continue côté serveur et reste visible sur le tableau.
  - Le volet fermé ne se rouvre pas tout seul tant que son chantier reste actif.
  - Fermer le volet gauche met le chantier de droite au focus (`POST /journey/chantiers/{n}/focus`), et l'écran normal le suit.
- **Téléphone** (< 820 px) : pas de split. Un sélecteur à deux onglets « Chantier F · Chantier N » montre un volet à la fois. Les deux restent branchés.
- **Charte** : jetons `--cg-*` seulement, séparateur `--cg-border`, espacements multiples de 4, icône et libellé, aucune couleur nouvelle.

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| `GET /chat/lanes` en échec | écran normal, rien n'est cassé (best-effort) |
| Historique d'un volet illisible | message dans le volet, saisie toujours disponible |
| Le flux d'un volet tombe | volet « réponse non reçue », l'historique est relu au prochain geste ; l'autre volet n'est pas touché |
| `GET /journey?chantier=N` sur un chantier clos | la vue du focus (le serveur se replie sur le focus) |
| Sujet d'autrui | `404` serveur, déjà couvert (`requireOwned`) |

## Critères d'acceptation
- [ ] Sujet guidé, focus 7 : l'envoi de l'écran normal porte `chantier=7`, l'historique est lu avec `?chantier=7`.
- [ ] Ouverture d'un sujet où les chantiers 7 et 8 sont actifs, focus 7 : deux volets, 7 à gauche, 8 à droite. Chacun lit son historique et se rebranche sur son tour.
- [ ] Un envoi dans le volet droit part avec `chantier=8`, sans toucher le volet gauche. « Interrompre » à droite appelle `interrupt?chantier=8`.
- [ ] Fermer le volet droit le retire et ne le rouvre pas tant que 8 est actif. Fermer le gauche met 8 au focus.
- [ ] Un seul chantier actif : pas de split.
- [ ] À moins de 820 px : onglets à la place du split, et un seul volet visible.
- [ ] Captures headless 1440×900 (split) et 390×844 (onglets), sans `--hide-scrollbars`, regardées : pas de débordement horizontal.

## Périmètre

### Hors scope (explicite)
- L'overlay « en parallèle » et le départ direct (SF-221-04).
- Les suites en split (SF-221-05).
- Le worktree (SF-221-06).
- La mosaïque (F-83) : inchangée.

## Valeurs initiales
- Split fermé. Volets refermés à la main : mémoire de session de l'écran seulement.

## Contraintes de validation
- Deux volets au plus.

## Technique

### Endpoint(s)
- `GET /api/workspaces/{id}/journey?chantier=N` (nouveau paramètre : vue du fil, `SubjectJourneyService.forLane`).
- Utilisés : `GET /chat/lanes`, `GET /chat?chantier=`, `POST /chat/stream` (`chantier`), `GET /chat/turn|attach?chantier=`, `POST /chat/steer|interrupt|confirm?chantier=`.

### Tables impactées
- Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `AtelierService` : `chantier` facultatif sur l'historique, le flux, le rebranchement, l'état, la précision, l'interruption et l'autorisation ; `lanes()`.
- `JourneyService` : `get(id, chantier?)` et les gestes par chantier.
- `AtelierTerminalComponent` :
  - entrées `laneChantier` (fil explicite d'un volet) et `paneTitle` ;
  - sorties `focusChantierChange` et `closePane` ;
  - en-tête de volet.
- `TerminalLanePaneComponent` (nouveau) : un terminal sur un fil, avec son état propre.
- `TerminalPaneHeadComponent` (nouveau) : l'en-tête de volet (styles propres : le budget de styles du terminal est plein).
- `thread-item.ts` : `toThreadItem` sorti de `atelier.component.ts` (réexporté), pour éviter un import circulaire avec le volet.
- `AtelierComponent` :
  - le fil de l'écran normal ;
  - la décision du split (`lanes`), la mise en page en deux colonnes et les onglets sur téléphone.

## Plan de test

### Tests unitaires (Karma)
- [ ] `AtelierService` : `chantier` présent dans le corps et les paramètres quand il est donné, absent sinon.
- [ ] `TerminalLanePaneComponent` : charge l'historique du fil, se rebranche avec `chantier`, envoie dans son fil et interrompt son fil.
- [ ] `AtelierComponent` : `splitFor(lanes, shown, dismissed)` (pur) décide le split, le volet droit et le nombre d'autres ; le volet fermé ne revient pas.
- [ ] `AtelierTerminalComponent` : en-tête de volet et `closePane`.

### Tests d'intégration
- [ ] Backend : `GET /journey?chantier=N` rend la vue du chantier N, focus persisté intact ; Bob → `404`.

### Isolation utilisateur
- [x] Applicable côté serveur (déjà couvert par SF-221-01/02 + le test `journey?chantier`).

## Préoccupations transversales
- **Navigation** : aucune route nouvelle. Le split est un état de l'écran du sujet. Liens vérifiés (ils ouvrent le sujet, le split se décide à l'arrivée) :
  - tableau du client ;
  - cloche ;
  - mosaïque ;
  - « Ouvrir le terminal ».

## Dépendances
- SF-221-01, SF-221-02 (mergées avant le merge de celle-ci).

## Notes et décisions
- **Le volet droit réutilise `app-atelier-terminal` entier.** On ne fait pas de vue allégée : un volet est un vrai terminal, comme le demande le PO. Son état de tour vit dans `TerminalLanePaneComponent`, qui est un pilote réduit : historique, direct, envoi, interruption, autorisations, questions.
- Les fonctions de l'écran principal qui ne concernent pas un fil sont laissées à l'écran principal : dépôt de fichiers, git, explorateur et réglage d'exécution.

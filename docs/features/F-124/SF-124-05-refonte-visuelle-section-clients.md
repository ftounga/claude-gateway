# Mini-spec — [F-124 / SF-124-05] Refonte visuelle de la section clients de la Forge (maquette A « Vitrine »)

> Subfeature FRONTEND — restyle/réorganisation de présentation. Aucune nouvelle logique métier.

---

## Identifiant

`F-124 / SF-124-05`

## Feature parente

`F-124` — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-124-05-refonte-visuelle`

---

## Objectif

> En une phrase : mettre en valeur la section clients de la Forge en présentant le revenu déjà exposé
> par F-124 (`GET /api/activity/revenue`) sous la forme validée par le PO — un **bandeau « fierté »**
> (total tous clients) et une **grille de grandes cartes clients** — sans changer aucun calcul, endpoint
> ni donnée.

---

## Comportement attendu

### Cas nominal

1. Depuis la Forge (`/forge`), l'utilisateur ouvre **« Mes clients »** (nouveau lien de l'en-tête) → `/forge/clients`.
2. L'écran lit **les données réelles** déjà exposées : `GET /api/activity/revenue` (total + par poste),
   `GET /api/activity/settings` (mois de départ), et la liste des postes (`GET /api/runner-hosts/overview`)
   pour le **nom** et l'**état de présence** (F-97) de chaque client.
3. **Bandeau « fierté »** (fond navy `--cg-primary`, dégradé) : le **total tous clients en très gros, en or**
   (`--cg-accent-2` sur navy, AA vérifié), l'accroche « Revenu cumulé · depuis <mois de départ> · HT », et
   des puces : nombre de clients, jours cumulés, « dont X € estimés » (affichée seulement si une part est supposée).
4. **Grille responsive de grandes cartes** (une par poste ayant un TJM, donc présent dans `revenue.postes`) :
   liseré or à gauche, ombre douce, survol léger ; chaque carte montre la **pastille d'identité + le nom**,
   l'**état de présence daté** (F-97 : « En ligne · vu il y a … » / « Hors ligne · vu il y a … » / « Jamais connecté »),
   le **revenu cumulé en TRÈS GROS**, les **jours travaillés** (« X jours travaillés », et « · dont Y estimés »
   quand une part est supposée), puis en pied le **TJM valorisé** et un **badge** « Déclaré » (§5 succès) /
   « Partiellement estimé » (§5 attente).
5. Le bouton **« Déclarer mon CRA »** (déjà livré SF-124-03) est présent, bien placé, et ouvre le dialogue CRA
   existant ; à la fermeture avec écriture, le cumul est relu.

> **Jours travaillés = donnée dérivée d'affichage, pas un nouveau calcul.** Le serveur a produit
> `cumulCents = jours × tjmCents` (SF-124-02). L'écran **retrouve** les jours par `cumulCents / tjmCents`
> (arrondi au demi-jour) ; il ne recompte **jamais** les jours ouvrés/fériés. Aucune règle de calcul serveur
> n'est touchée.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| **Aucun TJM réglé nulle part** (`revenue.postes` vide, total 0) | État vide sobre : « Aucun revenu à afficher », invitation à régler un TJM dans la Forge + bouton retour. Aucune carte, aucun crash. | — |
| **Poste hors ligne** | La carte s'affiche quand même : revenu affiché, pastille/état « Hors ligne · vu il y a … » (jamais masquée). | — |
| **Poste avec revenu mais TJM = 0** (garde anti-division) | Jours non affichés (« — »), pas de `NaN`. La carte reste lisible. | — |
| **`GET /api/activity/revenue` échoue** (réseau / 403 hors droit Forge) | Message sobre « Le revenu n'a pas pu être lu » + bouton « Réessayer » + retour Forge. Aucun crash, aucune donnée inventée. | 4xx/5xx |
| **Poste présent dans `revenue` mais introuvable dans l'overview** (course : poste supprimé) | Carte ignorée (on ne nomme jamais un client qu'on ne connaît pas). | — |

---

## Critères d'acceptation

- [ ] La route `/forge/clients` rend le bandeau « fierté » avec le **total réel** (`revenue.totalCents`) en or sur navy.
- [ ] L'accroche cite le **mois de départ réel** (`settings.startMonth`) en toutes lettres (« depuis septembre 2025 »).
- [ ] Une **carte par poste** de `revenue.postes`, avec nom réel, revenu cumulé réel (`cumulCents`), TJM réel (`tjmCents`).
- [ ] Chaque carte affiche l'**état de présence daté** issu de `HostPresenceService` (F-97), jamais « Connecté » seul.
- [ ] Le badge est **« Déclaré »** quand `supposedCents === 0`, **« Partiellement estimé »** quand `supposedCents > 0`.
- [ ] La puce « dont X € estimés » et la mention « · dont Y estimés » n'apparaissent **que** si une part est supposée.
- [ ] Le bouton **« Déclarer mon CRA »** ouvre `CraDialogComponent` et relit le cumul après écriture.
- [ ] **Responsive** : la grille passe à **une colonne** sous ~520 px ; le bandeau reste lisible.
- [ ] **Accessibilité AA** : or sur navy (bandeau) **et** or-encre sur blanc (cartes) passent — vérifié par test de contraste.
- [ ] **Charte** : aucun ton hors `--cg-*` ; l'ajout de la police display et du jeton or-encre est déclaré dans `DESIGN_SYSTEM.md`.
- [ ] La pastille d'identité passe par **`app-host-badge`** (§9), jamais recomposée à la main.
- [ ] `revenue` n'est **jamais** filtré/recalculé côté client ; aucun endpoint ni calcul serveur modifié.

---

## Périmètre

### Hors scope (explicite)

- **Aucune nouvelle logique de calcul** (déclaré/supposé, jours ouvrés, cumul, total restent ceux du serveur SF-124-02).
- Aucun nouvel endpoint, aucune migration, aucune table.
- Pas de modification du dialogue CRA (SF-124-03/04) ni des réglages TJM/mois de départ (ils restent dans la Forge).
- Pas de refonte de la colonne maître–détail de la Forge (F-98) : elle reste inchangée ; la Vitrine est un écran distinct.
- Pas d'export, pas de facture, pas de TVA (F-124 = suivi seulement).

---

## Décisions de présentation (charte / divergences maquette assumées)

| Sujet | Maquette A | Décision (et pourquoi) |
|-------|-----------|------------------------|
| Avatar client | carré à dégradé navy + initiale | **`app-host-badge`** (§9 : composant unique, contraste prouvé, jamais recomposé). |
| Statut | « Connecté » / « Hors ligne » nus | **État daté F-97** (« En ligne · vu il y a … ») : le statut date, il n'affirme pas (§16). |
| Total du bandeau | blanc, € en or | **En or** (`--cg-accent-2`), comme demandé par le PO ; AA-large sur navy vérifié. |
| Police display des chiffres | Fraunces (Google Fonts) | **Ajout charte contrôlé** : `--cg-font-display: 'Fraunces', Georgia, serif`, **bornée aux chiffres monétaires**, déclarée dans `DESIGN_SYSTEM.md` §19, fallback serif. |
| Or sur blanc (€ des cartes) | `--gold` clair (~2,6:1, échoue AA) | **Jeton approfondi `--cg-gold-ink`** (or-encre AA sur blanc), à la manière de `--cg-terminal-teams-warn` ; déclaré dans `DESIGN_SYSTEM.md`. |
| Ordre des cartes | libre | **Revenu décroissant** (fierté : le plus gros d'abord), tie-break par nom. |

---

## Technique

### Endpoint(s)

Aucun créé/modifié. **Lecture seule** de l'existant : `GET /api/activity/revenue`, `GET /api/activity/settings`,
`GET /api/runner-hosts/overview`. Isolation `user_id` garantie côté gateway (le jeton porte l'identité, aucun id dans l'appel).

### Composants Angular

- **NOUVEAU** `MesClientsComponent` (`frontend/src/app/forge-clients/`) — écran autonome `/forge/clients` (patron `forge/voir`).
- **NOUVEAU** helper pur `client-showcase.ts` — dérivation jours, libellé de mois, assemblage des cartes/bandeau (testé sans DOM).
- **Réutilisés** : `PosteBillingService`, `AtelierService.runnerHostsOverview`, `HostPresenceService`, `HostBadgeComponent`, `CraDialogComponent`, `money.ts`.
- **Édités** : `app.routes.ts` (segment réservé `clients` + route), `postes.component.html` (lien « Mes clients »), `index.html` (police Fraunces), `styles.scss` (jetons `--cg-font-display`, `--cg-gold-ink`), `docs/DESIGN_SYSTEM.md`.

### Migration Liquibase

- [x] Non applicable.

---

## Préoccupations transversales

### NAVIGATION / routing (déclenchée — liste d'impact obligatoire)

| Chemin | Impact | Vérification |
|--------|--------|--------------|
| `/forge/clients` (nouveau) | Nouveau segment réservé `clients` ajouté à `FORGE_RESERVED_SEGMENTS` + nouvelle route lazy. | Test route/rendu du composant. |
| `/forge` et `/forge/:hostRef` (F-98) | Le matcher exclut `clients` comme il exclut déjà `voir` ; aucune référence de poste ne vaut `clients` (UUID ou `heberge`). | `forgeMatcher` inchangé fonctionnellement ; segment réservé couvre la collision. |
| `/forge/voir`, redirections `mosaique`/`supervision` | Aucune modification. | Non-régression (suites existantes vertes). |
| Lien « Mes clients » (en-tête Forge) → `/forge/clients` | Nouveau point d'entrée. | Présent et navigable. |
| Cartes clients → `/forge/:hostRef` | Chaque carte est une entrée vers le poste dans le maître–détail. | Navigation vérifiée en test. |
| Retour → `/forge` | Lien retour explicite. | Présent. |

Aucune autre préoccupation transversale (Auth/Principal, Contexte tenant, Plans/limites) n'est touchée :
la Vitrine est en lecture seule et ne change ni l'auth, ni la résolution du tenant, ni un quota/gate.

---

## Plan de test

### Tests unitaires (helper pur `client-showcase.ts`)

- [ ] `monthLabel('2025-09')` → « septembre 2025 » ; entrée invalide → repli sûr (pas de « Invalid Date »).
- [ ] `derivedDays(1_430_000, 65_000)` → 22 ; `derivedDays(1_258_000, 68_000)` → 18,5 ; `derivedDays(x, 0)` → `null`.
- [ ] `buildShowcase` : une carte par poste, tri revenu décroissant, `estimated` vrai ssi `supposedCents > 0`,
      total et jours cumulés cohérents, poste sans nom (introuvable) ignoré.

### Tests de composant (`mes-clients.component.spec.ts`)

- [ ] Nominal : bandeau (total en or, mois de départ), N cartes réelles, badge Déclaré/Partiellement estimé.
- [ ] Poste hors ligne → carte présente avec état daté « Hors ligne ».
- [ ] `revenue.postes` vide → état vide (aucune carte), pas de crash.
- [ ] `revenue()` en erreur → message + bouton Réessayer, pas de crash.
- [ ] Le bouton « Déclarer mon CRA » ouvre `CraDialogComponent`.
- [ ] Carte cliquée → navigation `/forge/:hostRef`.

### Test de contraste AA (`client-showcase.spec.ts`)

- [ ] `contrast(--cg-accent-2, --cg-primary) ≥ 3` (or sur navy, texte display large).
- [ ] `contrast(--cg-gold-ink, #FFFFFF) ≥ 4.5` (or-encre sur blanc, texte normal).
- [ ] Auto-vérification de la mesure sur les extrêmes connus (noir/blanc = 21, blanc/blanc = 1).

### Build

- [ ] `npm test` ciblé (specs ci-dessus) vert.
- [ ] `ng build` vert.

### Isolation utilisateur

- [x] Non applicable côté écran (lecture seule) — l'isolation `user_id` est garantie et testée côté gateway (SF-124-02).

---

## Dépendances

### Subfeatures bloquantes

- `SF-124-02` — done (cumul & total, `GET /activity/revenue`).
- `SF-124-03` — done (dialogue CRA).
- `F-98` — done (Forge maître–détail, patron d'écran `forge/voir`).

### Questions ouvertes impactées

- Aucune (`OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- La Vitrine est un **écran de présentation** des données F-124, distinct du maître–détail F-98 : zéro régression sur la Forge opérationnelle.
- **Provider-First / Gateway-First** non concernés : purement frontend, lecture d'endpoints existants.
- Les deux ajouts charte (police display, jeton or-encre) sont **contrôlés, bornés et documentés** ; signalés en PR.

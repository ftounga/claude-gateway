# Mini-spec — [F-132 / SF-132-06] Le « Journal du runner » repliable en accordéon

## Identifiant

`F-132 / SF-132-06`

## Feature parente

`F-132` — Observabilité du runner (journal de diagnostic)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-132-06-journal-runner-accordeon`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre le panneau « Journal du runner » (SF-132-03) **repliable en accordéon, replié par défaut**, pour qu'il ne prenne plus toute la hauteur de la carte du poste, en gardant titre + un indice (compteur d'événements / pire niveau) visibles une fois replié.

---

## Comportement attendu

### Cas nominal

1. À l'affichage d'un poste dans la Vigie, le panneau « Journal du runner » est **replié** (contenu masqué).
2. L'en-tête reste visible : titre « Journal du runner » + un chevron + un indice court (nombre d'événements chargés, et le pire niveau présent sous forme de badge de charte).
3. Le chargement du journal a lieu comme avant (au binding du `hostId`), qu'il soit replié ou déplié : l'indice replié est donc juste.
4. L'utilisateur clique (ou active au clavier) l'en-tête → le panneau se déplie et révèle le contenu **inchangé** : bouton « Activer le DEBUG », filtre de niveau, recherche, bouton « Rafraîchir », liste des événements (déjà bornée en hauteur + scroll, SF-132-03).
5. Un nouveau clic / clavier replie le panneau. L'état ouvert/fermé est annoncé nativement aux lecteurs d'écran (`<details>`/`<summary>`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le journal est indisponible (appel en échec) | Une fois déplié, l'état d'échec discret existant (`.diag__error`) s'affiche ; l'indice replié n'affiche pas de compteur. Le repli/déplié fonctionne quand même. |
| Aucun événement de diagnostic | Une fois déplié, l'état vide existant (`.diag__empty`) s'affiche ; l'indice replié dit « Aucun événement ». |
| Chargement en cours | L'indice replié reste neutre (pas de compteur tant que non chargé) ; le contenu déplié montre le spinner existant. |

---

## Critères d'acceptation

- [ ] Le panneau « Journal du runner » est **replié par défaut** (aucun contenu visible à l'ouverture du poste).
- [ ] L'en-tête (titre + indice) reste visible en position repliée.
- [ ] Un clic sur l'en-tête déplie/replie le panneau ; l'action est aussi réalisable au clavier (Entrée/Espace via `<summary>` natif).
- [ ] L'état ouvert/fermé est exposé à l'accessibilité (sémantique `<details>`/`<summary>`).
- [ ] Une fois déplié, le contenu (DEBUG, filtre niveau, recherche, rafraîchir, liste) est **identique** à SF-132-03 et fonctionne (filtres, DEBUG, recherche, refresh).
- [ ] L'indice replié affiche le nombre d'événements chargés et, s'il y en a, un badge du pire niveau (couleurs de charte `.badge--*`).
- [ ] Aucune couleur hors charte ; jetons `--cg-*` ; espacements multiples de 4px.
- [ ] `ng build` vert (budgets respectés) ; specs Vigie/journal existantes vertes.

---

## Périmètre

### Hors scope (explicite)

- Aucune modification backend, runner, endpoint, modèle ou migration (frontend pur).
- Aucun changement au contenu du journal (filtres, DEBUG, recherche, format des lignes) hors le réagencement en accordéon.
- Aucun changement à la liste des réunions (traité par SF-128-15).
- Pas de persistance de l'état ouvert/fermé (repli par défaut à chaque affichage — cohérent charte « repli fermé au départ »).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées |
|-------|-------------|----------------------------|
| État initial du panneau | Oui | replié (`<details>` sans `open`) |
| Badge d'indice | Non | classes existantes `.badge--error/--warning/--info/--neutral` uniquement |

---

## Technique

### Endpoint(s)

Aucun (frontend pur — le composant consomme les endpoints existants SF-132-02/05, inchangés).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable

### Composants Angular

- `RunnerDiagJournalComponent` (`frontend/src/app/vigie/runner-diag-journal/`) — HTML enveloppé dans un `<details class="diag">` / `<summary>` ; TS enrichi d'un `computed` pour le pire niveau présent ; SCSS pour l'en-tête cliquable + rotation du chevron.

---

## Plan de test

### Tests unitaires (composant, Karma/Jasmine)

- [ ] `RunnerDiagJournalComponent` — le `<details>` est **replié par défaut** (`.open === false`).
- [ ] Déplié (`details.open = true`), le contenu (`.diag__content`, liste, filtres) est présent et les lignes se rendent.
- [ ] L'en-tête est un `<summary>` (support clavier natif) et affiche le titre.
- [ ] L'indice replié affiche le compteur d'événements et un badge du pire niveau quand des événements sont chargés.
- [ ] Non-régression : les tests existants SF-132-03 (chargement, filtre niveau, recherche, DEBUG remis/non joignable, état vide, échec discret) restent verts.

### Tests d'intégration

- Non applicable (composant frontend isolé, pas de nouvel appel HTTP ; les appels existants restent couverts par les specs SF-132-03).

### Isolation workspace / `user_id`

- [ ] Non applicable — aucune donnée nouvelle ni nouvel accès ; l'isolation `user_id`+`host_id` reste garantie côté endpoints existants (SF-132-02), non modifiés.

---

## Préoccupations transversales

- **Auth / Principal** : non concernée (aucun changement d'auth).
- **Contexte tenant** : non concernée (aucun changement de résolution du tenant ; endpoints inchangés).
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée (aucune route ajoutée/modifiée ; le composant reste au même emplacement dans `vigie.component.html`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-132-03` — statut : done (panneau existant, réutilisé).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Patron d'accordéon réutilisé** : `<details>`/`<summary>` natif, déjà employé dans `governance.component.html` (`.gouvernance__files`). Choisi pour l'accessibilité clavier et l'annonce ouvert/fermé natives, sans dépendance Material supplémentaire, conforme à la charte (« repli fermé au départ », DESIGN_SYSTEM §10/§ Missions clôturées).
- **Choix de découpage** : deux SF distinctes (SF-132-06 accordéon journal / SF-128-15 liste réunions défilante) car les deux changements portent sur deux features parentes et deux composants indépendants ; les regrouper conflictuerait avec la règle multi-features de CLAUDE.md et brouillerait l'étape 6 (mise à jour du statut de la feature parente).

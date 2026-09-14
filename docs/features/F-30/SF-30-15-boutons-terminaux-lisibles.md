# SF-30-15 — Les boutons des terminaux sont lisibles

> Cadrage de correctif issu du constat de SF-89-07 (PR #573, décision D5) et du reliquat
> `docs/features/RELIQUATS-2026-09-14.md`. **Cadrage validé par le PO** ; livraison lancée le
> 2026-09-14.

## Constat (SF-89-07, D5)

> « Les boutons Material sans encre propre (« Saisir un code d'accès », « Racheter des tokens »,
> « Tout autoriser… ») prenaient la couleur primaire du thème (2,6:1) et le sélecteur de cible
> l'encre des écrans clairs (1,0:1). Sous la peau Teams seulement, leurs jetons Material sont
> redéfinis sur la surface. **Même défaut probable dans les autres terminaux : hors périmètre,
> signalé comme risque résiduel.** »

Ce risque résiduel est ici réparé. Sur le fond sombre des terminaux **de projet**, **de poste**
(runner) et des **tuiles de mosaïque** (lecture seule), les composants Angular Material sans encre
propre héritent des inks des **écrans clairs** : le libellé d'un bouton texte prend la couleur
primaire du thème (~2,6:1 sur le navy), le sélecteur `mat-button-toggle` « Où s'exécutent les
outils » prend l'encre foncée des écrans clairs (~1,0:1), les boutons cerclés et les boutons-icônes
de même. Le bloc « Courriel envoyé » (`app-terminal-email`), transparent, hérite quant à lui de
l'encre foncée du corps de page (`--cg-text-primary`) et devient illisible sur le fond du terminal.

SF-89-07 n'a corrigé cela **que sous la peau Teams** (`.terminal-view--teams`). Cette subfeature
corrige **partout ailleurs**, avec les **jetons de la charte déjà définis** (§2, §13) — aucune
couleur nouvelle —, **sans toucher aux écrans clairs** ni **à la peau Teams** (SF-89-09 la refait
juste après).

---

## Identifiant

`F-30 / SF-30-15`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-30-15-boutons-terminaux-lisibles`

---

## Objectif

> Rendre lisibles, sur le fond sombre des terminaux non-Teams (projet, poste, tuiles de mosaïque),
> tous les composants Angular Material et le bloc « Courriel envoyé », avec les jetons de charte
> déjà définis, sans toucher aux écrans clairs ni à la peau Teams.

---

## Comportement attendu

### Cas nominal

Sur un terminal **non-Teams** (fond `--cg-primary` `#1A3A5C` pour un terminal ouvert, `--cg-navy-2`
`#141D33` pour une tuile de mosaïque en lecture seule), les inks Material par défaut, calibrés pour
les écrans clairs, sont redéfinis sur des jetons de charte clairs, lisibles sur le navy :

| Élément | Avant (défaut Material / héritage) | Après (jeton de charte) |
|---|---|---|
| Bouton texte (`mat-button` : Quitter, Fichiers, Publier, Réinitialiser, Instructions, **Préciser/Envoyer**, Réessayer runner…) | couleur primaire du thème ~2,6:1 | `--mdc-text-button-label-text-color` = `--cg-divider` (9,1:1) |
| Bouton texte « gestes » orange (Interrompre, Afficher tout, Racheter des tokens, Préciser/Envoyer) | ~2,6:1 | `--cg-orange-2` (5,0:1) — l'accent d'action de la charte (§2) rendu enfin visible |
| Bouton cerclé (`mat-stroked-button` : Voir le poste, Ouvrir la Forge, Saisir un code d'accès, Tout autoriser…, Refuser, Réessayer) | libellé ~2,6:1, filet des écrans clairs | libellé `--cg-surface`, filet `--cg-divider` |
| Bouton-icône (`mat-icon-button` : actualiser / journal runner, copier la commande) | icône des écrans clairs | `--mdc-icon-button-icon-color` = `--cg-divider` |
| Coupe-circuit runner (`color="warn"`, geste d'arrêt) | rouge illisible sur navy (~2,3:1) | `--cg-orange-2` (5,0:1) — cohérent avec « Interrompre » (§2, geste) |
| Sélecteur `mat-button-toggle` « Où s'exécutent les outils » | ~1,0:1 | texte `--cg-divider`, sélectionné `--cg-surface` sur `--cg-navy-2`, filet `--cg-divider` |
| Bloc « Courriel envoyé » (`app-terminal-email`) | hérite `--cg-text-primary` (foncé) ~1,3:1 | texte `--cg-divider` ; l'échec reste dit par son **filet gauche rouge** + l'icône + le libellé « — non remis… » |

Les boutons **pleins** de marque (`mat-flat`/`mat-raised` `color="primary"` : Autoriser, Connecter un
poste, Ouvrir une page…) ne changent pas : ils portent déjà l'or de la charte (`--cg-accent` sur
blanc) via `styles.scss`.

Le bloc « **Page publiée** » (`app-page-block`) et le panneau de page (`app-page-panel`) sont des
**îlots blancs** posés sur le terminal sombre : la redéfinition des inks Material se propage par
héritage de variables CSS jusqu'à eux et rendrait leurs boutons blanc-sur-blanc. Ils **réinitialisent
donc** ces jetons Material sur leur propre surface claire (encre foncée `--cg-primary` /
`--cg-text-secondary`). Leur apparence est **strictement inchangée**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal **Teams** (`.terminal-view--teams`) | **Aucun changement** : la peau Teams (SF-89-07) garde ses jetons `--cg-terminal-teams-*` — spécificité `.terminal-view.terminal-view--teams` supérieure, et les nouvelles règles sont explicitement bornées par `:not(.terminal-view--teams)`. | — |
| Écran clair (hors terminal) | **Aucun changement** : les jetons ne sont posés que sous `.terminal-view:not(.terminal-view--teams)`. | — |
| Bloc « Courriel envoyé » en échec (`data-status='FAILED'`) | Filet gauche rouge + icône + libellé « — non remis : … » (jamais la couleur du texte seule). | — |
| Îlot blanc (page publiée / panneau) dans le terminal | Boutons et icônes inchangés grâce au reset local des jetons Material. | — |

---

## Critères d'acceptation

- [ ] CA1 — Dans un terminal **non-Teams chargé** (barre, cible d'exécution, état du poste, bandeaux,
  demande d'autorisation, bloc courriel), **chaque libellé de bouton, chaque texte de bouton-toggle,
  chaque bouton-icône et le bloc courriel** ont un contraste ≥ 4,5:1 (≥ 3:1 pour une icône ou un
  grand texte) contre leur fond calculé — vérifié par un test Karma sur les **couleurs calculées**.
- [ ] CA2 — Le même test **échoue avant** la correction (au moins le `mat-button-toggle` ~1,0:1, les
  boutons texte ~2,6:1 et le bloc courriel).
- [ ] CA3 — Le sélecteur `mat-button-toggle` « Où s'exécutent les outils » : texte lisible, état
  sélectionné distinct et lisible.
- [ ] CA4 — Le bloc « Courriel envoyé » est lisible (états `PENDING`/`SENT`/`FAILED`) sur le fond du
  terminal ; l'échec reste signalé autrement que par la seule couleur.
- [ ] CA5 — **Aucun autre écran ne change** : la peau Teams garde `--cg-terminal-teams-*` (tests
  `terminal-teams-peau`, `terminal-markdown-lisible`, `mosaique`, `terminal-lecture-seule` verts
  sans modification de leurs attentes) ; le fond des terminaux (`--cg-primary`, `--cg-navy-2`,
  surface Teams) est inchangé.
- [ ] CA6 — Les îlots blancs (page publiée, panneau de page) gardent des boutons/icônes lisibles
  (encre foncée sur blanc), non affectés par la cascade.
- [ ] CA7 — Aucune couleur hors charte : seuls des jetons `--cg-*` **déjà définis** (§2) sont
  employés ; aucune nouvelle variable de palette. `DESIGN_SYSTEM.md` §13 le note.
- [ ] CA8 — `ng build` passe (budgets de style par composant compris).

---

## Périmètre

### Hors scope (explicite)

- **La peau Teams** (`.terminal-view--teams`, jetons `--cg-terminal-teams-*`) : intouchée
  (SF-89-09 la refait juste après).
- **Les écrans clairs** : aucun changement hors du terminal.
- **La mécanique** des terminaux, des blocs ou des outils.
- **Les écarts de charte préexistants sur fond sombre non nommés par le constat** : le texte
  secondaire `--cg-text-secondary` (~2,7:1 sur `--cg-primary`) des lignes d'aide / coût / compteurs,
  et le rouge `--cg-error` / vert `--cg-success` des lignes de diff et du résultat de publication,
  restent en l'état — **les corriger imposerait des jetons de palette nouveaux** (variantes claires
  de rouge/vert/gris, comme la charte l'a fait pour Teams avec `--cg-terminal-teams-*`), or cette
  subfeature s'interdit toute couleur hors charte. **Signalé comme risque résiduel** (voir Notes).

---

## Valeurs initiales

Non applicable — aucune entité, aucun état persistant.

## Contraintes de validation

Non applicable — aucun champ saisi.

---

## Technique

### Endpoint(s)

Aucun. Subfeature **frontend uniquement** (feuilles de style et test).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `atelier-terminal.component.scss` — redéfinition des jetons Material sous
  `.terminal-view:not(.terminal-view--teams)` (boutons texte / cerclés / icônes, `mat-button-toggle`) ;
  jeton `--cg-orange-2` restauré sur les boutons « gestes » (Interrompre, Afficher tout, Racheter,
  Préciser/Envoyer) et le coupe-circuit runner.
- `terminal-email.component.ts` — encre par défaut du bloc sur `--cg-divider` (lisible sur navy) ;
  l'échec cesse de reposer sur la couleur du texte (filet + icône + libellé).
- `page-block.component.ts` — reset local des jetons Material (îlot blanc).
- `page-panel.component.ts` — reset local du jeton bouton-icône (îlot blanc).
- `boutons-terminaux-lisibles.spec.ts` (nouveau) — balayage AA des couleurs calculées.
- `docs/DESIGN_SYSTEM.md` §13 — note sur les inks Material du terminal sombre.

### Préoccupations transversales

- [ ] Auth / Principal — non concerné.
- [ ] Contexte tenant — non concerné (aucun accès données).
- [ ] Plans / limites — non concerné.
- [x] **Navigation / routing — non concerné**, mais **cascade de variables CSS** : la redéfinition
  des jetons Material sous `.terminal-view` se propage par héritage à **tous** les descendants,
  **îlots blancs compris**. Composants impactés listés et traités : `app-page-block` (boutons
  Ouvrir/Plein écran) et `app-page-panel` (boutons-icônes) — reset local ; `app-terminal-email`
  (traité directement) ; `teams-card` (exclu par `:not(--teams)`). Vérifié : aucun autre composant
  enfant du terminal ne porte de contrôle Material sur surface claire.

---

## Plan de test

### Tests unitaires (Karma, styles globaux chargés)

- [ ] `boutons-terminaux-lisibles.spec.ts` (nouveau) : un terminal **non-Teams** rendu chargé (barre,
  cible d'exécution + toggle, état du poste + boutons-icônes + copie, bandeaux avec boutons cerclés,
  demande d'autorisation avec Autoriser/Tout autoriser/Refuser, bloc courriel SENT et FAILED,
  bloc page publiée) ; balayage du DOM → **chaque libellé de bouton / texte de toggle / bouton-icône
  et le bloc courriel** ≥ 4,5:1 (icônes/grand texte ≥ 3:1) sur fond calculé. Assertion « échoue
  avant » : les jetons Material par défaut donnent < 4,5:1 (démontré par une mesure des jetons
  calculés). Contrôle : un terminal **Teams** garde sa surface `#231A36` et reste vert ; les îlots
  blancs gardent une encre foncée lisible.
- [ ] Non-régression : `terminal-teams-peau`, `terminal-markdown-lisible`, `terminal-lecture-seule`,
  `mosaique`, `terminal-email` restent verts sans modification de leurs attentes.

### Tests d'intégration

- [ ] Non applicable — aucune route, aucun backend.

### Isolation utilisateur

- [ ] Non applicable — aucun accès données (styles + test de rendu).

---

## Dépendances

### Subfeatures bloquantes

- SF-89-07 — done (PR #573) : source du constat, référence des jetons Material.
- SF-30-14 — done (PR #561) : Markdown du terminal (contexte, non modifié).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Bornage `:not(.terminal-view--teams)`.** La peau Teams garde ses jetons `--cg-terminal-teams-*`
  par une spécificité supérieure ; le bornage explicite rend l'intention lisible et protège de tout
  chevauchement quand SF-89-09 refera la peau Teams.
- **D2 — Cascade des variables CSS jusqu'aux îlots blancs.** Les jetons Material posés sur
  `.terminal-view` se propagent par héritage aux composants enfants, y compris les surfaces claires
  (`app-page-block`, `app-page-panel`). Sans reset local, leurs boutons deviendraient blanc-sur-blanc.
  Le reset est posé sur l'îlot lui-même.
- **D3 — Coupe-circuit runner (`color="warn"`).** Le rouge de warn tombe à ~2,3:1 sur le navy ; comme
  il n'existe pas de rouge clair dans la charte et que cette subfeature s'interdit d'en créer, le
  geste d'arrêt prend `--cg-orange-2` — l'accent que le terminal emploie déjà pour l'autre geste
  d'arrêt, « Interrompre » (§2). Lisible et cohérent, sans couleur nouvelle.
- **D4 — Écarts de charte préexistants (risque résiduel).** `--cg-text-secondary` (~2,7:1 sur
  `--cg-primary`) et `--cg-error`/`--cg-success` (rouge/vert de diff, ~2,3:1 / ~4,1:1) sur le fond
  sombre restent en l'état : leur correction demande des **jetons de palette nouveaux** (variantes
  claires), hors de portée d'un correctif « jetons déjà définis ». À traiter par un ajout de charte
  dédié, comme l'a fait SF-89-07 pour Teams (`--cg-terminal-teams-muted`/`-error`). Le test AA de
  cette subfeature porte donc sur les éléments **nommés par le constat** (boutons, toggle, courriel),
  pas sur ces textes-là.
- **D5 — Pas de nouveau jeton, pas de migration, pas de backend.** Correctif de charte pur.

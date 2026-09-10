# Cadrage — F-56 — Passe visuelle sur les écrans

> Cadrage de la feature `F-56` (`docs/PRODUCT_SPEC.md`, ligne 166). Écrit le 2026-09-10, après la
> livraison de F-55 et F-57 — les deux dernières features de la semaine à toucher un écran.

---

## 1. Ce que la feature est, et ce qu'elle n'est pas

Sept features livrées en une semaine ont produit des écrans neufs. Chacun a été relu **seul**,
contre sa propre mini-spec. Aucun n'a été relu **avec les autres**. F-56 est cette relecture-là :
une **revue de cohérence** contre `docs/DESIGN_SYSTEM.md`.

**Ce n'est pas une refonte.** La règle tenue de bout en bout :

- aucun parcours ne change — pas un clic de plus, pas un clic de moins ;
- aucune fonction n'est ajoutée ni retirée ;
- aucun test existant n'est réécrit pour s'adapter à une correction ;
- la charte **fait autorité** : quand un écran et `DESIGN_SYSTEM.md` divergent, c'est l'écran
  qui a tort.

## 2. Périmètre

**Les écrans nés cette semaine, par ordre de priorité :**

| Écran | Feature |
|---|---|
| Dialogue d'appairage devenu parcours guidé | F-45 / SF-45-05 |
| Vue d'ensemble des postes | F-49 / SF-49-02 |
| Catalogue de gouvernance (utilisateur + admin) | F-51 / SF-51-05, SF-51-06 |
| Guide d'accueil de l'Atelier | F-53 |
| Bulle d'aide | F-54 / SF-54-02 |
| Assistant proxy | F-55 |
| Rappel de transparence | F-57 / SF-57-03 |

**Les sept points de contrôle** : couleurs, typographie, espacements, **états vides**, états de
chargement, messages d'erreur, écran étroit.

**Hors périmètre** (rappel de `PRODUCT_SPEC.md`) :

- **la charte elle-même** — elle fait autorité, F-56 ne la modifie pas, même là où elle se
  contredit (voir §4, divergence D1) ;
- **la landing publique** — elle a ses propres variables SCSS de marque, alignées en F-27/F-29 ;
- le **backend** : aucune ligne. F-56 est intégralement frontend.

## 3. Ce que la revue a trouvé

Sept divergences, dont **une visible à l'œil nu**.

| # | Écran | Divergence | Gravité |
|---|---|---|---|
| 1 | Postes (F-49) | Les pastilles de statut emploient des classes `cg-badge*` qui **n'existent nulle part**. `DESIGN_SYSTEM.md` §5 définit `.badge` / `.badge--success` / `--warning` / `--neutral`, et c'est ce que tous les autres écrans emploient. Les quatre pastilles de l'écran — *Connecté*, *Hors ligne*, *Administrateur*, *Actif* — s'affichent donc en **texte nu**, sans fond, sans couleur, sans forme | **visible** |
| 2 | Gouvernance (F-51), aperçu de dépôt (F-51) | `font-family: 'JetBrains Mono', monospace` en dur là où le jeton `--cg-font-mono` existe | latente |
| 3 | Bulle d'aide (F-54) | Espacements en pixels bruts, dont des `12px` **hors de l'échelle** autorisée (`DESIGN_SYSTEM.md` §6 : 4 / 8 / 16 / 24 / 32 / 48 / 64). L'en-tête du fichier revendique pourtant la conformité | latente |
| 4 | Catalogue de gouvernance (F-51) | `minmax(320px, 1fr)` : sous **368 px** de large, la carte est plus large que sa colonne et **la page défile horizontalement** | **écran étroit** |
| 5 | Assistant proxy (F-55) | Ouvert **par-dessus** le parcours d'appairage, il est plus large que lui (720 px contre 560 px). Son propre en-tête de feuille de style dit : *« deux grammaires superposées se liraient comme deux produits »* | visible |
| 6 | Appairage (F-45) | Le message d'erreur de génération de code n'est pas annoncé (`role="alert"`) là où celui de rattachement l'est, sur le même écran | latente (a11y) |
| 7 | Rappel de transparence (F-57) | Un `margin-left: 4px` en dur au lieu de `var(--cg-space-1)` | latente |

## 4. Ce que la revue a trouvé et **ne corrige pas**

| # | Constat | Pourquoi on n'y touche pas |
|---|---|---|
| D1 | `DESIGN_SYSTEM.md` se contredit : son titre annonce la charte legalcase (`navy #1A3A5C` / `or #C9973A`), sa table §2 liste encore l'ancienne (`#0B1020` / `#E07B39`). Les jetons `--cg-*` de `styles.scss` suivent le titre | **La charte est hors périmètre** (`PRODUCT_SPEC.md`). Les écrans emploient les jetons, donc la bonne valeur : la contradiction est documentaire, pas visuelle. À porter en question ouverte |
| D2 | Largeur maximale des écrans de gestion : 1080 px (billing, gouvernance, postes), 1100 px (admin), 1280 px (réglages) — la charte §4 dit 1280 px | Divergence **antérieure** à la semaine et **majoritaire à 1080 px**. L'aligner demanderait de toucher les réglages et l'admin, hors des « écrans nés cette semaine ». À trancher pour tous en une fois, pas au fil de l'eau |
| D3 | Rayon des panneaux flottants : 12 px (guide, rappel, bulle d'aide) là où la charte §5 dit 8 px pour les **cartes** | Les trois panneaux nés cette semaine sont **cohérents entre eux**, et la charte ne légifère pas sur les panneaux flottants. Les aligner sur 8 px serait un choix, pas une correction |
| D4 | Admin gouvernance emploie `mat-progress-bar` au chargement, les écrans utilisateur `mat-spinner` | Deux contextes différents (contenu déjà encarté / page entière), les deux conformes à Material. Uniformiser relèverait du goût |
| D5 | `mat-spinner` et `mat-progress-spinner` coexistent dans le dialogue d'appairage | **Le même composant**, deux alias Angular Material. Aucune différence à l'écran |
| D6 | `color: #fff` en dur sur les blocs de commande à fond navy | Le blanc n'a pas de jeton de rôle dans la charte (`--cg-surface` est un rôle de *surface*, pas de texte). Le remplacer serait un contresens sémantique |

## 5. Décisions de cadrage

| # | Sujet | Décision | Réversible |
|---|---|---|---|
| 1 | Portée des corrections | **Cosmétique uniquement** : classes CSS, jetons, unités d'espacement, une largeur de dialogue, un attribut ARIA. Aucune logique de composant modifiée, aucun appel réseau, aucun état | oui |
| 2 | Pastilles des postes | On **renomme les classes** vers `.badge` global plutôt que de définir `.cg-badge` localement : la charte a un nom pour cet objet, en inventer un second serait la divergence | oui |
| 3 | Jeton mono | Corrigé **partout** où le littéral traîne, y compris sur deux écrans antérieurs (chat, réglages) : le défaut est le même, en laisser deux derrière n'aurait aucun sens. Le jeton résout vers la même police — rien ne change à l'écran | oui |
| 4 | Espacements de la bulle d'aide | `12px` → `var(--cg-space-3)` (16 px) pour les marges de bloc, `var(--cg-space-2)` (8 px) pour les gouttières d'icône. L'écart visuel est de 4 px, l'échelle redevient celle de la charte | oui |
| 5 | Largeur de l'assistant proxy | Alignée sur les **560 px** du parcours qu'il recouvre. Les commandes longues sont déjà en `pre-wrap` / `break-all` : elles se replient, elles ne débordent pas | oui |
| 6 | Tests | On **ajoute**, on ne réécrit pas. Les tests existants doivent passer **tels quels** — s'ils cassaient, la correction serait devenue une refonte | non — c'est la garantie du « aucun parcours ne change » |

## 6. Découpage

Une seule subfeature — le travail est cosmétique, sans schéma, sans API, et tient largement sous
les deux jours.

| SF | Objet |
|---|---|
| `SF-56-01` | La passe de cohérence : les sept corrections du §3, plus les tests qui les tiennent |

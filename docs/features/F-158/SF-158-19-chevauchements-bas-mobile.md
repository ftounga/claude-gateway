# Mini-spec — [F-158 / SF-158-19] Bas d'écran mobile : la chip « Note poste » et le bouton d'aide « ? » ne masquent plus le fil ni le composeur

> Correctif P0 mobile. Pur frontend, style seul, display-only. Desktop (≥ 820 px) inchangé.

---

## Identifiant

`F-158 / SF-158-19`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

> Rattachement : F-158 est le foyer des correctifs P0 « terminal inutilisable sur téléphone ».
> SF-158-17 a rendu le guide (F-53) et le rappel poste (F-57) **repliés** en chips sur téléphone,
> mais la chip repliée « Note poste » (`app-workstation-notice`, `position:fixed` bas-gauche) et le
> bouton d'aide flottant « ? » (`app-help-chat-widget`, `position:fixed` bas-droite) **chevauchent**
> encore le bas du fil et le composeur ancré (SF-158-13). Même ligne P0.

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-19-chevauchements-bas-mobile`

---

## Objectif

> En une phrase : sur téléphone (≤ 819 px), dégager le bas de l'écran du terminal pour que la chip
> repliée « Note poste » (bas-gauche) et le bouton d'aide « ? » (bas-droite) ne recouvrent plus ni
> les dernières lignes du fil ni le composeur — sans rien changer au desktop.

---

## Comportement attendu

### Cas nominal

Constat (capture réelle 390 px) : les deux surfaces `position:fixed` sont ancrées au bas du viewport
(`bottom: var(--cg-space-4)` = 24 px). Le composeur du terminal (`.terminal-input`, `sticky bottom:0`
sur ~104 px + `safe-area`) occupe tout le bas ; la chip et le « ? » **passent par-dessus** le champ de
saisie et les derniers messages du fil (`.terminal-scrollback`).

Après correctif, sous ≤ 819 px :

- **La chip repliée « Note poste »** (bas-gauche) est **remontée au-dessus du composeur** : son
  `bottom` passe à une **clairance composeur** (`calc(112px + env(safe-area-inset-bottom))`, > hauteur
  du composeur) au lieu de 24 px. Elle ne recouvre plus le champ de saisie ni les icônes joindre/dicter.
- **Le bouton d'aide « ? »** (bas-droite, `app-help-chat-widget`) est **remonté** de la même clairance :
  son `bottom` passe à `calc(112px + env(safe-area-inset-bottom))`. Il ne recouvre plus le bouton
  Envoyer ni le champ.
- **Le fil ne masque plus rien** : `.terminal-scrollback` reçoit un `padding-bottom` +
  `scroll-padding-bottom` de dégagement (`calc(var(--cg-space-6) + var(--cg-space-4))` = 72 px) pour
  que ses dernières lignes puissent défiler **au-dessus** des deux éléments fixes remontés (qui
  flottent alors sur cette bande de dégagement, jamais sur du texte).
- **Chip et « ? » ne se chevauchent pas entre eux** : ils restent sur des **côtés opposés**
  (bas-gauche vs bas-droite), à la même hauteur mais sans recouvrement horizontal, y compris à 320 px.
- **Desktop (≥ 820 px)** : rigoureusement inchangé — toutes les règles ci-dessus sont sous
  `@media (max-width: 819px)` / `@include bp.phone`. En dehors du point de rupture, `bottom` et
  paddings gardent leurs valeurs actuelles.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `env(safe-area-inset-bottom)` non supporté par le navigateur | `env()` vaut 0 par défaut : la clairance reste `112px` / `72px`, dégagement conservé. |
| Rappel poste non dû (`visible()` faux) | Inchangé : ni chip ni bandeau ; le `padding-bottom` du fil reste inoffensif (bande vide en bas). |
| Notice déployée (tap sur la chip) | Hors scope de ce correctif : le bandeau déployé reste géré par SF-158-17 (< 480 px : pleine largeur, transitoire, invoqué par l'utilisateur). |
| Bouton « ? » sur un écran mobile sans composeur ancré (admin, rapports) | La remontée globale du widget le place ~112 px au-dessus du bas : il ne recouvre aucun contenu de bas de page (comportement plus sûr, jamais cassé). |

---

## Critères d'acceptation

- [ ] Sur ≤ 819 px, la chip repliée « Note poste » a un `bottom` de clairance composeur (≈ 112 px +
      `safe-area`) et ne recouvre plus le composeur (champ + icônes).
- [ ] Sur ≤ 819 px, le bouton d'aide « ? » a un `bottom` de clairance composeur (≈ 112 px +
      `safe-area`) et ne recouvre plus le composeur (Envoyer + champ).
- [ ] Sur ≤ 819 px, `.terminal-scrollback` porte un `padding-bottom` (et `scroll-padding-bottom`) de
      dégagement ≥ 64 px : les dernières lignes du fil défilent au-dessus des éléments fixes.
- [ ] La chip et le « ? » ne se chevauchent pas entre eux (côtés opposés), y compris à 320 px.
- [ ] Desktop (≥ 820 px) : `bottom` de la notice et du widget d'aide inchangés (24 px), aucun
      `padding-bottom` de dégagement ajouté au fil — garde-fou CSSOM.
- [ ] Cibles tactiles ≥ 44 px conservées (chip et « ? » non réduits sous 44 px).
- [ ] DESIGN_SYSTEM : jetons `--cg-*` pour les espacements, aucune couleur/police nouvelle, point de
      rupture unique 819 px, pas de scroll horizontal.
- [ ] `npm run build` vert (budget 12 ko/feuille tenu) ; Karma ciblé (notice + help-widget + contenu
      terminal) vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) — rigoureusement inchangé.
- Le comportement de la notice **déployée** (tap) et du guide (SF-158-17) — non touchés.
- Toute logique métier, tout service (`WorkstationNoticeService`, `HelpChatService`), tout endpoint,
  toute migration, tout DTO, tout changement de DOM/TS.
- Toute couleur ou police nouvelle.
- La coquille (F-151), la PWA (F-152), les notifications (F-153).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend, style seul.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular / feuilles impactées (style seul)

- `frontend/src/app/atelier/notice/workstation-notice.component.scss` — sous `@include bp.phone`,
  `bottom` de la chip repliée (`.notice--collapsed`) remonté à la clairance composeur.
- `frontend/src/app/help/help-chat-widget/help-chat-widget.component.scss` — nouveau bloc
  `@media (max-width: 819px)` : `bottom` du `.help-widget` remonté à la clairance composeur.
- `frontend/src/app/atelier/terminal/atelier-terminal-mobile.component.scss` — `.terminal-scrollback`
  reçoit `padding-bottom` + `scroll-padding-bottom` de dégagement.

Aucun template ni fichier `.ts` modifié.

---

## Plan de test

### Tests unitaires / garde-fous CSSOM (indépendants du viewport, patron SF-158-09)

- [ ] `workstation-notice` — sous 819 px, `.notice--collapsed` porte un `bottom` de clairance (calc
      contenant `112px` + `env(safe-area-inset-bottom)`).
- [ ] `help-chat-widget` — sous 819 px, `.help-widget` porte un `bottom` de clairance (calc contenant
      `112px` + `env(safe-area-inset-bottom)`) ; garde-fou non-régression : hors media, `bottom`
      reste `var(--cg-space-4)`.
- [ ] `atelier-terminal` (contenu responsive) — sous 819 px, `.terminal-scrollback` porte un
      `padding-bottom` de dégagement ; non-régression : `overflow-x: hidden` conservé.

### Tests d'intégration

- [ ] Non applicable (pur frontend, style seul — pas d'endpoint). Couverture par garde-fous CSSOM.

### Isolation utilisateur / workspace

- [ ] Non applicable — aucune donnée, aucun accès serveur (style CSS pur).

---

## Dépendances

### Subfeatures bloquantes

- `SF-158-17` — Done (chips repliées guide/notice) : cette SF corrige le positionnement de la chip
  qu'elle a introduite.
- `SF-158-13` — Done (composeur ancré `sticky bottom`) : définit la hauteur de composeur à dégager.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés / vérification |
|---------------|-------------|------------------------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | **Oui (léger)** | Le `.help-widget` est global (`shell.component.html`) : la remontée mobile s'applique à **tous** les écrans ≤ 819 px, pas seulement `/atelier/:id`. Vérifié : sur les écrans à composeur bas (chat), la remontée est bénéfique (le « ? » ne recouvre plus le composeur) ; sur les écrans sans composeur (admin, rapports, gouvernance), le « ? » flotte ~112 px au-dessus du bas — jamais cassé, ne recouvre aucun contenu. Aucun changement de route, de guard ni de redirection. |

---

## Notes et décisions

- **Clairance composeur = `calc(112px + env(safe-area-inset-bottom))`** : le composeur mobile
  (`.terminal-input`, SF-158-13) mesure ≈ 104 px (padding + champ + gap + rangée d'icônes ≥ 44 px) +
  `safe-area`. 112 px laisse ~8 px de jour au-dessus. Valeur en px (multiple de 4) + `env()` : c'est
  une **clairance de layout** pour surface fixe, pas un espacement de la grille — les jetons `--cg-*`
  restent employés pour tout espacement ; aucune couleur/police nouvelle (charte respectée).
- **Dégagement du fil = `calc(var(--cg-space-6) + var(--cg-space-4))` = 72 px** : couvre la hauteur du
  plus grand élément fixe remonté (« ? », mat-fab ≈ 56 px) au-dessus du bord bas du fil, avec marge.
- **Valeurs répétées (clairance) plutôt que jeton global** : choix de limiter le rayon d'impact aux
  3 feuilles dédiées mobiles (pas de modification de `styles.scss` global) ; chaque occurrence est
  commentée et référence SF-158-19.

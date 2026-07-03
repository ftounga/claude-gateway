# Mini-spec — [F-12 / SF-12-03] Refonte landing + identité visuelle (logo/favicon)

## Identifiant — Parente — Statut

`F-12 / SF-12-03` — `F-12` Landing / onboarding — `ready`

## Date — Branche

2026-07-03 — `feat/SF-12-03-refonte-landing-logo`

## Objectif

Refondre la landing publique (`/`) dans un univers visuel plus soigné, autour du **logo officiel**
« Claude Proxy », et faire de ce logo l'**identité de l'application** (favicon du navigateur + marque).

## Comportement attendu

- Landing redessinée : hero **navy/orange** (palette dérivée du logo) avec le logo, accroche, tagline
  « Unrestricted AI. No limits. », CTA (essai / connexion), bénéfices en cartes, étapes, footer.
- Le logo `claude-proxy-logo.png` (dans `public/`) apparaît en **favicon** (onglet navigateur) et sur la
  landing (nav, hero, footer). Titre de l'onglet et marque applicative : **« Claude Proxy »**.
- Comportement inchangé : CTA adaptés selon `AuthService.isAuthenticated` (essai/connexion vs ouvrir le chat).

## Critères d'acceptation

- [ ] Le logo est le **favicon** (`<link rel="icon" type="image/png" href="claude-proxy-logo.png">`) et le titre de l'onglet est « Claude Proxy ».
- [ ] La landing affiche le logo (hero) et une mise en page soignée (hero navy/orange, cartes bénéfices, étapes).
- [ ] Les liens/CTA restent corrects selon l'état d'authentification (non-régression du spec landing).
- [ ] Le logo est bien inclus dans le build (`public/` → racine dist).
- [ ] Palette de marque documentée dans `DESIGN_SYSTEM.md` ; l'UI applicative conserve la charte `--cg-*`.

## Périmètre / Hors scope

- **Hors scope** : refonte visuelle des écrans applicatifs (chat, billing, etc.) — l'UI garde la charte `--cg-*` ; seule la landing adopte la marque. Recadrage du logo pour un favicon net (le PNG complet sert de favicon).

## Technique

- `public/claude-proxy-logo.png` (asset). `index.html` : favicon PNG + `<title>`. `landing.component.html/scss`
  (refonte). `shell.component.html` : marque « Claude Proxy ». `DESIGN_SYSTEM.md` : logo + palette de marque.

## Plan de test

- [ ] `LandingComponent` (spec existant) : liens/CTA selon auth + nombre de bénéfices — **non-régression** (inchangé).
- [ ] Build : logo présent dans `dist/…/claude-proxy-logo.png`. 179 specs verts.

## Notes

- Le hero adopte la palette du logo (navy/orange) car le PNG a un fond navy : le hero navy fait « fondre » le logo sans halo. L'UI applicative reste sur la charte indigo (pas de rebrand global sans décision).

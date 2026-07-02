# Mini-spec — [F-19 / SF-19-01] Coquille applicative & navigation

## Identifiant

`F-19 / SF-19-01`

## Feature parente

`F-19` — Coquille applicative & navigation

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-19-01-coquille-navigation`

---

## Objectif

Fournir une **coquille de navigation** persistante (barre supérieure + menu compte) qui enveloppe toutes
les pages authentifiées, expose les liens vers les écrans existants (Chat, Documents, Q&A, Templates,
Rapports, Facturation, Réglages, Profil) et un **bouton Déconnexion** — aujourd'hui aucun de ces accès
n'est visible (les pages existent mais sont orphelines, sans chrome).

## Contexte

Le dossier `layout/shell/` est vide : chaque route authentifiée est un composant isolé, sans header ni
navigation. Les features F-09 (billing), F-10/F-16 (usage/reports), F-11 (settings), le logout (F-01)
existent mais sont **inaccessibles depuis l'UI**. Cette SF livre la coquille qui les expose.

---

## Comportement attendu

### Cas nominal

- Toutes les pages authentifiées (`/chat`, `/documents`, `/ask`, `/templates`, `/reports`, `/billing`,
  `/settings`, `/profile`) sont rendues **dans** une coquille : barre supérieure (fond `--cg-primary`,
  conforme au design system) avec la marque et les liens de navigation principaux, plus un **menu compte**
  (à droite) donnant accès à Rapports, Facturation, Réglages, Profil et **Déconnexion**.
- Le lien de la section courante est visuellement **actif** (`routerLinkActive`).
- **Déconnexion** : appelle `AuthService.logout()` (POST `/api/me/logout`, purge du token) puis redirige
  vers `/login`.
- Les pages **publiques** (landing `/`, `/login`, `/register`, `/auth/**`) et l'**onboarding** ne sont
  **pas** enveloppées (flux dédiés, sans navigation).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Échec réseau du logout | Le token est purgé localement quand même (défensif), redirection `/login` ; erreur non bloquante |
| Accès à une route enfant sans token | `authGuard` (parent) redirige vers `/login` avant rendu de la coquille |

---

## Critères d'acceptation

- [ ] Une barre de navigation persistante est affichée sur toutes les pages authentifiées listées, avec un lien par section et un bouton/menu **Déconnexion**.
- [ ] Le lien de la route active est marqué visuellement.
- [ ] « Déconnexion » appelle `AuthService.logout()` puis navigue vers `/login`.
- [ ] Les pages publiques (`/`, `/login`, `/register`, `/auth/**`) et `/onboarding` ne portent **pas** la coquille.
- [ ] La coquille n'introduit aucune couleur/police hors `DESIGN_SYSTEM.md` (barre fond `--cg-primary`, texte blanc, `--cg-*`).
- [ ] Les routes authentifiées restent aux **mêmes URLs** (`/chat`, `/billing`, …) — refactor transparent.

---

## Périmètre

### Hors scope (explicite)

- Lien/écran **Admin** (F-20, conditionné au rôle) — la coquille prévoira l'emplacement mais l'entrée admin est livrée avec F-20.
- Refonte visuelle globale (charte, chantier C).
- Responsive avancé (drawer mobile) : une barre simple suffit en V1 de la coquille ; le drawer pourra suivre.

---

## Préoccupation transversale — Navigation / routing (analyse d'impact)

Introduit une **route parente** (coquille) englobant les routes authentifiées. Chemins existants recensés
et préservés :

| Route | Impact | Vérification |
|-------|--------|--------------|
| `/chat`, `/documents`, `/ask`, `/templates`, `/reports`, `/billing`, `/settings`, `/profile` | Deviennent **enfants** de la route coquille (mêmes URLs, `authGuard` porté par le parent) | Navigation directe vers chaque URL rend la page dans la coquille |
| `/` (landing), `/login`, `/register`, `/auth/**` | **Inchangées** (publiques, hors coquille) | Rendues sans navigation |
| `/onboarding` | **Inchangée** (authed mais hors coquille) | Flux onboarding sans nav |
| `authGuard` | Appliqué au parent (au lieu de chaque route) ; comportement identique | Accès sans token → `/login` |
| `**` → `` | Inchangé | Redirection racine |

---

## Technique

### Composants Angular

- `layout/shell/shell.component.ts` (standalone) — `mat-toolbar` (fond `--cg-primary`) : marque + liens
  (`routerLink`/`routerLinkActive`) vers Chat/Documents/Q&A/Templates ; `mat-menu` compte à droite
  (Rapports, Facturation, Réglages, Profil, Déconnexion) ; `<router-outlet>` pour les enfants.
  `logout()` → `AuthService.logout()` puis `Router.navigate(['/login'])`.
- `app.routes.ts` — route parente pathless `{ path: '', component: ShellComponent, canActivate: [authGuard],
  children: [...pages authentifiées...] }`, placée après la landing et les routes publiques.

### Endpoints / Tables

- Aucun (frontend ; consomme `AuthService.logout` existant → `/api/me/logout`).

### Migration Liquibase

- [x] Non applicable.

---

## Plan de test

### Tests de composant (Jasmine)

- [ ] `ShellComponent` — rend les liens de navigation attendus et un contrôle de déconnexion.
- [ ] `ShellComponent` — « Déconnexion » appelle `AuthService.logout()` et navigue vers `/login` (services mockés).

### Isolation utilisateur

- [x] Non applicable (aucun accès données ; l'isolation reste portée par le backend et l'`authGuard`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-06` (logout backend/`AuthService`) — Done. Écrans cibles (F-09/F-11/F-16) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Option la plus complète retenue** : coquille persistante avec toolbar + menu compte, plutôt qu'un simple bouton logout isolé — expose d'un coup **toute** la surface existante (billing, reports, settings, profil).
- **Route parente pathless** : conserve les URLs actuelles et centralise l'`authGuard`.
- **Emplacement admin réservé** : l'entrée « Administration » (conditionnée au rôle) sera ajoutée avec F-20 pour éviter un lien mort.

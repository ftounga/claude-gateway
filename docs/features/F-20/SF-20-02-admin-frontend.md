# Mini-spec — [F-20 / SF-20-02] Écran admin & lien conditionnel — frontend

## Identifiant — Feature parente — Statut

`F-20 / SF-20-02` — `F-20` Console admin & super-admin — `ready`

## Date — Branche

2026-07-03 — `feat/SF-20-02-admin-frontend`

---

## Objectif

Fournir l'écran d'administration (`/admin`) listant les utilisateurs avec leur abonnement et leur
consommation, et n'exposer le lien « Administration » qu'aux administrateurs.

## Comportement attendu

- `/admin` (sous la coquille F-19, `authGuard`) affiche une **table paginée** (`mat-table` + `mat-paginator`)
  des utilisateurs : e-mail, rôle, plan, statut d'abonnement, tokens, date d'inscription — via
  `GET /api/admin/users` (SF-20-01).
- Le lien « Administration » (menu compte) n'apparaît **que si** l'utilisateur courant est ADMIN
  (`AuthService.isAdmin`, dérivé du claim `role` du JWT). Un non-admin qui force `/admin` reçoit **403**
  de l'API et un toast d'erreur.

### Cas d'erreur

| Situation | Comportement |
|-----------|--------------|
| API 403 (non-admin ayant forcé la route) | Toast d'erreur, table vide |
| Échec réseau | Toast d'erreur, `loading=false` |

---

## Critères d'acceptation

- [ ] `/admin` charge et affiche les utilisateurs dans une table **paginée**.
- [ ] Le lien « Administration » n'est visible que pour un utilisateur ADMIN (`isAdmin`).
- [ ] `AuthService.isAdmin` dérive `role === 'ADMIN'` du claim JWT (décodage local, affichage seulement).
- [ ] Échec API → toast d'erreur, pas de crash.
- [ ] Design system respecté (`mat-table`, `mat-paginator`, palette `--cg-*`).

## Périmètre / Hors scope

- Actions admin (édition abonnement, suspension) — ultérieur. Filtres/recherche — ultérieur.

## Préoccupation transversale — Navigation / routing

Nouvelle route enfant `/admin` sous la coquille (F-19) ; lien conditionnel dans le shell. Aucune autre
route modifiée ; `authGuard` inchangé ; l'autorisation réelle reste backend (403).

## Technique

- `admin/admin.service.ts` (`getUsers`), `admin/admin.models.ts` (`AdminUser`), `admin/admin.component.*`
  (table paginée). `app.routes.ts` : enfant `/admin`. `auth.service.ts` : `role`/`isAdmin` (décodage JWT).
  `shell.component.*` : entrée « Administration » conditionnée à `isAdmin`.

## Plan de test

- [ ] `AdminComponent` — charge et affiche les utilisateurs (service mocké).
- [ ] `AuthService.isAdmin` — vrai pour un JWT `role=ADMIN`, faux sinon.
- [ ] `ShellComponent` — spec mis à jour (spy `isAdmin`).

### Isolation

- [x] Non applicable (affichage ; autorisation backend gate l'API).

## Notes

- **Rôle via claim JWT** (décodage local) pour un lien nav instantané sans requête ; l'accès est de toute façon protégé côté serveur (403).

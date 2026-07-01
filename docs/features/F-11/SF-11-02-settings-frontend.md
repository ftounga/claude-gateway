# Mini-spec — [F-11 / SF-02] Écran Paramètres du compte & RGPD (frontend)

## Identifiant

`F-11 / SF-02`

## Feature parente

`F-11` — Settings & compte (réglages compte, export/suppression des données — RGPD)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-11-02-settings-frontend`

## Contrat importé

> Contrat API **importé de `SF-11-01-account-backend`** (figé) : `GET /api/account/export`,
> `DELETE /api/account`. Tests frontend sur **mock** du service (indépendants du backend mergé).

---

## Objectif

> Offrir un écran « Paramètres du compte » (`/settings`) permettant à l'utilisateur de consulter le
> récapitulatif de son compte, d'**exporter ses données** (téléchargement JSON) et de **supprimer
> définitivement son compte** via une confirmation destructive explicite.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié ouvre `/settings` (route protégée par `authGuard`).
2. L'écran affiche un récapitulatif du compte (e-mail, fournisseur, statut de vérification) obtenu
   via `GET /api/me` (service `AuthService` existant), et un lien vers `/profile` pour l'édition.
3. **Carte « Données personnelles (RGPD) »** :
   - Bouton **Exporter mes données** : appelle `GET /api/account/export`, déclenche le
     téléchargement d'un fichier `claude-gateway-export.json`. `MatSnackBar` de succès/erreur.
   - **Zone de danger** : bouton **Supprimer mon compte** (`color="warn"`) ouvrant un `MatDialog`
     de confirmation destructive exigeant la saisie de l'e-mail du compte (ou du mot « SUPPRIMER »)
     pour activer la confirmation.
4. À la confirmation, `DELETE /api/account` ; en cas de succès : purge du token, `MatSnackBar`,
   redirection vers `/login`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `GET /api/me` échoue | `MatSnackBar` d'erreur, récapitulatif non affiché |
| `GET /api/account/export` échoue | `MatSnackBar` d'erreur, pas de téléchargement |
| `DELETE /api/account` échoue | `MatSnackBar` d'erreur, l'utilisateur reste connecté |
| Saisie de confirmation ≠ e-mail attendu | Bouton de confirmation désactivé (pas d'appel) |
| Dialog annulé | Aucune action |

---

## Critères d'acceptation

- [ ] La route `/settings` est protégée par `authGuard` et déclarée en lazy-loading.
- [ ] L'écran affiche e-mail, fournisseur et statut de vérification du compte courant.
- [ ] « Exporter mes données » télécharge un fichier JSON issu de `GET /api/account/export`.
- [ ] « Supprimer mon compte » ouvre un `MatDialog` de confirmation ; la suppression n'est possible
      qu'après saisie correcte de l'e-mail du compte.
- [ ] Après suppression réussie : le token est purgé et l'utilisateur est redirigé vers `/login`.
- [ ] Aucune `window.alert/confirm/prompt` ; notifications via `MatSnackBar`, confirmation via `MatDialog`.
- [ ] Couleurs, polices et espacements conformes à `docs/DESIGN_SYSTEM.md` (palette `--cg-*`, multiples de 4px).
- [ ] Tests unitaires sur **mock** du service : export déclenche le téléchargement ; suppression
      appelle `DELETE` puis redirige ; confirmation invalide n'appelle pas `DELETE`.

---

## Périmètre

### Hors scope (explicite)

- Gestion de la clé BYOK (→ F-03).
- Édition e-mail / logout-all (déjà sur `/profile`, F-01) — l'écran s'y contente d'un lien.
- Toute logique de purge côté serveur (→ SF-11-01).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Confirmation de suppression | Doit être **strictement égale** à l'e-mail du compte (comparaison insensible à la casse/espaces) pour activer le bouton de confirmation. |

---

## Technique

### Composants / services Angular

| Élément | Type | Notes |
|---------|------|-------|
| `AccountService` | service (`core/services`) | `exportData(): Observable<AccountExport>`, `deleteAccount(): Observable<MessageResponse>` sur `/api/...` |
| `AccountExport` (+ sous-types) | modèle (`core/models`) | miroir du contrat figé SF-11-01 |
| `SettingsComponent` | composant (`settings/`) | route `/settings`, cartes récap + RGPD |
| `DeleteAccountDialogComponent` | composant dialog | confirmation destructive avec saisie e-mail |
| `app.routes.ts` | route | `/settings` lazy + `authGuard` |
| point de navigation | lien | accès à `/settings` depuis un point de navigation existant (`/profile`) |

### Endpoints consommés

| Méthode | URL | Source |
|---------|-----|--------|
| GET | `/api/me` | F-01 (existant) |
| GET | `/api/account/export` | SF-11-01 |
| DELETE | `/api/account` | SF-11-01 |

### Migration Liquibase

- [x] Non (frontend).

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|---------------|-----------|------------|
| **Navigation / routing** | Oui — **nouvelle route** `/settings` + lien depuis `/profile`. Chemins existants vérifiés : ajout non destructif, `authGuard` réutilisé, `{ path: '**' }` inchangé. Aucune redirection existante modifiée. |
| **Auth / Principal** | Non modifié — réutilise `AuthService` (token, `me()`), purge du token après suppression via `clearToken()` existant. |
| **Contexte tenant** | N/A côté client (isolation garantie backend via JWT). |
| **Plans / limites** | Non. |

---

## Plan de test

### Tests unitaires (`settings.component.spec.ts`, `account.service.spec.ts`)

- [ ] `AccountService.exportData` appelle `GET /api/account/export` (mock `HttpTestingController`).
- [ ] `AccountService.deleteAccount` appelle `DELETE /api/account`.
- [ ] Le composant charge le récap via `me()` (mock) et l'affiche.
- [ ] L'export déclenche la création d'un lien de téléchargement (spy sur l'utilitaire de download).
- [ ] La suppression confirmée appelle `deleteAccount`, purge le token et navigue vers `/login`.
- [ ] La confirmation avec un e-mail incorrect n'appelle pas `deleteAccount`.

### Isolation utilisateur

- [ ] N/A côté client (garantie backend). Le service n'envoie aucun `user_id` en paramètre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-11-01` (backend export/suppression) — contrat figé ; mergée avant le frontend.
- `F-01` (`AuthService`, `/api/me`, `authGuard`) — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Écran dédié `/settings`** distinct de `/profile` : `/profile` (F-01) reste centré édition
  e-mail + sessions ; `/settings` porte la gestion RGPD des données. Réversible (fusion possible).
- **Confirmation par saisie de l'e-mail** : garde-fou contre la suppression accidentelle d'une
  action irréversible, sans dépendre d'un champ serveur supplémentaire.
- **Téléchargement client** : le JSON reçu est sérialisé en `Blob` et téléchargé via un lien objet
  URL (pas d'appel serveur supplémentaire, pas de stockage).

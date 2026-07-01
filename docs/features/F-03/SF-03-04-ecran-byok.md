# Mini-spec — [F-03 / SF-03-04] Écran BYOK (frontend)

## Identifiant

`F-03 / SF-03-04`

## Feature parente

`F-03` — BYOK (Bring Your Own Key)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-03-04-ecran-byok`

---

## Objectif

> Offrir dans les Réglages une section « Clé API (BYOK) » permettant d'ajouter (champ masqué),
> consulter (last4 + validée le), supprimer sa clé et basculer entre Hosted et BYOK.

---

## Comportement attendu

### Cas nominal

- À l'ouverture des Réglages, le composant charge le statut via `GET /api/user/api-key`.
- **Ajout** : champ masqué (input `password`, `mat-form-field` outline) + bouton « Enregistrer » →
  `POST /api/user/api-key` → à succès : statut mis à jour (present, `sk-…last4`, mode BYOK), champ vidé,
  snackbar succès.
- **État** : si une clé existe → affichage `sk-…last4`, date de validation, badge du mode
  (BYOK/Hosted). Sinon → message « Aucune clé enregistrée ».
- **Bascule Hosted/BYOK** : `mat-slide-toggle` → `PUT /api/user/api-key/mode` `{mode}` ; désactivé si
  aucune clé (on ne peut activer BYOK sans clé).
- **Suppression** : bouton → **confirmation `MatDialog`** → `DELETE /api/user/api-key` → statut remis à
  « absente » (mode Hosted), snackbar succès.
- Aucune clé en clair n'est jamais affichée ni conservée côté client (seul le masqué transite).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Champ vide à l'enregistrement | `mat-error` « Clé requise », pas d'appel |
| 400 (clé invalide/refusée) | snackbar « Clé API invalide ou refusée par le fournisseur. » |
| 503 (BYOK indisponible) | snackbar « La gestion de clé est momentanément indisponible. » |
| Autre échec réseau | snackbar générique d'échec |
| Chargement du statut échoue | snackbar d'erreur, section en état neutre |

---

## Critères d'acceptation

- [ ] `ApiKeyService` consomme les 4 endpoints (`GET`/`POST`/`DELETE` `/api/user/api-key`, `PUT .../mode`).
- [ ] La section Réglages affiche l'état (present/absent, `sk-…last4`, validée le, mode).
- [ ] Ajout : champ **masqué**, validation « requis », succès → statut à jour + champ vidé.
- [ ] Suppression : **confirmation via `MatDialog`** avant l'appel `DELETE`.
- [ ] Bascule Hosted/BYOK via `mat-slide-toggle`, désactivée sans clé.
- [ ] Aucune clé en clair affichée/stockée côté client ; erreurs via `MatSnackBar` (pas d'`alert`).
- [ ] Conforme au design system (variables `--cg-*`, `mat-form-field` outline, espacements 4px).

---

## Périmètre

### Hors scope (explicite)

- Backend (endpoints livrés en SF-03-02/03).
- Rotation/expiration de clé, multi-provider.

---

## Contraintes de validation

| Champ | Obligatoire | Format | Normalisation |
|-------|-------------|--------|---------------|
| apiKey (input) | Oui (à l'ajout) | non vide | trim côté backend |
| mode (toggle) | — | HOSTED \| BYOK | — |

---

## Technique

### Composants Angular

- `ApiKeyService` (`core/services/api-key.service.ts`) + modèles `core/models/api-key.models.ts`
- Section « Clé API (BYOK) » ajoutée à `SettingsComponent` (`settings.component.ts/html/scss`)
- `RemoveApiKeyDialogComponent` (confirmation de suppression, `MatDialog`)

### Préoccupation transversale — Navigation/routing

- Aucune nouvelle route : la section vit dans `/settings` (route existante, `authGuard`). Chemins de
  navigation existants inchangés. Composant impacté : `SettingsComponent` (enrichi, non déplacé).

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [ ] `ApiKeyService` — `getStatus`/`saveKey`/`deleteKey`/`setMode` : bon verbe, bonne URL, bon corps
      (via `HttpTestingController`).
- [ ] `SettingsComponent` — charge le statut à l'init ; `saveApiKey` appelle le service et met à jour le
      statut ; champ vide → pas d'appel ; suppression confirmée → `deleteKey` appelé ; suppression annulée
      → pas d'appel ; `setMode` appelle le service.

### Isolation utilisateur

- [ ] Non applicable côté client — l'isolation `user_id` est garantie côté backend (JWT). Aucun `user_id`
      n'est transmis par le client.

---

## Dépendances

### Subfeatures bloquantes

- `SF-03-02` (endpoints clé) — **Done** (#48) ; `SF-03-03` (mode) — **Done** (#49).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Confirmation de suppression** : bien que réversible (re-saisie possible), la suppression d'une
  clé enregistrée est confirmée via `MatDialog` (conforme design system, pas d'`window.confirm`).
- **Mode dérivé** : le toggle reflète `status.mode` renvoyé par le backend ; il n'est activable que
  si une clé existe (sinon 409 côté backend, prévenu côté UI par `disabled`).

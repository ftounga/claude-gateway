# Mini-spec — [F-12 / SF-12-02] Onboarding 2 étapes (Hosted/BYOK)

## Identifiant

`F-12 / SF-12-02`

## Feature parente

`F-12` — Landing & onboarding (consultants)

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-12-02-onboarding`

---

## Objectif

Guider un nouvel utilisateur authentifié à travers un parcours en 2 étapes (bienvenue/compte puis
choix du mode fournisseur Hosted ou BYOK) qui l'oriente vers la bonne suite de la plateforme.

---

## Comportement attendu

### Cas nominal

- **Route `/onboarding`** (protégée par `authGuard`), affichée via un `mat-stepper` à 2 étapes.
- **Étape 1 — Votre compte** : message de bienvenue ; affiche l'e-mail du compte (via `GET /api/me`) ;
  rappelle de vérifier l'e-mail si non vérifié. Bouton « Continuer ».
- **Étape 2 — Votre mode d'accès** : deux cartes sélectionnables :
  - **Hosted** — « Utilisez la clé de la plateforme » → à la validation : mémorise le mode `HOSTED`,
    marque l'onboarding terminé, navigue vers `/chat`.
  - **BYOK** — « Utilisez votre propre clé Anthropic » → à la validation : mémorise le mode `BYOK`,
    marque l'onboarding terminé, affiche une info « la saisie de votre clé se fera dans les Réglages »,
    navigue vers `/billing` (choix d'un plan BYOK).
- **« Passer pour l'instant »** : marque l'onboarding terminé en mode `HOSTED` par défaut → `/chat`.
- **Redirection post-authentification** : après connexion (e-mail/mot de passe et OAuth Google),
  l'utilisateur est envoyé vers `/onboarding` **tant que l'onboarding n'est pas terminé**, sinon vers
  `/profile` (comportement historique préservé).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `GET /api/me` échoue à l'étape 1 | Message de bienvenue générique (sans e-mail), le parcours reste utilisable |
| Aucun mode sélectionné à l'étape 2 | Le bouton « Terminer » est désactivé ; aucune navigation |
| Accès à `/onboarding` sans JWT | `authGuard` redirige vers `/login` (comportement existant) |

---

## Critères d'acceptation

- [ ] `/onboarding` est protégée par `authGuard` et présente un parcours à 2 étapes.
- [ ] L'étape 1 affiche l'e-mail du compte courant (via `GET /api/me`) et un rappel de vérification si non vérifié.
- [ ] L'étape 2 permet de choisir Hosted ou BYOK ; « Terminer » est désactivé tant qu'aucun mode n'est choisi.
- [ ] Hosted → onboarding marqué terminé (mode `HOSTED`) + navigation `/chat`.
- [ ] BYOK → onboarding marqué terminé (mode `BYOK`) + info « clé dans les Réglages » + navigation `/billing`.
- [ ] « Passer pour l'instant » → onboarding terminé (`HOSTED`) + `/chat`.
- [ ] Après connexion (email + OAuth), l'utilisateur va vers `/onboarding` si non terminé, sinon `/profile`.
- [ ] Couleurs / polices / espacements conformes `DESIGN_SYSTEM.md` ; notifications via `MatSnackBar` ;
      aucun `window.alert/confirm/prompt`.
- [ ] Tests unitaires verts (`OnboardingService`, `OnboardingComponent`, non-régression login) ;
      `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- **Persistance serveur du mode fournisseur** : aucun endpoint V1 ne l'expose (voir Notes). La
  préférence est mémorisée côté client uniquement.
- **Gestion/validation de la clé BYOK** : c'est F-03 (non livrée). L'onboarding se contente d'orienter.
- Souscription/paiement Stripe : géré par F-09 (l'onboarding ne fait que router vers `/billing`).

---

## Valeurs initiales

| Champ (client, `localStorage` clé `cg_onboarding`) | Valeur initiale | Règle |
|-----|-----|-----|
| `completed` | `false` (absent) | passé à `true` à la validation ou au « passer » |
| `mode` | absent | `HOSTED` ou `BYOK` selon le choix (défaut `HOSTED` si « passer ») |

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs autorisées |
|-------|-------------|--------------------|
| mode (étape 2) | Oui pour « Terminer » | `HOSTED`, `BYOK` |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Consomme `GET /api/me` (F-01, existant). Aucune écriture serveur.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable (frontend, aucun schéma).

### Composants / services Angular (standalone)

- `onboarding/onboarding.component.ts|html|scss` — parcours `mat-stepper` 2 étapes.
- `core/services/onboarding.service.ts` — état d'onboarding en `localStorage` (`isCompleted`,
  `complete(mode)`, `providerMode`, `postLoginPath`).
- `app.routes.ts` : ajout `/onboarding` (lazy, `canActivate: [authGuard]`).
- **Modif transversale (navigation)** : `login.component.ts` et `oauth-callback.component.ts`
  redirigent via `OnboardingService.postLoginPath()` au lieu de `/profile` en dur.

### Préoccupation transversale — Navigation / routing (analyse d'impact)

| Chemin de navigation | Avant | Après | Non-régression |
|----------------------|-------|-------|----------------|
| Connexion e-mail réussie (`LoginComponent`) | `/profile` | `postLoginPath()` (`/onboarding` si non terminé, sinon `/profile`) | Spec `login.component.spec` mise à jour (2 cas) |
| Retour OAuth Google (`OauthCallbackComponent`) | `/profile` | `postLoginPath()` | Vérifié manuellement + logique déléguée au service testé |
| Nouvelle route `/onboarding` | — | protégée `authGuard` | `authGuard` inchangé (déjà testé) |
| Wildcard `**` | `/` | `/` | inchangé |

---

## Plan de test

### Tests unitaires (mock du service)

- [ ] `OnboardingService` — `isCompleted` faux par défaut ; `complete('HOSTED')` → `isCompleted` vrai
      et `providerMode`='HOSTED' ; `postLoginPath` = `/onboarding` puis `/profile` après complétion.
- [ ] `OnboardingComponent` — étape 1 charge l'e-mail (`AuthService.me` mocké) ; erreur me() gérée.
- [ ] `OnboardingComponent` — Hosted → `complete('HOSTED')` + navigate `/chat`.
- [ ] `OnboardingComponent` — BYOK → `complete('BYOK')` + navigate `/billing`.
- [ ] `OnboardingComponent` — « passer » → `complete('HOSTED')` + navigate `/chat`.
- [ ] `LoginComponent` (non-régression) — navigue via `postLoginPath()` (onboarding non terminé → `/onboarding`).

### Tests d'intégration

- [x] Non applicable (aucun endpoint backend créé/modifié).

### Isolation utilisateur

- [x] Non applicable côté front : l'onboarding n'affiche que le compte courant (via JWT). L'isolation
      reste garantie côté backend via `user_id`. Aucune donnée d'un autre utilisateur n'est manipulée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-12-01` (landing) — Done.
- `F-01` (`GET /api/me`, `authGuard`), `F-09` (`/billing`), `F-02` (`/chat`) — Done (cibles de routage).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Mode fournisseur non persisté côté serveur (arbitrage réversible)** : `PROJECT.md` §11.3 prévoit un
  « mode fournisseur » au profil, mais aucun endpoint V1 (F-01/F-09) ne l'expose. Créer une table/route
  pour cela sortirait du périmètre de F-12 (feature frontend) et empiéterait sur F-03. Décision :
  mémoriser la préférence en `localStorage` ; l'onboarding reste une couche de guidage. Réversible :
  quand F-03 exposera le mode, `OnboardingService` déléguera au backend sans changer les composants.
- **BYOK → `/billing` (arbitrage réversible)** : la gestion de clé (F-03) n'est pas livrée ; router vers
  l'abonnement (plans BYOK de F-09) avec une note évite toute dérive hors périmètre.
- **Redirection post-auth centralisée (arbitrage réversible)** : `login` et `oauth-callback` délèguent à
  `OnboardingService.postLoginPath()`. Non-régression assurée par les specs mises à jour.
</content>

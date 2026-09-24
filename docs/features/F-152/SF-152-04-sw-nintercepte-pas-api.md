# Mini-spec — F-152 / SF-152-04 — Le service worker ne doit PAS intercepter `/api` (correctif P0)

## Identifiant

`F-152 / SF-152-04`

## Feature parente

`F-152` — PWA installable (icône sur l'écran d'accueil)

## Statut

`ready`

## Date de création

2026-09-25

## Branche Git

`feat/SF-152-04-sw-api`

---

## Objectif

Exclure `/api/**` des `navigationUrls` du service worker (F-152) pour que les **navigations
pleine page vers `/api/...`** (démarrage OAuth Google et callback) atteignent le backend au lieu
d'être détournées vers `index.html` — **correctif P0 : « Continuer avec Google » cassé** sur tout
appareil ayant enregistré le service worker.

---

## Contexte / cause racine (P0)

`frontend/ngsw-config.json` (posé en SF-152-02) **ne définit aucun `navigationUrls`**. Angular
applique alors le défaut :

```
["/**", "!/**/*.*", "!/**/*__*", "!/**/*__*/**"]
```

Ce défaut **n'exclut pas `/api`**. Le service worker traite donc les **navigations pleine page**
vers `/api/...` comme des navigations d'application et sert `index.html` au lieu de laisser passer
la requête réseau. Conséquences :

- `login.component.ts:63` fait `window.location.href = this.authService.googleLoginUrl`
  (`/api/oauth2/authorization/google`, `auth.service.ts:40`) → **navigation `/api/...` interceptée
  → rien ne se passe** (Google login cassé, mobile ET desktop dès que le SW est actif).
- Le **callback** retour de Google `/api/login/oauth2/code/google` est aussi une navigation
  `/api/...` → également détournée.

> Rappel mémoire projet « ce qui part chez le client » : c'est le comportement **servi** qui
> compte ; on vérifie le `ngsw.json` **buildé**, pas seulement le fichier source.

---

## Comportement attendu

### Cas nominal

- **Après build de production**, le `ngsw.json` généré contient un `navigationUrls` qui **exclut
  `/api/**`** tout en conservant les défauts Angular.
- **Runtime** : une navigation pleine page vers `/api/oauth2/authorization/google` (clic
  « Continuer avec Google ») **n'est plus interceptée** par le service worker → la requête atteint
  le backend, qui redirige vers Google.
- Le **callback** `/api/login/oauth2/code/google` (navigation retour de Google) **n'est plus
  intercepté** non plus → le flux OAuth se termine normalement.
- Les navigations d'**application** légitimes (routes Angular : `/`, `/login`, `/atelier/...`,
  `/oauth-callback`, …) **continuent** d'être servies par `index.html` (aucune route Angular ne
  commence par `/api`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Navigateur sans service worker (dev, 1re visite, non supporté) | Aucun changement : les requêtes `/api/...` atteignent le backend nativement ; le correctif est sans effet et sans régression |
| Ancien service worker déjà installé (version précédente en cache) | Au prochain chargement stable, le SW récupère le nouveau `ngsw.json` (updateMode `prefetch`) et cesse d'intercepter `/api/**` |
| Requête XHR/`fetch` vers `/api/...` (intercepteur HTTP) | Inchangé : `navigationUrls` ne concerne **que** les requêtes de navigation ; les appels de données n'étaient déjà pas cachés (aucun `dataGroups`) |

---

## Critères d'acceptation

- [ ] `frontend/ngsw-config.json` définit `navigationUrls` incluant les 4 défauts Angular **et** l'exclusion `"!/api/**"`.
- [ ] Le `ngsw.json` **buildé** (`dist/frontend/browser/ngsw.json`) contient `!/api/**` (négation) dans `navigationUrls` — vérifié après `npm run build`.
- [ ] `npm run build` (production) est **vert**.
- [ ] Une garde/test échoue si `/api/**` n'est plus exclu des `navigationUrls` (protection anti-régression).
- [ ] Aucune route d'application Angular ne commence par `/api` (les navigations d'app restent servies par `index.html`) — vérifié.
- [ ] Aucun `dataGroups` ajouté (l'API/SSE ne sont toujours pas cachés — invariant de SF-152-02).

---

## Périmètre

### Hors scope (explicite)

- Type MIME du `manifest.webmanifest` côté nginx → **SF-152-03** (fichier `nginx`, sans recouvrement).
- Toute modification backend, migration, endpoint, règle cluster, protocole runner : **aucune**.
- Le cache des assets (coquille) : inchangé (SF-152-02).
- Les handlers `push`/`notificationclick` (F-153) : hors sujet.

---

## Contraintes de validation

| Réglage | Valeur | Règle |
|---------|--------|-------|
| `navigationUrls` | `["/**", "!/**/*.*", "!/**/*__*", "!/**/*__*/**", "!/api/**"]` | conserve les 4 défauts Angular + exclut explicitement `/api/**` |
| `dataGroups` | **absent** | invariant SF-152-02 : l'API/SSE ne sont jamais cachés |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable**.

### Fichiers impactés

- `frontend/ngsw-config.json` — ajout de `navigationUrls` avec `"!/api/**"`.
- `frontend/src/app/…` (test) — garde de non-régression vérifiant l'exclusion de `/api/**`
  dans `ngsw-config.json`.

### Composants Angular

Aucun composant ; configuration du service worker uniquement.

---

## Préoccupation transversale

**Auth / Navigation / routing** — le correctif touche le chemin d'authentification OAuth
(navigation pleine page). Composants impactés vérifiés :

| Composant | Vérification |
|-----------|--------------|
| `auth.service.ts` (`googleLoginUrl = '/api/oauth2/authorization/google'`) | navigation `/api/...` désormais **non interceptée** → atteint le backend ✔ |
| `login.component.ts:63` (`window.location.href = googleLoginUrl`) | flux OAuth démarre correctement ✔ |
| Callback backend `/api/login/oauth2/code/google` | navigation retour **non interceptée** ✔ |
| Routes Angular (`/`, `/login`, `/atelier/**`, `/oauth-callback`, …) | aucune ne commence par `/api` → navigations d'app toujours servies par `index.html` ✔ |
| Intercepteur HTTP (appels XHR/`fetch` `/api/...`) | non concerné par `navigationUrls` ; aucun `dataGroups` → inchangé ✔ |

---

## Plan de test

### Tests

- [ ] **Garde de non-régression** (unitaire, frontend) : lecture de `ngsw-config.json`, assert que `navigationUrls` existe et contient `"!/api/**"`. Échoue si l'exclusion disparaît.
- [ ] `npm run build` (production) **vert** ; `dist/frontend/browser/ngsw.json` présent.
- [ ] Vérif du **`ngsw.json` buildé** : `navigationUrls` contient bien la négation de `/api/**` (`grep` sur le fichier servi).
- [ ] Non-régression : suite frontend existante verte (aucun spir n'importe `app.config`).

> **Pourquoi une garde sur le fichier de config plutôt qu'un test du SW runtime** : le service
> worker n'est ni généré ni enregistré en environnement de test (`isDevMode()` vrai). Le bon
> comportement se vérifie par (1) la présence de l'exclusion dans la config source, protégée par
> une garde, et (2) sa présence dans le `ngsw.json` **buildé** (ce qui est réellement servi).

### Isolation workspace / user_id

- [x] Non applicable — configuration de build/runtime, aucun accès données.

---

## Dépendances

- **Bloquante amont** : SF-152-02 (service worker) — livrée. Ce correctif rectifie sa config.
- **Parallèle** : SF-152-03 (type MIME du manifest, fichier `nginx`) — fichiers disjoints, pas de
  conflit hormis PRODUCT_SPEC (garder les deux lignes).

---

## Notes et décisions

- **D-defaults-conservés** : on **ré-inscrit** les 4 défauts Angular dans `navigationUrls` (dès
  qu'on définit le tableau, il **remplace** le défaut implicite) puis on ajoute `"!/api/**"`. Ne
  pas définir seulement `"!/api/**"` casserait le fallback SPA des routes d'app.
- **D-api-only** : seule `/api/**` est exclue (c'est le préfixe unique des appels backend, cf.
  `auth.service.ts`, `runner-pairing-dialog.component.ts` `API_PREFIX`). `/atelier/...` est une
  **route Angular** (SPA) et doit rester servie par `index.html`.
- **P0** : correctif prioritaire — OAuth Google est cassé sur tout appareil ayant le SW.

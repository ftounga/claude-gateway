# SF-28-04 — Erreurs d'import lisibles (Atelier)

Feature parente : **F-28 — Atelier (Claude Code Lite)**
Type : Frontend (Angular) — préoccupation transversale : gestion d'erreur HTTP
Statut : En cours

## Objectif (une phrase)

Quand l'import d'un projet `.zip` échoue, l'utilisateur voit **la cause réelle et actionnable** (archive trop volumineuse, fichier interne trop gros, format invalide, quota) au lieu d'un message générique.

## Contexte / problème

- Le backend renvoie déjà un JSON structuré `{ "error": <code>, "message": <texte> }` (ex. `invalid_archive`, `validation_error`, `quota_exceeded`).
- Au-delà de la limite ingress, nginx renvoie un **413** HTML brut (non-JSON) que le `HttpClient` ne sait pas parser.
- Le composant Atelier ignore ces corps : `error: () => notifyError('L'import du projet a échoué…')` → l'utilisateur ne sait pas **pourquoi** ni **quoi faire**.

## Comportement nominal

1. L'utilisateur choisit un `.zip` trop volumineux ou invalide.
2. Le message affiché reflète la cause : message backend si présent ; message dédié pour 413 (trop volumineux) ; message dédié pour 0/typage.
3. Un **contrôle client** de la taille **avant envoi** évite un aller-retour inutile pour les archives manifestement hors limite (seuil aligné sur l'ingress, 150 Mo), avec un message qui **recommande d'exclure `node_modules/`, `.git/`, `target/`, `dist/`, `build/`**.

## Cas d'erreur (mapping)

| Situation | Statut / corps | Message utilisateur |
|---|---|---|
| Archive > seuil (client) | (pré-envoi) | « Archive trop volumineuse (X Mo, max 150 Mo). Excluez node_modules/, .git/, target/, dist/, build/ puis re-zippez. » |
| 413 (ingress) | 413, HTML | idem « trop volumineuse » (bornes serveur) |
| Fichier interne trop gros | 400 `invalid_archive` | message backend (« Un fichier de l'archive est trop volumineux. ») |
| Zip invalide / vide / mauvais type | 400 `validation_error` / `invalid_archive` | message backend |
| Quota | 402 `quota_exceeded` | message backend |
| Autre / réseau | 5xx / 0 | « L'import a échoué. Réessayez. » |

## Critères d'acceptation (vérifiables)

- CA1 : un `HttpErrorResponse` porteur d'un JSON `{error,message}` fait afficher `message` tel quel.
- CA2 : un statut **413** fait afficher le message « trop volumineuse » (jamais le HTML brut).
- CA3 : un `.zip` dépassant le seuil client **n'est pas envoyé** ; message immédiat avec la liste d'exclusions.
- CA4 : un statut 0 / 5xx sans corps exploitable retombe sur un message générique de repli.
- CA5 : la logique d'extraction est **factorisée** (utilitaire réutilisable) et couverte par des tests unitaires.

## Plan de test minimal

- **Unitaires (util `httpErrorMessage`)** : JSON `{message}` → message ; 413 → message taille ; corps HTML → repli ; statut 0 → repli ; absence de corps → repli.
- **Composant** : `onZipPicked` avec un faux fichier > seuil → `createWorkspace` **non appelé**, snackbar d'erreur ; erreur 413 simulée → message taille ; erreur `invalid_archive` simulée → message backend.
- **Isolation utilisateur** : inchangée (frontend ; isolation `user_id` reste 100 % backend). Aucun nouvel accès données.

## Composants / fichiers impactés

- `frontend/src/app/shared/http-error.util.ts` (nouveau) — extraction du message d'un `HttpErrorResponse`.
- `frontend/src/app/atelier/atelier.component.ts` — contrôle taille client + usage de l'utilitaire dans `onZipPicked` (et réutilisation pour les autres `notifyError`).
- Tests : `http-error.util.spec.ts` (nouveau), `atelier.component.spec.ts` (cas d'import).

## Analyse d'impact — préoccupation transversale (gestion d'erreur HTTP)

Composants susceptibles de réutiliser l'utilitaire (pas de régression, ajout seulement) : `atelier.component`, et par cohérence les écrans chat/billing pourront l'adopter **ultérieurement** (hors périmètre ici). Aucun changement de contrat backend. Aucun changement d'auth/tenant/quota.

## Hors périmètre

- Streaming du chat (SF-28-05), offre Gold / BYOK (SF-28-06).
- Refonte globale de la gestion d'erreur des autres écrans (adoption opportuniste plus tard).
- Modification des limites serveur (déjà traitées côté ops : ingress 155m + multipart/anti-zip-bomb).

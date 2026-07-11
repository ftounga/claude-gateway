# SF-28-03 — Écran « Atelier » (Claude Code Lite Phase 1)

## Objectif (une phrase)
Fournir un écran Angular `/atelier` où l'utilisateur téléverse un projet `.zip` et discute avec Claude qui lit/édite les fichiers du workspace, en réutilisant le backend F-28 (SF-28-01/02).

## Comportement nominal
- Liste des workspaces de l'utilisateur (`GET /api/workspaces`) avec sélection.
- « Nouveau projet » : input file `.zip` → `POST /api/workspaces` (multipart `file`) → rafraîchit la liste et sélectionne le workspace créé.
- Sélection d'un workspace : charge l'historique (`GET /api/workspaces/{id}/chat`) et l'arborescence (`GET /api/workspaces/{id}`).
- Flux de conversation unique (façon Claude Code) : messages `user` + réponses `assistant` (rendus via `messageSegments` + `app-copy-block` + pipe `markdown`), et les `actions[]` (fichiers lus/écrits) affichées inline.
- Saisie → `POST /api/workspaces/{id}/chat` `{message}` ; indicateur de traitement ; à la réponse, ajoute le tour et rafraîchit l'arborescence.
- Panneau « Fichiers » repliable : arborescence ; clic fichier → aperçu (`GET .../file?path=`) dans un `<textarea>` + bouton Enregistrer (`PUT .../file?path=`).

## Cas d'erreur
- Upload non-`.zip` ou échec réseau → MatSnackBar d'erreur, liste inchangée.
- Chargement liste/historique/arborescence/fichier en échec → MatSnackBar d'erreur, état vide.
- Envoi de message en échec → MatSnackBar, retrait du tour optimiste assistant.
- Enregistrement fichier en échec → MatSnackBar.

## Critères d'acceptation vérifiables
- L'upload d'un `.zip` appelle `createWorkspace(file)`.
- La sélection d'un workspace appelle `getHistory` et `getWorkspace`.
- L'envoi d'un message appelle `chat()` et ajoute le tour (user + assistant) au fil.
- Clic fichier → `getFile`, Enregistrer → `writeFile`.
- Toutes les erreurs déclenchent une notification (MatSnackBar).
- Aucune couleur codée en dur : uniquement `var(--cg-*)`.

## Plan de test minimal
- `atelier.service.spec.ts` (HttpTestingController) : chaque méthode cible la bonne URL `/api/...` / verbe / corps (multipart pour create, query `path`, body `{content}`, `{message}`).
- `atelier.component.spec.ts` (spy service) : upload → createWorkspace ; sélection → getHistory + getWorkspace ; envoi → chat + tour ajouté ; erreurs gérées (snackbar) ; ouverture fichier → getFile ; enregistrement → writeFile.

## Tables / endpoints / composants impactés
- Endpoints (existants, backend déjà mergé) : `/api/workspaces`, `/api/workspaces/{id}`, `/api/workspaces/{id}/file`, `/api/workspaces/{id}/chat`.
- Composants : `atelier.component`, `atelier.service`, `atelier.models`, route lazy `/atelier` (authGuard), lien nav shell.

## Préoccupations transversales
- Navigation / routing : ajout d'une route enfant `/atelier` sous la coquille authentifiée (authGuard hérité du parent) + lien nav dans `shell.component.html`. Chemins existants inchangés.
- Auth : réutilise l'`authInterceptor` existant (JWT), aucun changement du Principal. Isolation `user_id` garantie côté backend.

## Hors périmètre
- Modifications backend (déjà mergé).
- Streaming SSE (le chat Atelier est non-streamé).
- Édition d'arborescence (création/suppression de fichiers), diff visuel.

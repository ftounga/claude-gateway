# Mini-spec — [F-32 / SF-02] Bouton « Interrompre » et état interrompu (frontend)

---

## Identifiant

`F-32 / SF-02`

## Feature parente

`F-32` — Interrompre un run en cours

## Statut

`done` — livrée le 2026-08-25 (PR #150)

## Date de création

2026-08-25

## Branche Git

`feat/SF-32-02-bouton-interrompre-frontend`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner à l'utilisateur un bouton **Interrompre** dans l'en-tête du terminal pendant une exécution, et
marquer visiblement le tour ainsi arrêté dans le fil.

---

## Contexte

Le contrat API est **figé par SF-32-01** et importé tel quel ici (aucune renégociation) :
`POST /api/workspaces/{id}/agent/interrupt` → `204`, plus le champ additif `interrupted` sur
l'événement SSE `done` et sur le document `terminal` de l'historique.

Sans écran, la capacité backend est inatteignable : c'est ici que l'utilisateur récupère la main sur un
run parti de travers. L'arrêt étant **asynchrone** (frontière sûre côté fournisseur), l'écran doit dire
que l'interruption est *demandée* — pas prétendre qu'elle est faite.

---

## Comportement attendu

### Cas nominal

1. Pendant une exécution (`submitting`), l'en-tête du terminal affiche un bouton **Interrompre**
   (à côté du chronomètre, là où l'utilisateur regarde déjà défiler la sortie — décision D1 du cadrage).
2. Un clic appelle `POST /workspaces/{id}/agent/interrupt`. Le bouton devient inerte et son libellé
   passe à **Interruption…** : la demande est partie, l'arrêt viendra à la frontière sûre.
3. Le run se termine par son chemin nominal ; le tour rejoint le fil avec sa transcription partielle,
   son coût, et une ligne **« Exécution interrompue »**.
4. Après rechargement de la page, le tour relu depuis l'historique porte la même mention.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `409 no_active_session` | Message « Aucune exécution en cours à interrompre. » ; le bouton redevient actif |
| `404` | « Projet introuvable. » |
| `502` / autre échec réseau | « L'interruption n'a pas pu être transmise. Veuillez réessayer. » ; le bouton redevient actif |
| Le run se termine avant que l'interruption n'aboutisse | Le tour s'affiche normalement, **sans** mention d'interruption (le backend ne l'a pas marqué) |
| Double clic | Le second clic est ignoré tant que la demande est en cours |

---

## Critères d'acceptation

- [ ] Le bouton **Interrompre** n'apparaît que pendant une exécution (`submitting`), dans l'en-tête du terminal
- [ ] Un clic appelle `POST /workspaces/{id}/agent/interrupt` une seule fois (double clic sans effet)
- [ ] Pendant la demande, le bouton est inerte et son libellé indique l'attente
- [ ] Un tour interrompu reste dans le fil, avec sa transcription et son coût, marqué « Exécution interrompue »
- [ ] La mention survit au rechargement (lue depuis `terminal.interrupted` de l'historique)
- [ ] `409` produit un message explicite et rend le bouton à nouveau actif
- [ ] Aucun `window.alert/confirm/prompt` ; notifications via `MatSnackBar`
- [ ] Couleurs, polices et espacements conformes à `docs/DESIGN_SYSTEM.md`
- [ ] `npm run build` et `npm test` verts, tests du service sur **mock** HTTP (indépendants du backend)

---

## Périmètre

### Hors scope

- Dialogue de confirmation avant interruption (l'action est **réversible** : on relance un message)
- Interruption automatique sur seuil de coût
- Reprise d'un run interrompu
- Bouton dans le mode Assistant (pas de session longue à interrompre)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Visibilité | `submitting === true` uniquement |
| Inertie | `interrupting === true` ⇒ bouton `disabled` |
| Libellés | « Interrompre » / « Interruption… » / « Exécution interrompue » |
| Icône | `stop_circle` (Material Icons, déjà utilisé par la charte) |
| Couleur de la mention | `--cg-orange-2` (jeton de la charte, déjà utilisé par le chronomètre du terminal) — aucune valeur hexadécimale en dur |

---

## Technique

### Contrat API

*Contrat importé de `SF-32-01-interruption-run.md` (backend), figé :*

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `POST` | `/api/workspaces/{id}/agent/interrupt` | *(vide)* | `204` ; erreurs `401` / `404 not_found` / `409 no_active_session` / `502 provider_error` |

Champs additifs consommés : `done.interrupted` (SSE) et `terminal.interrupted` (historique).

### Tables impactées / Migration

Aucune (frontend uniquement).

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | `interrupted` sur `AtelierAgentStreamDone` et `AtelierPersistedTranscript` |
| `core/services/atelier.service.ts` | `interruptAgentSession(id)` + lecture de `interrupted` dans `done` |
| `atelier/atelier.types.ts` | `interrupted?: boolean` sur `AtelierThreadItem` |
| `atelier/atelier.component.ts` / `.html` | Signal `interrupting`, méthode `interruptRun()`, câblage de la vue |
| `atelier/terminal/atelier-terminal.component.*` | Bouton d'en-tête + ligne « Exécution interrompue » |
| Specs associées | Tests du service (mock `HttpTestingController`) et des composants |

---

## Plan de test

### Tests unitaires

- [ ] `interruptAgentSession` appelle `POST /api/workspaces/{id}/agent/interrupt` (mock HTTP)
- [ ] `dispatchAgentSseEvent` : `done` avec `interrupted: true` ⇒ `onDone` reçoit `interrupted: true`
- [ ] `done` sans le champ ⇒ `interrupted: false` (rétrocompatibilité)
- [ ] `toThreadItem` : `terminal.interrupted` ⇒ tour marqué ; absent ⇒ non marqué
- [ ] Composant terminal : bouton absent hors exécution, présent pendant, inerte pendant la demande
- [ ] Composant terminal : la ligne « Exécution interrompue » n'apparaît que sur un tour marqué
- [ ] `interruptRun()` : un seul appel malgré deux clics ; `409` ⇒ message dédié et bouton réactivé

### Tests d'intégration

- [ ] Non applicable côté frontend : les tests du service s'exécutent sur mock HTTP (`HttpTestingController`),
      sans dépendre du backend mergé.

### Isolation utilisateur

- [x] **Applicable** — l'écran n'envoie que l'identifiant du projet ouvert ; l'isolation est faite côté
  backend par `requireOwned`. Aucun `userId` n'est manipulé côté client.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Le jeton est porté par l'intercepteur HTTP existant. |
| Contexte tenant | **Non** | Aucun identifiant d'utilisateur côté client ; le projet ouvert est déjà résolu. |
| Plans / limites | **Non** | Aucune consommation, aucun gate touché. |
| Navigation / routing | **Non** | Bouton ajouté dans un écran existant ; aucune route, aucun guard, aucune redirection modifiés. |

---

## Dépendances

- **SF-32-01** (endpoint + champs additifs) — mergée avant celle-ci.

---

## Notes et décisions

- **Pas de confirmation** : l'interruption est réversible (on renvoie un message) et l'urgence est le
  motif même de l'action. Un dialogue ferait perdre les secondes qu'on cherche à économiser.
- **« Interruption… » plutôt que « Interrompu »** : l'arrêt intervient à une frontière sûre, pas au
  retour de l'appel. Annoncer un arrêt immédiat serait mentir à l'utilisateur, qui verrait la sortie
  continuer de défiler.
- **Le tour interrompu reste affiché** : il a réellement eu lieu et il est facturé (D2/D3). Le retirer,
  comme on le fait pour un run en erreur, contredirait ce que l'utilisateur vient de voir.

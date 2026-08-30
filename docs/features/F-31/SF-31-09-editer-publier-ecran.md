# Mini-spec — F-31 / SF-31-09 — Éditer et publier depuis l'écran (frontend)

## Identifiant
`F-31 / SF-31-09`

## Feature parente
`F-31` — Projet adossé à un dépôt Git

## Statut
`ready`

## Date de création
2026-08-30

## Branche Git
`feat/SF-31-09-editer-publier-ecran`

---

## Objectif

> Rendre l'éditeur de l'explorateur utilisable sur un projet Git : les modifications s'accumulent
> comme **non publiées**, et un bouton **Commiter et publier** les envoie en un commit sur une
> branche dédiée (SF-31-08).

---

## Comportement attendu

### Cas nominal
1. Sur un projet Git, l'aperçu d'un fichier devient **éditable** et le bouton d'enregistrement
   s'affiche — il ne dit plus « Lecture seule ».
2. *Enregistrer* ne part pas sur le réseau : la modification est **retenue localement**, marquée
   « non publiée », et le fichier apparaît dans un compteur.
3. Rouvrir un fichier modifié réaffiche **la version retenue**, pas celle de la branche.
4. **Commiter et publier** ouvre le dialogue existant de SF-31-04 (branche + message, branche de base
   refusée à la saisie), puis publie **tous** les fichiers en attente en un commit.
5. Après publication : les modifications en attente sont vidées, et l'écran affiche le lien de
   comparaison — ou celui de la pull request si elle existe déjà.
6. Un avertissement dit que **la session de l'agent travaille désormais sur une version antérieure**
   du dépôt, et propose de la réinitialiser.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Publication refusée (branche par défaut) | Message d'erreur du serveur affiché ; les modifications **restent en attente**, rien n'est perdu |
| Jeton absent ou sans droit d'écriture | Message du serveur, qui nomme le droit manquant ; modifications conservées |
| GitHub indisponible | Message de réessai ; modifications conservées |
| Aucune modification en attente | Le bouton *Commiter et publier* est désactivé |
| Quitter l'écran avec des modifications en attente | Confirmation `MatDialog` (*Quitter sans publier* / *Rester*) sur les deux sorties de l'écran, et `beforeunload` pour la fermeture d'onglet ou le rechargement |
| Projet d'archive | Comportement actuel **inchangé** : enregistrement direct, aucun bouton de publication |

---

## Critères d'acceptation

- [ ] Sur un projet Git, l'aperçu est éditable et *Enregistrer* met la modification en attente.
- [ ] Le compteur de modifications non publiées reflète le nombre de fichiers touchés, pas d'enregistrements.
- [ ] Rouvrir un fichier en attente montre la version retenue.
- [ ] *Commiter et publier* est désactivé sans modification, actif dès la première.
- [ ] La publication envoie **un seul appel** portant tous les fichiers.
- [ ] Un échec de publication **conserve** les modifications en attente.
- [ ] Le succès vide la file, affiche le lien, et avertit du clone périmé.
- [ ] Un projet d'archive garde exactement son comportement actuel.
- [ ] Conforme `DESIGN_SYSTEM.md` : composants Material, `MatSnackBar` pour les notifications,
      `MatDialog` pour la confirmation de perte, aucun `window.confirm`.
- [ ] Tests frontend verts.

---

## Périmètre

### Hors scope (explicite)
- L'ouverture de la pull request : le lien est affiché, la PR reste ouverte par SF-31-05 ou à la main.
- La résolution de conflits entre une édition de l'écran et une écriture de l'agent : la branche
  dédiée les rend visibles à la relecture. Rien d'automatique.
- La persistance des modifications en attente entre deux visites : elles vivent le temps de l'écran.
  Le dire honnêtement vaut mieux qu'un brouillon qu'on croirait sauvegardé.
- La création et la suppression de fichiers sur un projet Git : cette subfeature modifie l'existant.

---

## Technique

| Fichier | Nature |
|---|---|
| `core/services/atelier.service.ts` | + `commitGitFiles(id, branch, message, files)` |
| `core/models/atelier.models.ts` | + `GitCommitResult` |
| `atelier/files/atelier-files.component.ts` | file d'attente, compteur, publication, garde de sortie |
| `atelier/files/atelier-files.component.html` | éditeur actif sur Git, bandeau, bouton |
| `atelier/git/git-push-dialog.component.ts` | **réutilisé tel quel** |

### Endpoint consommé
`POST /api/workspaces/{id}/git/commit` (SF-31-08).

### Migration
- [x] Non applicable — aucun changement de schéma.

---

## Plan de test

- [ ] Sur un projet Git, *Enregistrer* n'appelle **pas** `writeFile` et incrémente le compteur.
- [ ] Deux enregistrements sur le même fichier → un seul fichier en attente.
- [ ] Publication → un appel `commitGitFiles` portant tous les fichiers, file vidée ensuite.
- [ ] Échec de publication → file **conservée**, message affiché.
- [ ] Projet d'archive → `writeFile` appelé comme avant, aucun bouton de publication.

### Isolation
- [x] Sans objet côté écran : l'isolation est portée par l'API (SF-31-08, testée).

---

## Dépendances
`SF-31-08` (endpoint) — **done**. `SF-31-04` (dialogue de branche) — **done**, réutilisé.

## Préoccupation transversale — Navigation
La garde de sortie ajoute une confirmation au départ de l'écran **uniquement** quand des
modifications sont en attente. Chemins vérifiés : *Retour au projet*, *Ouvrir le terminal*, et la
navigation directe par URL. Aucun autre écran n'est touché.

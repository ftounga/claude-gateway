# Mini-spec — F-31 / SF-31-11 — Le sélecteur de branche dans l'explorateur (frontend)

## Identifiant
`F-31 / SF-31-11` · Feature parente `F-31` · Statut `done` · 2026-08-31
Branche : `feat/SF-31-11-selecteur-branche-ecran`

## Objectif

> Voir la branche sur laquelle on travaille, en changer, et en créer une — depuis l'explorateur,
> à tout moment. C'est l'écran de SF-31-10.

## Comportement livré

1. Un **sélecteur** liste les branches du dépôt ; la branche par défaut est signalée comme telle.
2. En changer recharge l'arborescence et les fichiers depuis cette branche, et vide l'aperçu ouvert
   (il montrait un fichier d'une autre branche).
3. **Nouvelle branche** ouvre un dialogue : nom validé pendant la saisie — forme invalide et nom déjà
   pris sont dits **avant** l'envoi, pas découverts après.
4. Après un changement, un message dit que **si** une session Claude est ouverte, elle travaille
   encore sur l'ancienne branche, et qu'une réinitialisation l'amènera sur la nouvelle. Formulé au
   conditionnel : l'écran ne sait pas si une session existe, et le prétendre serait une supposition.
5. Changer de branche avec des **modifications non publiées** demande confirmation — elles seraient
   perdues.

## Cas d'erreur

| Situation | Comportement |
|---|---|
| Branche inexistante (course) | Message du serveur ; le projet reste sur sa branche |
| Nom invalide ou déjà pris | Dit pendant la saisie ; le bouton reste inactif |
| Liste des branches inaccessible | **Silencieux** : le sélecteur reste vide, la lecture des fichiers continue de fonctionner |
| GitHub indisponible | Message de réessai ; aucun changement appliqué |

## Critères d'acceptation

- [x] Les branches sont chargées à l'ouverture d'un projet Git, et seulement là.
- [x] La branche courante est affichée et sélectionnée.
- [x] Changer de branche appelle l'API et recharge le projet ; rester sur la même ne fait rien.
- [x] Créer une branche s'y place et recharge.
- [x] Changer avec des modifications en attente demande confirmation, et annuler ne perd rien.
- [x] Un échec de chargement des branches n'empêche pas d'utiliser l'explorateur.
- [x] Conforme `DESIGN_SYSTEM.md` : `mat-select` en `outline`, `MatDialog`, `MatSnackBar`.
- [x] Tests frontend verts (548).

## Hors périmètre
Supprimer ou fusionner une branche ; suivre automatiquement la branche après publication ;
réinitialiser la session à la place de l'utilisateur (ce serait détruire son environnement sans le
demander).

## Technique
`atelier.service.ts` (+3 appels), `atelier.models.ts` (+`GitBranches`), l'explorateur, et le nouveau
`git-branch-dialog.component`. Aucune migration.

## Préoccupation transversale — Navigation
La confirmation de changement de branche s'ajoute à celle de sortie d'écran (SF-31-09). Les deux
partagent `ConfirmDialogComponent` et ne se déclenchent que s'il y a des modifications à perdre.

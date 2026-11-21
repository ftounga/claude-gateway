# Mini-spec — [F-274 / SF-274-03] Le fil du sujet d'un clic, les gestes hors plan à l'écran

## Identifiant
`F-274 / SF-274-03`

## Feature parente
`F-274` — Les demandes hors plan s'exécutent dans un sujet à plusieurs chantiers

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-274-03-fil-du-sujet-d-un-clic`

---

## Objectif
Le fil du sujet (hors de tout chantier, il relit tout le sujet) est atteignable d'un clic depuis la carte des volets, et
chaque chantier montre les gestes hors plan qui lui sont rattachés.

## Cause racine (prouvée, cadrage §2)
C2 : dans un sujet à chantiers, le volet de gauche porte toujours un chantier (`arrivalLayout`, `prune`,
`onFocusChantierChange` le réimpose dès que le volet de gauche est vide) et la carte des volets ne liste que les
chantiers : le « fil principal » vers lequel l'agent renvoyait (alarm4tech 18/11 10:04) n'avait aucun accès.

## Comportement attendu

### Cas nominal
1. Carte des volets (`terminal-pane-map`) : une première case **« Fil du sujet »** avec un bouton ◧ « Afficher le fil du
   sujet (hors chantier) à gauche » ; « Gauche » et `aria-pressed` quand il y est.
2. Clic : le volet de gauche montre le fil du sujet (historique relu sans chantier, envois sans `chantier`), le volet de
   droite ne bouge pas ; le fil est **épinglé** : un changement de focus du serveur ne le chasse pas. Placer un
   chantier à gauche (carte, « Basculer », fermeture du volet) le désépingle.
3. Chaque case de chantier affiche « +N hors plan » quand des gestes hors plan lui sont rattachés (`horsPlan`,
   SF-274-02), avec l'infobulle « Hors plan, rattachés à ce chantier : objet (jj/mm) ; … ».
4. Design system : encre et couleurs de la carte existante (`--cg-divider`, `--cg-orange-2`), aucune couleur ni police
   nouvelle ; cibles ≥ 24 px (44 px au doigt) héritées.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Gateway antérieure (pas de `horsPlan`) | pas de compteur, aucune erreur |
| Changement de sujet | l'épinglage est oublié (`resetSplit`) |
| Le fil du sujet est déjà à gauche | clic sans effet |

## Critères d'acceptation
- [ ] La case « Fil du sujet » est la première de la carte et émet `commonPlaced` (test composant).
- [ ] Après le clic : `paneLayout().left === null`, `chatLane() === null`, historique relu sans chantier, droite
      inchangée, aucun appel `focus` (test).
- [ ] `onFocusChantierChange` ne remplace pas le fil épinglé ; placer un chantier à gauche le désépingle (test).
- [ ] Compteur et infobulle des gestes hors plan (test composant).
- [ ] Tests F-221/F-246 existants verts.

## Plan de test
### Tests unitaires
`terminal-pane-map.component.spec.ts`, `atelier.component.spec.ts` (F-246 / F-274).
### Tests d'intégration
`npm run build` ; `ng test` sur les specs touchées.
### Isolation utilisateur
Aucune lecture nouvelle : l'historique du fil du sujet et le parcours passent par les API existantes (token).

## Tables / endpoints / composants impactés
Aucun endpoint nouveau. `journey.models.ts` (`horsPlan`), `terminal-pane-map.component.{ts,scss}`,
`atelier.component.{ts,html}`.

## Préoccupations transversales
- Navigation / routing : pas de route ; l'état des volets change (impact listé : `onFocusChantierChange`,
  `applyLayout`, `resetSplit`, `onPaneChantiers`/`prune` — vérifiés : `prune` garde un volet de gauche vide).
- Auth, tenant, plans : non.

## Hors périmètre
Lien cliquable dans le texte des réponses de l'agent (la doctrine SF-274-01 interdit le renvoi : l'agent agit dans le
fil où on lui parle).

# Mini-spec — F-255 / SF-255-03 L'écran du rejeu, dans l'administration

## Identifiant
`F-255 / SF-255-03`

## Feature parente
`F-255` — Rejeu de séance Jarvis (cadrage §4)

## Statut
`in-progress` · **Estimation :** 1,5 j · **Branche :** `feat/SF-255-03-ecran-rejeu` · **Date :** 2026-11-11 ·
**Dépend de :** SF-255-02 (mergée)

## Objectif
Donner au PO, dans la page d'administration, une section lisible en français qui compare ses séances Jarvis et rejoue
chacune tour par tour, audio compris.

## Comportement attendu

### Cas nominal
1. La page `/admin` gagne la section **« Rejeu des séances Jarvis »**, après la voix de Jarvis : un tableau des séances
   des 7 derniers jours (heure de Paris), une ligne par séance, les colonnes du bilan côte à côte pour comparer —
   tours, perdus (taux), répétés, lents, premier son médian, mauvais sujet, « Voici » sans affichage, replis (taux).
2. « Rejouer » ouvre la séance sous le tableau : bilan en une phrase, seuils rappelés, puis chaque tour — numéro,
   heure, verdicts en pastilles (vert bien, ambre à surveiller, rouge défaut ; détail au survol et en clair sous le
   tour), « Vous » (texte retenu, sinon « rien retenu »), le texte du flux s'il diffère, « Jarvis » (réponse),
   délégations (sujet, chantier, libellé, réponse rendue), écran (montré ou refusé et pourquoi), temps
   (transcription, premier son, fin, cerveau, réponse provisoire), le journal d'écoute repliable.
3. **Réécouter** : quand l'énoncé a été gardé (capture F-239), un bouton joue l'audio par la route existante
   `/api/admin/jarvis/captures/{id}/audio`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Chargement des séances en échec | « Les séances n'ont pas pu être chargées. » |
| Aucune séance | « Aucune séance vocale ces 7 derniers jours. » |
| Rejeu en échec | message bref (`MatSnackBar`), le tableau reste |
| Audio effacé (> 7 j) | « L'énoncé n'est plus disponible (gardé 7 jours). » |
| Tour sans mesure / sans audio | « aucune mesure reçue », pas de bouton Réécouter |

## Critères d'acceptation
- [ ] Le tableau montre une ligne par séance avec les chiffres du bilan serveur (aucun recalcul dans le navigateur).
- [ ] Le rejeu affiche, pour un tour, ce qui a été dit, compris, répondu, délégué, montré ou refusé, et ses temps.
- [ ] Les verdicts sont en français, avec la couleur de leur ton.
- [ ] Réécouter appelle la route des captures pour le bon énoncé.
- [ ] Couleurs, polices et espacements conformes au design system (badges §5, jetons `--cg-*`).

## Périmètre — hors scope
L'écran holographique `/jarvis` (aucun texte ajouté) ; tout calcul de verdict côté navigateur ; la comparaison au-delà
de 7 jours.

## Contraintes de validation
Aucun champ saisi.

## Technique
- Composants : `AdminJarvisReplayComponent`, `AdminJarvisReplayService` (`admin/jarvis-replay/`) ; insertion dans
  `admin.component` (aucune route nouvelle).
- Endpoints consommés : `GET /api/admin/jarvis/replay/sessions`, `GET /api/admin/jarvis/replay`,
  `GET /api/admin/jarvis/captures/{id}/audio`. Aucune table, aucune migration.

## Plan de test
- `admin-jarvis-replay.component.spec.ts` : liste, rejeu d'un tour complet, tour perdu sans mesure ni audio, aucune
  séance, échec, libellés et formats (heure de Paris, secondes à la française, pourcentages).
- `admin.component.spec.ts` : la section est présente.
- Isolation : portée par les routes serveur (SF-255-02, testée) ; l'écran n'affiche que ce qu'elles rendent.

## Préoccupations transversales
Aucune (aucune route, aucun garde : la section vit dans la page d'administration existante).

# Mini-spec — F-249 / SF-249-08 L'annonceur sur tous les écrans, Jarvis le dit

## Identifiant
`F-249 / SF-249-08`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D10, D11, D12, D15, D17)

## Statut
`done` (2026-11-07)

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-08-annonceur`

## Objectif
Les annonces (nouveaux mails sur un sujet, rappel du soir, réunion imminente, récap du matin, mails répétitifs)
apparaissent en pop-up sur n'importe quel écran de l'application, et Jarvis les dit dans `/jarvis` — jamais comme un
message dans le fil d'un sujet.

## Comportement attendu

### Cas nominal
1. `app-notice-announcer` vit dans le **shell** (tous les écrans authentifiés hors `/jarvis`) : sondage
   `GET /api/jarvis/notices` au chargement puis toutes les **60 s** ; jusqu'à **3** annonces empilées en bas à droite
   (icône par genre, texte, « Voir » si un lien, « OK »).
2. « Voir » ouvre le lien (`/atelier/<sujet>` ou `/jarvis`) et ferme l'annonce ; « OK » la ferme
   (`POST …/{id}/seen`) — fermée partout.
3. Dans `/jarvis` (hors présentation) : même sondage ; quand Jarvis est **au repos** (pas de tour, pas de parole), il
   **dit** la plus ancienne annonce non dite (`speech`), une par passage, puis la marque dite
   (`POST …/{id}/spoken`) ; elle reste à l'écran de l'application jusqu'au « OK ».
4. Rien n'est écrit dans un fil de sujet ; aucune route ajoutée.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Sondage en échec | silence, nouvel essai au passage suivant |
| « OK » en échec | l'annonce disparaît localement, reviendra au sondage suivant |
| Présentation (`/jarvis`) | rien n'est dit |
| Tour en cours ou parole | rien n'est dit (au passage suivant) |

## Contraintes de validation
3 annonces montrées ; une annonce dite par passage.

## Critères d'acceptation
- [ ] Une annonce apparaît sur un écran du shell, avec « Voir » qui navigue et « OK » qui la ferme (route vue).
- [ ] Au-delà de 3, seules les 3 plus récentes sont montrées.
- [ ] Dans `/jarvis`, au repos, la phrase est dite puis marquée dite ; jamais en présentation ni pendant un tour.
- [ ] Charte : surface blanche, filet orange, police du corps ; responsive.

## Plan de test
- **Specs** `notice-announcer.component.spec.ts` (sondage, 3 au plus, Voir, OK, erreur silencieuse),
  `jarvis-notices.spec.ts` (`toSpeak`, icônes), `jarvis.component.spec.ts` (dit au repos, pas en présentation).
- Suite complète frontend à la review.

## Impact
- **Frontend** : `jarvis/jarvis-notices.ts`, `layout/notice-announcer/notice-announcer.component.ts`,
  `layout/shell/shell.component.{ts,html}`, `jarvis.component.ts`.

## Préoccupations transversales
- **Navigation** : « Voir » navigue vers des routes existantes (`/atelier/:id`, `/jarvis`) ; aucun garde ni route
  modifié. Composants : `ShellComponent` (montage), `NoticeAnnouncerComponent`.

## Hors périmètre
Le contenu des annonces (SF-249-03/04/05/06).

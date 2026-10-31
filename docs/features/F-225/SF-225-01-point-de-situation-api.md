# SF-225-01 — Le point de situation d'un terminal (backend)

**Feature :** F-225 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-225-01-point-de-situation`

## Objectif
Une route rend, en une lecture et sans appel modèle, « où on en était » dans un terminal.

## Comportement
`GET /api/workspaces/{id}/situation` → `Situation` : composition déterministe de lectures existantes.
- `lastActivityAt`, `idleHours` (dernier message du fil) — l'écran décide d'ouvrir la carte ;
- `mode` (Libre/Guidé) et `chantiers` : numéro, titre, phase, focus, avancement, **étape suivante** (rang + titre), plan à valider, diagnostic à confirmer, qui débloque, « en sommeil » (F-222) ;
- `attentes` ouvertes : description, état, qui, à qui, depuis quand, chantier (F-227) ;
- `envois` : encarts mail/message proposés ces **7 derniers jours** (`MessageBlocks`), avec objet, destinataire, date, début du texte (`excerpt`, pour [Voir]), `sentAt` (F-211, `null` = « pas marqué envoyé »), et l'état de l'attente liée ;
- `exchange` : dernière demande de l'utilisateur et dernière réponse (brute, 4 000 caractères au plus — l'écran en tire l'essentiel F-126) ;
- `pieces` : dépôts du terminal (F-169), les plus récents d'abord ;
- `suites` : celles de la dernière réponse (F-192, relevé `nextSteps`).

Bornes : 30 éléments par liste. Isolation : `requireOwned` d'abord (404 chez autrui), toutes les lectures sous `user_id`.

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Terminal d'autrui ou inconnu | introuvable | 404 |
| Terminal neuf | listes vides, `exchange` et `lastActivityAt` absents | 200 |
| Relevé des suites illisible | `suites` vide | 200 |

## Critères d'acceptation
- [x] Dernière demande, dernière réponse, encarts (envoyé / non envoyé + attente liée), attentes, suites.
- [x] Terminal neuf sans erreur.
- [x] Bob → 404.

## Plan de test
`SituationApiIntegrationTest` (2 tests). Suite backend complète.

## Impact
Nouveau paquet `atelier/situation` (service, DTO, contrôleur) ; lectures via `SubjectJourneyService`, `TerminalActionService`, `MessageBlockSendService`, `AtelierMessageRepository`, `AtelierDepositedFileRepository`. Aucune migration. Préoccupations transversales : aucune.

## Hors périmètre
L'écran (SF-225-02) ; un récit rédigé par le modèle (cadrage : plus tard, si la mesure le justifie).
